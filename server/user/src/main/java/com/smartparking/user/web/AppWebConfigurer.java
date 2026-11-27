package com.smartparking.user.web;

import java.util.List;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 小程序接口 {@code /api/app/**} 除登录外都要求登录 */
public class AppWebConfigurer implements WebMvcConfigurer {

    static final String APP_PATHS = "/api/app/**";
    static final String LOGIN_PATH = "/api/app/auth/login";

    /** 最先执行：设备权限判定（control 域，order 100）依赖这里写入的当前用户 */
    static final int ORDER = 0;

    private final AppAuthInterceptor interceptor;

    public AppWebConfigurer(AppAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns(APP_PATHS)
                .excludePathPatterns(LOGIN_PATH)
                .order(ORDER);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AppPrincipalArgumentResolver());
    }
}
