package com.smartparking.admin.account;

import com.smartparking.admin.account.AdminAccounts.Account;
import com.smartparking.admin.account.AdminAccounts.Status;
import com.smartparking.admin.auth.AdminSessionStore;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AuditTrail;
import com.smartparking.common.web.PageResult;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

/** 后台账号管理（仅超级管理员）：新建、修改角色 / 名称 / 状态、重置密码 */
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9_]{2,31}$");
    public static final int DISPLAY_NAME_MAX = 32;

    private final AdminAccounts accounts;
    private final Passwords passwords;
    private final AdminSessionStore sessions;

    public AdminUserService(AdminAccounts accounts, Passwords passwords, AdminSessionStore sessions) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    public record WithPassword(Account account, String tempPassword) {}

    public PageResult<Account> list(int page, int size) {
        return new PageResult<>(accounts.count(), page, size, accounts.page(page, size));
    }

    /** 用户名不合规、名称为空或超长、未给角色 10001；用户名已存在 40030 */
    public WithPassword create(String username, String displayName, AdminRole role) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名为小写字母开头的 3–32 位小写字母、数字或下划线");
        }
        String name = displayName(displayName);
        if (role == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择角色");
        }
        String temp = passwords.temporary();
        long id;
        try {
            id = accounts.create(username, passwords.hash(temp), name, role);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        AuditTrail.target(id);
        AuditTrail.detail("username", username);
        AuditTrail.detail("role", role.name());
        log.info("新建后台账号 {}（{}）", username, role);
        return new WithPassword(accounts.find(id).orElseThrow(), temp);
    }

    /**
     * 只改传入的字段（为 null 表示不改）。不能停用自己或修改自己的角色（10003）；停用时立即作废该账号全部会话。
     */
    public Account update(AdminPrincipal actor, long adminId, String displayName, AdminRole role, Status status) {
        Account current = accounts.find(adminId).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "账号不存在"));
        String name = displayName == null ? current.displayName() : displayName(displayName);
        AdminRole nextRole = role == null ? current.role() : role;
        Status nextStatus = status == null ? current.status() : status;
        if (actor.adminId() == adminId && (nextRole != current.role() || nextStatus == Status.DISABLED)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能停用自己或修改自己的角色");
        }
        accounts.update(adminId, name, nextRole, nextStatus);
        if (!name.equals(current.displayName())) {
            AuditTrail.detail("displayName", name);
        }
        if (nextRole != current.role()) {
            AuditTrail.detail("role", current.role() + " → " + nextRole);
        }
        if (nextStatus != current.status()) {
            AuditTrail.detail("status", current.status() + " → " + nextStatus);
        }
        if (nextStatus == Status.DISABLED && current.status() != Status.DISABLED) {
            int revoked = sessions.revokeAll(adminId, null);
            log.info("后台账号 {} 已停用，作废会话 {} 个", current.username(), revoked);
        }
        return accounts.find(adminId).orElseThrow();
    }

    /** 生成新的临时密码并解除锁定，作废该账号全部会话 */
    public WithPassword resetPassword(long adminId) {
        String temp = passwords.temporary();
        if (!accounts.resetPassword(adminId, passwords.hash(temp))) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        sessions.revokeAll(adminId, null);
        Account account = accounts.find(adminId).orElseThrow();
        log.info("重置后台账号 {} 的密码", account.username());
        return new WithPassword(account, temp);
    }

    public List<AdminAccounts.Role> roles() {
        return accounts.roles();
    }

    private static String displayName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > DISPLAY_NAME_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称为 1–" + DISPLAY_NAME_MAX + " 个字符");
        }
        return name;
    }
}
