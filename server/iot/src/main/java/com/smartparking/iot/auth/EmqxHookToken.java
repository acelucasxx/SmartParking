package com.smartparking.iot.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/** EMQX 回调本服务时携带的 {@code X-Emqx-Token}，认证回调与连接事件回调共用。缺失时拒绝启动。 */
@Component
public class EmqxHookToken {

    public static final String HEADER = "X-Emqx-Token";

    private final byte[] token;

    public EmqxHookToken(DeviceAuthProperties properties) {
        if (properties.hookToken() == null || properties.hookToken().isBlank()) {
            throw new IllegalStateException("缺少 smartparking.mqtt.auth.hook-token");
        }
        this.token = properties.hookToken().getBytes(StandardCharsets.UTF_8);
    }

    /** 恒定时间比较 */
    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
