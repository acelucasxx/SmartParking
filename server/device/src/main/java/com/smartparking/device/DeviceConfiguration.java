package com.smartparking.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.device.imports.DeviceImportService;
import com.smartparking.device.imports.ImportTaskRepository;
import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.device.registry.DeviceRegistry;
import com.smartparking.device.registry.DeviceRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeviceProperties.class)
public class DeviceConfiguration {

    /** 缺少或过短的密钥直接拒绝启动：激活码会以错误的密钥写入，之后全部对不上 */
    @Bean
    ActivateCodes activateCodes(DeviceProperties properties) {
        return new ActivateCodes(properties.activateCodeKey());
    }

    @Bean
    DeviceRepository deviceRepository(JdbcClient jdbc, JdbcTemplate template) {
        return new DeviceRepository(jdbc, template);
    }

    @Bean
    DeviceRegistry deviceRegistry(DeviceRepository devices, ActivateCodes codes) {
        return new DeviceRegistry(devices, codes);
    }

    @Bean
    ImportTaskRepository importTaskRepository(JdbcClient jdbc, JdbcTemplate template) {
        return new ImportTaskRepository(jdbc, template);
    }

    /** 导入在单线程里逐个处理：同一实例内的导入不会互相冲突，也不会挤占请求线程 */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor deviceImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("device-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean
    DeviceImportService deviceImportService(
            ImportTaskRepository tasks,
            DeviceRepository devices,
            ActivateCodes codes,
            DeviceProperties properties,
            TransactionTemplate tx,
            @Qualifier("deviceImportExecutor") ThreadPoolTaskExecutor executor,
            ObjectMapper json) {
        return new DeviceImportService(tasks, devices, codes, properties, tx, executor, json);
    }

    @Bean
    StaleImportSweeper staleImportSweeper(DeviceImportService imports) {
        return new StaleImportSweeper(imports);
    }

    /** 每 5 分钟把处理实例已退出的导入任务记为失败 */
    static class StaleImportSweeper {
        private final DeviceImportService imports;

        StaleImportSweeper(DeviceImportService imports) {
            this.imports = imports;
        }

        @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
        void sweep() {
            imports.failStale();
        }
    }
}
