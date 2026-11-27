package com.smartparking.admin.web;

import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRoles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 启动检查：{@code /api/admin/**} 下除登录外的每个接口都必须标 {@link AdminRoles}（方法或类上），且角色不能为空；
 * 声明了 {@link AdminPrincipal} 参数的接口必须在后台路径下。任一不满足即拒绝启动。
 */
final class AdminAccessGuard {

    static final String ADMIN_PREFIX = "/api/admin/";
    static final String LOGIN_PATH = "/api/admin/auth/login";

    private AdminAccessGuard() {}

    static AdminRoles roles(HandlerMethod method) {
        AdminRoles onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), AdminRoles.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), AdminRoles.class);
    }

    static void verify(RequestMappingHandlerMapping mapping) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mapping.getHandlerMethods().entrySet()) {
            problems.addAll(problems(entry.getKey().getPatternValues(), entry.getValue()));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("后台接口未声明允许的角色：\n  " + String.join("\n  ", problems));
        }
    }

    static List<String> problems(Set<String> patterns, HandlerMethod method) {
        AdminRoles roles = roles(method);
        boolean wantsPrincipal = Arrays.stream(method.getMethodParameters())
                .anyMatch(p -> AdminPrincipal.class.equals(p.getParameterType()));
        List<String> problems = new ArrayList<>();
        for (String pattern : patterns) {
            boolean admin = pattern.startsWith(ADMIN_PREFIX) && !pattern.equals(LOGIN_PATH);
            if (admin && (roles == null || roles.value().length == 0)) {
                problems.add(pattern + " → " + method + " 缺少 @AdminRoles");
            }
            if (wantsPrincipal && !admin) {
                problems.add(pattern + " → " + method + " 不在后台路径下却声明了 AdminPrincipal");
            }
        }
        return problems;
    }
}
