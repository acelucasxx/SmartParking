package com.smartparking.dtubench;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

/**
 * 以云端身份连 EMQX，订阅 {@code parking/up/{imei}}、向 {@code parking/down/{imei}} 下发，与业务服务的
 * {@code MqttGateway} 一致：QoS 1、MQTT 5、下行带消息过期时间。客户端 ID 为 {@code bench-随机}，与业务服务并存。
 */
public final class CloudLink implements Link, AutoCloseable {

    private final MqttClient client;
    private final String imei;
    private final Duration expiry;
    private final PayloadAnalyzer analyzer = new PayloadAnalyzer();
    private final BlockingQueue<Received> uplinks = new LinkedBlockingQueue<>();
    private final Clock clock = Clock.systemUTC();

    /** @param username 为空时匿名连接（未开启认证的 broker） */
    public CloudLink(String broker, String username, String password, String imei, Duration expiry)
            throws MqttException {
        this.imei = imei;
        this.expiry = expiry;
        this.client = new MqttClient(
                broker, "bench-" + UUID.randomUUID().toString().substring(0, 8), new MemoryPersistence());
        var options = new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setAutomaticReconnect(false);
        if (username != null) {
            options.setUserName(username);
            options.setPassword(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        client.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse response) {}

            @Override
            public void mqttErrorOccurred(MqttException exception) {}

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                onUplink(message.getPayload());
            }

            @Override
            public void deliveryComplete(IMqttToken token) {}

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {}

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        });
        client.connect(options);
        client.subscribe("parking/up/" + imei, 1);
    }

    /** 回调线程里串行调用，分析器的暂存半帧按到达顺序拼接 */
    private synchronized void onUplink(byte[] payload) {
        long nanos = System.nanoTime();
        uplinks.add(new Received(clock.instant(), nanos, payload, analyzer.accept(payload)));
    }

    @Override
    public void send(byte[] frame) throws MqttException {
        var message = new MqttMessage(frame);
        message.setQos(1);
        var properties = new MqttProperties();
        properties.setMessageExpiryInterval(expiry.toSeconds());
        message.setProperties(properties);
        client.publish("parking/down/" + imei, message);
    }

    @Override
    public BlockingQueue<Received> uplinks() {
        return uplinks;
    }

    @Override
    public void close() throws MqttException {
        if (client.isConnected()) {
            client.disconnect();
        }
        client.close();
    }
}
