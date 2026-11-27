package com.smartparking.admin.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 注入当前后台账号；请求里没有时按未登录处理 */
class AdminPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AdminPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Object principal = webRequest.getAttribute(AdminPrincipal.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (principal instanceof AdminPrincipal p) {
            return p;
        }
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }
}
