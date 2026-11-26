package com.smartparking.user.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.user.AppUserRepository;
import com.smartparking.user.AppUserRepository.Status;
import com.smartparking.user.session.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /api/app/**} 的登录与封禁拦截（PRD 5.8②：封禁用户所有接口拒绝）。
 *
 * <ul>
 *   <li>没有或无效的 {@code Authorization: Bearer} → 401 / 10002，前端统一重新 {@code wx.login}。
 *   <li>已封禁 → 403 / 10010。每个请求都查库，封禁即时生效；退出登录例外，封禁用户也可以退出。
 * </ul>
 */
public class AppAuthInterceptor implements HandlerInterceptor {

    static final String TOKEN_ATTRIBUTE = AppAuthInterceptor.class.getName() + ".token";
    private static final String BEARER = "Bearer ";

    private final SessionStore sessions;
    private final AppUserRepository users;

    public AppAuthInterceptor(SessionStore sessions, AppUserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String token = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        long userId = sessions.find(token).orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        Optional<Status> status = users.status(userId);
        if (status.isEmpty()) {
            sessions.revoke(token);
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (status.get() == Status.BANNED && !isLogout(request)) {
            throw new BizException(ErrorCode.USER_BANNED);
        }
        request.setAttribute(AppPrincipal.ATTRIBUTE, new AppPrincipal(userId));
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        return true;
    }

    private static boolean isLogout(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/api/app/auth/logout");
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).strip();
    }
}
