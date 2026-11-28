package com.smartparking.control.ble;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.command.CommandAction;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 蓝牙票据（PRD 5.4）：60 秒有效、一次性，签发需联网和有效权限；手机执行后上报结果，无网时缓存、恢复后补报。
 *
 * <p>固件票据校验（EXT-04）确认前总开关 {@code smartparking.ble.enabled} 关闭，签发返回 20010，小程序不显示蓝牙入口。票据载荷
 * {@code payload}（SN + 有效期 + HMAC，产线写入的 deviceKey 签名）格式待固件确定，现在不返回。
 */
public class BleTicketService {

    private static final Logger log = LoggerFactory.getLogger(BleTicketService.class);

    public static final Duration TTL = Duration.ofSeconds(60);
    public static final int MAX_REPORTS = 50;
    /** 上报的耗时超过票据有效期即不可信，不记录 */
    static final int MAX_DURATION_MS = (int) TTL.toMillis();

    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Result {
        SUCCESS,
        FAILED
    }

    public enum FailReason {
        /** 手机蓝牙未开 */
        BLE_OFF(false),
        /** 没搜到设备 */
        NOT_FOUND(false),
        /** 连接失败 */
        CONNECT_FAILED(false),
        /** 设备拒绝票据（过期、已用、签名不对） */
        TICKET_REJECTED(true),
        /** 设备执行出错 */
        DEVICE_ERROR(true);

        private final boolean reachedDevice;

        FailReason(boolean reachedDevice) {
            this.reachedDevice = reachedDevice;
        }

        /** 是否到达过设备：只有到达过设备的结果才写入操作记录 */
        public boolean reachedDevice() {
            return reachedDevice;
        }
    }

    public record Ticket(String ticketId, Instant expireAt, String bleMac, String sn, CommandAction action) {}

    public record Report(
            String ticketId,
            CommandAction action,
            Result result,
            FailReason failReason,
            Instant at,
            Integer durationMs) {}

    public record ReportOutcome(int accepted, List<String> rejected) {}

    private record Stored(
            long id,
            long deviceId,
            long userId,
            String action,
            String role,
            Instant createdAt,
            Instant expireAt,
            boolean used) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Clock clock;

    public BleTicketService(JdbcClient jdbc, TransactionTemplate tx, boolean enabled, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.enabled = enabled;
        this.clock = clock;
    }

    // ------------------------------------------------------------ 签发

    /** 调用方已按 CONTROL 判定（权限、设备启用）。蓝牙未开放或设备没有蓝牙 MAC 20010 */
    public Ticket issue(DeviceGrant grant, CommandAction action) {
        if (!enabled) {
            throw new BizException(ErrorCode.BLE_NOT_AVAILABLE);
        }
        String[] device = jdbc.sql("SELECT sn, ble_mac FROM device WHERE id = ?")
                .param(grant.deviceId())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
                .single();
        if (device[1] == null) {
            throw new BizException(ErrorCode.BLE_NOT_AVAILABLE, "该设备暂不支持蓝牙控制");
        }
        Instant now = clock.instant();
        Instant expireAt = now.plus(TTL);
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String ticketId = HexFormat.of().formatHex(raw);
        jdbc.sql(
                        """
                        INSERT INTO ble_ticket (ticket_id, device_id, user_id, action, role, expire_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        ticketId,
                        grant.deviceId(),
                        grant.userId(),
                        action.name(),
                        grant.role().name(),
                        DbTime.toDb(expireAt),
                        DbTime.toDb(now))
                .update();
        log.info("用户 {} 为设备 {} 申请蓝牙票据 {}（{}）", grant.userId(), grant.deviceId(), ticketId, action);
        return new Ticket(ticketId, expireAt, device[1], device[0], action);
    }

    // ------------------------------------------------------------ 上报

    /**
     * 批量上报（已校验 1–{@value #MAX_REPORTS} 条、字段齐全）。每条单独一个事务：
     *
     * <ul>
     *   <li>票据不存在、不属于当前用户、动作与签发时不一致 → 放进 {@code rejected}。
     *   <li>已上报过的票据（含同一批里重复的）→ 忽略，计入 {@code accepted}，手机可以清掉缓存（补报幂等）。
     *   <li>其余计入 {@code accepted}，票据记下结果；到达过设备的结果（成功、票据被拒、设备出错）同时写一条蓝牙通道的指令，进入操作记录。
     * </ul>
     *
     * <p>不检查上报时票据是否过期、用户当前是否仍有权限：补报的是当时发生的事。
     */
    public ReportOutcome report(long userId, List<Report> reports) {
        Instant now = clock.instant();
        int accepted = 0;
        List<String> rejected = new ArrayList<>();
        for (Report r : reports) {
            Boolean ok = tx.execute(status -> apply(userId, r, now));
            if (Boolean.TRUE.equals(ok)) {
                accepted++;
            } else {
                rejected.add(r.ticketId());
            }
        }
        return new ReportOutcome(accepted, rejected);
    }

    private boolean apply(long userId, Report r, Instant now) {
        Optional<Stored> found = jdbc.sql(
                        """
                        SELECT id, device_id, user_id, action, role, created_at, expire_at, used
                          FROM ble_ticket WHERE ticket_id = ? FOR UPDATE""")
                .param(r.ticketId())
                .query((rs, n) -> new Stored(
                        rs.getLong("id"),
                        rs.getLong("device_id"),
                        rs.getLong("user_id"),
                        rs.getString("action"),
                        rs.getString("role"),
                        DbTime.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                        DbTime.fromDb(rs.getObject("expire_at", LocalDateTime.class)),
                        rs.getBoolean("used")))
                .optional();
        if (found.isEmpty()) {
            return false;
        }
        Stored t = found.get();
        if (t.userId() != userId || !t.action().equals(r.action().name())) {
            return false;
        }
        if (t.used()) {
            return true;
        }
        // 手机时钟可能不准：执行时刻限定在票据有效期内（设备只在有效期内接受票据）
        Instant at = clamp(r.at(), t.createdAt(), t.expireAt());
        Integer duration = r.durationMs() != null && r.durationMs() >= 0 && r.durationMs() <= MAX_DURATION_MS
                ? r.durationMs()
                : null;
        Long commandId = null;
        if (r.result() == Result.SUCCESS || r.failReason().reachedDevice()) {
            Instant finished = duration == null ? at : at.plusMillis(duration);
            var keys = new GeneratedKeyHolder();
            jdbc.sql(
                            """
                            INSERT INTO control_command (device_id, user_id, role, channel, action, status, ble_ticket_id,
                                                         idempotency_key, issued_at, finished_at, duration_ms)
                            VALUES (?, ?, ?, 'BLE', ?, ?, ?, ?, ?, ?, ?)""")
                    .params(
                            t.deviceId(),
                            userId,
                            t.role(),
                            t.action(),
                            r.result().name(),
                            r.ticketId(),
                            "ble:" + r.ticketId(),
                            DbTime.toDb(at),
                            DbTime.toDb(finished),
                            duration)
                    .update(keys, "id");
            commandId = keys.getKey().longValue();
        }
        jdbc.sql(
                        """
                        UPDATE ble_ticket SET used = 1, used_at = ?, result = ?, fail_reason = ?, reported_at = ?, command_id = ?
                         WHERE id = ?""")
                .params(
                        DbTime.toDb(at),
                        r.result().name(),
                        r.failReason() == null ? null : r.failReason().name(),
                        DbTime.toDb(now),
                        commandId,
                        t.id())
                .update();
        log.info("用户 {} 上报蓝牙票据 {}：{} {}", userId, r.ticketId(), r.result(), r.failReason());
        return true;
    }

    private static Instant clamp(Instant at, Instant from, Instant to) {
        if (at.isBefore(from)) {
            return from;
        }
        return at.isAfter(to) ? to : at;
    }
}
