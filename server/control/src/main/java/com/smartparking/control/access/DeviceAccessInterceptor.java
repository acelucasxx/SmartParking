package com.smartparking.control.access;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 对标了 {@link DeviceAccess} 的接口做权限判定，结果放入请求属性供 {@link DeviceGrant} 参数注入。排在登录拦截器之后；拿不到当前用户时按未登录拒绝，不放行。
 */
class DeviceAccessInterceptor implements HandlerInterceptor {

    static final String GRANT_ATTRIBUTE = DeviceGrant.class.getName();
    static final String PATH_VARIABLE = "deviceId";

    private final DeviceAccessService access;

    DeviceAccessInterceptor(DeviceAccessService access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        DeviceAccess required = method.getMethodAnnotation(DeviceAccess.class);
        if (required == null) {
            return true;
        }
        if (!(request.getAttribute(AppPrincipal.ATTRIBUTE) instanceof AppPrincipal principal)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        long deviceId = deviceId(request);
        request.setAttribute(GRANT_ATTRIBUTE, access.check(principal.userId(), deviceId, required.value()));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static long deviceId(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String raw = vars instanceof Map<?, ?> map ? ((Map<String, String>) map).get(PATH_VARIABLE) : null;
        try {
            long id = Long.parseLong(raw);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // 落到下面统一按不存在处理
        }
        throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
    }
}
