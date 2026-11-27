package com.smartparking.ownership;

import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.iot.sync.StatusSyncService;
import com.smartparking.ownership.bind.BindAttempts;
import com.smartparking.ownership.bind.BindService;
import com.smartparking.ownership.unbind.OwnershipRelease;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class OwnershipConfiguration {

    @Bean
    OwnershipRelease ownershipRelease(JdbcClient jdbc) {
        return new OwnershipRelease(jdbc);
    }

    @Bean
    BindAttempts bindAttempts(StringRedisTemplate redis) {
        return new BindAttempts(redis);
    }

    @Bean
    BindService bindService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ActivateCodes codes,
            BindAttempts attempts,
            StatusSyncService statusSync) {
        return new BindService(jdbc, tx, codes, attempts, statusSync);
    }
}
