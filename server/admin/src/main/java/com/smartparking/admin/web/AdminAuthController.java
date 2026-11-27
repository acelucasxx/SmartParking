package com.smartparking.admin.web;

import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.account.AdminAccounts.Account;
import com.smartparking.admin.auth.AdminAuthService;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AuditTrail;
import com.smartparking.common.web.Audited;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 后台登录与账号安全（契约 auth 分组） */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService auth;
    private final AdminAccounts accounts;

    public AdminAuthController(AdminAuthService auth, AdminAccounts accounts) {
        this.auth = auth;
        this.accounts = accounts;
    }

    public record LoginRequest(String username, String password) {}

    public record PasswordRequest(String oldPassword, String newPassword) {}

    /** 契约 AdminProfile；权限点用于前端按钮级控制，超级管理员为 ["*"] */
    public record AdminProfile(
            long id,
            String username,
            String displayName,
            AdminRole role,
            List<String> permissions,
            boolean mustChangePassword) {}

    public record LoginResult(String token, OffsetDateTime expiresAt, AdminProfile admin) {}

    /** 不经过会话拦截器与角色判定（{@link AdminAccessGuard#LOGIN_PATH}） */
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody LoginRequest body) {
        AdminAuthService.LoginResult result = auth.login(body.username(), body.password());
        return ApiResponse.ok(new LoginResult(
                result.session().token(), Times.of(result.session().expiresAt()), profile(result.account())));
    }

    @PostMapping("/logout")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.SUPPORT})
    public ApiResponse<Void> logout(AdminPrincipal admin, HttpServletRequest request) {
        if (request.getAttribute(AdminSessionInterceptor.TOKEN_ATTRIBUTE) instanceof String token) {
            auth.logout(token);
        }
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.SUPPORT})
    public ApiResponse<AdminProfile> me(AdminPrincipal admin) {
        Account account = accounts.find(admin.adminId()).orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        return ApiResponse.ok(profile(account));
    }

    @PutMapping("/password")
    @AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.SUPPORT})
    @Audited(action = "ADMIN_PASSWORD_CHANGE", targetType = "ADMIN_USER")
    public ApiResponse<Void> changePassword(
            AdminPrincipal admin, @RequestBody PasswordRequest body, HttpServletRequest request) {
        AuditTrail.target(admin.adminId());
        String token = request.getAttribute(AdminSessionInterceptor.TOKEN_ATTRIBUTE) instanceof String t ? t : null;
        auth.changePassword(admin.adminId(), token, body.oldPassword(), body.newPassword());
        return ApiResponse.ok();
    }

    private AdminProfile profile(Account a) {
        return new AdminProfile(
                a.id(),
                a.username(),
                a.displayName(),
                a.role(),
                accounts.permissions(a.role()),
                a.mustChangePassword());
    }
}
