package com.smartparking.admin;

import com.smartparking.common.config.SystemSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 从 {@code system_config} 读取参数，按键缓存 {@code smartparking.settings.cache-ttl}（默认 30 秒），后台修改后在该时间内生效。
 */
@Component
class JdbcSystemSettings implements SystemSettings {

    private static final Logger log = LoggerFactory.getLogger(JdbcSystemSettings.class);

    private record Cached(String value, Instant loadedAt) {}

    private final JdbcClient jdbc;
    private final Duration ttl;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    JdbcSystemSettings(JdbcClient jdbc, @Value("${smartparking.settings.cache-ttl:30s}") Duration ttl) {
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String raw = value(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("系统参数 {} 不是整数：{}，使用默认值 {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private String value(String key) {
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && Duration.between(cached.loadedAt(), now).compareTo(ttl) < 0) {
            return cached.value();
        }
        try {
            String value = jdbc.sql("SELECT config_value FROM system_config WHERE config_key = ?")
                    .param(key)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            cache.put(key, new Cached(value, now));
            return value;
        } catch (RuntimeException e) {
            log.warn("读取系统参数 {} 失败：{}", key, e.getMessage());
            return cached == null ? null : cached.value();
        }
    }
}
