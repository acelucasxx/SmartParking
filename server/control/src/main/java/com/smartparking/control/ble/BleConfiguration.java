package com.smartparking.control.ble;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 蓝牙票据（BIZ-17） */
@Configuration(proxyBeanMethods = false)
public class BleConfiguration {

    @Bean
    BleTicketService bleTicketService(
            JdbcClient jdbc, TransactionTemplate tx, @Value("${smartparking.ble.enabled:false}") boolean enabled) {
        return new BleTicketService(jdbc, tx, enabled, Clock.systemUTC());
    }
}
