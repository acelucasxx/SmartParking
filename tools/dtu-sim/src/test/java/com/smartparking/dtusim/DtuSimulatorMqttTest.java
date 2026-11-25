package com.smartparking.dtusim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 用真实 EMQX 验证模拟器的 MQTT 收发：多设备并发、回显、故障注入、断线重连、持久会话下的离线排队。 */
@Testcontainers
class DtuSimulatorMqttTest {

    private static final long TRAVEL_MS = 300;

    @Container
    static final GenericContainer<?> EMQX = new GenericContainer<>("emqx/emqx:5.8.6")
            .withExposedPorts(1883)
            .waitingFor(Wait.forLogMessage(".*is running now.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    private static DtuSimulator simulator;
    private static MqttClient cloud;
    private static final Map<String, BlockingQueue<UplinkFrame>> UPLINK = new ConcurrentHashMap<>();

    static String broker() {
        return "tcp://" + EMQX.getHost() + ":" + EMQX.getMappedPort(1883);
    }

    @BeforeAll
    static void start() throws Exception {
        cloud = new MqttClient(broker(), "cloud-test", new MemoryPersistence());
        var options = new MqttConnectOptions();
        options.setCleanSession(true);
        cloud.connect(options);
        cloud.subscribe("parking/up/+", 1, (topic, message) -> {
            String imei = topic.substring(topic.lastIndexOf('/') + 1);
            queue(imei).add(Rs485Codec.decodeUplink(message.getPayload()));
        });

        var config = new SimulatorConfig(
                broker(), Imeis.sequence("86000000000000", 3), TRAVEL_MS, true, false, true, 10, 500, null, null);
        simulator = new DtuSimulator(config);
        simulator.start();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> simulator.devices().stream().allMatch(DtuDevice::isConnected));
    }

    @AfterAll
    static void stop() throws Exception {
        if (simulator != null) {
            simulator.close();
        }
        if (cloud != null) {
            cloud.disconnect();
            cloud.close();
        }
    }

    private static BlockingQueue<UplinkFrame> queue(String imei) {
        return UPLINK.computeIfAbsent(imei, k -> new LinkedBlockingQueue<>());
    }

    private static void down(String imei, byte[] frame) throws Exception {
        cloud.publish("parking/down/" + imei, frame, 1, false);
    }

    /** 下发查询并取回状态帧；超时返回 null */
    private static UplinkFrame.Status query(String imei, long timeoutMs) throws Exception {
        var q = queue(imei);
        q.clear();
        down(imei, Rs485Codec.encodeQuery());
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            UplinkFrame frame = q.poll(50, TimeUnit.MILLISECONDS);
            if (frame instanceof UplinkFrame.Status status) {
                return status;
            }
        }
        return null;
    }

    /** 每 100 ms 查询一次，直到闸杆进入目标状态 */
    private static void awaitStatus(String imei, GateStatus target) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    var status = query(imei, 500);
                    return status != null && status.status() == target;
                });
    }

    @Test
    void devicesRespondConcurrentlyWithEchoThenSettle() throws Exception {
        List<DtuDevice> devices = simulator.devices();
        for (DtuDevice d : devices) {
            d.board().repair();
            queue(d.imei()).clear();
            down(d.imei(), Rs485Codec.encodeOpen());
        }
        for (DtuDevice d : devices) {
            UplinkFrame first = queue(d.imei()).poll(3, TimeUnit.SECONDS);
            assertThat(first).as("回显帧").isInstanceOf(UplinkFrame.Echo.class);
        }
        for (DtuDevice d : devices) {
            awaitStatus(d.imei(), GateStatus.OPENED);
        }
        for (DtuDevice d : devices) {
            down(d.imei(), Rs485Codec.encodeClose());
        }
        for (DtuDevice d : devices) {
            awaitStatus(d.imei(), GateStatus.CLOSED);
        }
    }

    @Test
    void injectedMotorFaultIsVisibleOverMqtt() throws Exception {
        DtuDevice device = simulator.devices().get(1);
        device.board().repair();
        device.board().inject(Fault.MOTOR_FAULT);
        down(device.imei(), Rs485Codec.encodeOpen());
        awaitStatus(device.imei(), GateStatus.FAULT);
        device.board().repair();
        awaitStatus(device.imei(), GateStatus.CLOSED);
    }

    @Test
    void droppedDeviceReconnectsAndAnswersAgain() throws Exception {
        DtuDevice device = simulator.devices().get(2);
        device.dropFor(2);
        assertThat(device.isConnected()).isFalse();
        assertThat(query(device.imei(), 500)).as("断线期间无回包").isNull();
        await().atMost(Duration.ofSeconds(10)).until(device::isConnected);
        assertThat(query(device.imei(), 2000)).isNotNull();
    }

    @Test
    void persistentSessionDeliversCommandSentWhileOffline() throws Exception {
        // 复现 IOT-01 评估 3.7 的风险：DTU 用持久会话时，离线期间下发的指令会在重连后被执行
        var config = new SimulatorConfig(
                broker(), List.of("490154203237518"), TRAVEL_MS, true, false, false, 10, 500, null, null);
        try (var scheduler = new ExecutorScheduler(1);
                var device = new DtuDevice("490154203237518", config, scheduler)) {
            device.connect();
            device.dropFor(3);
            // 等 broker 感知到断开，再下发
            Thread.sleep(500);
            down(device.imei(), Rs485Codec.encodeOpen());
            await().atMost(Duration.ofSeconds(10)).until(device::isConnected);
            await().atMost(Duration.ofSeconds(5)).until(() -> device.board().status() == GateStatus.OPENED);
        }
    }
}
