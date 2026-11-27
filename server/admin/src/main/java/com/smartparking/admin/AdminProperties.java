package com.smartparking.admin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台账号与会话配置，前缀 {@code smartparking.admin}。
 *
 * @param sessionIdle 空闲超时：这么久没有请求即需重新登录，每次请求顺延
 * @param sessionMax 绝对超时：自登录起最长有效期，到期必须重新登录
 * @param maxFailures 连续输错密码几次锁定
 * @param lockDuration 锁定时长
 * @param bootstrapUsername 初始超级管理员登录名
 * @param bootstrapPassword 初始超级管理员的临时密码；库里没有任何后台账号时用它创建，首次登录必须修改。为空则不创建
 */
@ConfigurationProperties("smartparking.admin")
public record AdminProperties(
        Duration sessionIdle,
        Duration sessionMax,
        Integer maxFailures,
        Duration lockDuration,
        String bootstrapUsername,
        String bootstrapPassword) {

    public AdminProperties {
        sessionIdle = sessionIdle == null ? Duration.ofMinutes(30) : sessionIdle;
        sessionMax = sessionMax == null ? Duration.ofHours(12) : sessionMax;
        maxFailures = maxFailures == null ? 5 : maxFailures;
        lockDuration = lockDuration == null ? Duration.ofMinutes(15) : lockDuration;
        bootstrapUsername = bootstrapUsername == null || bootstrapUsername.isBlank() ? "admin" : bootstrapUsername;
        if (sessionIdle.compareTo(sessionMax) > 0) {
            throw new IllegalArgumentException("smartparking.admin.session-idle 不能大于 session-max");
        }
        if (maxFailures < 1) {
            throw new IllegalArgumentException("smartparking.admin.max-failures 至少为 1");
        }
    }
}
