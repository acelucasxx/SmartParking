package com.smartparking.iot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.presence.ConnectionEventService;
import com.smartparking.iot.presence.ConnectionState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 运行态写入链路：上行事件 → device_runtime / device_event → Redis，使用真实 MySQL 与 Redis。 */
@SpringBootTest(properties = {"smartparking.mqtt.enabled=false", "smartparking.device-event.partitions.enabled=false"})
@Import({TestcontainersConfiguration.class, DeviceRuntimeIntegrationTest.Changes.class})
class DeviceRuntimeIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-26T02:00:00Z");

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private DeviceRuntimeService runtime;

    @Autowired
    private RuntimeCache cache;

    @Autowired
    private ConnectionEventService connections;

    @Autowired
    private Changes changes;

    private long deviceId;
    private String imei;

    @BeforeEach
    void setUp() {
        imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql("INSERT INTO device (sn, imei, model, batch_no, activate_code_hash) VALUES (?, ?, 'SP-G1', 'IT', 'x')")
                .params("SN" + imei, imei)
                .update();
        deviceId = jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
        changes.events.clear();
    }

    private void uplink(byte[] frame, Instant at) {
        publisher.publishEvent(new DeviceUplinkEvent(deviceId, imei, Rs485Codec.decodeUplink(frame), at));
    }

    private void status(int status, int reason, int car, Instant at) {
        uplink(Rs485Codec.encodeStatus(1, status, reason, car), at);
    }

    private List<Map<String, Object>> events() {
        return jdbc.sql("SELECT gate_status, status_code, reason, occupied, raw_frame, approx, at FROM device_event"
                        + " WHERE device_id = ? ORDER BY id")
                .param(deviceId)
                .query()
                .listOfRows();
    }

    private DeviceRuntime db() {
        return new RuntimeRepository(jdbc).find(deviceId).orElseThrow();
    }

    @Test
    void firstStatusFrameCreatesRuntimeEventAndCache() {
        status(0x09, 0x16, 0x11, T0);

        DeviceRuntime r = db();
        assertThat(r.gateStatus()).isEqualTo(GateStatus.OPENED);
        assertThat(r.statusCode()).isEqualTo(0x09);
        assertThat(r.lastReason()).isEqualTo(0x16);
        assertThat(r.occupied()).isTrue();
        assertThat(r.statusRefreshedAt()).isEqualTo(T0);
        assertThat(r.lastSeenAt()).isEqualTo(T0);

        var rows = events();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("gate_status", "OPENED").containsEntry("approx", false);
            assertThat((byte[]) row.get("raw_frame")).isEqualTo(Rs485Codec.encodeStatus(1, 0x09, 0x16, 0x11));
        });

        assertThat(cache.get(deviceId)).contains(r);
        assertThat(changes.events).singleElement().satisfies(e -> {
            assertThat(e.previous()).isNull();
            assertThat(e.frame().status()).isEqualTo(GateStatus.OPENED);
            assertThat(e.approx()).isFalse();
        });
    }

    @Test
    void unchangedFrameRefreshesButWritesNoEvent() {
        status(0x0C, 0x16, 0x00, T0);
        long rev = db().rev();
        status(0x0C, 0x16, 0x00, T0.plusMillis(300));

        assertThat(events()).hasSize(1);
        assertThat(changes.events).hasSize(1);
        assertThat(db().statusRefreshedAt()).isEqualTo(T0.plusMillis(300));
        assertThat(db().rev()).isGreaterThan(rev);
        assertThat(cache.get(deviceId)).contains(db());
    }

    @Test
    void nonAppTriggerIsMarkedApproximate() {
        status(0x0C, 0x16, 0x00, T0);
        status(0x09, 0x01, 0x00, T0.plusSeconds(60));

        assertThat(events()).hasSize(2).last().satisfies(row -> assertThat(row).containsEntry("approx", true));
        assertThat(changes.events).last().satisfies(e -> {
            assertThat(e.previous().gateStatus()).isEqualTo(GateStatus.CLOSED);
            assertThat(e.approx()).isTrue();
        });
    }

    @Test
    void occupancyChangeIsAnEvent() {
        status(0x0C, 0x16, 0x00, T0);
        status(0x0C, 0x16, 0x11, T0.plusSeconds(1));
        assertThat(events()).hasSize(2);
    }

    @Test
    void echoFrameOnlyRefreshesLastSeen() {
        status(0x0C, 0x16, 0x00, T0);
        uplink(Rs485Codec.encodeEcho(1, 0x03), T0.plusSeconds(5));

        DeviceRuntime r = db();
        assertThat(r.lastSeenAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(r.statusRefreshedAt()).isEqualTo(T0);
        assertThat(r.gateStatus()).isEqualTo(GateStatus.CLOSED);
        assertThat(events()).hasSize(1);
        assertThat(cache.get(deviceId)).contains(r);
    }

    @Test
    void staleStatusFrameIsIgnored() {
        status(0x09, 0x16, 0x00, T0.plusSeconds(10));
        status(0x0C, 0x16, 0x00, T0);
        assertThat(db().gateStatus()).isEqualTo(GateStatus.OPENED);
        assertThat(events()).hasSize(1);
    }

    @Test
    void connectionEventIsReflectedInCache() {
        status(0x0C, 0x16, 0x00, T0);
        connections.handle(
                "client.disconnected", "dtu-" + imei, T0.plusSeconds(30).toEpochMilli(), "tcp_closed");

        assertThat(cache.get(deviceId)).hasValueSatisfying(r -> {
            assertThat(r.lastConnEvent()).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(r.lastConnAt()).isEqualTo(T0.plusSeconds(30));
            assertThat(r.lastSeenAt()).isEqualTo(T0);
        });
    }

    @Test
    void findFallsBackToMysqlAndRefillsCache() {
        status(0x09, 0x16, 0x00, T0);
        redis.delete(RuntimeCache.key(deviceId));

        assertThat(runtime.find(deviceId)).contains(db());
        assertThat(redis.hasKey(RuntimeCache.key(deviceId))).isTrue();
        assertThat(runtime.find(-1)).isEmpty();
    }

    @Test
    void cacheRejectsOlderRevision() {
        status(0x0C, 0x16, 0x00, T0);
        DeviceRuntime old = db();
        status(0x09, 0x16, 0x00, T0.plusSeconds(1));
        DeviceRuntime current = db();

        assertThat(cache.put(old)).isFalse();
        assertThat(cache.get(deviceId)).contains(current);
        assertThat(redis.getExpire(RuntimeCache.key(deviceId))).isPositive();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Changes {

        final List<DeviceStateChangedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DeviceStateChangedEvent event) {
            events.add(event);
        }
    }
}
