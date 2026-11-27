package com.smartparking.admin.query;

import com.smartparking.iot.DbTime;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 仪表盘（PRD 6.2）。
 *
 * <ul>
 *   <li>在线 / 休眠 / 离线 / 故障只统计已激活（已绑定）的设备：库存设备不上电，算进离线没有意义。按库里存的在线状态，最多滞后 60 秒；故障指闸杆状态为
 *       E3，与在线状态重叠计数。
 *   <li>今日指令：东八区今天 0 点起下发的 App 抬杆 / 落杆中已终结的；成功含“已在目标状态”。没有指令时成功率为 0，前端据 total 显示“—”。
 * </ul>
 */
public class DashboardQueries {

    private final JdbcClient jdbc;
    private final Clock clock;

    DashboardQueries(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Devices(long total, long bound, long online, long sleep, long offline, long fault) {}

    public record Commands(long total, long success, double successRate) {}

    public record Dashboard(Devices devices, Commands commandsToday, long openAlarms) {}

    public Dashboard get() {
        Map<String, Object> d = jdbc.sql(
                        """
                        SELECT COUNT(*) AS total,
                               COALESCE(SUM(d.bind_status = 'BOUND'), 0) AS bound,
                               COALESCE(SUM(d.bind_status = 'BOUND' AND r.online_state = 'ONLINE'), 0) AS online,
                               COALESCE(SUM(d.bind_status = 'BOUND' AND r.online_state = 'SLEEP'), 0) AS sleep,
                               COALESCE(SUM(d.bind_status = 'BOUND' AND COALESCE(r.online_state, 'OFFLINE') = 'OFFLINE'), 0)
                                   AS offline,
                               COALESCE(SUM(d.bind_status = 'BOUND' AND r.gate_status = 'FAULT'), 0) AS fault
                        FROM device d LEFT JOIN device_runtime r ON r.device_id = d.id""")
                .query()
                .singleRow();
        var today = LocalDate.ofInstant(clock.instant(), DbTime.ZONE).atStartOfDay();
        Map<String, Object> c = jdbc.sql(
                        """
                        SELECT COUNT(*) AS total, COALESCE(SUM(status = 'SUCCESS'), 0) AS success
                        FROM control_command
                        WHERE issued_at >= ? AND action IN ('OPEN', 'CLOSE') AND status IN ('SUCCESS', 'FAILED', 'TIMEOUT')""")
                .param(today)
                .query()
                .singleRow();
        long alarms = jdbc.sql("SELECT COUNT(*) FROM alarm WHERE status = 'OPEN'")
                .query(Long.class)
                .single();
        long total = number(c, "total");
        long success = number(c, "success");
        return new Dashboard(
                new Devices(
                        number(d, "total"),
                        number(d, "bound"),
                        number(d, "online"),
                        number(d, "sleep"),
                        number(d, "offline"),
                        number(d, "fault")),
                new Commands(total, success, total == 0 ? 0 : Math.round(success * 10000.0 / total) / 10000.0),
                alarms);
    }

    private static long number(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }
}
