package com.smartparking.admin.web;

import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration {

    @Bean
    WebMvcConfigurer adminWebConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AdminAccessInterceptor())
                        .addPathPatterns("/api/admin/**")
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
