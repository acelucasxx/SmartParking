package com.smartparking.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.dtusim.DtuDevice;
import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.SimulatorConfig;
import com.smartparking.iot.auth.DeviceCredentials;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.mqtt.MqttGateway;
import com.smartparking.iot.presence.ConnectionState;
import com.smartparking.iot.presence.DeviceConnectionEvent;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * MQTT 接入端到端：EMQX 加载 deploy/local/emqx 的真实配置，认证回调本服务，设备由 DTU 模拟器冒充。
 *
 * <p>覆盖：一机一密认证、未登记 IMEI 拒连、主题 ACL、上行解码发布事件、下行消息过期（离线不排队）、
 * 连接 / 断开事件经规则引擎写入 device_runtime（IOT-05）、上线后主动查询状态（IOT-10）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({TestcontainersConfiguration.class, MqttAccessIntegrationTest.Listener.class})
class MqttAccessIntegrationTest {

    private static final String KEY = "device_secret_key_dev_only_0123456789";
    private static final String REGISTERED = "860000000000009";
    private static final String REGISTERED_2 = "860000000000017";
    private static final String UNREGISTERED = "860000000000025";

    /** EMQX 回调认证需要固定端口，在容器启动前选好 */
    private static final int SERVER_PORT = freePort();

    private static final GenericContainer<?> EMQX = emqx();

    static {
        EMQX.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> SERVER_PORT);
        registry.add("smartparking.mqtt.url", MqttAccessIntegrationTest::broker);
        // 缩短过期时间，便于验证离线不排队
        registry.add("smartparking.mqtt.downlink-expiry", () -> "2s");
        registry.add("smartparking.mqtt.reconnect-max-delay", () -> "2s");
    }

    @Autowired
    private MqttGateway gateway;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Listener listener;

    @Autowired
    private com.smartparking.iot.command.CommandService commands;

    @Autowired
    private com.smartparking.iot.runtime.DeviceRuntimeService runtime;

    private ExecutorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ExecutorScheduler(2);
        for (String imei : List.of(REGISTERED, REGISTERED_2)) {
            jdbc.sql(
                            """
                    INSERT IGNORE INTO device (sn, imei, model, batch_no, activate_code_hash)
                    VALUES (?, ?, 'SP-G1', 'IT', 'x')""")
                    .params("SN" + imei, imei)
                    .update();
        }
        await().atMost(Duration.ofSeconds(30)).until(gateway::isConnected);
        listener.events.clear();
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    static String broker() {
        return "tcp://" + EMQX.getHost() + ":" + EMQX.getMappedPort(1883);
    }

    private static SimulatorConfig config(boolean cleanSession, String secretKey) {
        return new SimulatorConfig(broker(), List.of(), 300, true, false, cleanSession, 10, 500, secretKey);
    }

    private DtuDevice device(String imei, boolean cleanSession) throws MqttException {
        return new DtuDevice(imei, config(cleanSession, KEY), scheduler);
    }

    /** 取下一条该设备的上行；期间收到的其他设备的上行记入 {@code others} */
    private UplinkFrame nextUplink(String imei, List<DeviceUplinkEvent> others) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            DeviceUplinkEvent e = listener.events.poll(100, TimeUnit.MILLISECONDS);
            if (e == null) {
                continue;
            }
            if (e.imei().equals(imei)) {
                return e.frame();
            }
            others.add(e);
        }
        return null;
    }

    private UplinkFrame nextUplink(String imei) throws InterruptedException {
        return nextUplink(imei, new ArrayList<>());
    }

    @Test
    void registeredDeviceConnectsAndRoundTripsThroughServer() throws Exception {
        try (var device = device(REGISTERED, true)) {
            device.connect();
            gateway.send(REGISTERED, Rs485Codec.encodeQuery());
            var frame = nextUplink(REGISTERED);
            assertThat(frame).isInstanceOf(UplinkFrame.Status.class);
            assertThat(((UplinkFrame.Status) frame).status()).isEqualTo(GateStatus.CLOSED);

            gateway.send(REGISTERED, Rs485Codec.encodeOpen());
            assertThat(nextUplink(REGISTERED)).as("回显帧照常上报").isInstanceOf(UplinkFrame.Echo.class);
        }
    }

    @Test
    void remoteOpenSucceedsThroughEmqx() throws Exception {
        // 完整链路：CommandService → MqttGateway → EMQX → 模拟器 → EMQX → UplinkDispatcher → 运行态 / 状态机
        try (var device = device(REGISTERED, true)) {
            device.connect();
            long deviceId = jdbc.sql("SELECT id FROM device WHERE imei = ?")
                    .param(REGISTERED)
                    .query(Long.class)
                    .single();
            device.board().repair();
            gateway.send(REGISTERED, Rs485Codec.encodeQuery());
            await().atMost(Duration.ofSeconds(10)).until(() -> runtime.find(deviceId)
                    .map(r -> r.statusRefreshedAt() != null
                            && r.lastConnEvent() == ConnectionState.CONNECTED
                            && r.gateStatus() == GateStatus.CLOSED)
                    .orElse(false));

            var started = commands.submit(
                    deviceId,
                    com.smartparking.iot.command.CommandAction.OPEN,
                    java.util.UUID.randomUUID().toString(),
                    com.smartparking.iot.command.Initiator.user(1, "OWNER"));
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> commands.get(deviceId, started.id()).status().isTerminal());
            var done = commands.get(deviceId, started.id());
            assertThat(done.status()).isEqualTo(com.smartparking.iot.command.CommandStatus.SUCCESS);
            assertThat(device.board().status()).isEqualTo(GateStatus.OPENED);
        }
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        try (var device = new DtuDevice(REGISTERED, config(true, KEY.replace('0', '1')), scheduler)) {
            assertThatThrownBy(device::connect).isInstanceOf(MqttException.class);
        }
    }

    @Test
    void unregisteredImeiCannotConnect() throws Exception {
        try (var device = device(UNREGISTERED, true)) {
            assertThatThrownBy(device::connect).isInstanceOf(MqttException.class);
        }
    }

    @Test
    void anonymousClientIsRejected() throws Exception {
        try (var device = new DtuDevice(REGISTERED, config(true, null), scheduler)) {
            assertThatThrownBy(device::connect).isInstanceOf(MqttException.class);
        }
    }

    @Test
    void deviceCannotPublishAsAnotherImei() throws Exception {
        // 设备 1 用自己的合法身份连上，但往设备 2 的上行主题发帧：ACL 拒绝，云端收不到
        var password = new DeviceCredentials(KEY).passwordFor(REGISTERED);
        var client = new MqttClient(broker(), "dtu-" + REGISTERED, new MemoryPersistence());
        var options = new MqttConnectOptions();
        options.setUserName(REGISTERED);
        options.setPassword(password.toCharArray());
        client.connect(options);
        try {
            client.publish("parking/up/" + REGISTERED_2, Rs485Codec.encodeStatus(1, 0x09, 0x16, 0x00), 1, false);
            client.publish("parking/up/" + REGISTERED, Rs485Codec.encodeStatus(1, 0x0C, 0x16, 0x00), 1, false);
            var others = new ArrayList<DeviceUplinkEvent>();
            assertThat(nextUplink(REGISTERED, others)).as("自己的主题正常").isNotNull();
            assertThat(others).as("冒用他人主题的帧被 ACL 拦截").isEmpty();
            assertThat(listener.events).noneMatch(e -> e.imei().equals(REGISTERED_2));
        } finally {
            client.disconnectForcibly(0, 1000, false);
            client.close();
        }
    }

    @Test
    void downlinkExpiresWhileDeviceIsOffline() throws Exception {
        // 持久会话下，若不设过期时间，离线期间的指令会在重连后执行（见 DtuSimulatorMqttTest）；
        // 云端带 2 秒过期，设备 4 秒后才回来，指令已被丢弃
        try (var device = device(REGISTERED_2, false)) {
            device.connect();
            device.dropFor(4);
            Thread.sleep(500);
            gateway.send(REGISTERED_2, Rs485Codec.encodeOpen());
            await().atMost(Duration.ofSeconds(15)).until(device::isConnected);
            Thread.sleep(1_000);
            assertThat(device.board().status()).isEqualTo(GateStatus.CLOSED);
        }
    }

    /** device_runtime 中的连接状态 */
    private record Conn(String event, java.time.LocalDateTime at, java.time.LocalDateTime seen) {}

    private Conn conn(String imei) {
        return jdbc.sql(
                        """
                        SELECT r.last_conn_event, r.last_conn_at, r.last_seen_at
                        FROM device_runtime r JOIN device d ON d.id = r.device_id WHERE d.imei = ?""")
                .param(imei)
                .query((rs, n) -> new Conn(
                        rs.getString(1),
                        rs.getObject(2, java.time.LocalDateTime.class),
                        rs.getObject(3, java.time.LocalDateTime.class)))
                .optional()
                .orElse(new Conn(null, null, null));
    }

    private MqttClient rawDevice(String imei) throws MqttException {
        var client = new MqttClient(broker(), "dtu-" + imei, new MemoryPersistence());
        var options = new MqttConnectOptions();
        // 与 DTU 常见配置一致：持久会话，旧连接被接管时 EMQX 报 takenover
        options.setCleanSession(false);
        options.setUserName(imei);
        options.setPassword(new DeviceCredentials(KEY).passwordFor(imei).toCharArray());
        client.connect(options);
        return client;
    }

    @Test
    void connectAndDisconnectAreRecordedInRuntime() throws Exception {
        // device_runtime 跨用例保留。同一 IMEI 的前一个用例关闭连接后，断开事件经 EMQX 规则引擎异步回调，
        // 可能还没写入：此时行仍是上一次连接的 CONNECTED，last_seen_at 已被那次的上行刷新到连接时刻之后。
        // 所以要等到本次连接的事件（时刻晚于连接前的记录）落库，不能只看 last_conn_event
        Conn before = conn(REGISTERED);
        try (var device = device(REGISTERED, true)) {
            device.connect();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                Conn c = conn(REGISTERED);
                return "CONNECTED".equals(c.event()) && (before.at() == null || c.at().isAfter(before.at()));
            });
            Conn up = conn(REGISTERED);
            // 连接事件把 last_seen_at 刷新到连接时刻；之后平台延迟 connect-delay 主动查询，设备回的状态帧会再往后刷新，
            // 故断言不早于连接时刻（若连接事件未刷新，last_seen_at 停在上一次连接期间，必早于本次连接）
            assertThat(up.seen()).as("连接事件刷新 last_seen_at").isNotNull().isAfterOrEqualTo(up.at());
            assertThat(listener.connections)
                    .anyMatch(e -> e.imei().equals(REGISTERED) && e.state() == ConnectionState.CONNECTED);

            device.dropFor(3);
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> "DISCONNECTED".equals(conn(REGISTERED).event()));
            Conn down = conn(REGISTERED);
            assertThat(down.at()).isAfter(up.at());
            // 读 up 与断线之间可能恰好到达查询回帧，因此不与 up.seen() 比相等；断开事件若刷新，last_seen_at 会等于断开时刻
            assertThat(down.seen())
                    .as("断开事件不刷新 last_seen_at")
                    .isAfterOrEqualTo(up.seen())
                    .isBefore(down.at());

            await().atMost(Duration.ofSeconds(20))
                    .until(() -> "CONNECTED".equals(conn(REGISTERED).event()));
            assertThat(conn(REGISTERED).at()).isAfter(down.at());
        }
    }

    @Test
    void connectingDeviceIsQueriedWithoutAnyRequest() throws Exception {
        // PRD 8.7：上线即同步状态。EMQX 在连接建立时就发事件，此刻设备可能尚未订阅下行主题，
        // 平台延迟 connect-delay 再查；这里验证经真实 EMQX 的时序下查询不会丢
        try (var device = device(REGISTERED, true)) {
            device.board().trigger(GateStatus.OPENED, 0x18);
            await().atMost(Duration.ofSeconds(3)).until(() -> device.board().status() == GateStatus.OPENED);
            listener.events.clear();

            device.connect();

            long deadline = System.currentTimeMillis() + 15_000;
            UplinkFrame frame = null;
            while (frame == null && System.currentTimeMillis() < deadline) {
                frame = nextUplink(REGISTERED);
            }
            assertThat(frame).as("连接后平台主动查询，设备回状态帧").isInstanceOf(UplinkFrame.Status.class);
            assertThat(((UplinkFrame.Status) frame).status()).isEqualTo(GateStatus.OPENED);
        }
    }

    @Test
    void takeoverByNewConnectionKeepsDeviceConnected() throws Exception {
        // 同一 IMEI 重连时旧连接被接管，旧连接的断开不能把设备记为断开。
        // EMQX 5.8 实测：该断开事件的时间戳早于新连接事件，按时间戳即被忽略；takenover / discarded 过滤是兜底
        MqttClient first = rawDevice(REGISTERED_2);
        MqttClient second = null;
        try {
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> "CONNECTED".equals(conn(REGISTERED_2).event()));
            var before = conn(REGISTERED_2).at();
            second = rawDevice(REGISTERED_2);
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> conn(REGISTERED_2).at().isAfter(before));
            Thread.sleep(2_000);
            assertThat(conn(REGISTERED_2).event()).isEqualTo("CONNECTED");
        } finally {
            for (MqttClient c : new MqttClient[] {first, second}) {
                if (c != null) {
                    if (c.isConnected()) {
                        c.disconnectForcibly(0, 1000, false);
                    }
                    c.close();
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Listener {

        final BlockingQueue<DeviceUplinkEvent> events = new LinkedBlockingQueue<>();
        final BlockingQueue<DeviceConnectionEvent> connections = new LinkedBlockingQueue<>();

        @EventListener
        void on(DeviceConnectionEvent event) {
            connections.add(event);
        }

        @EventListener
        void on(DeviceUplinkEvent event) {
            events.add(event);
        }
    }

    private static GenericContainer<?> emqx() {
        Testcontainers.exposeHostPorts(SERVER_PORT);
        Path dir = Path.of(System.getProperty("smartparking.deployDir", "../../deploy/local"))
                .resolve("emqx");
        try {
            String conf = Files.readString(dir.resolve("emqx.conf"))
                    .replace("host.docker.internal:8080", "host.testcontainers.internal:" + SERVER_PORT);
            return new GenericContainer<>("emqx/emqx:5.8.6")
                    .withExposedPorts(1883)
                    .withCopyToContainer(Transferable.of(conf), "/opt/emqx/etc/emqx.conf")
                    .withCopyToContainer(
                            Transferable.of(Files.readString(dir.resolve("acl.conf"))), "/opt/emqx/etc/acl.conf")
                    .waitingFor(Wait.forLogMessage(".*is running now.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
        } catch (IOException e) {
            throw new IllegalStateException("读取 EMQX 配置失败：" + dir, e);
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
