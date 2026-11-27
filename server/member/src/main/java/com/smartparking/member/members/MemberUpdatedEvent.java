package com.smartparking.member.members;

import java.time.Instant;

/** 主人修改了授权的有效期或备注（事务已提交）。站内消息（BIZ-15）订阅：有效期变化时通知被授权人 */
public record MemberUpdatedEvent(
        long deviceId,
        long memberId,
        long userId,
        boolean periodChanged,
        Instant validFrom,
        Instant validTo,
        Instant at) {}
