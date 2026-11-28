package com.smartparking.ownership.unbind;

import java.time.Instant;
import java.util.List;

/**
 * 设备已解除归属（事务已提交）。站内消息（BIZ-15）订阅：原主人与被撤销的被授权人各收到一条。
 *
 * @param type {@code UNBIND} 主人解绑 / {@code FORCE_UNBIND} 强制解绑
 * @param deviceName 解绑前的设备展示名（解绑后名称已清空）
 */
public record DeviceUnboundEvent(
        long deviceId,
        long formerOwner,
        List<Long> revokedMembers,
        OwnershipRelease.Type type,
        String deviceName,
        Instant at) {}
