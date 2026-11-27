package com.smartparking.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 在 {@link Audited} 接口内为本次操作日志补充原因、明细与对象 ID。只在请求线程内有效；不在请求中调用时忽略。
 */
public final class AuditTrail {

    public static final String ATTRIBUTE = AuditTrail.class.getName();

    private String targetId;
    private String reason;
    private final Map<String, Object> detail = new LinkedHashMap<>();

    private AuditTrail() {}

    /** 对象 ID，例如新建账号后才知道的 ID */
    public static void target(Object id) {
        current().ifPresent(t -> t.targetId = id == null ? null : id.toString());
    }

    /** 高危操作的原因 */
    public static void reason(String reason) {
        current().ifPresent(t -> t.reason = reason);
    }

    /** 明细，写入 {@code detail} JSON；不要放密码、激活码等敏感值 */
    public static void detail(String key, Object value) {
        current().ifPresent(t -> t.detail.put(key, value));
    }

    public String targetId() {
        return targetId;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> detail() {
        return detail;
    }

    /** 本请求的记录；不在请求中时为空 */
    public static Optional<AuditTrail> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object existing = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof AuditTrail trail) {
            return Optional.of(trail);
        }
        AuditTrail trail = new AuditTrail();
        attributes.setAttribute(ATTRIBUTE, trail, RequestAttributes.SCOPE_REQUEST);
        return Optional.of(trail);
    }
}
