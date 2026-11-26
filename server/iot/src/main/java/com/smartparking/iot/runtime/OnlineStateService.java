package com.smartparking.iot.runtime;

import com.smartparking.common.config.SystemSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 在线判定落库（PRD 8.5）。规则见 {@link OnlinePolicy}，窗口取后台参数 {@code online.sleep_minutes} /
 * {@code online.offline_hours}（默认 10 分钟 / 24 小时）。
 *
 * <p>三个判定时机：
 *
 * <ol>
 *   <li>收到上行或连接事件：由 {@link DeviceRuntimeService} 在写入运行态后调用 {@link #reevaluate}。
 *   <li>每 60 秒扫描：时间流逝本身会让设备转入休眠 / 离线，没有事件触发，靠扫描发现。
 *   <li>控制下发前：{@link #judge} 以当前时刻判定，与已存状态不同时顺带落库。
 * </ol>
 *
 * <p>写入以修订号为条件：读到的行在判定期间被别处改过就重读重判。因此并发判定（多实例扫描、事件与扫描同时到达）不会写回过期结论，
 * 每次变化也只发布一次 {@link OnlineStateChangedEvent}。
 */
@Service
public class OnlineStateService {

    private static final Logger log = LoggerFactory.getLogger(OnlineStateService.class);
    static final int MAX_ATTEMPTS = 3;
    static final int PAGE = 500;
    /** 单轮最多扫描的页数，避免积压时一轮跑太久 */
    static final int MAX_PAGES = 40;

    private final RuntimeRepository repository;
    private final RuntimeCache cache;
    private final SystemSettings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public OnlineStateService(
            RuntimeRepository repository,
            RuntimeCache cache,
            SystemSettings settings,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.cache = cache;
        this.settings = settings;
        this.events = events;
    }

    /** 按当前时刻判定，不落库；运行态为空（从未上报）时为离线 */
    public OnlineState evaluate(DeviceRuntime runtime) {
        return evaluate(runtime, clock.instant());
    }

    OnlineState evaluate(DeviceRuntime runtime, Instant now) {
        return OnlinePolicy.evaluate(runtime, now, sleepAfter(), offlineAfter());
    }

    /**
     * 控制下发前的判定：以当前时刻对给定快照（通常来自缓存）判定；结论与已存状态不同时，以 MySQL 最新行重判并落库，返回落库后的结论。
     */
    public OnlineState judge(DeviceRuntime runtime) {
        OnlineState state = evaluate(runtime);
        if (runtime == null || state == runtime.onlineState()) {
            return state;
        }
        return reevaluate(runtime.deviceId()).map(DeviceRuntime::onlineState).orElse(state);
    }

    /**
     * 读 MySQL 最新运行态重新判定，变化时写入 {@code online_state} 并发布事件。
     *
     * @return 判定后的运行态（已含新的在线状态）；设备没有运行态行时为空
     */
    public Optional<DeviceRuntime> reevaluate(long deviceId) {
        for (int attempt = 1; ; attempt++) {
            Optional<DeviceRuntime> current = repository.find(deviceId);
            if (current.isEmpty()) {
                return current;
            }
            DeviceRuntime row = current.get();
            Instant now = clock.instant();
            OnlineState next = evaluate(row, now);
            if (next == row.onlineState()) {
                return current;
            }
            if (repository.updateOnlineState(deviceId, next, row.rev())) {
                log.info("设备 {} 在线状态 {} → {}（最近上行 {}）", deviceId, row.onlineState(), next, row.lastSeenAt());
                events.publishEvent(
                        new OnlineStateChangedEvent(deviceId, row.onlineState(), next, row.lastSeenAt(), now));
                DeviceRuntime updated = repository.find(deviceId).orElseThrow();
                cache.put(updated);
                return Optional.of(updated);
            }
            if (attempt >= MAX_ATTEMPTS) {
                // 行在持续变化：每次变化的写入方都会自己判定一次，这里放弃不会留下错误状态
                log.debug("设备 {} 运行态并发更新，放弃本次在线判定", deviceId);
                return repository.find(deviceId);
            }
        }
    }

    /** 60 秒扫描：找出已存状态与当前判定不一致的设备，逐台重判落库 */
    @Scheduled(
            fixedDelayString = "${smartparking.online.sweep-interval:60s}",
            initialDelayString = "${smartparking.online.sweep-interval:60s}")
    public void scheduled() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("在线状态扫描失败：{}", e.getMessage(), e);
        }
    }

    /** @return 本轮状态发生变化的设备数 */
    public int sweep() {
        Instant now = clock.instant();
        Instant sleepBefore = now.minus(sleepAfter());
        Instant offlineBefore = now.minus(offlineAfter());
        int changed = 0;
        long after = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<DeviceRuntime> candidates =
                    repository.findOnlineStateMismatch(sleepBefore, offlineBefore, after, PAGE);
            for (DeviceRuntime candidate : candidates) {
                OnlineState before = candidate.onlineState();
                OnlineState judged = reevaluate(candidate.deviceId())
                        .map(DeviceRuntime::onlineState)
                        .orElse(before);
                if (judged != before) {
                    changed++;
                }
            }
            if (candidates.size() < PAGE) {
                break;
            }
            after = candidates.get(candidates.size() - 1).deviceId();
        }
        if (changed > 0) {
            log.info("在线状态扫描：{} 台设备状态变化", changed);
        }
        return changed;
    }

    private Duration sleepAfter() {
        return Duration.ofMinutes(settings.getInt(SystemSettings.Keys.ONLINE_SLEEP_MINUTES, 10));
    }

    private Duration offlineAfter() {
        return Duration.ofHours(settings.getInt(SystemSettings.Keys.ONLINE_OFFLINE_HOURS, 24));
    }
}
