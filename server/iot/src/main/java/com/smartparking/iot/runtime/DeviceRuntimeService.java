package com.smartparking.iot.runtime;

import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.presence.DeviceConnectionEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 运行态维护：上行帧与连接事件 → {@code device_runtime}（事实来源）→ Redis（热数据），状态变化写 {@code device_event}。
 *
 * <p>每次写入后由 {@link OnlineStateService} 判定在线状态。读取 {@link #find} 先读 Redis，未命中或 Redis 不可用时读 MySQL 并回填。
 */
@Service
public class DeviceRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRuntimeService.class);

    private final RuntimeRepository repository;
    private final RuntimeCache cache;
    private final OnlineStateService onlineStates;
    private final ApplicationEventPublisher events;

    public DeviceRuntimeService(
            RuntimeRepository repository,
            RuntimeCache cache,
            OnlineStateService onlineStates,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.cache = cache;
        this.onlineStates = onlineStates;
        this.events = events;
    }

    /** 当前运行态；设备从未上报过时为空 */
    public Optional<DeviceRuntime> find(long deviceId) {
        Optional<DeviceRuntime> cached = cache.get(deviceId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<DeviceRuntime> loaded = repository.find(deviceId);
        loaded.ifPresent(cache::put);
        return loaded;
    }

    /**
     * 先于其他订阅方执行（指令执行器、查询等待方等）：它们据此帧做出判定后，调用方立刻读到的运行态必须已是这一帧。
     * 否则指令已判失败（如电机故障），紧接着的下一次提交仍读到旧状态而被下发。
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onUplink(DeviceUplinkEvent event) {
        if (event.frame() instanceof UplinkFrame.Status status) {
            var result = repository.applyStatus(event.deviceId(), status, event.receivedAt());
            if (result.stale()) {
                log.debug("{} 状态帧早于已记录状态，忽略：{}", event.imei(), status.rawFrame());
                return;
            }
            refresh(event.deviceId());
            if (result.changed()) {
                log.info("{} 状态 {} 原因 {} {}", event.imei(), status.status(), status.trigger(), occupancy(status));
                events.publishEvent(new DeviceStateChangedEvent(
                        event.deviceId(),
                        event.imei(),
                        result.previous(),
                        status,
                        event.receivedAt(),
                        RuntimeRepository.isApprox(status)));
            }
        } else if (repository.touch(event.deviceId(), event.receivedAt())) {
            refresh(event.deviceId());
        }
    }

    /** 连接事件已由 presence 写入 MySQL，这里判定在线状态并同步缓存 */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onConnection(DeviceConnectionEvent event) {
        refresh(event.deviceId());
    }

    /** 运行态刚被写入：先做在线判定（PRD 8.5 时机①），再把最新行同步到缓存 */
    private void refresh(long deviceId) {
        onlineStates.reevaluate(deviceId).ifPresentOrElse(cache::put, () -> cache.evict(deviceId));
    }

    private static String occupancy(UplinkFrame.Status status) {
        return status.occupied() == null ? "车位未知" : status.occupied() ? "有车" : "无车";
    }
}
