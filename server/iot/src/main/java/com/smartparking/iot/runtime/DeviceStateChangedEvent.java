package com.smartparking.iot.runtime;

import com.smartparking.iot.codec.UplinkFrame;
import java.time.Instant;

/**
 * 闸杆状态、动作原因或车位发生了变化，已写入 {@code device_event}。告警、操作记录（IOT-13）等订阅此事件。
 *
 * @param previous 变化前的运行态；设备第一次上报状态时为空
 * @param approx 是否为“发现时间”：动作不是平台指令触发，只能在下一次查询时发现，时刻显示“约”（PRD 8.7）
 */
public record DeviceStateChangedEvent(
        long deviceId, String imei, DeviceRuntime previous, UplinkFrame.Status frame, Instant at, boolean approx) {}
