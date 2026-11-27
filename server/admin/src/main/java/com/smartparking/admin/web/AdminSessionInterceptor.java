package com.smartparking.admin.web;

import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.account.AdminAccounts.Account;
import com.smartparking.admin.account.AdminAccounts.Status;
import com.smartparking.admin.auth.AdminSessionStore;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台会话：按 {@code Authorization: Bearer} 找到会话、顺延空闲超时，并按库里的最新状态写入 {@link AdminPrincipal}。
 *
 * <ul>
 *   <li>没有或无效的 token 不写入，交给 {@link AdminAccessInterceptor} 返回 401 / 10002。
 *   <li>账号已删除或已停用：作废该会话，按未登录处理。
 *   <li>仍在用临时密码：除查看当前账号、修改密码、退出外，一律 403 / 10003“请先修改密码”。
 * </ul>
 */
public class AdminSessionInterceptor implements HandlerInterceptor {

    static final String TOKEN_ATTRIBUTE = AdminSessionInterceptor.class.getName() + ".token";
    static final String MUST_CHANGE_ATTRIBUTE = AdminSessionInterceptor.class.getName() + ".mustChangePassword";
    private static final String BEARER = "Bearer ";
    private static final Set<String> TEMP_PASSWORD_ALLOWED =
            Set.of("GET /api/admin/auth/me", "PUT /api/admin/auth/password", "POST /api/admin/auth/logout");

    private final AdminSessionStore sessions;
    private final AdminAccounts accounts;
    private final Clock clock;

    public AdminSessionInterceptor(AdminSessionStore sessions, AdminAccounts accounts, Clock clock) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod()) || request.getAttribute(AdminPrincipal.ATTRIBUTE) != null) {
            return true;
        }
        String token = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        Optional<Long> adminId = sessions.touch(token, clock.instant());
        if (adminId.isEmpty()) {
            return true;
        }
        Optional<Account> account = accounts.find(adminId.get());
        if (account.isEmpty() || account.get().status() == Status.DISABLED) {
            sessions.revoke(token);
            return true;
        }
        Account a = account.get();
        request.setAttribute(
                AdminPrincipal.ATTRIBUTE, new AdminPrincipal(a.id(), a.username(), a.displayName(), a.role()));
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        request.setAttribute(MUST_CHANGE_ATTRIBUTE, a.mustChangePassword());
        if (a.mustChangePassword() && !TEMP_PASSWORD_ALLOWED.contains(request.getMethod() + " " + path(request))) {
            throw new BizException(ErrorCode.FORBIDDEN, "请先修改密码");
        }
        return true;
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).strip();
    }
}
