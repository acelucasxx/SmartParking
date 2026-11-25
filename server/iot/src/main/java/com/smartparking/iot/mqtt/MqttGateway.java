package com.smartparking.iot.mqtt;

import com.smartparking.iot.codec.Hex;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 云端 MQTT 客户端（PRD 8.1）：订阅 {@code parking/up/+}，向 {@code parking/down/{imei}} 下发，QoS 1。
 *
 * <ul>
 *   <li>应用就绪后再连接：EMQX 认证会回调本服务的 {@code /internal/emqx/authn}，Web 服务必须先启动。
 *   <li>broker 不可用不阻断启动，按指数退避重连（1 秒起，上限 {@code reconnectMaxDelay}），每次重连新建客户端。
 *   <li>干净会话：云端重启期间的上行不补收，状态由对账与定时查询补齐（PRD 8.4、8.7）。
 *   <li>下行带 MQTT 5 消息过期时间：设备离线时 broker 最多暂存 {@code downlinkExpiry}，过期丢弃，不会在设备上线后补执行。
 * </ul>
 */
@Component
public class MqttGateway implements DownlinkSender, ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);
    private static final int QOS = 1;
    private static final long OPERATION_TIMEOUT_MS = 5_000;

    private final MqttProperties props;
    private final UplinkDispatcher dispatcher;
    private final String clientId;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connector");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile MqttAsyncClient client;

    public MqttGateway(MqttProperties props, UplinkDispatcher dispatcher) {
        this.props = props;
        this.dispatcher = dispatcher;
        this.clientId = props.clientId() == null || props.clientId().isBlank()
                ? "smartparking-server-"
                        + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt())
                : props.clientId();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!props.enabled()) {
            log.info("MQTT 接入已关闭（smartparking.mqtt.enabled=false）");
            return;
        }
        if (running.compareAndSet(false, true)) {
            scheduler.execute(this::connect);
        }
    }

    public String clientId() {
        return clientId;
    }

    @Override
    public boolean isConnected() {
        MqttAsyncClient c = client;
        return c != null && c.isConnected();
    }

    @Override
    public void send(String imei, byte[] frame) {
        MqttAsyncClient c = client;
        if (c == null || !c.isConnected()) {
            throw new DeviceLinkUnavailableException("MQTT 未连接，无法下发到 " + imei);
        }
        var message = new MqttMessage(frame);
        message.setQos(QOS);
        var properties = new org.eclipse.paho.mqttv5.common.packet.MqttProperties();
        properties.setMessageExpiryInterval(props.downlinkExpiry().toSeconds());
        message.setProperties(properties);
        String topic = MqttProperties.downlinkTopic(imei);
        try {
            c.publish(topic, message).waitForCompletion(OPERATION_TIMEOUT_MS);
            log.debug("↓ {} {}", imei, Hex.format(frame));
        } catch (MqttException e) {
            throw new DeviceLinkUnavailableException("下发到 " + imei + " 失败：" + e.getMessage(), e);
        }
    }

    private void connect() {
        reconnectScheduled.set(false);
        if (!running.get() || isConnected()) {
            return;
        }
        closeQuietly(client);
        client = null;
        MqttAsyncClient c = null;
        try {
            c = new MqttAsyncClient(props.url(), clientId, new MemoryPersistence());
            c.setCallback(new Callback());
            c.connect(options()).waitForCompletion(OPERATION_TIMEOUT_MS * 2);
            IMqttToken sub = c.subscribe(MqttProperties.UPLINK_SUBSCRIPTION, QOS);
            sub.waitForCompletion(OPERATION_TIMEOUT_MS);
            int[] granted = sub.getGrantedQos();
            if (granted == null || granted.length == 0 || granted[0] >= 0x80) {
                throw new MqttException(granted == null || granted.length == 0 ? 0x80 : granted[0]);
            }
            client = c;
            attempts.set(0);
            log.info("MQTT 已连接 {}（{}），订阅 {}", props.url(), clientId, MqttProperties.UPLINK_SUBSCRIPTION);
        } catch (MqttException e) {
            closeQuietly(c);
            long delay = nextDelayMs();
            log.warn("MQTT 连接 {} 失败：{}，{} ms 后重试", props.url(), e.getMessage(), delay);
            scheduleReconnect(delay);
        }
    }

    private MqttConnectionOptions options() {
        var options = new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setSessionExpiryInterval(0L);
        options.setAutomaticReconnect(false);
        options.setKeepAliveInterval((int) props.keepAlive().toSeconds());
        options.setConnectionTimeout(10);
        if (props.username() != null && !props.username().isBlank()) {
            options.setUserName(props.username());
            options.setPassword(
                    props.password() == null ? new byte[0] : props.password().getBytes(StandardCharsets.UTF_8));
        }
        return options;
    }

    private long nextDelayMs() {
        int n = Math.min(attempts.getAndIncrement(), 16);
        return Math.min(
                props.reconnectMaxDelay().toMillis(), Duration.ofSeconds(1).toMillis() << n);
    }

    private void scheduleReconnect(long delayMs) {
        if (running.get() && reconnectScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private static void closeQuietly(MqttAsyncClient c) {
        if (c == null) {
            return;
        }
        try {
            if (c.isConnected()) {
                c.disconnectForcibly(0, 1000, false);
            }
            c.close(true);
        } catch (MqttException e) {
            // 旧客户端释放失败不影响重连
        }
    }

    @Override
    public void destroy() {
        running.set(false);
        scheduler.shutdownNow();
        MqttAsyncClient c = client;
        client = null;
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect().waitForCompletion(OPERATION_TIMEOUT_MS);
                }
            } catch (MqttException e) {
                log.debug("MQTT 断开失败：{}", e.getMessage());
            }
            closeQuietly(c);
        }
    }

    private final class Callback implements MqttCallback {

        @Override
        public void disconnected(MqttDisconnectResponse response) {
            log.warn("MQTT 连接断开：{}", response.getReasonString() != null ? response.getReasonString() : response);
            scheduleReconnect(nextDelayMs());
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
            log.warn("MQTT 错误：{}", exception.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            try {
                dispatcher.dispatch(topic, message.getPayload());
            } catch (RuntimeException e) {
                // 回调抛异常会让 Paho 断开连接；下游监听器的异常在这里截住
                log.error("处理上行 {} 出错", topic, e);
            }
        }

        @Override
        public void deliveryComplete(IMqttToken token) {}

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {}

        @Override
        public void authPacketArrived(
                int reasonCode, org.eclipse.paho.mqttv5.common.packet.MqttProperties properties) {}
    }
}
