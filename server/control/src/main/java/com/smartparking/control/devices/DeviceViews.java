package com.smartparking.control.devices;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccessService;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.runtime.DeviceRuntime;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.OnlineState;
import com.smartparking.iot.runtime.OnlineStateService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 小程序的设备视图（PRD 6.1 页面 2、3，契约 device 分组）：车位列表、控制页详情、实时状态、名称与车位号。
 *
 * <ul>
 *   <li>闸杆、车位、最近上行等运行态取 {@link DeviceRuntimeService}（Redis 热数据，未命中回 MySQL）。
 *   <li>在线状态按当前时刻判定（{@link OnlineStateService#evaluate}），不用库里存的值：后者最多晚 60 秒扫描才更新。
 *   <li>电量、信号在 IOT-12 之前为空，前端显示“—”。
 * </ul>
 */
public class DeviceViews {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final String DEFAULT_NICKNAME = "微信用户";

    private final JdbcClient jdbc;
    private final DeviceAccessService access;
    private final DeviceRuntimeService runtime;
    private final OnlineStateService online;
    private final boolean bleAvailable;
    private final Clock clock;

    public DeviceViews(
            JdbcClient jdbc,
            DeviceAccessService access,
            DeviceRuntimeService runtime,
            OnlineStateService online,
            boolean bleAvailable,
            Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.runtime = runtime;
        this.online = online;
        this.bleAvailable = bleAvailable;
        this.clock = clock;
    }

    // ------------------------------------------------------------ 契约结构

    public record DeviceCard(
            long id,
            String sn,
            String name,
            String spaceNo,
            DeviceGrant.Role role,
            OffsetDateTime validTo,
            boolean enabled,
            OnlineState onlineState,
            GateStatus gateStatus,
            Boolean occupied,
            Integer batteryPct,
            Long pendingCommandId) {}

    public record DeviceStatus(
            long id,
            boolean enabled,
            OnlineState onlineState,
            GateStatus gateStatus,
            Boolean occupied,
            Integer batteryPct,
            Integer signalLevel,
            OffsetDateTime statusRefreshedAt,
            OffsetDateTime lastSeenAt,
            Long pendingCommandId) {}

    public record OperationRecord(
            String id,
            OffsetDateTime at,
            boolean approx,
            String source,
            String action,
            String operatorName,
            String operatorRole,
            String channel,
            String result,
            String resultCode,
            Integer durationMs) {}

    /** 契约 DeviceDetail = DeviceCard 的字段 + 控制页附加字段 */
    public record DeviceDetail(
            long id,
            String sn,
            String name,
            String spaceNo,
            DeviceGrant.Role role,
            OffsetDateTime validTo,
            boolean enabled,
            OnlineState onlineState,
            GateStatus gateStatus,
            Boolean occupied,
            Integer batteryPct,
            Long pendingCommandId,
            String model,
            String fwVersion,
            Integer signalLevel,
            OffsetDateTime statusRefreshedAt,
            OffsetDateTime lastSeenAt,
            String ownerNickname,
            boolean bleAvailable,
            OperationRecord lastOperation) {}

    // ------------------------------------------------------------ 查询

    /** 设备主档里与展示有关的列 */
    private record Row(
            long id,
            String sn,
            String name,
            String spaceNo,
            boolean enabled,
            DeviceGrant.Role role,
            LocalDateTime validTo,
            Integer batteryPct,
            Integer signalLevel,
            Long pendingCommandId) {}

    private static final String COLUMNS =
            """
            d.id, d.sn, d.name, d.space_no, d.enabled, r.battery_pct, r.signal_level,
            (SELECT c.id FROM control_command c WHERE c.device_id = d.id AND c.inflight_flag = 1) AS pending_command_id
            """;

    /** 我的车位：作为主人的在前（按成为主人的先后），有效期内被授权的在后（按授权开始先后）；到期、撤销、退出的不出现 */
    public List<DeviceCard> list(long userId) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZONE);
        List<Row> rows = new ArrayList<>();
        rows.addAll(jdbc.sql(
                        "SELECT " + COLUMNS + ", NULL AS valid_to, 'OWNER' AS role"
                                + """
                           FROM device d
                           LEFT JOIN device_runtime r ON r.device_id = d.id
                          WHERE d.bind_status = 'BOUND' AND d.owner_user_id = :user
                          ORDER BY d.owner_since, d.id""")
                .param("user", userId)
                .query(DeviceViews::row)
                .list());
        rows.addAll(jdbc.sql(
                        "SELECT " + COLUMNS + ", m.valid_to, 'MEMBER' AS role"
                                + """
                           FROM device_member m
                           JOIN device d ON d.id = m.device_id
                           LEFT JOIN device_runtime r ON r.device_id = d.id
                          WHERE m.user_id = :user AND m.status = 'ACTIVE'
                            AND m.valid_from <= :now AND (m.valid_to IS NULL OR m.valid_to > :now)
                            AND d.bind_status = 'BOUND' AND d.owner_user_id <> :user
                          ORDER BY m.valid_from, m.id""")
                .param("user", userId)
                .param("now", now)
                .query(DeviceViews::row)
                .list());
        return rows.stream().map(this::card).toList();
    }

    /** 已判定权限的用户看到的卡片 */
    public DeviceCard card(DeviceGrant grant) {
        return card(load(grant));
    }

    /** 绑定成功等流程直接取卡片：按 VIEW 级别判定权限 */
    public DeviceCard card(long userId, long deviceId) {
        return card(access.check(userId, deviceId, AccessLevel.VIEW));
    }

    public DeviceStatus status(DeviceGrant grant) {
        Row row = load(grant);
        Optional<DeviceRuntime> rt = runtime.find(row.id());
        return new DeviceStatus(
                row.id(),
                row.enabled(),
                online.evaluate(rt.orElse(null)),
                rt.map(DeviceRuntime::gateStatus).orElse(GateStatus.UNKNOWN),
                rt.map(DeviceRuntime::occupied).orElse(null),
                row.batteryPct(),
                row.signalLevel(),
                time(rt.map(DeviceRuntime::statusRefreshedAt).orElse(null)),
                time(rt.map(DeviceRuntime::lastSeenAt).orElse(null)),
                row.pendingCommandId());
    }

    public DeviceDetail detail(DeviceGrant grant) {
        DeviceCard card = card(grant);
        DeviceStatus status = status(grant);
        var extra = jdbc.sql(
                        """
                        SELECT d.model, d.fw_version, u.nickname
                          FROM device d LEFT JOIN app_user u ON u.id = d.owner_user_id
                         WHERE d.id = ?""")
                .param(grant.deviceId())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)})
                .single();
        return new DeviceDetail(
                card.id(),
                card.sn(),
                card.name(),
                card.spaceNo(),
                card.role(),
                card.validTo(),
                card.enabled(),
                card.onlineState(),
                card.gateStatus(),
                card.occupied(),
                card.batteryPct(),
                card.pendingCommandId(),
                extra[0],
                extra[1],
                status.signalLevel(),
                status.statusRefreshedAt(),
                status.lastSeenAt(),
                extra[2] == null || extra[2].isBlank() ? DEFAULT_NICKNAME : extra[2],
                bleAvailable,
                lastOperation(grant).orElse(null));
    }

    /**
     * 最近一条本人可见的远程 / 蓝牙操作，可见范围与操作记录一致（主人：自成为主人起全部 + 本人；被授权人：本人）。操作人名称：主人看被授权人时备注优先，
     * 其余为昵称。遥控器、地感等非 App 动作在 IOT-13 / BIZ-14 接入。
     */
    Optional<OperationRecord> lastOperation(DeviceGrant grant) {
        return jdbc.sql(
                        """
                        SELECT c.id, c.issued_at, c.action, c.channel, c.status, c.result_code, c.duration_ms, c.role,
                               u.nickname, m.remark
                          FROM control_command c
                          JOIN device d ON d.id = c.device_id
                          LEFT JOIN app_user u ON u.id = c.user_id
                          LEFT JOIN device_member m
                                 ON m.device_id = c.device_id AND m.user_id = c.user_id AND m.active_flag = 1
                         WHERE c.device_id = :device AND c.action IN ('OPEN', 'CLOSE') AND c.user_id IS NOT NULL
                           AND (c.user_id = :user OR (:owner AND (d.owner_since IS NULL OR c.issued_at >= d.owner_since)))
                         ORDER BY c.issued_at DESC, c.id DESC
                         LIMIT 1""")
                .param("device", grant.deviceId())
                .param("user", grant.userId())
                .param("owner", grant.isOwner())
                .query((rs, n) -> {
                    String status = rs.getString("status");
                    boolean terminal = status.equals("SUCCESS") || status.equals("FAILED") || status.equals("TIMEOUT");
                    String remark = rs.getString("remark");
                    String nickname = rs.getString("nickname");
                    String name = grant.isOwner() && remark != null && !remark.isBlank()
                            ? remark
                            : nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname;
                    String role = rs.getString("role");
                    return new OperationRecord(
                            "c-" + rs.getLong("id"),
                            time(rs.getObject("issued_at", LocalDateTime.class)),
                            false,
                            "APP",
                            rs.getString("action"),
                            name,
                            role.equals("OWNER") || role.equals("MEMBER") ? role : null,
                            rs.getString("channel"),
                            terminal ? status : null,
                            rs.getString("result_code"),
                            rs.getObject("duration_ms") == null ? null : rs.getInt("duration_ms"));
                })
                .optional();
    }

    // ------------------------------------------------------------ 修改

    /**
     * 修改名称 / 车位号（仅主人，设备禁用时也可以）。只改请求里出现的字段：名称去首尾空白后 1–32 个字符；车位号至多 32 个字符，空串或
     * null 表示清空。
     */
    public DeviceCard update(DeviceGrant grant, boolean hasName, String name, boolean hasSpaceNo, String spaceNo) {
        if (!hasName && !hasSpaceNo) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少修改名称或车位号中的一项");
        }
        if (hasName) {
            String n = name == null ? "" : name.strip();
            if (n.isEmpty() || n.codePointCount(0, n.length()) > 32) {
                throw new BizException(ErrorCode.BAD_REQUEST, "名称长度应为 1–32 个字符");
            }
            jdbc.sql("UPDATE device SET name = ? WHERE id = ?")
                    .params(n, grant.deviceId())
                    .update();
        }
        if (hasSpaceNo) {
            String s = spaceNo == null ? "" : spaceNo.strip();
            if (s.codePointCount(0, s.length()) > 32) {
                throw new BizException(ErrorCode.BAD_REQUEST, "车位号至多 32 个字符");
            }
            jdbc.sql("UPDATE device SET space_no = ? WHERE id = ?")
                    .params(s.isEmpty() ? null : s, grant.deviceId())
                    .update();
        }
        return card(grant);
    }

    // ------------------------------------------------------------ 内部

    /** 单台设备：角色与有效期取权限判定结果 */
    private Row load(DeviceGrant grant) {
        LocalDateTime validTo = grant.validTo() == null ? null : LocalDateTime.ofInstant(grant.validTo(), ZONE);
        return jdbc.sql("SELECT " + COLUMNS + ", :validTo AS valid_to, :role AS role"
                        + " FROM device d LEFT JOIN device_runtime r ON r.device_id = d.id WHERE d.id = :id")
                .param("validTo", validTo)
                .param("role", grant.role().name())
                .param("id", grant.deviceId())
                .query(DeviceViews::row)
                .single();
    }

    private DeviceCard card(Row row) {
        Optional<DeviceRuntime> rt = runtime.find(row.id());
        return new DeviceCard(
                row.id(),
                row.sn(),
                row.name(),
                row.spaceNo(),
                row.role(),
                row.validTo() == null ? null : row.validTo().atZone(ZONE).toOffsetDateTime(),
                row.enabled(),
                online.evaluate(rt.orElse(null)),
                rt.map(DeviceRuntime::gateStatus).orElse(GateStatus.UNKNOWN),
                rt.map(DeviceRuntime::occupied).orElse(null),
                row.batteryPct(),
                row.pendingCommandId());
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getString("sn"),
                rs.getString("name"),
                rs.getString("space_no"),
                rs.getBoolean("enabled"),
                DeviceGrant.Role.valueOf(rs.getString("role")),
                rs.getObject("valid_to", LocalDateTime.class),
                rs.getObject("battery_pct") == null ? null : rs.getInt("battery_pct"),
                rs.getObject("signal_level") == null ? null : rs.getInt("signal_level"),
                rs.getObject("pending_command_id") == null ? null : rs.getLong("pending_command_id"));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime time(LocalDateTime local) {
        return local == null ? null : local.atZone(ZONE).toOffsetDateTime();
    }
}
