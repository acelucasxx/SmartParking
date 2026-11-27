package com.smartparking.admin.query;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.PageResult;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.TriggerSource;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.OnlineState;
import com.smartparking.iot.runtime.OnlineStateService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 后台设备列表、详情与事件流（PRD 6.2 设备管理）。
 *
 * <p>列表按库里存的在线状态筛选与显示（定时扫描每 60 秒更新，最多滞后 60 秒），详情按当前时刻判定（PRD 8.5）。
 */
public class AdminDeviceQueries {

    private static final Pattern SN_PREFIX = Pattern.compile("^[A-Z0-9]{1,32}$");
    private static final Pattern IMEI_PREFIX = Pattern.compile("^\\d{1,15}$");

    private final JdbcClient jdbc;
    private final Phones phones;
    private final DeviceRuntimeService runtime;
    private final OnlineStateService online;
    private final Clock clock;

    AdminDeviceQueries(
            JdbcClient jdbc, Phones phones, DeviceRuntimeService runtime, OnlineStateService online, Clock clock) {
        this.jdbc = jdbc;
        this.phones = phones;
        this.runtime = runtime;
        this.online = online;
        this.clock = clock;
    }

    /** 契约 AdminDevice */
    public record DeviceRow(
            long id,
            String sn,
            String imei,
            String bleMac,
            String model,
            String batchNo,
            String bindStatus,
            boolean enabled,
            String onlineState,
            String gateStatus,
            Integer batteryPct,
            String ownerPhoneMasked,
            Instant lastSeenAt,
            Instant createdAt) {}

    /**
     * 列表筛选，均可为空。SN、IMEI 为前缀匹配（SN 不区分大小写）；手机号为主人手机号完整 11 位精确匹配。
     */
    public record Filter(
            String sn, String imei, String phone, String bindStatus, OnlineState onlineState, Boolean enabled) {}

    private static final String LIST_FROM = " "
            + """
             FROM device d
             LEFT JOIN device_runtime r ON r.device_id = d.id
             LEFT JOIN app_user u ON u.id = d.owner_user_id""";

    public PageResult<DeviceRow> list(Filter f, int page, int size) {
        Where w = new Where()
                .and("d.sn LIKE ?", prefix(f.sn(), true, SN_PREFIX, "SN"))
                .and("d.imei LIKE ?", prefix(f.imei(), false, IMEI_PREFIX, "IMEI"))
                .and("u.phone_hash = ?", phones.hashOf(f.phone()))
                .and("d.bind_status = ?", f.bindStatus())
                .and(
                        "COALESCE(r.online_state, 'OFFLINE') = ?",
                        f.onlineState() == null ? null : f.onlineState().name())
                .and("d.enabled = ?", f.enabled());
        long total = jdbc.sql("SELECT COUNT(*)" + LIST_FROM + w.sql())
                .params(w.params())
                .query(Long.class)
                .single();
        List<DeviceRow> items = jdbc.sql(
                        """
                        SELECT d.id, d.sn, d.imei, d.ble_mac, d.model, d.batch_no, d.bind_status, d.enabled, d.created_at,
                               COALESCE(r.online_state, 'OFFLINE') AS online_state,
                               COALESCE(r.gate_status, 'UNKNOWN') AS gate_status,
                               r.battery_pct, r.last_seen_at, u.phone_cipher"""
                                + LIST_FROM + w.sql() + " ORDER BY d.id DESC LIMIT ? OFFSET ?")
                .params(w.paged(page, size))
                .query((rs, n) -> row(rs, rs.getString("online_state")))
                .list();
        return new PageResult<>(total, page, size, items);
    }

    // ------------------------------------------------------------ 详情

    public record Owner(long userId, String nickname, String phoneMasked, Instant since) {}

    /** @param status 生效中但已过 valid_to（到期任务尚未处理）时显示 EXPIRED */
    public record Member(
            long userId,
            String nickname,
            String phoneMasked,
            String remark,
            Instant validFrom,
            Instant validTo,
            String status) {}

    public record Detail(
            DeviceRow device,
            String iccid,
            String fwVersion,
            String workMode,
            Boolean occupied,
            Integer signalLevel,
            Instant statusRefreshedAt,
            String name,
            String spaceNo,
            Owner owner,
            List<Member> members) {}

    private record Extra(
            String iccid,
            String fwVersion,
            String workMode,
            Boolean occupied,
            Integer signalLevel,
            Instant statusRefreshedAt,
            String name,
            String spaceNo,
            Long ownerId,
            String ownerNickname,
            byte[] ownerPhone,
            Instant ownerSince) {}

    /** 设备不存在 10004。在线状态按当前时刻判定；被授权人只列生效中的（含尚未开始的） */
    public Detail detail(long deviceId) {
        record Loaded(DeviceRow row, Extra extra) {}
        Loaded loaded = jdbc.sql(
                        """
                        SELECT d.id, d.sn, d.imei, d.ble_mac, d.model, d.batch_no, d.bind_status, d.enabled, d.created_at,
                               COALESCE(r.online_state, 'OFFLINE') AS online_state,
                               COALESCE(r.gate_status, 'UNKNOWN') AS gate_status,
                               r.battery_pct, r.last_seen_at, u.phone_cipher,
                               d.iccid, d.fw_version, d.work_mode, r.occupied, r.signal_level, r.status_refreshed_at,
                               d.name, d.space_no, d.owner_user_id, u.nickname AS owner_nickname, d.owner_since"""
                                + LIST_FROM + " WHERE d.id = ?")
                .param(deviceId)
                .query((rs, n) -> new Loaded(
                        row(rs, rs.getString("online_state")),
                        new Extra(
                                rs.getString("iccid"),
                                rs.getString("fw_version"),
                                rs.getString("work_mode"),
                                bool(rs, "occupied"),
                                integer(rs, "signal_level"),
                                Times.read(rs, "status_refreshed_at"),
                                rs.getString("name"),
                                rs.getString("space_no"),
                                rs.getObject("owner_user_id", Long.class),
                                rs.getString("owner_nickname"),
                                rs.getBytes("phone_cipher"),
                                Times.read(rs, "owner_since"))))
                .optional()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "设备不存在"));
        DeviceRow row = loaded.row();
        Extra x = loaded.extra();
        String state = runtime.find(deviceId)
                .map(online::evaluate)
                .orElse(OnlineState.OFFLINE)
                .name();
        DeviceRow live = new DeviceRow(
                row.id(),
                row.sn(),
                row.imei(),
                row.bleMac(),
                row.model(),
                row.batchNo(),
                row.bindStatus(),
                row.enabled(),
                state,
                row.gateStatus(),
                row.batteryPct(),
                row.ownerPhoneMasked(),
                row.lastSeenAt(),
                row.createdAt());
        Owner owner = x.ownerId() == null || !"BOUND".equals(row.bindStatus())
                ? null
                : new Owner(
                        x.ownerId(), Times.nickname(x.ownerNickname()), phones.masked(x.ownerPhone()), x.ownerSince());
        Instant now = clock.instant();
        List<Member> members = jdbc.sql(
                        """
                        SELECT m.user_id, u.nickname, u.phone_cipher, m.remark, m.valid_from, m.valid_to
                        FROM device_member m JOIN app_user u ON u.id = m.user_id
                        WHERE m.device_id = ? AND m.status = 'ACTIVE'
                        ORDER BY m.valid_from, m.id""")
                .param(deviceId)
                .query((rs, n) -> {
                    Instant validTo = Times.read(rs, "valid_to");
                    return new Member(
                            rs.getLong("user_id"),
                            Times.nickname(rs.getString("nickname")),
                            phones.masked(rs.getBytes("phone_cipher")),
                            rs.getString("remark"),
                            Times.read(rs, "valid_from"),
                            validTo,
                            validTo != null && !validTo.isAfter(now) ? "EXPIRED" : "ACTIVE");
                })
                .list();
        return new Detail(
                live,
                x.iccid(),
                x.fwVersion(),
                x.workMode(),
                x.occupied(),
                x.signalLevel(),
                x.statusRefreshedAt(),
                x.name(),
                x.spaceNo(),
                owner,
                members);
    }

    // ------------------------------------------------------------ 事件流

    /** 契约 DeviceEvent */
    public record Event(
            long id,
            String gateStatus,
            String statusCode,
            String reasonCode,
            String source,
            Boolean occupied,
            boolean approx,
            String rawFrame,
            Instant at) {}

    /** 按时间倒序；设备不存在 10004 */
    public PageResult<Event> events(long deviceId, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        Times.checkRange(from, to);
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM device WHERE id = ?")
                        .param(deviceId)
                        .query(Long.class)
                        .single()
                > 0;
        if (!exists) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        Where w = new Where()
                .and("device_id = ?", deviceId)
                .and("at >= ?", Times.db(from))
                .and("at < ?", Times.db(to));
        long total = jdbc.sql("SELECT COUNT(*) FROM device_event" + w.sql())
                .params(w.params())
                .query(Long.class)
                .single();
        List<Event> items = jdbc.sql(
                        "SELECT id, gate_status, status_code, reason, occupied, raw_frame, approx, at FROM device_event"
                                + w.sql() + " ORDER BY at DESC, id DESC LIMIT ? OFFSET ?")
                .params(w.paged(page, size))
                .query((rs, n) -> {
                    Integer code = integer(rs, "status_code");
                    Integer reason = integer(rs, "reason");
                    return new Event(
                            rs.getLong("id"),
                            rs.getString("gate_status"),
                            code == null ? null : String.format("%02X", code),
                            reason == null ? null : String.format("%02X", reason),
                            reason == null
                                    ? TriggerSource.UNKNOWN.name()
                                    : TriggerSource.of(reason).name(),
                            bool(rs, "occupied"),
                            rs.getBoolean("approx"),
                            Hex.format(rs.getBytes("raw_frame")),
                            Times.read(rs, "at"));
                })
                .list();
        return new PageResult<>(total, page, size, items);
    }

    // ------------------------------------------------------------

    private DeviceRow row(ResultSet rs, String onlineState) throws SQLException {
        boolean bound = "BOUND".equals(rs.getString("bind_status"));
        return new DeviceRow(
                rs.getLong("id"),
                rs.getString("sn"),
                rs.getString("imei"),
                rs.getString("ble_mac"),
                rs.getString("model"),
                rs.getString("batch_no"),
                rs.getString("bind_status"),
                rs.getBoolean("enabled"),
                onlineState,
                rs.getString("gate_status"),
                integer(rs, "battery_pct"),
                bound ? phones.masked(rs.getBytes("phone_cipher")) : null,
                Times.read(rs, "last_seen_at"),
                Times.read(rs, "created_at"));
    }

    private static String prefix(String raw, boolean upper, Pattern allowed, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip();
        if (upper) {
            v = v.toUpperCase(Locale.ROOT);
        }
        if (!allowed.matcher(v).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + " 格式不正确");
        }
        // 只含字母数字，不会出现 LIKE 通配符
        return v + "%";
    }

    static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }
}
