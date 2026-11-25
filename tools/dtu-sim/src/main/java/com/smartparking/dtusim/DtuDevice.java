package com.smartparking.dtusim;

import com.smartparking.iot.auth.DeviceCredentials;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.ProtocolException;
import com.smartparking.iot.codec.Rs485Codec;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一台 4G DTU（透传）+ 一块闸板。MQTT 约定见 PRD 8.1：客户端 ID {@code dtu-{imei}}，订阅
 * {@code parking/down/{imei}}，发布 {@code parking/up/{imei}}，QoS 1，载荷为原始字节。
 *
 * <p>DTU 不解析协议，收到什么字节就原样交给闸板；字节不合协议时闸板不回包，这里记日志后丢弃。
 */
public final class DtuDevice implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DtuDevice.class);
    private static final int QOS = 1;

    private final String imei;
    private final SimulatorConfig config;
    private final Scheduler scheduler;
    private final GateBoard board;
    private final MqttClient client;
    private final MqttConnectOptions options;
    private volatile boolean closed;

    public DtuDevice(String imei, SimulatorConfig config, Scheduler scheduler) throws MqttException {
        this.imei = imei;
        this.config = config;
        this.scheduler = scheduler;
        this.board = new GateBoard(Rs485Codec.DEFAULT_ADDRESS, config.travelMs(), scheduler, this::publish);
        board.setEcho(config.echo());
        board.setActiveReport(config.activeReport());
        this.client = new MqttClient(config.broker(), "dtu-" + imei, new MemoryPersistence());
        this.options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(config.cleanSession());
        options.setKeepAliveInterval(config.keepAliveSeconds());
        options.setAutomaticReconnect(false);
        if (config.secretKey() != null) {
            // 一机一密：用户名为 IMEI，密码由平台密钥派生（与云端 DeviceCredentials 同一算法）
            options.setUserName(imei);
            options.setPassword(
                    new DeviceCredentials(config.secretKey()).passwordFor(imei).toCharArray());
        }
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("{} 连接断开：{}", imei, cause.getMessage());
                scheduleReconnect(config.reconnectDelayMs());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                onDownlink(message.getPayload());
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });
    }

    public String imei() {
        return imei;
    }

    public GateBoard board() {
        return board;
    }

    public String downTopic() {
        return "parking/down/" + imei;
    }

    public String upTopic() {
        return "parking/up/" + imei;
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /** 首次连接；broker 未就绪时按重连间隔重试，不抛异常 */
    public void start() {
        try {
            connect();
        } catch (MqttException e) {
            log.warn("{} 连接 {} 失败：{}，{} ms 后重试", imei, config.broker(), e.getMessage(), config.reconnectDelayMs());
            scheduleReconnect(config.reconnectDelayMs());
        }
    }

    /** 连接并订阅下行主题 */
    public synchronized void connect() throws MqttException {
        if (closed || client.isConnected()) {
            return;
        }
        client.connect(options);
        client.subscribe(downTopic(), QOS);
        log.info("{} 已连接，订阅 {}", imei, downTopic());
    }

    /**
     * 模拟断线：不发 DISCONNECT 报文直接断开 TCP（与断电、掉网一致），{@code seconds} 秒后重连。
     */
    public synchronized void dropFor(long seconds) throws MqttException {
        if (client.isConnected()) {
            client.disconnectForcibly(0, 1000, false);
            log.info("{} 模拟断线 {} 秒", imei, seconds);
        }
        scheduleReconnect(seconds * 1000);
    }

    private void scheduleReconnect(long delayMs) {
        if (closed) {
            return;
        }
        scheduler.schedule(
                () -> {
                    try {
                        connect();
                    } catch (MqttException e) {
                        log.warn("{} 重连失败：{}，{} ms 后重试", imei, e.getMessage(), config.reconnectDelayMs());
                        scheduleReconnect(config.reconnectDelayMs());
                    }
                },
                delayMs);
    }

    private void onDownlink(byte[] payload) {
        try {
            var frame = Rs485Codec.decodeDownlink(payload);
            log.debug("{} ↓ {}", imei, frame.rawFrame());
            board.handle(frame);
        } catch (ProtocolException e) {
            log.warn("{} ↓ 非法帧，闸板不响应：{}", imei, e.getMessage());
        }
    }

    private void publish(byte[] frame) {
        if (!client.isConnected()) {
            log.debug("{} 未连接，丢弃上行 {}", imei, Hex.format(frame));
            return;
        }
        try {
            client.publish(upTopic(), frame, QOS, false);
            log.debug("{} ↑ {}", imei, Hex.format(frame));
        } catch (MqttException e) {
            log.warn("{} 上行发布失败：{}", imei, e.getMessage());
        }
    }

    @Override
    public synchronized void close() throws MqttException {
        closed = true;
        if (client.isConnected()) {
            client.disconnect();
        }
        client.close();
    }
}
