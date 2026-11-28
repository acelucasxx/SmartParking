package com.smartparking.admin.web;

import com.smartparking.admin.ops.AdminUserOps;
import com.smartparking.admin.web.AdminDeviceOpsController.ReasonInput;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.Audited;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 封禁 / 解封用户（契约 user 分组）：必填原因，写操作日志 */
@RestController
@RequestMapping("/api/admin/users/{userId}")
public class AdminUserOpsController {

    private final AdminUserOps ops;

    public AdminUserOpsController(AdminUserOps ops) {
        this.ops = ops;
    }

    @PostMapping("/ban")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.SUPPORT})
    @Audited(action = "USER_BAN", targetType = "APP_USER", targetIdVariable = "userId")
    public ApiResponse<Void> ban(AdminPrincipal admin, @PathVariable long userId, @RequestBody ReasonInput body) {
        ops.setBanned(admin, userId, true, body.reason());
        return ApiResponse.ok();
    }

    @PostMapping("/unban")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.SUPPORT})
    @Audited(action = "USER_UNBAN", targetType = "APP_USER", targetIdVariable = "userId")
    public ApiResponse<Void> unban(AdminPrincipal admin, @PathVariable long userId, @RequestBody ReasonInput body) {
        ops.setBanned(admin, userId, false, body.reason());
        return ApiResponse.ok();
    }
}
