package com.smartparking.iot.presence;

import java.time.Instant;

/** 连接状态持久化。实现必须按事件时刻判定新旧：早于已记录时刻的事件不覆盖。 */
public interface ConnectionStateStore {

    /**
     * 记录一次连接事件；连接事件同时刷新 {@code last_seen_at}（PRD 8.5），断开事件不刷新。
     *
     * @return 是否已写入；事件早于或等于已记录的时刻时返回 false
     */
    boolean record(long deviceId, ConnectionState state, Instant at);
}
