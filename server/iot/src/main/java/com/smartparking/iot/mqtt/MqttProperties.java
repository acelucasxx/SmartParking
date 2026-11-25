package com.smartparking.iot.mqtt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT 接入配置，前缀 {@code smartparking.mqtt}。
 *
 * @param enabled 关闭后不连接 broker，下发直接报不可用；用于不需要设备链路的测试
 * @param url broker 地址，生产为 {@code ssl://…:8883}，本地为 {@code tcp://localhost:1883}
 * @param clientId 云端客户端 ID；为空时自动生成 {@code smartparking-server-xxxxxxxx}，多实例部署不会互相踢下线
 * @param username 云端账号，EMQX 认证时识别为超级用户
 * @param password 云端账号密码
 * @param downlinkExpiry 下行消息过期时间。设备离线时 broker 最多暂存这么久，过期即丢弃，不在设备上线后补执行
 * @param keepAlive MQTT 心跳
 * @param reconnectMaxDelay 断线重连的最大退避间隔
 */
@ConfigurationProperties("smartparking.mqtt")
public record MqttProperties(
        Boolean enabled,
        String url,
        String clientId,
        String username,
        String password,
        Duration downlinkExpiry,
        Duration keepAlive,
        Duration reconnectMaxDelay) {

    public MqttProperties {
        enabled = enabled == null || enabled;
        downlinkExpiry = downlinkExpiry == null ? Duration.ofSeconds(15) : downlinkExpiry;
        keepAlive = keepAlive == null ? Duration.ofSeconds(30) : keepAlive;
        reconnectMaxDelay = reconnectMaxDelay == null ? Duration.ofSeconds(30) : reconnectMaxDelay;
    }

    /** 云端订阅的上行主题 */
    public static final String UPLINK_SUBSCRIPTION = "parking/up/+";

    public static String uplinkTopic(String imei) {
        return "parking/up/" + imei;
    }

    public static String downlinkTopic(String imei) {
        return "parking/down/" + imei;
    }
}
