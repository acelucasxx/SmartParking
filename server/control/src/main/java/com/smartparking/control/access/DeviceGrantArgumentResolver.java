package com.smartparking.control.access;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 注入 {@link DeviceAccessInterceptor} 的判定结果。没标 {@link DeviceAccess} 的方法声明此参数属于编码错误，启动时由 {@link DeviceAccessGuard} 拦下。 */
class DeviceGrantArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return DeviceGrant.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Object grant =
                webRequest.getAttribute(DeviceAccessInterceptor.GRANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (grant instanceof DeviceGrant g) {
            return g;
        }
        throw new IllegalStateException("未经权限判定却请求了 DeviceGrant：" + parameter.getExecutable());
    }
}
