package com.smartparking.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.user.session.SessionStore;
import com.smartparking.user.web.AppAuthInterceptor;
import com.smartparking.user.web.AppWebConfigurer;
import com.smartparking.user.wechat.HttpWeChatClient;
import com.smartparking.user.wechat.MockWeChatClient;
import com.smartparking.user.wechat.WeChatClient;
import com.smartparking.user.wechat.WeChatProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({UserProperties.class, WeChatProperties.class})
public class UserConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UserConfiguration.class);

    /** 缺少或过短的数据密钥直接拒绝启动，避免手机号以错误的密钥写入 */
    @Bean
    PhoneCipher phoneCipher(UserProperties properties) {
        return new PhoneCipher(properties.dataKey());
    }

    @Bean
    WeChatClient weChatClient(WeChatProperties properties, RestClient.Builder builder, ObjectMapper json) {
        if (properties.mode() == WeChatProperties.Mode.MOCK) {
            log.warn("微信接口为 mock 模式：任何 code 都能登录，只能用于本地开发与测试");
            return new MockWeChatClient();
        }
        return new HttpWeChatClient(properties, builder, json, Clock.systemUTC());
    }

    @Bean
    AppUserRepository appUserRepository(JdbcClient jdbc) {
        return new AppUserRepository(jdbc);
    }

    @Bean
    SessionStore appSessionStore(StringRedisTemplate redis) {
        return new SessionStore(redis);
    }

    @Bean
    AuthService authService(
            WeChatClient wechat,
            AppUserRepository users,
            SessionStore sessions,
            PhoneCipher phones,
            UserProperties properties) {
        return new AuthService(wechat, users, sessions, phones, properties, Clock.systemUTC());
    }

    @Bean
    AppWebConfigurer appWebConfigurer(SessionStore sessions, AppUserRepository users) {
        return new AppWebConfigurer(new AppAuthInterceptor(sessions, users));
    }
}
