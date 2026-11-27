package com.smartparking.control.devices;

import com.smartparking.common.web.PageResult;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.devices.DeviceViews.OperationRecord;
import com.smartparking.iot.codec.TriggerSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 操作记录（PRD 4、6.1 页面 11、8.7）：App 指令（{@code c-}）与非 App 动作（{@code e-}）合并，按时间倒序。
 *
 * <ul>
 *   <li><b>App 指令</b>：抬杆 / 落杆，可见范围与指令查询一致——主人看自成为主人起的全部和本人发起的，被授权人只看本人发起的。
 *       操作人：主人看被授权人发起的指令时备注优先，其余为昵称。未终结的指令 {@code result} 为 null。
 *   <li><b>非 App 动作</b>：只有主人可见、自成为主人起。取 {@code device_event} 中闸杆到位（开闸到位 / 关闸到位）、且动作原因为遥控器、地感、
 *       外部开关、延时自动关、遇阻、红外、上电找零的事件，每次动作只记一条：闸杆状态与原因都与上一条事件相同（如只是车位变化）的不算新动作。
 *       由定时查询发现的动作 {@code approx = true}，时间为发现时间。
 * </ul>
 */
public class OperationRecords {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final String DEFAULT_NICKNAME = "微信用户";

    /** 非 App 的动作原因码（PRD 8.3）；平台指令 16 来自指令表，00 / 17 / 码表外不算动作 */
    static final String NON_APP_REASONS = "0x01, 0x02, 0x03, 0x04, 0x06, 0x07, 0x10, 0x11, 0x12, 0x15";

    private static final String UNION =
            """
            SELECT 'c' AS kind, c.id, c.issued_at AS at, 0 AS approx, NULL AS reason, NULL AS gate,
                   c.action, c.channel, c.status, c.result_code, c.duration_ms, c.role, u.nickname, m.remark
              FROM control_command c
              JOIN device d ON d.id = c.device_id
              LEFT JOIN app_user u ON u.id = c.user_id
              LEFT JOIN device_member m ON m.device_id = c.device_id AND m.user_id = c.user_id AND m.active_flag = 1
             WHERE c.device_id = :device AND c.action IN ('OPEN', 'CLOSE') AND c.user_id IS NOT NULL
               AND (c.user_id = :user OR (:owner AND (d.owner_since IS NULL OR c.issued_at >= d.owner_since)))
               AND c.issued_at >= :from AND c.issued_at < :to
            UNION ALL
            SELECT 'e', e.id, e.at, e.approx, e.reason, e.gate_status,
                   NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
              FROM (SELECT ev.id, ev.at, ev.approx, ev.reason, ev.gate_status,
                           LAG(ev.gate_status) OVER w AS prev_gate, LAG(ev.reason) OVER w AS prev_reason
                      FROM device_event ev
                     WHERE ev.device_id = :device
                    WINDOW w AS (ORDER BY ev.at, ev.id)) e
              JOIN device d ON d.id = :device
             WHERE :owner AND (d.owner_since IS NULL OR e.at >= d.owner_since)
               AND e.gate_status IN ('OPENED', 'CLOSED')
               AND e.reason IN (%s)
               AND (e.prev_gate IS NULL OR e.prev_reason IS NULL OR e.prev_gate <> e.gate_status OR e.prev_reason <> e.reason)
               AND e.at >= :from AND e.at < :to"""
                    .formatted(NON_APP_REASONS);

    /** 不按日筛选时的时间范围 */
    private static final LocalDateTime MIN = LocalDateTime.of(2000, 1, 1, 0, 0);

    private static final LocalDateTime MAX = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final JdbcClient jdbc;

    public OperationRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param date 东八区某一天；为空时为全部 */
    public PageResult<OperationRecord> page(DeviceGrant grant, LocalDate date, int page, int size) {
        LocalDateTime from = date == null ? MIN : date.atStartOfDay();
        LocalDateTime to = date == null ? MAX : date.plusDays(1).atStartOfDay();
        long total = jdbc.sql("SELECT COUNT(*) FROM (" + UNION + ") r")
                .param("device", grant.deviceId())
                .param("user", grant.userId())
                .param("owner", grant.isOwner())
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .single();
        List<OperationRecord> items = jdbc.sql("SELECT * FROM (" + UNION + ") r ORDER BY r.at DESC, r.kind, r.id DESC"
                        + " LIMIT :limit OFFSET :offset")
                .param("device", grant.deviceId())
                .param("user", grant.userId())
                .param("owner", grant.isOwner())
                .param("from", from)
                .param("to", to)
                .param("limit", size)
                .param("offset", (long) (page - 1) * size)
                .query((rs, n) -> map(rs, grant.isOwner()))
                .list();
        return new PageResult<>(total, page, size, items);
    }

    private static OperationRecord map(ResultSet rs, boolean ownerView) throws SQLException {
        LocalDateTime at = rs.getObject("at", LocalDateTime.class);
        if ("e".equals(rs.getString("kind"))) {
            int reason = rs.getInt("reason");
            return new OperationRecord(
                    "e-" + rs.getLong("id"),
                    at.atZone(ZONE).toOffsetDateTime(),
                    rs.getBoolean("approx"),
                    TriggerSource.of(reason).name(),
                    "OPENED".equals(rs.getString("gate")) ? "OPEN" : "CLOSE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        String status = rs.getString("status");
        boolean terminal = status.equals("SUCCESS") || status.equals("FAILED") || status.equals("TIMEOUT");
        String remark = rs.getString("remark");
        String nickname = rs.getString("nickname");
        String name = ownerView && remark != null && !remark.isBlank()
                ? remark
                : nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname;
        String role = rs.getString("role");
        return new OperationRecord(
                "c-" + rs.getLong("id"),
                at.atZone(ZONE).toOffsetDateTime(),
                false,
                TriggerSource.APP.name(),
                rs.getString("action"),
                name,
                role.equals("OWNER") || role.equals("MEMBER") ? role : null,
                rs.getString("channel"),
                terminal ? status : null,
                rs.getString("result_code"),
                rs.getObject("duration_ms") == null ? null : rs.getInt("duration_ms"));
    }
}
