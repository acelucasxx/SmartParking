package com.smartparking.admin.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台接口守卫，失败即拒：请求里没有 {@link AdminPrincipal}（未登录）→ 401 / 10002；角色不在 {@link AdminRoles} 中 → 403 / 10003。
 *
 * <p>后台登录与会话（BIZ-08）上线前，没有任何代码写入 {@link AdminPrincipal}，后台接口全部 401，不会裸奔。
 */
public class AdminAccessInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        if (!(request.getAttribute(AdminPrincipal.ATTRIBUTE) instanceof AdminPrincipal principal)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        AdminRoles allowed = handler instanceof HandlerMethod method ? AdminAccessGuard.roles(method) : null;
        // 没有映射到接口（404）时不判角色；映射到的接口都已由启动检查保证标了角色
        if (allowed != null && Arrays.stream(allowed.value()).noneMatch(r -> r == principal.role())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return true;
    }
}
