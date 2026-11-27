package com.smartparking.ownership.bind;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 契约 DeviceCard（车位首页卡片）。绑定成功后返回主人视角的卡片；车位列表（BIZ-06）复用同一结构。
 * 运行态取 {@code device_runtime}，刚入库、从未上线的设备为离线 / 未知。
 */
public class DeviceCards {

    private final JdbcClient jdbc;

    public DeviceCards(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DeviceCard(
            long id,
            String sn,
            String name,
            String spaceNo,
            String role,
            java.time.OffsetDateTime validTo,
            boolean enabled,
            String onlineState,
            String gateStatus,
            Boolean occupied,
            Integer batteryPct,
            Long pendingCommandId) {}

    public DeviceCard ownerCard(long deviceId) {
        return jdbc.sql(
                        """
                        SELECT d.id, d.sn, d.name, d.space_no, d.enabled,
                               COALESCE(r.online_state, 'OFFLINE') AS online_state,
                               COALESCE(r.gate_status, 'UNKNOWN') AS gate_status,
                               r.occupied, r.battery_pct,
                               (SELECT c.id FROM control_command c
                                 WHERE c.device_id = d.id AND c.inflight_flag = 1) AS pending_command_id
                          FROM device d
                          LEFT JOIN device_runtime r ON r.device_id = d.id
                         WHERE d.id = ?""")
                .param(deviceId)
                .query((rs, n) -> new DeviceCard(
                        rs.getLong("id"),
                        rs.getString("sn"),
                        rs.getString("name"),
                        rs.getString("space_no"),
                        "OWNER",
                        null,
                        rs.getBoolean("enabled"),
                        rs.getString("online_state"),
                        rs.getString("gate_status"),
                        rs.getObject("occupied") == null ? null : rs.getBoolean("occupied"),
                        rs.getObject("battery_pct") == null ? null : rs.getInt("battery_pct"),
                        rs.getObject("pending_command_id") == null ? null : rs.getLong("pending_command_id")))
                .single();
    }
}
