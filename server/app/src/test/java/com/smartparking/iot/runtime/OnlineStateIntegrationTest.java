package com.smartparking.iot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.presence.ConnectionEventService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/** 在线判定（PRD 8.5）的三个时机与落库，使用真实 MySQL 与 Redis；窗口为默认的 10 分钟 / 24 小时。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            // 扫描由测试直接调用，避免定时任务并发干扰断言
            "smartparking.online.sweep-interval=1h"
        })
@Import({TestcontainersConfiguration.class, OnlineStateIntegrationTest.Changes.class})
class OnlineStateIntegrationTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DeviceRuntimeService runtime;

    @Autowired
    private OnlineStateService onlineStates;

    @Autowired
    private RuntimeRepository repository;

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
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private void connected(Instant at) {
        connections.handle("client.connected", "dtu-" + imei, at.toEpochMilli(), null);
    }

    private void disconnected(Instant at) {
        connections.handle("client.disconnected", "dtu-" + imei, at.toEpochMilli(), "keepalive_timeout");
    }

    private void status(Instant at) {
        publisher.publishEvent(new DeviceUplinkEvent(
                deviceId, imei, Rs485Codec.decodeUplink(Rs485Codec.encodeStatus(1, 0x0C, 0x16, 0x00)), at));
    }

    private DeviceRuntime db() {
        return repository.find(deviceId).orElseThrow();
    }

    /** 模拟时间流逝：只把最近上行改早，不改在线状态（相当于设备沉默了这么久、扫描还没来） */
    private void silentFor(Duration silence) {
        jdbc.sql("UPDATE device_runtime SET last_seen_at = ?, rev = rev + 1 WHERE device_id = ?")
                .params(DbTime.toDb(Instant.now().minus(silence)), deviceId)
                .update();
    }

    private List<OnlineStateChangedEvent> transitions() {
        return changes.events.stream().filter(e -> e.deviceId() == deviceId).toList();
    }

    @Test
    void uplinkBringsNewDeviceOnline() {
        status(now());

        assertThat(db().onlineState()).isEqualTo(OnlineState.ONLINE);
        assertThat(cache.get(deviceId)).contains(db());
        assertThat(transitions()).singleElement().satisfies(e -> {
            assertThat(e.previous()).isEqualTo(OnlineState.OFFLINE);
            assertThat(e.current()).isEqualTo(OnlineState.ONLINE);
        });
    }

    @Test
    void connectionEventsDriveSleepAndOnline() {
        Instant t = now();
        connected(t);
        assertThat(db().onlineState()).isEqualTo(OnlineState.ONLINE);

        // 断开不刷新 last_seen_at，但最近一次连接事件为断开即休眠
        disconnected(t.plusMillis(100));
        assertThat(db().onlineState()).isEqualTo(OnlineState.SLEEP);
        assertThat(db().lastSeenAt()).isEqualTo(t);
        assertThat(cache.get(deviceId).orElseThrow().onlineState()).isEqualTo(OnlineState.SLEEP);

        // 断开后仍收到上行（EMQX 事件延迟）：连接事件仍为断开，保持休眠
        status(t.plusMillis(200));
        assertThat(db().onlineState()).isEqualTo(OnlineState.SLEEP);

        connected(t.plusMillis(300));
        assertThat(db().onlineState()).isEqualTo(OnlineState.ONLINE);

        assertThat(transitions())
                .extracting(OnlineStateChangedEvent::current)
                .containsExactly(OnlineState.ONLINE, OnlineState.SLEEP, OnlineState.ONLINE);
    }

    @Test
    void sweepMovesSilentDeviceToSleepThenOffline() {
        connected(now());
        changes.events.clear();

        silentFor(Duration.ofMinutes(9));
        onlineStates.sweep();
        assertThat(db().onlineState()).isEqualTo(OnlineState.ONLINE);

        silentFor(Duration.ofMinutes(11));
        onlineStates.sweep();
        assertThat(db().onlineState()).isEqualTo(OnlineState.SLEEP);
        assertThat(cache.get(deviceId).orElseThrow().onlineState()).isEqualTo(OnlineState.SLEEP);

        silentFor(Duration.ofHours(23));
        onlineStates.sweep();
        assertThat(db().onlineState()).isEqualTo(OnlineState.SLEEP);

        silentFor(Duration.ofHours(25));
        onlineStates.sweep();
        assertThat(db().onlineState()).isEqualTo(OnlineState.OFFLINE);

        assertThat(transitions())
                .extracting(OnlineStateChangedEvent::previous, OnlineStateChangedEvent::current)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(OnlineState.ONLINE, OnlineState.SLEEP),
                        org.assertj.core.groups.Tuple.tuple(OnlineState.SLEEP, OnlineState.OFFLINE));
    }

    @Test
    void offlineTakesPriorityOverOnlineSkippingSleep() {
        connected(now());
        changes.events.clear();

        silentFor(Duration.ofHours(25));
        onlineStates.sweep();

        assertThat(db().onlineState()).isEqualTo(OnlineState.OFFLINE);
        assertThat(transitions()).singleElement().satisfies(e -> {
            assertThat(e.previous()).isEqualTo(OnlineState.ONLINE);
            assertThat(e.current()).isEqualTo(OnlineState.OFFLINE);
        });
    }

    @Test
    void sweepIsIdempotentAndPublishesOnce() {
        connected(now());
        silentFor(Duration.ofMinutes(11));
        changes.events.clear();

        onlineStates.sweep();
        onlineStates.sweep();

        assertThat(transitions()).hasSize(1);
        assertThat(repository.findOnlineStateMismatch(
                        Instant.now().minus(Duration.ofMinutes(10)),
                        Instant.now().minus(Duration.ofHours(24)),
                        deviceId - 1,
                        1))
                .noneMatch(r -> r.deviceId() == deviceId);
    }

    @Test
    void offlineDeviceComesBackOnUplink() {
        connected(now());
        silentFor(Duration.ofHours(30));
        onlineStates.sweep();
        assertThat(db().onlineState()).isEqualTo(OnlineState.OFFLINE);

        status(now());

        assertThat(db().onlineState()).isEqualTo(OnlineState.ONLINE);
    }

    @Test
    void judgeBeforeControlPersistsStaleState() {
        connected(now());
        silentFor(Duration.ofMinutes(11));
        cache.evict(deviceId);
        // 运行态仍记为“在线”：设备已沉默 11 分钟，但扫描尚未到来
        DeviceRuntime cached = runtime.find(deviceId).orElseThrow();
        assertThat(cached.onlineState()).isEqualTo(OnlineState.ONLINE);
        changes.events.clear();

        assertThat(onlineStates.judge(cached)).isEqualTo(OnlineState.SLEEP);

        assertThat(db().onlineState()).isEqualTo(OnlineState.SLEEP);
        assertThat(runtime.find(deviceId).orElseThrow().onlineState()).isEqualTo(OnlineState.SLEEP);
        assertThat(transitions())
                .singleElement()
                .extracting(OnlineStateChangedEvent::current)
                .isEqualTo(OnlineState.SLEEP);
    }

    @Test
    void judgeWithoutRuntimeIsOffline() {
        assertThat(onlineStates.judge(null)).isEqualTo(OnlineState.OFFLINE);
        assertThat(onlineStates.reevaluate(deviceId)).isEmpty();
    }

    @Test
    void staleRevisionDoesNotOverwrite() {
        connected(now());
        DeviceRuntime read = db();
        // 读完之后行被别处改过（如又来了一个断开事件）
        disconnected(now().plusMillis(50));
        long sleeping = db().rev();

        assertThat(repository.updateOnlineState(deviceId, OnlineState.ONLINE, read.rev()))
                .isFalse();
        assertThat(db().onlineState()).isEqualTo(OnlineState.SLEEP);
        assertThat(db().rev()).isEqualTo(sleeping);
    }

    @TestConfiguration
    static class Changes {

        final List<OnlineStateChangedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(OnlineStateChangedEvent event) {
            events.add(event);
        }
    }
}
