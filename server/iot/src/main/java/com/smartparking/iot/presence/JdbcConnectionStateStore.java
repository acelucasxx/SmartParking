package com.smartparking.iot.presence;

import com.smartparking.iot.DbTime;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写 {@code device_runtime} 的连接字段。行不存在时先补一行默认值，再按 {@code last_conn_at} 做条件更新，
 * 并发与乱序到达的事件只会让时刻最新的那条生效。
 */
@Component
class JdbcConnectionStateStore implements ConnectionStateStore {

    private final JdbcClient jdbc;

    JdbcConnectionStateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean record(long deviceId, ConnectionState state, Instant at) {
        LocalDateTime time = DbTime.toDb(at);
        jdbc.sql("INSERT INTO device_runtime (device_id) VALUES (?) ON DUPLICATE KEY UPDATE device_id = device_id")
                .param(deviceId)
                .update();
        String seen =
                state == ConnectionState.CONNECTED ? ", last_seen_at = GREATEST(COALESCE(last_seen_at, :at), :at)" : "";
        int updated = jdbc.sql("UPDATE device_runtime SET last_conn_event = :state, last_conn_at = :at, rev = rev + 1"
                        + seen + " WHERE device_id = :id AND (last_conn_at IS NULL OR last_conn_at < :at)")
                .param("state", state.name())
                .param("at", time)
                .param("id", deviceId)
                .update();
        return updated > 0;
    }
}
