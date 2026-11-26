package com.smartparking.user.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AppPrincipal;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 把 {@link AppAuthInterceptor} 写入的当前用户注入控制器参数；拦截范围之外的接口声明此参数时按未登录处理 */
class AppPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AppPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Object principal = webRequest.getAttribute(AppPrincipal.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (principal instanceof AppPrincipal p) {
            return p;
        }
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }
}
