package com.smartparking.iot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.presence.ConnectionState;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RuntimeCacheTest {

    private static Map<Object, Object> asMap(List<String> fields) {
        var map = new HashMap<Object, Object>();
        for (int i = 0; i < fields.size(); i += 2) {
            map.put(fields.get(i), fields.get(i + 1));
        }
        return map;
    }

    @Test
    void roundTripsFullSnapshot() {
        var runtime = new DeviceRuntime(
                9,
                GateStatus.OPENED,
                0x09,
                0x16,
                true,
                OnlineState.ONLINE,
                Instant.parse("2026-11-26T01:00:00.123Z"),
                Instant.parse("2026-11-26T01:00:00.100Z"),
                ConnectionState.CONNECTED,
                Instant.parse("2026-11-26T00:59:00Z"),
                42);
        assertThat(RuntimeCache.fromHash(9, asMap(RuntimeCache.toHash(runtime))))
                .isEqualTo(runtime);
    }

    @Test
    void roundTripsSnapshotWithUnknowns() {
        var runtime = new DeviceRuntime(
                9, GateStatus.UNKNOWN, null, null, null, OnlineState.OFFLINE, null, null, null, null, 0);
        var fields = RuntimeCache.toHash(runtime);
        assertThat(fields).containsExactly("rev", "0", "gateStatus", "UNKNOWN", "onlineState", "OFFLINE");
        assertThat(RuntimeCache.fromHash(9, asMap(fields))).isEqualTo(runtime);
    }

    @Test
    void redisUnavailableDegradesInsteadOfThrowing() {
        var factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(java.time.Duration.ofMillis(300))
                        .build());
        factory.afterPropertiesSet();
        try {
            var cache = new RuntimeCache(new StringRedisTemplate(factory));
            var runtime = new DeviceRuntime(
                    9, GateStatus.CLOSED, 0x0C, 0x16, false, OnlineState.ONLINE, null, null, null, null, 1);
            assertThat(cache.get(9)).isEmpty();
            assertThat(cache.put(runtime)).isFalse();
            cache.evict(9);
        } finally {
            factory.destroy();
        }
    }
}
