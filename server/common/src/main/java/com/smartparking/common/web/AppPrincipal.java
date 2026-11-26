package com.smartparking.common.web;

/**
 * 已登录的小程序用户。{@code /api/app/**} 的控制器方法声明此类型的参数即可拿到当前用户，未登录、会话失效或已封禁的请求到不了控制器。
 *
 * <p>由用户域的拦截器写入请求属性 {@link #ATTRIBUTE}，其他模块只依赖本类型，不依赖用户域。
 */
public record AppPrincipal(long userId) {

    public static final String ATTRIBUTE = AppPrincipal.class.getName();
}
