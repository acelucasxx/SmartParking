package com.smartparking.user.wechat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信小程序接入配置，前缀 {@code smartparking.wechat}。
 *
 * @param mode {@code real} 调用微信接口；{@code mock} 不出网，按约定的 code 返回固定结果，只用于本地开发与测试，见 {@link MockWeChatClient}
 * @param appId 小程序 AppID（EXT-10）
 * @param appSecret 小程序 AppSecret，只放在密钥管理中
 * @param baseUrl 微信接口地址，测试时替换为本地桩
 */
@ConfigurationProperties("smartparking.wechat")
public record WeChatProperties(
        Mode mode, String appId, String appSecret, String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public enum Mode {
        REAL,
        MOCK
    }

    public WeChatProperties {
        mode = mode == null ? Mode.REAL : mode;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.weixin.qq.com" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
