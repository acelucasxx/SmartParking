package com.smartparking.iot.runtime;

import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.TriggerSource;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.presence.ConnectionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code device_runtime} 与 {@code device_event} 的读写。运行态是事实来源，Redis 只是它的缓存。 */
@Repository
public class RuntimeRepository {

    private static final String COLUMNS = "device_id, gate_status, status_code, last_reason, occupied, online_state,"
            + " last_seen_at, status_refreshed_at, last_conn_event, last_conn_at, rev";

    private final JdbcClient jdbc;

    public RuntimeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 状态帧写入结果 */
    public record StatusResult(DeviceRuntime previous, boolean changed, boolean stale) {}

    public Optional<DeviceRuntime> find(long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM device_runtime WHERE device_id = ?")
                .param(deviceId)
                .query(RuntimeRepository::map)
                .optional();
    }

    /**
     * 写入一帧状态帧：刷新闸杆状态、{@code status_refreshed_at} 与 {@code last_seen_at}；状态码、原因码或车位与上一帧不同时
     * 追加一条 {@code device_event}。早于已记录 {@code status_refreshed_at} 的帧视为过期，不写。
     */
    @Transactional
    public StatusResult applyStatus(long deviceId, UplinkFrame.Status frame, Instant at) {
        ensureRow(deviceId);
        DeviceRuntime current = jdbc.sql("SELECT " + COLUMNS + " FROM device_runtime WHERE device_id = ? FOR UPDATE")
                .param(deviceId)
                .query(RuntimeRepository::map)
                .single();
        if (current.statusRefreshedAt() != null && current.statusRefreshedAt().isAfter(at)) {
            return new StatusResult(current, false, true);
        }
        boolean first = current.statusCode() == null;
        boolean changed = first
                || current.statusCode() != frame.statusCode()
                || !Objects.equals(current.lastReason(), frame.reasonCode())
                || !Objects.equals(current.occupied(), frame.occupied());

        jdbc.sql(
                        """
                        UPDATE device_runtime
                        SET gate_status = :gate, status_code = :code, last_reason = :reason, occupied = :occupied,
                            status_refreshed_at = :at, last_seen_at = GREATEST(COALESCE(last_seen_at, :at), :at),
                            rev = rev + 1
                        WHERE device_id = :id""")
                .param("gate", frame.status().name())
                .param("code", frame.statusCode())
                .param("reason", frame.reasonCode())
                .param("occupied", frame.occupied())
                .param("at", DbTime.toDb(at))
                .param("id", deviceId)
                .update();

        if (changed) {
            jdbc.sql(
                            """
                            INSERT INTO device_event (device_id, gate_status, status_code, reason, occupied, raw_frame, approx, at)
                            VALUES (:id, :gate, :code, :reason, :occupied, :raw, :approx, :at)""")
                    .param("id", deviceId)
                    .param("gate", frame.status().name())
                    .param("code", frame.statusCode())
                    .param("reason", frame.reasonCode())
                    .param("occupied", frame.occupied())
                    .param("raw", Hex.parse(frame.rawFrame()))
                    .param("approx", isApprox(frame))
                    .param("at", DbTime.toDb(at))
                    .update();
        }
        return new StatusResult(first ? null : current, changed, false);
    }

    /** 非状态帧（回显、版本、未知帧）只说明设备在线说话，刷新 {@code last_seen_at}（PRD 8.5） */
    @Transactional
    public boolean touch(long deviceId, Instant at) {
        ensureRow(deviceId);
        return jdbc.sql(
                                """
                        UPDATE device_runtime SET last_seen_at = :at, rev = rev + 1
                        WHERE device_id = :id AND (last_seen_at IS NULL OR last_seen_at < :at)""")
                        .param("at", DbTime.toDb(at))
                        .param("id", deviceId)
                        .update()
                > 0;
    }

    /**
     * 不是平台指令触发的变化，在闸板不主动上报的前提下只能靠查询发现，时刻记为“发现时间”（PRD 8.7）。
     * 原因码未知时同样按“约”处理。
     */
    static boolean isApprox(UplinkFrame.Status frame) {
        return frame.trigger() != TriggerSource.APP;
    }

    private void ensureRow(long deviceId) {
        jdbc.sql("INSERT INTO device_runtime (device_id) VALUES (?) ON DUPLICATE KEY UPDATE device_id = device_id")
                .param(deviceId)
                .update();
    }

    private static DeviceRuntime map(ResultSet rs, int row) throws SQLException {
        return new DeviceRuntime(
                rs.getLong("device_id"),
                GateStatus.valueOf(rs.getString("gate_status")),
                rs.getObject("status_code", Integer.class),
                rs.getObject("last_reason", Integer.class),
                rs.getObject("occupied", Boolean.class),
                OnlineState.valueOf(rs.getString("online_state")),
                DbTime.fromDb(rs.getObject("last_seen_at", LocalDateTime.class)),
                DbTime.fromDb(rs.getObject("status_refreshed_at", LocalDateTime.class)),
                rs.getString("last_conn_event") == null
                        ? null
                        : ConnectionState.valueOf(rs.getString("last_conn_event")),
                DbTime.fromDb(rs.getObject("last_conn_at", LocalDateTime.class)),
                rs.getLong("rev"));
    }
}
