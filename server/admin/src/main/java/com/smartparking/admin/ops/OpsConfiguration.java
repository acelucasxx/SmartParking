package com.smartparking.admin.ops;

import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.iot.selfcheck.SelfCheckService;
import com.smartparking.ownership.bind.BindAttempts;
import com.smartparking.ownership.unbind.OwnershipRelease;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 后台运维操作（BIZ-10 设备、BIZ-16 用户封禁） */
@Configuration(proxyBeanMethods = false)
public class OpsConfiguration {

    @Bean
    AdminDeviceOps adminDeviceOps(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ActivateCodes codes,
            BindAttempts attempts,
            OwnershipRelease release,
            SelfCheckService selfCheck,
            ApplicationEventPublisher events) {
        return new AdminDeviceOps(jdbc, tx, codes, attempts, release, selfCheck, events, Clock.systemUTC());
    }

    @Bean
    AdminUserOps adminUserOps(JdbcClient jdbc) {
        return new AdminUserOps(jdbc);
    }
}
