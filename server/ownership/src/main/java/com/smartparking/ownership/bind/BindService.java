package com.smartparking.ownership.bind;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.iot.sync.StatusSyncService;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 扫码绑定（PRD 5.2）。
 *
 * <ol>
 *   <li>已授权手机号（契约 UserProfile.phoneBound：绑定设备前必须完成），否则 10001。
 *   <li>原子地占用一次尝试机会（{@link BindAttempts}）；超过 5 次即已锁定 → 30002。
 *   <li>事务内锁设备行：不存在 20001；已禁用 20002；已被他人绑定 30003；已是本人的 → 直接返回（重复提交不报错）。
 *   <li>激活码哈希不匹配 → 这次机会不归还，30001 带剩余次数；第 5 次直接 30002。其余失败归还机会。
 *   <li>条件更新为已绑定、写 {@code ownership_log}（BIND），同一事务提交；成功后清零错误计数，并立即下发一次状态查询（IOT-10）。
 * </ol>
 */
public class BindService {

    private static final Logger log = LoggerFactory.getLogger(BindService.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ActivateCodes codes;
    private final BindAttempts attempts;
    private final StatusSyncService statusSync;

    public BindService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ActivateCodes codes,
            BindAttempts attempts,
            StatusSyncService statusSync) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.codes = codes;
        this.attempts = attempts;
        this.statusSync = statusSync;
    }

    public enum PreviewState {
        BINDABLE,
        BOUND_BY_ME,
        BOUND_BY_OTHER,
        DISABLED
    }

    public record Preview(String sn, String model, PreviewState state, Long deviceId) {}

    private record DeviceRow(
            long id, String sn, String model, boolean enabled, boolean bound, Long ownerId, String codeHash) {}

    /** 扫码后预检；SN 不存在 20001 */
    public Preview preview(long userId, String rawSn) {
        String sn = normalizeSn(rawSn);
        DeviceRow d = find(sn, false).orElseThrow(() -> new BizException(ErrorCode.DEVICE_NOT_FOUND));
        if (d.bound() && Long.valueOf(userId).equals(d.ownerId())) {
            return new Preview(d.sn(), d.model(), PreviewState.BOUND_BY_ME, d.id());
        }
        if (!d.enabled()) {
            return new Preview(d.sn(), d.model(), PreviewState.DISABLED, null);
        }
        if (d.bound()) {
            return new Preview(d.sn(), d.model(), PreviewState.BOUND_BY_OTHER, null);
        }
        return new Preview(d.sn(), d.model(), PreviewState.BINDABLE, null);
    }

    /** @return 绑定后的设备 ID */
    public long bind(long userId, String rawSn, String rawCode) {
        String sn = normalizeSn(rawSn);
        String code = ActivateCodes.normalize(rawCode);
        if (!code.matches("[0-9A-Z]{" + ActivateCodes.LENGTH + "}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "激活码应为 8 位字母或数字");
        }
        if (!phoneBound(userId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先授权手机号");
        }
        long attempt = attempts.reserve(sn);
        if (attempt > BindAttempts.MAX_FAILURES) {
            throw locked(attempts.remaining(sn));
        }

        Outcome outcome;
        try {
            outcome = tx.execute(status -> bindInTx(userId, sn, code));
        } catch (RuntimeException e) {
            // 设备不存在、已被绑定、已禁用：没有校验激活码，归还这次机会
            attempts.release(sn);
            throw e;
        }
        switch (outcome.kind()) {
            case WRONG_CODE -> {
                long left = BindAttempts.MAX_FAILURES - attempt;
                log.info("SN {} 激活码错误（第 {} 次，用户 {}）", sn, attempt, userId);
                if (left <= 0) {
                    throw locked(BindAttempts.LOCK);
                }
                throw new BizException(ErrorCode.ACTIVATE_CODE_WRONG, "激活码错误，还可尝试 " + left + " 次");
            }
            case BOUND -> {
                attempts.clear(sn);
                log.info("设备 {}（{}）绑定到用户 {}", outcome.deviceId(), sn, userId);
                requestStatus(outcome.deviceId());
            }
            case ALREADY_MINE -> attempts.release(sn);
        }
        return outcome.deviceId();
    }

    private enum Kind {
        BOUND,
        ALREADY_MINE,
        WRONG_CODE
    }

    private record Outcome(Kind kind, long deviceId) {}

    private Outcome bindInTx(long userId, String sn, String code) {
        DeviceRow d = find(sn, true).orElseThrow(() -> new BizException(ErrorCode.DEVICE_NOT_FOUND));
        if (d.bound()) {
            if (Long.valueOf(userId).equals(d.ownerId())) {
                return new Outcome(Kind.ALREADY_MINE, d.id());
            }
            throw new BizException(ErrorCode.DEVICE_ALREADY_BOUND);
        }
        if (!d.enabled()) {
            throw new BizException(ErrorCode.DEVICE_DISABLED);
        }
        if (!codes.matches(code, d.codeHash())) {
            return new Outcome(Kind.WRONG_CODE, d.id());
        }
        int updated = jdbc.sql(
                        """
                        UPDATE device
                           SET bind_status = 'BOUND', owner_user_id = :user, owner_since = CURRENT_TIMESTAMP(3),
                               version = version + 1
                         WHERE id = :id AND bind_status = 'PENDING'""")
                .param("user", userId)
                .param("id", d.id())
                .update();
        if (updated != 1) {
            throw new BizException(ErrorCode.DEVICE_ALREADY_BOUND);
        }
        jdbc.sql(
                        """
                        INSERT INTO ownership_log (device_id, type, from_user, to_user, operator_type, operator_id)
                        VALUES (?, 'BIND', NULL, ?, 'USER', ?)""")
                .params(d.id(), userId, userId)
                .update();
        return new Outcome(Kind.BOUND, d.id());
    }

    /** 设备在线时立即查询，回包刷新运行态即“联网成功”；不在线时等它上线由连接事件触发。失败不影响绑定结果 */
    private void requestStatus(long deviceId) {
        try {
            if (!statusSync.requestQuery(deviceId)) {
                log.info("设备 {} 绑定后未能立即查询（链路不可用），等待设备上线", deviceId);
            }
        } catch (RuntimeException e) {
            log.warn("设备 {} 绑定后查询状态失败：{}", deviceId, e.toString());
        }
    }

    private Optional<DeviceRow> find(String sn, boolean forUpdate) {
        return jdbc.sql(
                        "SELECT id, sn, model, enabled, bind_status, owner_user_id, activate_code_hash FROM device WHERE sn = ?"
                                + (forUpdate ? " FOR UPDATE" : ""))
                .param(sn)
                .query((rs, n) -> new DeviceRow(
                        rs.getLong("id"),
                        rs.getString("sn"),
                        rs.getString("model"),
                        rs.getBoolean("enabled"),
                        "BOUND".equals(rs.getString("bind_status")),
                        rs.getObject("owner_user_id") == null ? null : rs.getLong("owner_user_id"),
                        rs.getString("activate_code_hash")))
                .optional();
    }

    private boolean phoneBound(long userId) {
        return jdbc.sql("SELECT phone_cipher IS NOT NULL FROM app_user WHERE id = ?")
                .param(userId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private static BizException locked(Duration remaining) {
        long minutes = Math.max(1, (remaining.toSeconds() + 59) / 60);
        return new BizException(ErrorCode.ACTIVATE_LOCKED, "错误次数过多，请 " + minutes + " 分钟后再试");
    }

    static String normalizeSn(String raw) {
        String sn = raw == null ? "" : raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!sn.matches("[A-Z0-9]{8,32}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "SN 格式不正确");
        }
        return sn;
    }
}
