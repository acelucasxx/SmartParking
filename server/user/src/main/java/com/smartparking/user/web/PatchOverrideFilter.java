package com.smartparking.user.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 小程序 {@code wx.request} 不支持 PATCH：{@code POST} + 请求头 {@code X-HTTP-Method-Override: PATCH} 按 PATCH 处理。
 *
 * <p>只允许改写成 PATCH，其他取值原样按 POST 处理，避免借此绕过方法限制。只作用于 {@code /api/app/*}。
 */
public class PatchOverrideFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-HTTP-Method-Override";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "PATCH".equalsIgnoreCase(request.getHeader(HEADER))) {
            chain.doFilter(
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public String getMethod() {
                            return "PATCH";
                        }
                    },
                    response);
            return;
        }
        chain.doFilter(request, response);
    }
}
