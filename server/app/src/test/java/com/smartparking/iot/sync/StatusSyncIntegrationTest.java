package com.smartparking.iot.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.GateBoard;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.command.InMemoryDeviceLink;
import com.smartparking.iot.presence.ConnectionEventService;
import com.smartparking.iot.runtime.DeviceRuntime;
import com.smartparking.iot.runtime.OnlineState;
import com.smartparking.iot.runtime.RuntimeRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 状态同步（PRD 8.7）：连接、绑定触发的查询与定时查询。设备链路为内存中的 DTU 模拟器，MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            // 定时查询由测试直接调用
            "smartparking.status-sync.tick=1h",
            "smartparking.status-sync.connect-delay=200ms"
        })
@Import({TestcontainersConfiguration.class, InMemoryDeviceLink.class})
class StatusSyncIntegrationTest {

    private static final String QUERY = Hex.format(Rs485Codec.encodeQuery());

    @Autowired
    private StatusSyncService sync;

    @Autowired
    private StatusPollRepository polls;

    @Autowired
    private ConnectionEventService connections;

    @Autowired
    private RuntimeRepository runtime;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InMemoryDeviceLink link;

    private ExecutorScheduler scheduler;
    private long deviceId;
    private String imei;
    private GateBoard board;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE system_config SET config_value = '5' WHERE config_key = 'status_poll.interval_minutes'")
                .update();
        // 其他用例留下的“连接中”设备不参与本用例的定时查询
        jdbc.sql("UPDATE device_runtime SET last_conn_event = 'DISCONNECTED' WHERE last_conn_event = 'CONNECTED'")
                .update();
        scheduler = new ExecutorScheduler(2);
        imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql("INSERT INTO device (sn, imei, model, batch_no, activate_code_hash) VALUES (?, ?, 'SP-G1', 'IT', 'x')")
                .params("SN" + imei, imei)
                .update();
        deviceId = jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
        board = link.attach(imei, 100, scheduler);
        link.connected = true;
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("UPDATE system_config SET config_value = '5' WHERE config_key = 'status_poll.interval_minutes'")
                .update();
        scheduler.close();
    }

    private long queries() {
        return link.sent.stream().filter(s -> s.equals(imei + " " + QUERY)).count();
    }

    private DeviceRuntime db() {
        return runtime.find(deviceId).orElseThrow();
    }

    /** 设备 MQTT 连接中、最近一次上行在 {@code silence} 之前；不经连接事件，避免触发上线查询 */
    private void connectedAndSilentFor(Duration silence) {
        jdbc.sql(
                        """
                        INSERT INTO device_runtime (device_id, last_conn_event, last_conn_at, last_seen_at)
                        VALUES (:id, 'CONNECTED', :at, :at)
                        ON DUPLICATE KEY UPDATE last_conn_event = 'CONNECTED', last_conn_at = :at, last_seen_at = :at""")
                .param("id", deviceId)
                .param("at", DbTime.toDb(Instant.now().minus(silence)))
                .update();
    }

    private Instant lastPollAt() {
        return DbTime.fromDb(jdbc.sql("SELECT last_poll_at FROM device_runtime WHERE device_id = ?")
                .param(deviceId)
                .query(LocalDateTime.class)
                .optional()
                .orElse(null));
    }

    @Test
    void connectionEventTriggersQueryAndSyncsState() {
        board.trigger(GateStatus.OPENED, 0x18); // 遥控器抬杆，闸板不主动上报
        await().atMost(Duration.ofSeconds(2)).until(() -> board.status() == GateStatus.OPENED);

        connections.handle("client.connected", "dtu-" + imei, System.currentTimeMillis(), null);

        await().atMost(Duration.ofSeconds(3)).until(() -> db().statusRefreshedAt() != null);
        assertThat(queries()).isEqualTo(1);
        assertThat(db().gateStatus()).isEqualTo(GateStatus.OPENED);
        assertThat(db().onlineState()).isEqualTo(OnlineState.ONLINE);
        assertThat(lastPollAt()).isNotNull();
    }

    @Test
    void disconnectionDoesNotQuery() throws InterruptedException {
        connections.handle("client.disconnected", "dtu-" + imei, System.currentTimeMillis(), "sock_closed");
        Thread.sleep(500);
        assertThat(queries()).isZero();
    }

    @Test
    void requestQueryAfterBindingRefreshesState() {
        assertThat(sync.requestQuery(deviceId)).isTrue();

        await().atMost(Duration.ofSeconds(2)).until(() -> runtime.find(deviceId)
                .map(DeviceRuntime::statusRefreshedAt)
                .isPresent());
        assertThat(db().gateStatus()).isEqualTo(GateStatus.CLOSED);
    }

    @Test
    void requestQueryReportsUnavailableLinkAndUnknownDevice() {
        link.connected = false;
        assertThat(sync.requestQuery(deviceId)).isFalse();
        assertThat(sync.requestQuery(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void silentConnectedDeviceIsPolledOnceAndKeptAlive() {
        connectedAndSilentFor(Duration.ofMinutes(6));
        Instant before = db().lastSeenAt();

        assertThat(sync.pollDue()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> db().lastSeenAt().isAfter(before));

        // 回包刷新了 last_seen_at，下一轮不再查
        assertThat(sync.pollDue()).isZero();
        assertThat(queries()).isEqualTo(1);
    }

    @Test
    void recentlyActiveDeviceIsNotPolled() {
        connectedAndSilentFor(Duration.ofMinutes(4));
        assertThat(sync.pollDue()).isZero();
        assertThat(queries()).isZero();
    }

    @Test
    void disconnectedDeviceIsNotPolled() {
        connectedAndSilentFor(Duration.ofMinutes(30));
        jdbc.sql("UPDATE device_runtime SET last_conn_event = 'DISCONNECTED' WHERE device_id = ?")
                .param(deviceId)
                .update();
        assertThat(sync.pollDue()).isZero();
    }

    @Test
    void unresponsiveDeviceIsPolledOncePerInterval() {
        board.setSilent(true);
        connectedAndSilentFor(Duration.ofMinutes(8));

        assertThat(sync.pollDue()).isEqualTo(1);
        assertThat(sync.pollDue()).isZero();
        assertThat(queries()).isEqualTo(1);

        // 一个周期后再查
        jdbc.sql("UPDATE device_runtime SET last_poll_at = ? WHERE device_id = ?")
                .params(DbTime.toDb(Instant.now().minus(Duration.ofMinutes(6))), deviceId)
                .update();
        assertThat(sync.pollDue()).isEqualTo(1);
        assertThat(queries()).isEqualTo(2);
    }

    @Test
    void claimSucceedsOnlyOnce() {
        connectedAndSilentFor(Duration.ofMinutes(6));
        Instant now = Instant.now();
        Instant due = now.minus(Duration.ofMinutes(5));

        assertThat(polls.claim(deviceId, due, now)).isTrue();
        assertThat(polls.claim(deviceId, due, now)).isFalse();
    }

    @Test
    void intervalFollowsSettingsAndZeroDisables() {
        connectedAndSilentFor(Duration.ofMinutes(20));
        jdbc.sql("UPDATE system_config SET config_value = '30' WHERE config_key = 'status_poll.interval_minutes'")
                .update();
        assertThat(sync.pollDue()).isZero();

        jdbc.sql("UPDATE system_config SET config_value = '0' WHERE config_key = 'status_poll.interval_minutes'")
                .update();
        connectedAndSilentFor(Duration.ofDays(1));
        assertThat(sync.pollDue()).isZero();
        assertThat(queries()).isZero();
    }

    @Test
    void brokerDownSkipsPolling() {
        connectedAndSilentFor(Duration.ofMinutes(6));
        link.connected = false;
        assertThat(sync.pollDue()).isZero();
        assertThat(lastPollAt()).isNull();
    }
}
