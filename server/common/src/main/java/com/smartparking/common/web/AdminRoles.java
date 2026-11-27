package com.smartparking.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 后台接口允许的角色，取接口契约的 {@code x-roles}。{@code /api/admin/**} 下除登录外的每个接口都必须标注，否则服务拒绝启动；
 * 当前账号的角色不在其中时返回 10003。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AdminRoles {

    AdminRole[] value();
}
