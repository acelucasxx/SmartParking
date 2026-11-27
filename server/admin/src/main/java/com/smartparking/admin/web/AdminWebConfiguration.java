package com.smartparking.admin.web;

import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.auth.AdminSessionStore;
import com.smartparking.admin.oplog.OperationLogInterceptor;
import com.smartparking.admin.oplog.OperationLogRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * {@code /api/admin/**}（登录除外）依次经过：会话（写入当前账号）→ 操作日志（请求结束后写入）→ 角色判定（失败即拒）。
 */
@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration {

    static final String ADMIN_PATHS = "/api/admin/**";

    @Bean
    WebMvcConfigurer adminWebConfigurer(
            AdminSessionStore sessions, AdminAccounts accounts, OperationLogRepository logs) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AdminSessionInterceptor(sessions, accounts, Clock.systemUTC()))
                        .addPathPatterns(ADMIN_PATHS)
                        .excludePathPatterns(AdminAccessGuard.LOGIN_PATH);
                registry.addInterceptor(new OperationLogInterceptor(logs))
                        .addPathPatterns(ADMIN_PATHS)
                        .excludePathPatterns(AdminAccessGuard.LOGIN_PATH);
                registry.addInterceptor(new AdminAccessInterceptor())
                        .addPathPatterns(ADMIN_PATHS)
                        .excludePathPatterns(AdminAccessGuard.LOGIN_PATH);
            }

            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new AdminPrincipalArgumentResolver());
            }
        };
    }

    @Bean
    SmartInitializingSingleton adminAccessGuard(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        return () -> AdminAccessGuard.verify(mapping);
    }
}
