package com.smartparking.notify.alarm;

/** 告警级别，取值与契约 {@code Alarm.level} 一致 */
public enum AlarmLevel {
    /** 设备无法使用：电机故障、离线超 24 小时 */
    CRITICAL,
    /** 单次动作受阻或远程控制不稳定 */
    WARNING
}
