package com.smartparking.notify.alarm;

import java.time.Instant;

/**
 * 告警已写入（事务已提交）。站内故障消息（BIZ-15）、监控（INF-08）订阅此事件。
 *
 * @param merged 是否合并进了 30 分钟内同设备同类的未处理告警（只累加次数，没有新告警）
 */
public record AlarmRaisedEvent(
        long alarmId, long deviceId, AlarmType type, AlarmLevel level, boolean merged, Instant at) {}
