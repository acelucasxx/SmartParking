package com.smartparking.control.command;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class CommandConfiguration {

    @Bean
    CommandVisibility commandVisibility(JdbcClient jdbc) {
        return new CommandVisibility(jdbc);
    }
}
