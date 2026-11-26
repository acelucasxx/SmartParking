package com.smartparking.iot.command;

/** 指令结果码，取值与接口契约 {@code CommandResultCode} 一致。 */
public enum CommandResult {
    /** 已在目标状态，未下发（视为成功） */
    ALREADY_IN_TARGET,
    /** 状态码 E3 */
    MOTOR_FAULT,
    /** 原因码 11 */
    OBSTRUCTED,
    /** 原因码 12 */
    INFRARED,
    /** 状态码 00 中间停止 */
    INTERRUPTED,
    /** 超时未到位 */
    TIMEOUT,
    /** 落库后下发失败（云端与 broker 链路不可用） */
    OFFLINE
}
