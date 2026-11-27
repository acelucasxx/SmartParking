package com.smartparking.admin.oplog;

import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AuditTrail;
import com.smartparking.common.web.Audited;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 操作日志“切面”：标了 {@link Audited} 的后台接口在请求结束后写一条 {@code operation_log}。
 *
 * <p>排在会话拦截器之后、角色判定之前，因此越权调用（403）也会记为 FAIL；未登录的请求不记。结果按响应状态判定：2xx/3xx 为
 * SUCCESS，其余为 FAIL。写入失败只记错误日志，不影响响应。
 */
public class OperationLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OperationLogInterceptor.class);

    private final OperationLogRepository logs;

    public OperationLogInterceptor(OperationLogRepository logs) {
        this.logs = logs;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(Audited.class)) {
            // 先建好本请求的记录，控制器里的 AuditTrail 调用写入同一个对象
            AuditTrail.current();
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod method)) {
            return;
        }
        Audited audited = method.getMethodAnnotation(Audited.class);
        if (audited == null || !(request.getAttribute(AdminPrincipal.ATTRIBUTE) instanceof AdminPrincipal admin)) {
            return;
        }
        AuditTrail trail = request.getAttribute(AuditTrail.ATTRIBUTE) instanceof AuditTrail t ? t : null;
        String targetId = trail != null && trail.targetId() != null ? trail.targetId() : pathVariable(request, audited);
        boolean success = ex == null && response.getStatus() < 400;
        try {
            logs.insert(new OperationLogRepository.Entry(
                    admin.adminId(),
                    admin.username(),
                    audited.action(),
                    audited.targetType().isEmpty() ? null : audited.targetType(),
                    targetId,
                    trail == null ? null : trail.reason(),
                    trail == null ? null : trail.detail(),
                    success,
                    request.getRemoteAddr()));
        } catch (RuntimeException e) {
            log.error("操作日志写入失败：{} {} 目标 {}", admin.username(), audited.action(), targetId, e);
        }
    }

    private static String pathVariable(HttpServletRequest request, Audited audited) {
        if (audited.targetIdVariable().isEmpty()) {
            return null;
        }
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object value = vars instanceof Map<?, ?> map ? map.get(audited.targetIdVariable()) : null;
        return value == null ? null : value.toString();
    }
}
