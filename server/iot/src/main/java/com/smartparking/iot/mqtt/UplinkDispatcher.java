package com.smartparking.iot.mqtt;

import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.Imei;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.ProtocolException;
import com.smartparking.iot.codec.Rs485Codec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 上行消息入口：从主题取 IMEI → 确认已登记 → 解码 → 发布 {@link DeviceUplinkEvent}。
 *
 * <p>任何一步不通过都只记日志并丢弃，不回包、不抛异常，避免一条坏消息打断订阅回调。未登记 IMEI 的日志按 IMEI 节流，防止刷屏。
 */
@Component
public class UplinkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UplinkDispatcher.class);
    private static final String PREFIX = "parking/up/";
    private static final Duration WARN_INTERVAL = Duration.ofMinutes(10);
    private static final int WARN_CACHE_LIMIT = 10_000;
    /** 日志里最多打印的载荷字节数 */
    private static final int LOG_BYTES = 32;

    private final DeviceDirectory directory;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<String, Instant> unknownWarnedAt = new ConcurrentHashMap<>();

    @Autowired
    public UplinkDispatcher(DeviceDirectory directory, ApplicationEventPublisher events) {
        this(directory, events, Clock.systemDefaultZone());
    }

    UplinkDispatcher(DeviceDirectory directory, ApplicationEventPublisher events, Clock clock) {
        this.directory = directory;
        this.events = events;
        this.clock = clock;
    }

    /** 处理一条上行消息；返回是否已发布事件，便于测试 */
    public boolean dispatch(String topic, byte[] payload) {
        Instant now = clock.instant();
        String imei = topic != null && topic.startsWith(PREFIX) ? topic.substring(PREFIX.length()) : null;
        if (!Imei.isWellFormed(imei)) {
            log.warn("丢弃上行：主题不合法 {}", topic);
            return false;
        }
        var device = directory.findByImei(imei);
        if (device.isEmpty()) {
            warnUnknown(imei, payload, now);
            return false;
        }
        try {
            var frame = Rs485Codec.decodeUplink(payload);
            log.debug("↑ {} {}", imei, frame.rawFrame());
            events.publishEvent(new DeviceUplinkEvent(device.get().deviceId(), imei, frame, now));
            return true;
        } catch (ProtocolException e) {
            log.warn("丢弃上行：{} 帧结构不合法：{}", imei, e.getMessage());
            return false;
        }
    }

    private void warnUnknown(String imei, byte[] payload, Instant now) {
        Instant last = unknownWarnedAt.get(imei);
        if (last != null && Duration.between(last, now).compareTo(WARN_INTERVAL) < 0) {
            return;
        }
        if (unknownWarnedAt.size() >= WARN_CACHE_LIMIT) {
            unknownWarnedAt
                    .entrySet()
                    .removeIf(e -> Duration.between(e.getValue(), now).compareTo(WARN_INTERVAL) >= 0);
        }
        unknownWarnedAt.put(imei, now);
        byte[] head = payload == null ? new byte[0] : Arrays.copyOf(payload, Math.min(payload.length, LOG_BYTES));
        log.warn("拒收未登记 IMEI 的上行：{}（{}），10 分钟内不再提示", imei, Hex.format(head));
    }
}
