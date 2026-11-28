package com.smartparking.notify.subscribe;

import com.smartparking.user.wechat.WeChatClient;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 微信订阅消息（BIZ-18） */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubscribeProperties.class)
public class SubscribeConfiguration {

    /** 推送用的线程池：2 个线程、最多排队 1000 条，满了丢弃（站内消息已写入） */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor subscribeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("subscribe-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    SubscribePusher subscribePusher(
            SubscribeProperties properties,
            WeChatClient wechat,
            JdbcClient jdbc,
            ThreadPoolTaskExecutor subscribeExecutor) {
        return new SubscribePusher(properties, wechat, jdbc, subscribeExecutor);
    }
}
