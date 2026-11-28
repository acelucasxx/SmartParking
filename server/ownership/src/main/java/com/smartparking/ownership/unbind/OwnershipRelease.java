package com.smartparking.ownership.unbind;

import com.smartparking.iot.DbTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 归属变更的级联（PRD 5.6⑦、5.7④）：主人解绑与转让（BIZ-13）、强制解绑（BIZ-10）共用。必须在调用方的事务内、已锁定设备行之后调用。
 *
 * <p>同一事务内：作废全部待接受邀请与有效转让卡片、全部生效中的被授权人置为“已撤销”、设备回到待激活（清空主人、名称与车位号）、
 * 写 {@code ownership_log}。设备记录不删除，历史指令保留用于审计。
 */
public class OwnershipRelease {

    public enum Type {
        UNBIND,
        FORCE_UNBIND
    }

    /**
     * @param revokedMembers 被撤销授权的用户，用于站内消息（BIZ-15）
     * @param deviceName 变更前的设备展示名（名称，未命名时为“车位 SN”）；解绑会清空名称，消息里仍要显示它
     */
    public record Released(
            long deviceId,
            long formerOwner,
            List<Long> revokedMembers,
            int voidedInvites,
            int voidedTransfers,
            String deviceName) {}

    private final JdbcClient jdbc;

    public OwnershipRelease(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 解绑：设备回到待激活（清空主人、名称与车位号）。
     *
     * @param operatorType {@code USER}（主人自己解绑）或 {@code ADMIN}
     * @param reason 强制解绑必填（表约束）
     */
    public Released release(
            long deviceId,
            long formerOwner,
            Type type,
            String operatorType,
            long operatorId,
            String reason,
            Instant at) {
        var now = DbTime.toDb(at);
        String name = displayName(deviceId);
        Cleared c = clearDependents(deviceId, now);
        jdbc.sql(
                        """
                        UPDATE device SET bind_status = 'PENDING', owner_user_id = NULL, owner_since = NULL, name = NULL,
                                          space_no = NULL, version = version + 1
                        WHERE id = ?""")
                .param(deviceId)
                .update();
        jdbc.sql(
                        """
                        INSERT INTO ownership_log (device_id, type, from_user, operator_type, operator_id, reason, at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(deviceId, type.name(), formerOwner, operatorType, operatorId, reason, now)
                .update();
        return new Released(deviceId, formerOwner, c.members(), c.invites(), c.transfers(), name);
    }

    /**
     * 转让（PRD 5.6⑦）：同样的级联，设备换主人，{@code owner_since} 记为此刻，新主人只能看到此刻之后的记录。名称与车位号保留
     * （车位号是物理位置），新主人可再修改。调用方须先把被接受的转让卡片置为已接受，否则它也会被作废。
     */
    public Released transfer(long deviceId, long fromUser, long toUser, Instant at) {
        var now = DbTime.toDb(at);
        String name = displayName(deviceId);
        Cleared c = clearDependents(deviceId, now);
        jdbc.sql("UPDATE device SET owner_user_id = ?, owner_since = ?, version = version + 1 WHERE id = ?")
                .params(toUser, now, deviceId)
                .update();
        jdbc.sql(
                        """
                        INSERT INTO ownership_log (device_id, type, from_user, to_user, operator_type, operator_id, at)
                        VALUES (?, 'TRANSFER', ?, ?, 'USER', ?, ?)""")
                .params(deviceId, fromUser, toUser, toUser, now)
                .update();
        return new Released(deviceId, fromUser, c.members(), c.invites(), c.transfers(), name);
    }

    /** 设备展示名：名称，未命名时为“车位 SN” */
    public String displayName(long deviceId) {
        return jdbc.sql("SELECT COALESCE(NULLIF(TRIM(name), ''), CONCAT('车位 ', sn)) FROM device WHERE id = ?")
                .param(deviceId)
                .query(String.class)
                .single();
    }

    private record Cleared(List<Long> members, int invites, int transfers) {}

    /** 作废待接受邀请与有效转让卡片，生效中的被授权人置为已撤销 */
    private Cleared clearDependents(long deviceId, LocalDateTime now) {
        int invites = jdbc.sql("UPDATE share_invite SET status = 'VOIDED' WHERE device_id = ? AND status = 'PENDING'")
                .param(deviceId)
                .update();
        int transfers = jdbc.sql(
                        "UPDATE device_transfer SET status = 'VOIDED' WHERE device_id = ? AND status = 'PENDING'")
                .param(deviceId)
                .update();
        List<Long> members = jdbc.sql(
                        "SELECT user_id FROM device_member WHERE device_id = ? AND status = 'ACTIVE' ORDER BY id FOR UPDATE")
                .param(deviceId)
                .query(Long.class)
                .list();
        jdbc.sql("UPDATE device_member SET status = 'REVOKED', ended_at = ? WHERE device_id = ? AND status = 'ACTIVE'")
                .params(now, deviceId)
                .update();
        return new Cleared(members, invites, transfers);
    }
}
