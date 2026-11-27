package com.smartparking.ownership.transfer;

import java.time.Instant;
import java.util.List;

/** 设备已转让（事务已提交）。站内消息（BIZ-15）订阅：原主人与被撤销的被授权人各收到一条 */
public record DeviceTransferredEvent(
        long deviceId, long fromUser, long toUser, List<Long> revokedMembers, Instant at) {}
