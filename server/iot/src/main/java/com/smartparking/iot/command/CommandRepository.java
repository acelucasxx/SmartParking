package com.smartparking.iot.command;

import com.smartparking.iot.DbTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code control_command} 读写。状态推进一律带“当前仍未终结”的条件，多个推进方（执行器、对账）并发时只有一个生效。
 *
 * <p>设备级串行由唯一键 {@code uk_command_inflight} 保证：同一设备同时最多一条未终结指令，跨实例同样有效。
 */
@Repository
public class CommandRepository {

    private static final String COLUMNS =
            "id, device_id, action, channel, status, result_code, issued_at, finished_at, duration_ms";
    private static final String INFLIGHT = "status IN ('PENDING', 'SENT', 'EXECUTING')";

    private final JdbcClient jdbc;

    public CommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 指令行（不含发起人） */
    public record Row(
            long id,
            long deviceId,
            CommandAction action,
            String channel,
            CommandStatus status,
            CommandResult result,
            Instant issuedAt,
            Instant finishedAt,
            Integer durationMs) {}

    /**
     * 插入一条远程指令。
     *
     * @throws org.springframework.dao.DuplicateKeyException 幂等键重复，或该设备已有未终结指令
     */
    public long insert(
            long deviceId,
            CommandAction action,
            Initiator initiator,
            String idempotencyKey,
            byte[] downFrame,
            CommandStatus status,
            CommandResult result,
            Instant issuedAt) {
        var keys = new GeneratedKeyHolder();
        boolean terminal = status.isTerminal();
        jdbc.sql(
                        """
                        INSERT INTO control_command (device_id, user_id, admin_id, role, channel, action, status, result_code,
                                                     idempotency_key, down_frame, issued_at, finished_at, duration_ms)
                        VALUES (:device, :user, :admin, :role, 'REMOTE', :action, :status, :result,
                                :key, :down, :issued, :finished, :duration)""")
                .param("device", deviceId)
                .param("user", initiator.userId())
                .param("admin", initiator.adminId())
                .param("role", initiator.role())
                .param("action", action.name())
                .param("status", status.name())
                .param("result", result == null ? null : result.name())
                .param("key", idempotencyKey)
                .param("down", downFrame)
                .param("issued", DbTime.toDb(issuedAt))
                .param("finished", terminal ? DbTime.toDb(issuedAt) : null)
                .param("duration", terminal ? 0 : null)
                .update(keys, "id");
        return keys.getKeyAs(java.math.BigInteger.class).longValueExact();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM control_command WHERE id = ?")
                .param(id)
                .query(CommandRepository::map)
                .optional();
    }

    public Optional<Row> findByIdempotencyKey(long deviceId, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM control_command WHERE device_id = ? AND idempotency_key = ?")
                .params(deviceId, key)
                .query(CommandRepository::map)
                .optional();
    }

    public Optional<Row> findInflight(long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM control_command WHERE device_id = ? AND " + INFLIGHT)
                .param(deviceId)
                .query(CommandRepository::map)
                .optional();
    }

    /** 早于 {@code before} 下发且仍未终结的指令，供对账（IOT-08）使用 */
    public List<Row> findInflightIssuedBefore(Instant before, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM control_command WHERE " + INFLIGHT
                        + " AND issued_at < ? ORDER BY issued_at LIMIT ?")
                .params(DbTime.toDb(before), limit)
                .query(CommandRepository::map)
                .list();
    }

    /** PENDING → SENT；回包早于本次更新到达、已进入执行中或终态时不变 */
    public boolean markSent(long id) {
        return jdbc.sql("UPDATE control_command SET status = 'SENT' WHERE id = ? AND status = 'PENDING'")
                        .param(id)
                        .update()
                > 0;
    }

    /** SENT → EXECUTING */
    public boolean markExecuting(long id) {
        return jdbc.sql(
                                "UPDATE control_command SET status = 'EXECUTING' WHERE id = ? AND status IN ('PENDING', 'SENT')")
                        .param(id)
                        .update()
                > 0;
    }

    /**
     * 未终结 → 终态。
     *
     * @param rawFrame 判定结果的上行帧；超时等没有对应帧时为空
     * @return 是否由本次调用完成（已被其他推进方终结时返回 false）
     */
    public boolean finish(long id, CommandStatus status, CommandResult result, byte[] rawFrame, Instant at) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("不是终态：" + status);
        }
        var row = find(id).orElseThrow();
        long duration = Math.max(0, Duration.between(row.issuedAt(), at).toMillis());
        return jdbc.sql("UPDATE control_command SET status = :status, result_code = :result, raw_frame = :raw,"
                                + " finished_at = :at, duration_ms = :duration WHERE id = :id AND " + INFLIGHT)
                        .param("status", status.name())
                        .param("result", result == null ? null : result.name())
                        .param("raw", rawFrame)
                        .param("at", DbTime.toDb(at))
                        .param("duration", duration)
                        .param("id", id)
                        .update()
                > 0;
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        String result = rs.getString("result_code");
        return new Row(
                rs.getLong("id"),
                rs.getLong("device_id"),
                CommandAction.valueOf(rs.getString("action")),
                rs.getString("channel"),
                CommandStatus.valueOf(rs.getString("status")),
                result == null ? null : CommandResult.valueOf(result),
                DbTime.fromDb(rs.getObject("issued_at", LocalDateTime.class)),
                DbTime.fromDb(rs.getObject("finished_at", LocalDateTime.class)),
                rs.getObject("duration_ms", Integer.class));
    }
}
