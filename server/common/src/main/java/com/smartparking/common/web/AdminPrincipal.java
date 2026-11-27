package com.smartparking.common.web;

/**
 * 已登录的后台账号。{@code /api/admin/**} 的控制器方法声明此类型参数即可拿到当前账号。
 *
 * <p>由后台会话拦截器（BIZ-08）按 {@code Authorization: Bearer} 写入请求属性 {@link #ATTRIBUTE}，每个请求都按库里的最新状态生成：
 * 停用、改角色即时生效。
 *
 * @param username 登录名，操作日志记它的快照
 * @param displayName 姓名，导入记录、告警处理人等展示用
 */
public record AdminPrincipal(long adminId, String username, String displayName, AdminRole role) {

    public static final String ATTRIBUTE = AdminPrincipal.class.getName();
}
