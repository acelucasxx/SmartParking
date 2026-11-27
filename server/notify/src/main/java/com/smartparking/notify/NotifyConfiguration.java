package com.smartparking.notify;

import com.smartparking.notify.alarm.AlarmRules;
import com.smartparking.notify.alarm.AlarmService;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class NotifyConfiguration {

    @Bean
    AlarmService alarmService(JdbcClient jdbc, PlatformTransactionManager txManager, ApplicationEventPublisher events) {
        return new AlarmService(jdbc, txManager, events, Clock.systemUTC());
    }

    @Bean
    AlarmRules alarmRules(AlarmService alarms, JdbcClient jdbc) {
        return new AlarmRules(alarms, jdbc);
    }
}
