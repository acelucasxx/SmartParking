package com.smartparking.ownership;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.control.devices.DeviceViews;
import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.iot.sync.StatusSyncService;
import com.smartparking.ownership.bind.BindAttempts;
import com.smartparking.ownership.bind.BindService;
import com.smartparking.ownership.transfer.TransferService;
import com.smartparking.ownership.unbind.OwnershipRelease;
import com.smartparking.ownership.unbind.UnbindService;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
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
    UnbindService unbindService(
            JdbcClient jdbc, TransactionTemplate tx, OwnershipRelease release, ApplicationEventPublisher events) {
        return new UnbindService(jdbc, tx, release, events, Clock.systemUTC());
    }

    @Bean
    TransferService transferService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            SystemSettings settings,
            OwnershipRelease release,
            DeviceViews views,
            ApplicationEventPublisher events) {
        return new TransferService(jdbc, tx, settings, release, views, events, Clock.systemUTC());
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
