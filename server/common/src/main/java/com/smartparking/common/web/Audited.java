package com.smartparking.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 后台写操作记操作日志（{@code operation_log}，PRD 9：保留 ≥ 180 天）。请求结束后由后台模块的拦截器统一写入，成功与失败（含
 * 无权限、参数不合法）都记；未登录的请求不记。
 *
 * <p>原因、明细或运行时才知道的对象 ID 用 {@link AuditTrail} 补充。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {

    /** 动作编码，如 {@code ADMIN_USER_CREATE}、{@code DEVICE_FORCE_UNBIND} */
    String action();

    /** 对象类型，如 {@code DEVICE}、{@code APP_USER}、{@code ADMIN_USER}、{@code SYSTEM_CONFIG}；可空 */
    String targetType() default "";

    /** 取哪个路径变量作为对象 ID；可空 */
    String targetIdVariable() default "";
}
