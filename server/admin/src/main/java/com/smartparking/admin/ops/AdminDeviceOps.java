package com.smartparking.admin.ops;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AuditTrail;
import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.device.registry.DeviceRules;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.selfcheck.SelfCheckAction;
import com.smartparking.iot.selfcheck.SelfCheckService;
import com.smartparking.ownership.bind.BindAttempts;
import com.smartparking.ownership.unbind.DeviceUnboundEvent;
import com.smartparking.ownership.unbind.OwnershipRelease;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 后台运维操作（PRD 5.7、5.8、6.2 设备运维操作）：强制解绑、重置激活码、禁用 / 启用、换件、自检。
 *
 * <p>除自检外都是高危操作：必须填原因（去首尾空白后 2–255 个字符，缺少或过短 40020、超长 10001），原因写入操作日志。
 */
public class AdminDeviceOps {

    private static final Logger log = LoggerFactory.getLogger(AdminDeviceOps.class);

    public static final int REASON_MIN = 2;
    public static final int REASON_MAX = 255;

    public enum Part {
        /** 换 4G 模块：新 IMEI */
        DTU,
        /** 换主板：新蓝牙 MAC */
        BOARD
    }

    private record Locked(String sn, String bindStatus, Long ownerId, String imei, String bleMac) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ActivateCodes codes;
    private final BindAttempts attempts;
    private final OwnershipRelease release;
    private final SelfCheckService selfCheck;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AdminDeviceOps(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ActivateCodes codes,
            BindAttempts attempts,
            OwnershipRelease release,
            SelfCheckService selfCheck,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.codes = codes;
        this.attempts = attempts;
        this.release = release;
        this.selfCheck = selfCheck;
        this.events = events;
        this.clock = clock;
    }

    /** 高危操作原因 */
    public static String reason(String raw) {
        String r = raw == null ? "" : raw.strip();
        if (r.length() < REASON_MIN) {
            throw new BizException(ErrorCode.REASON_REQUIRED, "请填写原因（至少 " + REASON_MIN + " 个字符）");
        }
        if (r.length() > REASON_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原因至多 " + REASON_MAX + " 个字符");
        }
        AuditTrail.reason(r);
        return r;
    }

    // ------------------------------------------------------------ 强制解绑

    /**
     * 设备不存在 10004；未绑定 40021。同一事务内级联（{@link OwnershipRelease}）；{@code resetActivateCode} 时同时换新激活码并返回明文
     * （只出现这一次），否则返回 null。
     */
    public String forceUnbind(AdminPrincipal admin, long deviceId, String rawReason, boolean resetActivateCode) {
        String reason = reason(rawReason);
        Instant now = clock.instant();
        String newCode = resetActivateCode ? codes.generate() : null;
        record Result(String sn, OwnershipRelease.Released released) {}
        Result result = tx.execute(status -> {
            Locked d = lock(deviceId);
            if (!"BOUND".equals(d.bindStatus()) || d.ownerId() == null) {
                throw new BizException(ErrorCode.DEVICE_NOT_BOUND);
            }
            var released = release.release(
                    deviceId, d.ownerId(), OwnershipRelease.Type.FORCE_UNBIND, "ADMIN", admin.adminId(), reason, now);
            if (newCode != null) {
                setCode(deviceId, newCode);
            }
            return new Result(d.sn(), released);
        });
        var released = Objects.requireNonNull(result).released();
        if (newCode != null) {
            attempts.clear(result.sn());
        }
        AuditTrail.detail("formerOwner", released.formerOwner());
        AuditTrail.detail("revokedMembers", released.revokedMembers().size());
        AuditTrail.detail("resetActivateCode", resetActivateCode);
        log.warn(
                "后台 {} 强制解绑设备 {}（原主人 {}，撤销授权 {} 个，重置激活码 {}）：{}",
                admin.username(),
                result.sn(),
                released.formerOwner(),
                released.revokedMembers().size(),
                resetActivateCode,
                reason);
        events.publishEvent(new DeviceUnboundEvent(
                deviceId,
                released.formerOwner(),
                released.revokedMembers(),
                OwnershipRelease.Type.FORCE_UNBIND,
                released.deviceName(),
                now));
        return newCode;
    }

    // ------------------------------------------------------------ 重置激活码

    /** 旧码立即失效，同时清除该 SN 的错误计数与锁定；新码只在返回值中出现。设备不存在 10004 */
    public String resetActivateCode(AdminPrincipal admin, long deviceId, String rawReason) {
        String reason = reason(rawReason);
        String code = codes.generate();
        String sn = tx.execute(status -> {
            Locked d = lock(deviceId);
            setCode(deviceId, code);
            return d.sn();
        });
        attempts.clear(sn);
        log.warn("后台 {} 重置设备 {} 的激活码：{}", admin.username(), sn, reason);
        return code;
    }

    // ------------------------------------------------------------ 禁用 / 启用

    /** 重复禁用、重复启用视为成功；设备不存在 10004 */
    public void setEnabled(AdminPrincipal admin, long deviceId, boolean enabled, String rawReason) {
        String reason = reason(rawReason);
        int changed = jdbc.sql("UPDATE device SET enabled = ? WHERE id = ? AND enabled <> ?")
                .params(enabled, deviceId, enabled)
                .update();
        if (changed == 0 && !exists(deviceId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        AuditTrail.detail("changed", changed > 0);
        log.warn("后台 {} {}设备 {}：{}", admin.username(), enabled ? "启用" : "禁用", deviceId, reason);
    }

    // ------------------------------------------------------------ 换件

    /**
     * 只改 IMEI（DTU）或蓝牙 MAC（BOARD），SN 与绑定、授权关系不变；写 {@code device_part_log}。
     *
     * <ul>
     *   <li>IMEI 不合法 40011、已被其他设备使用 40012；MAC 格式不对 10001、已被使用 40013；与当前值相同 10001。
     *   <li>换 4G 模块后旧 IMEI 立即不再登记，旧模块的上行被丢弃；新模块用新 IMEI 派生的密码连接。
     * </ul>
     */
    public void replacePart(AdminPrincipal admin, long deviceId, Part part, String newValue, String rawReason) {
        if (part == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "part 为 DTU 或 BOARD");
        }
        String reason = reason(rawReason);
        String value;
        try {
            value = part == Part.DTU ? DeviceRules.imei(newValue) : DeviceRules.bleMac(newValue);
        } catch (DeviceRules.Invalid e) {
            throw new BizException(e.code(), e.getMessage());
        }
        if (value == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写新的蓝牙 MAC");
        }
        String old = tx.execute(status -> {
            Locked d = lock(deviceId);
            String current = part == Part.DTU ? d.imei() : d.bleMac();
            if (value.equals(current)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "新值与当前值相同");
            }
            try {
                jdbc.sql("UPDATE device SET " + (part == Part.DTU ? "imei" : "ble_mac") + " = ?, version = version + 1"
                                + " WHERE id = ?")
                        .params(value, deviceId)
                        .update();
            } catch (DuplicateKeyException e) {
                throw new BizException(part == Part.DTU ? ErrorCode.IMEI_DUPLICATE : ErrorCode.MAC_DUPLICATE);
            }
            jdbc.sql(
                            "INSERT INTO device_part_log (device_id, part, old_value, new_value, operator_id, reason, created_at)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            deviceId,
                            part.name(),
                            current == null ? "" : current,
                            value,
                            admin.adminId(),
                            reason,
                            DbTime.toDb(clock.instant()))
                    .update();
            return current;
        });
        AuditTrail.detail("part", part.name());
        AuditTrail.detail("from", old);
        AuditTrail.detail("to", value);
        log.warn("后台 {} 为设备 {} 更换 {}：{} → {}（{}）", admin.username(), deviceId, part, old, value, reason);
    }

    // ------------------------------------------------------------ 自检

    /** 只下发查询类命令；返回指令 ID，结果用指令审计接口轮询 */
    public long selfCheck(AdminPrincipal admin, long deviceId, SelfCheckAction action) {
        if (action == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "action 为 QUERY_STATUS 或 QUERY_VERSION");
        }
        long id = selfCheck.submit(deviceId, action, admin.adminId());
        AuditTrail.detail("action", action.name());
        AuditTrail.detail("commandId", id);
        return id;
    }

    // ------------------------------------------------------------

    private Locked lock(long deviceId) {
        return jdbc.sql("SELECT sn, bind_status, owner_user_id, imei, ble_mac FROM device WHERE id = ? FOR UPDATE")
                .param(deviceId)
                .query((rs, n) -> new Locked(
                        rs.getString("sn"),
                        rs.getString("bind_status"),
                        rs.getObject("owner_user_id", Long.class),
                        rs.getString("imei"),
                        rs.getString("ble_mac")))
                .optional()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "设备不存在"));
    }

    private void setCode(long deviceId, String code) {
        jdbc.sql("UPDATE device SET activate_code_hash = ? WHERE id = ?")
                .params(codes.hash(code), deviceId)
                .update();
    }

    private boolean exists(long deviceId) {
        return jdbc.sql("SELECT COUNT(*) FROM device WHERE id = ?")
                        .param(deviceId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
