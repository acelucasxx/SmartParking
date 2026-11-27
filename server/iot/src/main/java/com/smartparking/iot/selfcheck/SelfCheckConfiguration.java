package com.smartparking.iot.selfcheck;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.mqtt.DownlinkSender;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.OnlineStateService;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class SelfCheckConfiguration {

    /** 每条自检占一个线程等待回包（最长为指令超时）；后台人工触发，量很小 */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService selfCheckExecutor() {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "self-check-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    SelfCheckService selfCheckService(
            JdbcClient jdbc,
            DeviceDirectory directory,
            DeviceRuntimeService runtime,
            OnlineStateService onlineStates,
            DownlinkSender sender,
            SystemSettings settings,
            ExecutorService selfCheckExecutor) {
        return new SelfCheckService(
                jdbc, directory, runtime, onlineStates, sender, settings, selfCheckExecutor, Clock.systemUTC());
    }
}
