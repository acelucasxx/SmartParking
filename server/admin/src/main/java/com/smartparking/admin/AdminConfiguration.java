package com.smartparking.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.account.AdminUserService;
import com.smartparking.admin.account.Passwords;
import com.smartparking.admin.auth.AdminAuthService;
import com.smartparking.admin.auth.AdminSessionStore;
import com.smartparking.admin.oplog.OperationLogRepository;
import com.smartparking.admin.settings.ConfigService;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 后台账号、会话、操作日志与业务参数（BIZ-08） */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfiguration {

    @Bean
    Passwords passwords() {
        return new Passwords();
    }

    @Bean
    AdminAccounts adminAccounts(JdbcClient jdbc, ObjectMapper json) {
        return new AdminAccounts(jdbc, json);
    }

    @Bean
    AdminSessionStore adminSessionStore(StringRedisTemplate redis, AdminProperties properties) {
        return new AdminSessionStore(redis, properties.sessionIdle(), properties.sessionMax());
    }

    @Bean
    AdminAuthService adminAuthService(
            AdminAccounts accounts, Passwords passwords, AdminSessionStore sessions, AdminProperties properties) {
        return new AdminAuthService(accounts, passwords, sessions, properties, Clock.systemUTC());
    }

    @Bean
    AdminUserService adminUserService(AdminAccounts accounts, Passwords passwords, AdminSessionStore sessions) {
        return new AdminUserService(accounts, passwords, sessions);
    }

    @Bean
    OperationLogRepository operationLogRepository(JdbcClient jdbc, ObjectMapper json) {
        return new OperationLogRepository(jdbc, json);
    }

    @Bean
    ConfigService configService(JdbcClient jdbc, JdbcSystemSettings settings) {
        return new ConfigService(jdbc, settings::invalidate);
    }

    @Bean
    AdminBootstrap adminBootstrap(AdminAccounts accounts, Passwords passwords, AdminProperties properties) {
        return new AdminBootstrap(accounts, passwords, properties);
    }
}
