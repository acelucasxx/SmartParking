package com.smartparking.iot.presence;

import java.time.Instant;

/**
 * 已登记设备的连接状态发生了变化，且已写入 {@code device_runtime}。在线判定（IOT-09）与上线后同步查询（IOT-10，PRD 8.7）订阅此事件。
 *
 * @param at EMQX 记录的事件时刻
 * @param reason 断开原因（EMQX 的 reason，如 {@code keepalive_timeout}、{@code sock_closed}）；连接事件为空
 */
public record DeviceConnectionEvent(long deviceId, String imei, ConnectionState state, Instant at, String reason) {}
