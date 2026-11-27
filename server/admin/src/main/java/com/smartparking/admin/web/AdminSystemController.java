package com.smartparking.admin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartparking.admin.account.AdminAccounts.Account;
import com.smartparking.admin.account.AdminAccounts.Status;
import com.smartparking.admin.account.AdminUserService;
import com.smartparking.admin.oplog.OperationLogRepository;
import com.smartparking.admin.oplog.OperationLogRepository.LogView;
import com.smartparking.admin.settings.ConfigService;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.Audited;
import com.smartparking.common.web.PageResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统设置（契约 system 分组）：账号、角色、操作日志、业务参数。仅超级管理员 */
@RestController
@RequestMapping("/api/admin")
@AdminRoles(AdminRole.SUPER_ADMIN)
public class AdminSystemController {

    private static final Set<String> PATCH_FIELDS = Set.of("displayName", "role", "status");

    private final AdminUserService users;
    private final OperationLogRepository logs;
    private final ConfigService configs;

    public AdminSystemController(AdminUserService users, OperationLogRepository logs, ConfigService configs) {
        this.users = users;
        this.logs = logs;
        this.configs = configs;
    }

    // ------------------------------------------------------------ 账号

    /** 契约 AdminUser；锁定已过期时 lockedUntil 为 null */
    public record AdminUser(
            long id,
            String username,
            String displayName,
            AdminRole role,
            String status,
            OffsetDateTime lockedUntil,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt) {

        static AdminUser of(Account a) {
            boolean locked = a.lockedUntil() != null && a.lockedUntil().isAfter(Instant.now());
            return new AdminUser(
                    a.id(),
                    a.username(),
                    a.displayName(),
                    a.role(),
                    a.status().name(),
                    locked ? Times.of(a.lockedUntil()) : null,
                    Times.of(a.lastLoginAt()),
                    Times.of(a.createdAt()));
        }
    }

    /** 契约 AdminUserWithPassword：临时密码只在本次响应中出现 */
    public record AdminUserWithPassword(
            long id,
            String username,
            String displayName,
            AdminRole role,
            String status,
            OffsetDateTime lockedUntil,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt,
            String tempPassword) {

        static AdminUserWithPassword of(AdminUserService.WithPassword w) {
            AdminUser u = AdminUser.of(w.account());
            return new AdminUserWithPassword(
                    u.id(),
                    u.username(),
                    u.displayName(),
                    u.role(),
                    u.status(),
                    u.lockedUntil(),
                    u.lastLoginAt(),
                    u.createdAt(),
                    w.tempPassword());
        }
    }

    public record CreateAdminUser(String username, String displayName, AdminRole role) {}

    @GetMapping("/admin-users")
    public ApiResponse<PageResult<AdminUser>> listAdminUsers(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        PageResult<Account> result = users.list(ps[0], ps[1]);
        return ApiResponse.ok(new PageResult<>(
                result.total(),
                result.page(),
                result.size(),
                result.items().stream().map(AdminUser::of).toList()));
    }

    @PostMapping("/admin-users")
    @Audited(action = "ADMIN_USER_CREATE", targetType = "ADMIN_USER")
    public ApiResponse<AdminUserWithPassword> createAdminUser(@RequestBody CreateAdminUser body) {
        return ApiResponse.ok(AdminUserWithPassword.of(users.create(body.username(), body.displayName(), body.role())));
    }

    /** 只改出现的字段；空对象、未知字段、取值不合法都是 10001 */
    @PatchMapping("/admin-users/{adminId}")
    @Audited(action = "ADMIN_USER_UPDATE", targetType = "ADMIN_USER", targetIdVariable = "adminId")
    public ApiResponse<AdminUser> updateAdminUser(
            AdminPrincipal admin, @PathVariable long adminId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少修改一项");
        }
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!PATCH_FIELDS.contains(field)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不支持修改 " + field);
            }
        }
        String displayName = text(body, "displayName");
        AdminRole role = enumValue(body, "role", AdminRole.class);
        Status status = enumValue(body, "status", Status.class);
        return ApiResponse.ok(AdminUser.of(users.update(admin, adminId, displayName, role, status)));
    }

    @PostMapping("/admin-users/{adminId}/reset-password")
    @Audited(action = "ADMIN_USER_RESET_PASSWORD", targetType = "ADMIN_USER", targetIdVariable = "adminId")
    public ApiResponse<AdminUserWithPassword> resetPassword(@PathVariable long adminId) {
        return ApiResponse.ok(AdminUserWithPassword.of(users.resetPassword(adminId)));
    }

    // ------------------------------------------------------------ 角色

    public record Role(AdminRole code, String name, List<String> permissions) {}

    @GetMapping("/roles")
    public ApiResponse<List<Role>> roles() {
        return ApiResponse.ok(users.roles().stream()
                .map(r -> new Role(r.code(), r.name(), r.permissions()))
                .toList());
    }

    // ------------------------------------------------------------ 操作日志

    /** 契约 OperationLog */
    public record OperationLog(
            long id,
            String adminName,
            String action,
            String targetType,
            String targetId,
            String reason,
            String result,
            String ip,
            JsonNode detail,
            OffsetDateTime createdAt) {

        static OperationLog of(LogView v) {
            return new OperationLog(
                    v.id(),
                    v.adminName(),
                    v.action(),
                    v.targetType(),
                    v.targetId(),
                    v.reason(),
                    v.result(),
                    v.ip(),
                    v.detail(),
                    Times.of(v.createdAt()));
        }
    }

    @GetMapping("/operation-logs")
    public ApiResponse<PageResult<OperationLog>> operationLogs(
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "起始时间须早于结束时间");
        }
        PageResult<LogView> result = logs.list(
                new OperationLogRepository.Filter(
                        adminId,
                        action,
                        targetId,
                        from == null ? null : from.toInstant(),
                        to == null ? null : to.toInstant()),
                ps[0],
                ps[1]);
        return ApiResponse.ok(new PageResult<>(
                result.total(),
                result.page(),
                result.size(),
                result.items().stream().map(OperationLog::of).toList()));
    }

    // ------------------------------------------------------------ 业务参数

    /** 契约 SystemConfig；updatedBy 为修改人的显示名 */
    public record SystemConfig(
            String key,
            String value,
            String valueType,
            String description,
            String updatedBy,
            OffsetDateTime updatedAt) {

        static SystemConfig of(ConfigService.Config c) {
            return new SystemConfig(
                    c.key(), c.value(), c.valueType(), c.description(), c.updatedBy(), Times.of(c.updatedAt()));
        }
    }

    public record ConfigValue(String value) {}

    @GetMapping("/configs")
    public ApiResponse<List<SystemConfig>> configs() {
        return ApiResponse.ok(configs.list().stream().map(SystemConfig::of).toList());
    }

    @PutMapping("/configs/{key}")
    @Audited(action = "CONFIG_UPDATE", targetType = "SYSTEM_CONFIG", targetIdVariable = "key")
    public ApiResponse<SystemConfig> updateConfig(
            AdminPrincipal admin, @PathVariable String key, @RequestBody ConfigValue body) {
        return ApiResponse.ok(SystemConfig.of(configs.update(admin, key, body.value())));
    }

    // ------------------------------------------------------------

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isTextual()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 须为字符串");
        }
        return node.asText();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode body, String field, Class<E> type) {
        String raw = text(body, field);
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 取值不合法");
        }
    }
}
