package com.smartparking.user;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户域配置，前缀 {@code smartparking.user}。
 *
 * @param dataKey 用户敏感数据主密钥，至少 32 字节；手机号的加密密钥与查询哈希密钥都由它派生，见 {@link PhoneCipher}
 * @param sessionTtl 小程序会话有效期，到期后前端重新 {@code wx.login} 静默登录
 */
@ConfigurationProperties("smartparking.user")
public record UserProperties(String dataKey, Duration sessionTtl) {

    public UserProperties {
        sessionTtl = sessionTtl == null ? Duration.ofDays(7) : sessionTtl;
    }
}
