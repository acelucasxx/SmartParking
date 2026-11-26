package com.smartparking.user.web;

import java.util.List;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 小程序接口 {@code /api/app/**} 除登录外都要求登录 */
public class AppWebConfigurer implements WebMvcConfigurer {

    static final String APP_PATHS = "/api/app/**";
    static final String LOGIN_PATH = "/api/app/auth/login";

    private final AppAuthInterceptor interceptor;

    public AppWebConfigurer(AppAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(APP_PATHS).excludePathPatterns(LOGIN_PATH);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AppPrincipalArgumentResolver());
    }
}
