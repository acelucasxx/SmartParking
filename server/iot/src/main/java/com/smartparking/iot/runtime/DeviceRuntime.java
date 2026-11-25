package com.smartparking.iot.runtime;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.presence.ConnectionState;
import java.time.Instant;

/**
 * 设备运行态快照（{@code device_runtime} 一行）。
 *
 * @param statusCode 最近状态帧的原始状态码；从未收到状态帧时为空
 * @param lastReason 最近状态帧的动作原因码
 * @param occupied 车位是否有车；未知为空
 * @param statusRefreshedAt 最近一次由状态帧刷新闸杆状态的时刻，用于“30 秒内可直接判定已在目标态”（PRD 5.3②）
 * @param rev 修订号，每次更新 +1
 */
public record DeviceRuntime(
        long deviceId,
        GateStatus gateStatus,
        Integer statusCode,
        Integer lastReason,
        Boolean occupied,
        OnlineState onlineState,
        Instant lastSeenAt,
        Instant statusRefreshedAt,
        ConnectionState lastConnEvent,
        Instant lastConnAt,
        long rev) {}
