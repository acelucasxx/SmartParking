package com.smartparking.iot;

import com.smartparking.iot.auth.DeviceAuthProperties;
import com.smartparking.iot.auth.DeviceCredentials;
import com.smartparking.iot.mqtt.MqttProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MqttProperties.class, DeviceAuthProperties.class})
public class IotConfiguration {

    /** 缺少或过短的设备密钥直接拒绝启动：否则所有设备都会连不上，且只在运行时才暴露 */
    @Bean
    DeviceCredentials deviceCredentials(DeviceAuthProperties properties) {
        return new DeviceCredentials(properties.deviceSecretKey());
    }
}
