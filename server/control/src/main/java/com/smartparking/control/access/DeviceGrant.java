package com.smartparking.control.access;

import java.time.Instant;

/**
 * 权限判定通过的结果。
 *
 * @param role 当前用户在该设备上的角色，写入 {@code control_command.role}
 * @param memberId 被授权人记录 {@code device_member.id}；主人为空
 * @param validTo 被授权人的有效期止；主人或永久授权为空
 * @param deviceEnabled 设备是否启用；VIEW / OWNER 级别下可能为 false，供页面展示“设备已停用”
 */
public record DeviceGrant(
        long deviceId, long userId, Role role, Long memberId, Instant validTo, boolean deviceEnabled) {

    /** 与接口契约 MemberRole 一致 */
    public enum Role {
        OWNER,
        MEMBER
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
