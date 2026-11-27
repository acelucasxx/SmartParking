package com.smartparking.admin.web;

import com.smartparking.admin.ops.AdminDeviceOps;
import com.smartparking.admin.query.AdminDeviceQueries;
import com.smartparking.admin.query.CommandAudits;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.Audited;
import com.smartparking.iot.selfcheck.SelfCheckAction;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备运维操作（契约 device 分组）：每个操作都写操作日志 */
@RestController
@RequestMapping("/api/admin/devices/{deviceId}")
public class AdminDeviceOpsController {

    private final AdminDeviceOps ops;
    private final AdminDeviceQueries devices;
    private final CommandAudits commands;

    public AdminDeviceOpsController(AdminDeviceOps ops, AdminDeviceQueries devices, CommandAudits commands) {
        this.ops = ops;
        this.devices = devices;
        this.commands = commands;
    }

    public record ReasonInput(String reason) {}

    public record ForceUnbindInput(String reason, Boolean resetActivateCode) {}

    public record ReplacePartInput(String reason, AdminDeviceOps.Part part, String newValue) {}

    public record SelfCheckInput(SelfCheckAction action) {}

    public record ActivateCode(String activateCode) {}

    @PostMapping("/force-unbind")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.SUPPORT})
    @Audited(action = "DEVICE_FORCE_UNBIND", targetType = "DEVICE", targetIdVariable = "deviceId")
    public ApiResponse<Map<String, String>> forceUnbind(
            AdminPrincipal admin, @PathVariable long deviceId, @RequestBody ForceUnbindInput body) {
        String code = ops.forceUnbind(admin, deviceId, body.reason(), Boolean.TRUE.equals(body.resetActivateCode()));
        // 契约：activateCode 为 string 或 null，未重置时显式返回 null
        Map<String, String> data = new HashMap<>();
        data.put("activateCode", code);
        return ApiResponse.ok(data);
    }

    @PostMapping("/reset-activate-code")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.SUPPORT})
    @Audited(action = "DEVICE_RESET_CODE", targetType = "DEVICE", targetIdVariable = "deviceId")
    public ApiResponse<ActivateCode> resetActivateCode(
            AdminPrincipal admin, @PathVariable long deviceId, @RequestBody ReasonInput body) {
        return ApiResponse.ok(new ActivateCode(ops.resetActivateCode(admin, deviceId, body.reason())));
    }

    @PostMapping("/disable")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS})
    @Audited(action = "DEVICE_DISABLE", targetType = "DEVICE", targetIdVariable = "deviceId")
    public ApiResponse<Void> disable(AdminPrincipal admin, @PathVariable long deviceId, @RequestBody ReasonInput body) {
        ops.setEnabled(admin, deviceId, false, body.reason());
        return ApiResponse.ok();
    }

    @PostMapping("/enable")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS})
    @Audited(action = "DEVICE_ENABLE", targetType = "DEVICE", targetIdVariable = "deviceId")
    public ApiResponse<Void> enable(AdminPrincipal admin, @PathVariable long deviceId, @RequestBody ReasonInput body) {
        ops.setEnabled(admin, deviceId, true, body.reason());
        return ApiResponse.ok();
    }

    /** 返回换件后的设备（契约 AdminDevice） */
    @PostMapping("/replace-part")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS})
    @Audited(action = "DEVICE_REPLACE_PART", targetType = "DEVICE", targetIdVariable = "deviceId")
    public ApiResponse<AdminQueryController.AdminDevice> replacePart(
            AdminPrincipal admin, @PathVariable long deviceId, @RequestBody ReplacePartInput body) {
        ops.replacePart(admin, deviceId, body.part(), body.newValue(), body.reason());
        return ApiResponse.ok(
                AdminQueryController.AdminDevice.of(devices.detail(deviceId).device()));
    }

    /** 返回新建的自检指令（契约 CommandAudit），用 GET /commands/{commandId} 轮询结果 */
    @PostMapping("/self-check")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS})
    @Audited(action = "DEVICE_SELF_CHECK", targetType = "DEVICE", targetIdVariable = "deviceId")
    public ApiResponse<AdminQueryController.CommandAudit> selfCheck(
            AdminPrincipal admin, @PathVariable long deviceId, @RequestBody SelfCheckInput body) {
        long id = ops.selfCheck(admin, deviceId, body.action());
        return ApiResponse.ok(AdminQueryController.CommandAudit.of(commands.get(id)));
    }
}
