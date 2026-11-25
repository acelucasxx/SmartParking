package com.smartparking.iot.presence;

import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.Imei;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 处理 EMQX 的客户端连接 / 断开事件：只认已登记的 DTU，写入运行态后发布 {@link DeviceConnectionEvent}。
 *
 * <p>新旧以 EMQX 的事件时间戳为准，早于已记录时刻的事件丢弃（HTTP 动作可能并发、乱序、重试）。断开原因为 {@code takenover} /
 * {@code discarded} 时也忽略：这表示同一客户端 ID 已建立新连接。EMQX 5.8 实测这类断开事件的时间戳早于新连接事件，
 * 时间戳判定已能挡住，原因过滤作为兜底。
 */
@Service
public class ConnectionEventService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionEventService.class);
    private static final String CLIENT_PREFIX = "dtu-";
    static final Set<String> REPLACED_REASONS = Set.of("takenover", "discarded");

    /** 一次事件的处理结果，便于测试与日志 */
    public enum Outcome {
        APPLIED,
        STALE,
        NOT_DEVICE,
        UNREGISTERED,
        REPLACED,
        UNSUPPORTED
    }

    private final DeviceDirectory directory;
    private final ConnectionStateStore store;
    private final ApplicationEventPublisher events;

    public ConnectionEventService(
            DeviceDirectory directory, ConnectionStateStore store, ApplicationEventPublisher events) {
        this.directory = directory;
        this.store = store;
        this.events = events;
    }

    /**
     * @param event EMQX 事件名：{@code client.connected} / {@code client.disconnected}
     * @param timestampMs EMQX 记录的事件时刻（毫秒）
     */
    public Outcome handle(String event, String clientId, long timestampMs, String reason) {
        ConnectionState state;
        if ("client.connected".equals(event)) {
            state = ConnectionState.CONNECTED;
        } else if ("client.disconnected".equals(event)) {
            state = ConnectionState.DISCONNECTED;
        } else {
            log.warn("忽略不支持的 EMQX 事件：{}（{}）", event, clientId);
            return Outcome.UNSUPPORTED;
        }
        String imei = clientId != null && clientId.startsWith(CLIENT_PREFIX)
                ? clientId.substring(CLIENT_PREFIX.length())
                : null;
        if (!Imei.isWellFormed(imei)) {
            return Outcome.NOT_DEVICE;
        }
        if (state == ConnectionState.DISCONNECTED && reason != null && REPLACED_REASONS.contains(reason)) {
            log.debug("{} 旧连接被新连接替换（{}），不记为断开", imei, reason);
            return Outcome.REPLACED;
        }
        var device = directory.findByImei(imei);
        if (device.isEmpty()) {
            log.warn("忽略未登记 IMEI 的连接事件：{} {}", imei, state);
            return Outcome.UNREGISTERED;
        }
        Instant at = Instant.ofEpochMilli(timestampMs);
        if (!store.record(device.get().deviceId(), state, at)) {
            log.debug("{} {} 事件早于已记录的连接状态，忽略", imei, state);
            return Outcome.STALE;
        }
        log.info(
                "{} {}{}",
                imei,
                state == ConnectionState.CONNECTED ? "上线" : "断开",
                reason == null ? "" : "（" + reason + "）");
        events.publishEvent(new DeviceConnectionEvent(device.get().deviceId(), imei, state, at, reason));
        return Outcome.APPLIED;
    }
}
