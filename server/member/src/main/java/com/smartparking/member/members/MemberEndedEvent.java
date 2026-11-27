package com.smartparking.member.members;

import java.time.Instant;

/**
 * 一条授权结束（事务已提交）。站内消息（BIZ-15）订阅：撤销、到期通知被授权人，退出通知主人。
 *
 * @param ownerId 结束时的主人
 */
public record MemberEndedEvent(long deviceId, long memberId, long userId, long ownerId, Reason reason, Instant at) {

    public enum Reason {
        /** 主人撤销 */
        REVOKED,
        /** 有效期到期 */
        EXPIRED,
        /** 被授权人主动退出 */
        QUIT
    }
}
