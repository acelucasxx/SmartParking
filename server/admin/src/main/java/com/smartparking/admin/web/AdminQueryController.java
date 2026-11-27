package com.smartparking.admin.web;

import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.query.AdminDeviceQueries;
import com.smartparking.admin.query.AdminDeviceQueries.DeviceRow;
import com.smartparking.admin.query.AdminUserQueries;
import com.smartparking.admin.query.AdminUserQueries.UserRow;
import com.smartparking.admin.query.CommandAudits;
import com.smartparking.admin.query.CommandAudits.Audit;
import com.smartparking.admin.query.DashboardQueries;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AuditTrail;
import com.smartparking.common.web.Audited;
import com.smartparking.common.web.PageResult;
import com.smartparking.iot.command.CommandStatus;
import com.smartparking.iot.runtime.OnlineState;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台查询（BIZ-09）：仪表盘、设备列表与详情、事件流、C 端用户、指令审计与导出 */
@RestController
@RequestMapping("/api/admin")
@AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.SUPPORT})
public class AdminQueryController {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DashboardQueries dashboard;
    private final AdminDeviceQueries devices;
    private final AdminUserQueries users;
    private final CommandAudits commands;

    public AdminQueryController(
            DashboardQueries dashboard, AdminDeviceQueries devices, AdminUserQueries users, CommandAudits commands) {
        this.dashboard = dashboard;
        this.devices = devices;
        this.users = users;
        this.commands = commands;
    }

    // ------------------------------------------------------------ 仪表盘

    @GetMapping("/dashboard")
    public ApiResponse<DashboardQueries.Dashboard> dashboard() {
        return ApiResponse.ok(dashboard.get());
    }

    // ------------------------------------------------------------ 设备

    /** 契约 AdminDevice */
    public record AdminDevice(
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
            OffsetDateTime lastSeenAt,
            OffsetDateTime createdAt) {

        static AdminDevice of(DeviceRow d) {
            return new AdminDevice(
                    d.id(),
                    d.sn(),
                    d.imei(),
                    d.bleMac(),
                    d.model(),
                    d.batchNo(),
                    d.bindStatus(),
                    d.enabled(),
                    d.onlineState(),
                    d.gateStatus(),
                    d.batteryPct(),
                    d.ownerPhoneMasked(),
                    Times.of(d.lastSeenAt()),
                    Times.of(d.createdAt()));
        }
    }

    public record Owner(long userId, String nickname, String phoneMasked, OffsetDateTime since) {}

    public record Member(
            long userId,
            String nickname,
            String phoneMasked,
            String remark,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            String status) {}

    /** 契约 AdminDeviceDetail */
    public record AdminDeviceDetail(
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
            OffsetDateTime lastSeenAt,
            OffsetDateTime createdAt,
            String iccid,
            String fwVersion,
            String workMode,
            Boolean occupied,
            Integer signalLevel,
            OffsetDateTime statusRefreshedAt,
            String name,
            String spaceNo,
            Owner owner,
            List<Member> members) {}

    public record DeviceEvent(
            long id,
            String gateStatus,
            String statusCode,
            String reasonCode,
            String source,
            Boolean occupied,
            boolean approx,
            String rawFrame,
            OffsetDateTime at) {}

    private static final Set<String> BIND_STATUSES = Set.of("PENDING", "BOUND");

    @GetMapping("/devices")
    public ApiResponse<PageResult<AdminDevice>> listDevices(
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String imei,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String bindStatus,
            @RequestParam(required = false) OnlineState onlineState,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        oneOf("bindStatus", bindStatus, BIND_STATUSES);
        return ApiResponse.ok(map(
                devices.list(
                        new AdminDeviceQueries.Filter(sn, imei, phone, bindStatus, onlineState, enabled), ps[0], ps[1]),
                AdminDevice::of));
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<AdminDeviceDetail> getDevice(@PathVariable long deviceId) {
        AdminDeviceQueries.Detail x = devices.detail(deviceId);
        DeviceRow d = x.device();
        return ApiResponse.ok(new AdminDeviceDetail(
                d.id(),
                d.sn(),
                d.imei(),
                d.bleMac(),
                d.model(),
                d.batchNo(),
                d.bindStatus(),
                d.enabled(),
                d.onlineState(),
                d.gateStatus(),
                d.batteryPct(),
                d.ownerPhoneMasked(),
                Times.of(d.lastSeenAt()),
                Times.of(d.createdAt()),
                x.iccid(),
                x.fwVersion(),
                x.workMode(),
                x.occupied(),
                x.signalLevel(),
                Times.of(x.statusRefreshedAt()),
                x.name(),
                x.spaceNo(),
                x.owner() == null
                        ? null
                        : new Owner(
                                x.owner().userId(),
                                x.owner().nickname(),
                                x.owner().phoneMasked(),
                                Times.of(x.owner().since())),
                x.members().stream()
                        .map(m -> new Member(
                                m.userId(),
                                m.nickname(),
                                m.phoneMasked(),
                                m.remark(),
                                Times.of(m.validFrom()),
                                Times.of(m.validTo()),
                                m.status()))
                        .toList()));
    }

    @GetMapping("/devices/{deviceId}/events")
    public ApiResponse<PageResult<DeviceEvent>> deviceEvents(
            @PathVariable long deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        return ApiResponse.ok(map(
                devices.events(deviceId, from, to, ps[0], ps[1]),
                e -> new DeviceEvent(
                        e.id(),
                        e.gateStatus(),
                        e.statusCode(),
                        e.reasonCode(),
                        e.source(),
                        e.occupied(),
                        e.approx(),
                        e.rawFrame(),
                        Times.of(e.at()))));
    }

    // ------------------------------------------------------------ C 端用户

    /** 契约 AppUser */
    public record AppUser(
            long id,
            String nickname,
            String phoneMasked,
            String status,
            int ownedCount,
            int memberCount,
            OffsetDateTime createdAt,
            OffsetDateTime lastLoginAt) {

        static AppUser of(UserRow u) {
            return new AppUser(
                    u.id(),
                    u.nickname(),
                    u.phoneMasked(),
                    u.status(),
                    u.ownedCount(),
                    u.memberCount(),
                    Times.of(u.createdAt()),
                    Times.of(u.lastLoginAt()));
        }
    }

    public record OwnedDevice(long deviceId, String sn, String name, OffsetDateTime since) {}

    public record MemberDevice(
            long deviceId, String sn, String name, OffsetDateTime validFrom, OffsetDateTime validTo, String status) {}

    /** 契约 AppUserDetail */
    public record AppUserDetail(
            long id,
            String nickname,
            String phoneMasked,
            String status,
            int ownedCount,
            int memberCount,
            OffsetDateTime createdAt,
            OffsetDateTime lastLoginAt,
            List<OwnedDevice> ownedDevices,
            List<MemberDevice> memberDevices) {}

    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "BANNED");

    @GetMapping("/users")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.SUPPORT})
    public ApiResponse<PageResult<AppUser>> listUsers(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        oneOf("status", status, USER_STATUSES);
        return ApiResponse.ok(map(users.list(phone, status, ps[0], ps[1]), AppUser::of));
    }

    @GetMapping("/users/{userId}")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.SUPPORT})
    public ApiResponse<AppUserDetail> getUser(@PathVariable long userId) {
        AdminUserQueries.Detail d = users.detail(userId);
        AppUser u = AppUser.of(d.user());
        return ApiResponse.ok(new AppUserDetail(
                u.id(),
                u.nickname(),
                u.phoneMasked(),
                u.status(),
                u.ownedCount(),
                u.memberCount(),
                u.createdAt(),
                u.lastLoginAt(),
                d.ownedDevices().stream()
                        .map(o -> new OwnedDevice(o.deviceId(), o.sn(), o.name(), Times.of(o.since())))
                        .toList(),
                d.memberDevices().stream()
                        .map(m -> new MemberDevice(
                                m.deviceId(),
                                m.sn(),
                                m.name(),
                                Times.of(m.validFrom()),
                                Times.of(m.validTo()),
                                m.status()))
                        .toList()));
    }

    // ------------------------------------------------------------ 指令审计

    /** 契约 CommandAudit */
    public record CommandAudit(
            long id,
            long deviceId,
            String sn,
            CommandAudits.Operator operator,
            String role,
            String channel,
            String action,
            String status,
            String resultCode,
            String fwVersion,
            String downFrame,
            String rawFrame,
            OffsetDateTime issuedAt,
            OffsetDateTime finishedAt,
            Integer durationMs) {

        static CommandAudit of(Audit a) {
            return new CommandAudit(
                    a.id(),
                    a.deviceId(),
                    a.sn(),
                    a.operator(),
                    a.role(),
                    a.channel(),
                    a.action(),
                    a.status(),
                    a.resultCode(),
                    a.fwVersion(),
                    a.downFrame(),
                    a.rawFrame(),
                    Times.of(a.issuedAt()),
                    Times.of(a.finishedAt()),
                    a.durationMs());
        }
    }

    private static final Set<String> CHANNELS = Set.of("REMOTE", "BLE");

    @GetMapping("/commands")
    public ApiResponse<PageResult<CommandAudit>> listCommands(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) CommandStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        oneOf("channel", channel, CHANNELS);
        return ApiResponse.ok(map(
                commands.list(
                        new CommandAudits.Filter(
                                deviceId, sn, phone, channel, status == null ? null : status.name(), from, to),
                        ps[0],
                        ps[1]),
                CommandAudit::of));
    }

    /** 筛选同列表（契约不含手机号）；文件流，失败时在写出之前返回 JSON 错误 */
    @GetMapping("/commands/export")
    @Audited(action = "COMMAND_EXPORT", targetType = "CONTROL_COMMAND")
    public void exportCommands(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) CommandStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
            HttpServletResponse response)
            throws IOException {
        oneOf("channel", channel, CHANNELS);
        CommandAudits.Filter filter =
                new CommandAudits.Filter(deviceId, sn, null, channel, status == null ? null : status.name(), from, to);
        commands.export(filter, response.getOutputStream(), rows -> {
            AuditTrail.detail("from", String.valueOf(from));
            AuditTrail.detail("to", String.valueOf(to));
            AuditTrail.detail("rows", rows);
            String fileName = "commands-" + FILE_DATE.format(from.atZoneSameInstant(AdminAccounts.ZONE)) + "-"
                    + FILE_DATE.format(to.atZoneSameInstant(AdminAccounts.ZONE)) + ".xlsx";
            response.setContentType(XLSX);
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(fileName).build().toString());
        });
    }

    @GetMapping("/commands/{commandId}")
    public ApiResponse<CommandAudit> getCommand(@PathVariable long commandId) {
        return ApiResponse.ok(CommandAudit.of(commands.get(commandId)));
    }

    // ------------------------------------------------------------

    private static <T, R> PageResult<R> map(PageResult<T> page, Function<T, R> f) {
        return new PageResult<>(
                page.total(),
                page.page(),
                page.size(),
                page.items().stream().map(f).toList());
    }

    private static void oneOf(String name, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new BizException(ErrorCode.BAD_REQUEST, name + " 取值不合法");
        }
    }
}
