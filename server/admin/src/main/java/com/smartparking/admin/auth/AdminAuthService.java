package com.smartparking.admin.auth;

import com.smartparking.admin.AdminProperties;
import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.account.AdminAccounts.Account;
import com.smartparking.admin.account.AdminAccounts.Status;
import com.smartparking.admin.account.Passwords;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 后台登录、退出、修改密码（PRD 6.2 登录与账号安全） */
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    private final AdminAccounts accounts;
    private final Passwords passwords;
    private final AdminSessionStore sessions;
    private final AdminProperties properties;
    private final Clock clock;

    public AdminAuthService(
            AdminAccounts accounts,
            Passwords passwords,
            AdminSessionStore sessions,
            AdminProperties properties,
            Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    public record LoginResult(AdminSessionStore.Session session, Account account) {}

    /**
     * 顺序：账号不存在 → 40001（与密码错误同一提示，不暴露账号是否存在）；锁定中 → 40002（带剩余分钟数，不校验密码、不计次）；
     * 密码错误 → 40001 并计次，达到上限即锁定并返回 40002；已停用 → 40003（密码正确才告知）。
     */
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入用户名和密码");
        }
        Optional<Account> found = accounts.findByUsername(username.strip().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            passwords.burn(password);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        Account account = found.get();
        Instant now = clock.instant();
        if (account.lockedAt(now)) {
            throw locked(account.lockedUntil(), now);
        }
        if (!passwords.matches(password, account.passwordHash())) {
            Optional<Account> after = accounts.recordFailure(
                    account.id(), now, properties.maxFailures(), now.plus(properties.lockDuration()));
            if (after.isEmpty()) {
                // 并发的另一次错误刚好触发了锁定
                throw locked(
                        accounts.find(account.id()).map(Account::lockedUntil).orElse(now), now);
            }
            if (after.get().lockedAt(now)) {
                log.warn(
                        "后台账号 {} 连续 {} 次密码错误，锁定至 {}",
                        account.username(),
                        properties.maxFailures(),
                        after.get().lockedUntil());
                throw locked(after.get().lockedUntil(), now);
            }
            int left = properties.maxFailures() - after.get().failedAttempts();
            throw new BizException(ErrorCode.LOGIN_FAILED, "用户名或密码错误，还可尝试 " + left + " 次");
        }
        if (account.status() == Status.DISABLED) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        accounts.loginSucceeded(account.id(), now);
        log.info("后台账号 {} 登录", account.username());
        return new LoginResult(sessions.create(account.id(), now), account);
    }

    public void logout(String token) {
        sessions.revoke(token);
    }

    /** 旧密码错误 40001（不计入登录锁定）；新密码不合规或与旧密码相同 10001。成功后作废该账号其他会话，保留当前会话 */
    public void changePassword(long adminId, String currentToken, String oldPassword, String newPassword) {
        Account account = accounts.find(adminId).orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        if (!passwords.matches(oldPassword, account.passwordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "原密码错误");
        }
        Passwords.validate(newPassword);
        if (newPassword.equals(oldPassword)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        accounts.changePassword(adminId, passwords.hash(newPassword), clock.instant());
        int revoked = sessions.revokeAll(adminId, currentToken);
        log.info("后台账号 {} 修改密码，作废其他会话 {} 个", account.username(), revoked);
    }

    private static BizException locked(Instant until, Instant now) {
        long minutes = Math.max(1, (Duration.between(now, until).toSeconds() + 59) / 60);
        return new BizException(ErrorCode.ACCOUNT_LOCKED, "账号已锁定，请 " + minutes + " 分钟后再试");
    }
}
