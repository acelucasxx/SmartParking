package com.smartparking.control.devices;

import com.smartparking.control.access.DeviceAccessService;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.OnlineStateService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class DevicesConfiguration {

    @Bean
    OperationRecords operationRecords(JdbcClient jdbc) {
        return new OperationRecords(jdbc);
    }

    /** 蓝牙入口（PRD 5.4）依赖固件（EXT-03 / EXT-04），上线前为 false，控制页离线时只显示原因 */
    @Bean
    DeviceViews deviceViews(
            JdbcClient jdbc,
            DeviceAccessService access,
            DeviceRuntimeService runtime,
            OnlineStateService online,
            OperationRecords records,
            @Value("${smartparking.ble.enabled:false}") boolean bleEnabled) {
        return new DeviceViews(jdbc, access, runtime, online, records, bleEnabled, Clock.systemUTC());
    }
}
