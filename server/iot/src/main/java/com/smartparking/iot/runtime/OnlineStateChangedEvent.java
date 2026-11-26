package com.smartparking.iot.runtime;

import java.time.Instant;

/**
 * 设备在线状态发生了变化，已写入 {@code device_runtime.online_state}。告警（BIZ-07“离线超 24 小时”）、监控（INF-08）等订阅此事件。
 *
 * <p>同一变化在多实例、多时机并发判定时只发布一次（修订号条件更新，只有一方生效）。
 *
 * @param lastSeenAt 判定所依据的最近上行时刻；从未上行时为空
 * @param at 判定时刻
 */
public record OnlineStateChangedEvent(
        long deviceId, OnlineState previous, OnlineState current, Instant lastSeenAt, Instant at) {}
