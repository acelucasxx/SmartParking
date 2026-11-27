package com.smartparking.control.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在路径含 {@code {deviceId}} 的小程序控制器方法上：进入方法前按 {@link AccessLevel} 判定当前用户对该设备的权限，不通过直接返回错误码。
 * 方法可声明 {@link DeviceGrant} 参数拿到判定结果（角色、有效期）。
 *
 * <p>强制：{@code /api/app/**} 下路径含 {@code {deviceId}} 而没有本注解的接口会让服务启动失败，见 {@link DeviceAccessGuard}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DeviceAccess {

    AccessLevel value();
}
