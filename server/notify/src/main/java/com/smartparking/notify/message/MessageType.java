package com.smartparking.notify.message;

/** 站内消息类型，取值与契约 {@code MessageType}、{@code app_message.type} 一致 */
public enum MessageType {
    GRANT,
    REVOKE,
    EXPIRE,
    TRANSFER,
    UNBIND,
    FAULT,
    SYSTEM
}
