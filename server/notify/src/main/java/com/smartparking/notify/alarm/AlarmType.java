package com.smartparking.notify.alarm;

/** 告警类型，取值与接口契约 {@code AlarmType}、{@code alarm.type} 一致；级别由类型决定。 */
public enum AlarmType {
    /** 状态码 E3 */
    MOTOR_FAULT(AlarmLevel.CRITICAL),
    /** 原因码 11 */
    OBSTRUCTED(AlarmLevel.WARNING),
    /** 原因码 12 */
    INFRARED(AlarmLevel.WARNING),
    /** 同一设备最近 3 条已终结的指令都是超时 */
    CMD_TIMEOUT(AlarmLevel.WARNING),
    /** {@code last_seen_at} 距今超过 24 小时（PRD 8.5 离线） */
    OFFLINE_24H(AlarmLevel.CRITICAL);

    private final AlarmLevel level;

    AlarmType(AlarmLevel level) {
        this.level = level;
    }

    public AlarmLevel level() {
        return level;
    }
}
