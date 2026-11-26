package com.smartparking.control.access;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * “所有控制类接口强制经过”权限判定的落实方式：启动时检查全部接口映射，以下任一情况直接拒绝启动，而不是等上线后被越权。
 *
 * <ul>
 *   <li>{@code /api/app/**} 下路径含 {@code {deviceId}}，却没有标 {@link DeviceAccess}。
 *   <li>标了 {@link DeviceAccess}，路径里却没有 {@code {deviceId}}（判定拿不到设备）。
 *   <li>声明了 {@link DeviceGrant} 参数却没有标 {@link DeviceAccess}。
 * </ul>
 */
class DeviceAccessGuard {

    static final String APP_PREFIX = "/api/app/";
    static final String DEVICE_VARIABLE = "{" + DeviceAccessInterceptor.PATH_VARIABLE + "}";

    private DeviceAccessGuard() {}

    static void verify(RequestMappingHandlerMapping mapping) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mapping.getHandlerMethods().entrySet()) {
            problems.addAll(problems(entry.getKey().getPatternValues(), entry.getValue()));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("设备类接口未接入统一权限判定（BIZ-02）：\n  " + String.join("\n  ", problems));
        }
    }

    static List<String> problems(Set<String> patterns, HandlerMethod method) {
        boolean annotated = method.hasMethodAnnotation(DeviceAccess.class);
        boolean wantsGrant = Arrays.stream(method.getMethodParameters())
                .anyMatch(p -> DeviceGrant.class.equals(p.getParameterType()));
        List<String> problems = new ArrayList<>();
        for (String pattern : patterns) {
            boolean hasDevice = pattern.contains(DEVICE_VARIABLE);
            if (pattern.startsWith(APP_PREFIX) && hasDevice && !annotated) {
                problems.add(pattern + " → " + method + " 缺少 @DeviceAccess");
            }
            if (annotated && !hasDevice) {
                problems.add(pattern + " → " + method + " 标了 @DeviceAccess，路径却没有 " + DEVICE_VARIABLE);
            }
        }
        if (wantsGrant && !annotated) {
            problems.add(method + " 声明了 DeviceGrant 参数却没有标 @DeviceAccess");
        }
        return problems;
    }
}
