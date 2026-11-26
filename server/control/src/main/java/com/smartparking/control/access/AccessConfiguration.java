package com.smartparking.control.access;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
public class AccessConfiguration {

    /** 登录拦截器（用户域，order 0）先确定当前用户，这里再判定设备权限 */
    static final int INTERCEPTOR_ORDER = 100;

    @Bean
    DeviceAccessService deviceAccessService(JdbcClient jdbc) {
        return new DeviceAccessService(jdbc, Clock.systemUTC());
    }

    @Bean
    WebMvcConfigurer deviceAccessWebConfigurer(DeviceAccessService access) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new DeviceAccessInterceptor(access))
                        .addPathPatterns("/api/app/**")
                        .order(INTERCEPTOR_ORDER);
            }

            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new DeviceGrantArgumentResolver());
            }
        };
    }

    @Bean
    SmartInitializingSingleton deviceAccessGuard(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        return () -> DeviceAccessGuard.verify(mapping);
    }
}
