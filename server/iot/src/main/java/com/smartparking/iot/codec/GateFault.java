package com.smartparking.iot.codec;

/**
 * 状态帧表达的异常（PRD 8.4）：执行中收到即判指令失败，并触发告警。名称与接口契约 {@code CommandResultCode} 中的同名值一致。
 */
public enum GateFault {
    /** 状态码 {@code E3} */
    MOTOR_FAULT,
    /** 原因码 {@code 11} */
    OBSTRUCTED,
    /** 原因码 {@code 12} */
    INFRARED
}
