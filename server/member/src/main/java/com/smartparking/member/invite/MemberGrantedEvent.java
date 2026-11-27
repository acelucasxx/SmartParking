package com.smartparking.member.invite;

import java.time.Instant;

/** 有人接受邀请成为被授权人（事务已提交）。站内消息（BIZ-15）订阅：通知主人 */
public record MemberGrantedEvent(
        long deviceId, long ownerId, long memberUserId, long memberId, long inviteId, Instant at) {}
