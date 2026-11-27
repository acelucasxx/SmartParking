package com.smartparking.admin.query;

import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.OnlineStateService;
import com.smartparking.user.PhoneCipher;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 后台查询（BIZ-09） */
@Configuration(proxyBeanMethods = false)
public class QueryConfiguration {

    @Bean
    Phones adminPhones(PhoneCipher cipher) {
        return new Phones(cipher);
    }

    @Bean
    AdminDeviceQueries adminDeviceQueries(
            JdbcClient jdbc, Phones phones, DeviceRuntimeService runtime, OnlineStateService online) {
        return new AdminDeviceQueries(jdbc, phones, runtime, online, Clock.systemUTC());
    }

    @Bean
    AdminUserQueries adminUserQueries(JdbcClient jdbc, Phones phones) {
        return new AdminUserQueries(jdbc, phones);
    }

    /** 导出行数上限可配置，仅为测试缩小；契约上限为 10 万行 */
    @Bean
    CommandAudits commandAudits(
            JdbcClient jdbc,
            Phones phones,
            @Value("${smartparking.admin.command-export-max-rows:" + CommandAudits.EXPORT_MAX_ROWS + "}") int maxRows) {
        return new CommandAudits(jdbc, phones, Math.min(maxRows, CommandAudits.EXPORT_MAX_ROWS));
    }

    @Bean
    DashboardQueries dashboardQueries(JdbcClient jdbc) {
        return new DashboardQueries(jdbc, Clock.systemUTC());
    }
}
