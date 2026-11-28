package com.smartparking.notify.message;

import java.time.Instant;

/** 一条站内消息已写入。微信订阅消息（BIZ-18）订阅此事件，复用站内消息的内容。 */
public record MessageCreatedEvent(
        long userId, MessageType type, Long deviceId, String title, String content, Instant createdAt) {}
