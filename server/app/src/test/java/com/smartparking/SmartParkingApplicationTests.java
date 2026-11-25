package com.smartparking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SmartParkingApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthEndpointIsUp() {
        var body = rest.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"status\":\"UP\"");
        // 引入数据源后 health 汇总包含数据库检查
    }
}
