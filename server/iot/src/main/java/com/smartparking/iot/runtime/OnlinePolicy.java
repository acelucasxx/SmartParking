package com.smartparking.iot.runtime;

import com.smartparking.iot.presence.ConnectionState;
import java.time.Duration;
import java.time.Instant;

/**
 * 在线判定（PRD 8.5），按优先级自上而下、命中即止：
 *
 * <ol>
 *   <li>离线：从未上行，或 {@code last_seen_at} 距今超过 {@code offlineAfter}（默认 24 小时）。
 *   <li>休眠：最近一次连接事件为断开，或 {@code last_seen_at} 距今超过 {@code sleepAfter}（默认 10 分钟）。
 *   <li>在线：以上都不满足。
 * </ol>
 *
 * <p>纯函数。三个判定时机与落库见 {@link OnlineStateService}。
 */
public final class OnlinePolicy {

    private OnlinePolicy() {}

    public static OnlineState evaluate(DeviceRuntime runtime, Instant now, Duration sleepAfter, Duration offlineAfter) {
        if (runtime == null || runtime.lastSeenAt() == null) {
            return OnlineState.OFFLINE;
        }
        Duration silence = Duration.between(runtime.lastSeenAt(), now);
        if (silence.compareTo(offlineAfter) > 0) {
            return OnlineState.OFFLINE;
        }
        if (runtime.lastConnEvent() == ConnectionState.DISCONNECTED || silence.compareTo(sleepAfter) > 0) {
            return OnlineState.SLEEP;
        }
        return OnlineState.ONLINE;
    }
}
