package com.smartparking.iot.runtime;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.presence.ConnectionState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 运行态热数据：Redis Hash {@code sp:runtime:{deviceId}}，TTL 1 天。
 *
 * <ul>
 *   <li>写入按修订号比较，只接受更新的快照，并发回写时旧数据不会覆盖新数据。
 *   <li>Redis 不可用时只记日志：读返回空、写跳过，由调用方回落到 MySQL，不影响主链路。
 * </ul>
 */
@Component
public class RuntimeCache {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCache.class);
    static final Duration TTL = Duration.ofDays(1);

    /** KEYS[1] 键；ARGV[1] 修订号；ARGV[2] TTL 毫秒；ARGV[3..] 字段名、值交替 */
    private static final RedisScript<Long> PUT_IF_NEWER = new DefaultRedisScript<>(
            """
            local cur = redis.call('HGET', KEYS[1], 'rev')
            if cur and tonumber(cur) >= tonumber(ARGV[1]) then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], unpack(ARGV, 3))
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """,
            Long.class);

    private final StringRedisTemplate redis;

    public RuntimeCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(long deviceId) {
        return "sp:runtime:" + deviceId;
    }

    public Optional<DeviceRuntime> get(long deviceId) {
        try {
            Map<Object, Object> hash = redis.opsForHash().entries(key(deviceId));
            return hash.isEmpty() ? Optional.empty() : Optional.of(fromHash(deviceId, hash));
        } catch (RuntimeException e) {
            log.warn("读取运行态缓存失败（设备 {}），回落 MySQL：{}", deviceId, e.getMessage());
            return Optional.empty();
        }
    }

    /** @return 是否写入；缓存中已有相同或更新的修订号时返回 false */
    public boolean put(DeviceRuntime runtime) {
        try {
            var args = new ArrayList<String>();
            args.add(Long.toString(runtime.rev()));
            args.add(Long.toString(TTL.toMillis()));
            args.addAll(toHash(runtime));
            Long result = redis.execute(PUT_IF_NEWER, List.of(key(runtime.deviceId())), args.toArray());
            return result != null && result == 1L;
        } catch (RuntimeException e) {
            log.warn("写入运行态缓存失败（设备 {}）：{}", runtime.deviceId(), e.getMessage());
            evict(runtime.deviceId());
            return false;
        }
    }

    public void evict(long deviceId) {
        try {
            redis.delete(key(deviceId));
        } catch (RuntimeException e) {
            log.warn("清除运行态缓存失败（设备 {}）：{}", deviceId, e.getMessage());
        }
    }

    static List<String> toHash(DeviceRuntime r) {
        var fields = new ArrayList<String>();
        add(fields, "rev", r.rev());
        add(fields, "gateStatus", r.gateStatus());
        add(fields, "statusCode", r.statusCode());
        add(fields, "lastReason", r.lastReason());
        add(fields, "occupied", r.occupied());
        add(fields, "onlineState", r.onlineState());
        add(fields, "lastSeenAt", millis(r.lastSeenAt()));
        add(fields, "statusRefreshedAt", millis(r.statusRefreshedAt()));
        add(fields, "lastConnEvent", r.lastConnEvent());
        add(fields, "lastConnAt", millis(r.lastConnAt()));
        return fields;
    }

    static DeviceRuntime fromHash(long deviceId, Map<Object, Object> h) {
        return new DeviceRuntime(
                deviceId,
                GateStatus.valueOf((String) h.get("gateStatus")),
                integer(h.get("statusCode")),
                integer(h.get("lastReason")),
                h.get("occupied") == null ? null : Boolean.valueOf((String) h.get("occupied")),
                OnlineState.valueOf((String) h.get("onlineState")),
                instant(h.get("lastSeenAt")),
                instant(h.get("statusRefreshedAt")),
                h.get("lastConnEvent") == null ? null : ConnectionState.valueOf((String) h.get("lastConnEvent")),
                instant(h.get("lastConnAt")),
                Long.parseLong((String) h.get("rev")));
    }

    private static void add(List<String> fields, String name, Object value) {
        if (value != null) {
            fields.add(name);
            fields.add(value instanceof Enum<?> e ? e.name() : value.toString());
        }
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Integer integer(Object value) {
        return value == null ? null : Integer.valueOf((String) value);
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong((String) value));
    }
}
