package com.smartparking.admin;

import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 仅测试用：后台登录（BIZ-08）上线前，用请求头 {@code X-Test-Admin: <adminId>:<角色>:<姓名>} 模拟已登录的后台账号。
 * 只在显式 {@code @Import} 的测试里生效，生产代码里没有任何写入 {@link AdminPrincipal} 的途径。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAdminIdentity {

    public static final String HEADER = "X-Test-Admin";

    public static String header(long adminId, AdminRole role, String name) {
        return adminId + ":" + role + ":" + name;
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> testAdminIdentityFilter() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String value = request.getHeader(HEADER);
                if (value != null) {
                    String[] parts = value.split(":", 3);
                    request.setAttribute(
                            AdminPrincipal.ATTRIBUTE,
                            new AdminPrincipal(
                                    Long.parseLong(parts[0]), "it-" + parts[0], parts[2], AdminRole.valueOf(parts[1])));
                }
                chain.doFilter(request, response);
            }
        });
        registration.addUrlPatterns("/api/admin/*");
        return registration;
    }
}
