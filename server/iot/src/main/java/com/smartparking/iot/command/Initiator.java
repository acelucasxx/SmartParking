package com.smartparking.iot.command;

/**
 * 指令发起人，原样写入 {@code control_command}。iot 不解释、不校验，权限由调用方（BIZ-05）判定。
 *
 * @param role OWNER / MEMBER / ADMIN
 */
public record Initiator(Long userId, Long adminId, String role) {

    public Initiator {
        if (userId == null && adminId == null) {
            throw new IllegalArgumentException("userId 与 adminId 至少一个不为空");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role 不能为空");
        }
    }

    public static Initiator user(long userId, String role) {
        return new Initiator(userId, null, role);
    }

    public static Initiator admin(long adminId) {
        return new Initiator(null, adminId, "ADMIN");
    }
}
