package com.smartparking.common.web;

/**
 * 已登录的后台账号。{@code /api/admin/**} 的控制器方法声明此类型参数即可拿到当前账号。
 *
 * <p>由后台登录（BIZ-08）写入请求属性 {@link #ATTRIBUTE}；在此之前请求里没有它，后台接口一律 401。
 *
 * @param displayName 姓名，写入导入记录、操作日志等展示用
 */
public record AdminPrincipal(long adminId, String displayName, AdminRole role) {

    public static final String ATTRIBUTE = AdminPrincipal.class.getName();
}
