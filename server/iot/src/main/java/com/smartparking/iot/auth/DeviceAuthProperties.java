package com.smartparking.iot.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EMQX 认证回调配置，前缀 {@code smartparking.mqtt.auth}。
 *
 * @param hookToken EMQX 调用 {@code /internal/emqx/authn} 时携带的 {@code X-Emqx-Token}，与 EMQX 配置一致
 * @param deviceSecretKey 一机一密的平台密钥，至少 32 字节，见 {@link DeviceCredentials}
 */
@ConfigurationProperties("smartparking.mqtt.auth")
public record DeviceAuthProperties(String hookToken, String deviceSecretKey) {}
