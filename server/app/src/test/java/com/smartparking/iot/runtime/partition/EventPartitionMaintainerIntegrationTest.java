package com.smartparking.iot.runtime.partition;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.iot.DbTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 分区维护在真实 MySQL 上执行 DDL。用独立的 Spring 上下文（独立容器），避免改动分区影响其他测试。
 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.test.context=partition-maintenance"
        })
@Import(TestcontainersConfiguration.class)
class EventPartitionMaintainerIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    private EventPartitionMaintainer at(LocalDate day) {
        var clock = Clock.fixed(day.atStartOfDay(DbTime.ZONE).toInstant(), DbTime.ZONE);
        return new EventPartitionMaintainer(jdbc, new EventPartitionProperties(true, 186, 3), clock);
    }

    private List<String> partitions() {
        return jdbc.sql(
                        """
                        SELECT partition_name FROM information_schema.partitions
                        WHERE table_schema = DATABASE() AND table_name = 'device_event' ORDER BY partition_ordinal_position""")
                .query(String.class)
                .list();
    }

    @Test
    void extendsAheadThenDropsExpiredThenIsIdempotent() throws Exception {
        jdbc.sql(
                        "INSERT INTO device_event (device_id, gate_status, raw_frame, at) VALUES (1, 'CLOSED', x'00', '2026-11-15 08:00:00')")
                .update();

        // 2027-06-05：仍覆盖到 2027-09，只有 p202611 整月超过 186 天保留期
        assertThat(at(LocalDate.of(2027, 6, 5)).maintain())
                .containsExactly("ALTER TABLE device_event DROP PARTITION p202611");
        assertThat(partitions()).startsWith("p202612").doesNotContain("p202611");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM device_event")
                        .query(Integer.class)
                        .single())
                .as("过期分区的数据随分区删除")
                .isZero();
        assertThat(at(LocalDate.of(2027, 6, 5)).maintain()).as("同一天重复执行无变更").isEmpty();

        // 2027-10-15：需覆盖到 2028-01，补 p202801；同时 2027-03 及以前的分区过期
        assertThat(at(LocalDate.of(2027, 10, 15)).maintain()).hasSize(2);
        assertThat(partitions()).startsWith("p202704").endsWith("p202712", "p202801", "p_future");
        jdbc.sql(
                        "INSERT INTO device_event (device_id, gate_status, raw_frame, at) VALUES (1, 'OPENED', x'00', '2028-01-20 08:00:00')")
                .update();

        try (Connection other = dataSource.getConnection();
                var statement = other.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT GET_LOCK('smartparking.device_event.partitions', 0)")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            assertThat(at(LocalDate.of(2028, 3, 1)).maintain()).as("其他实例持锁时跳过").isEmpty();
            statement.execute("SELECT RELEASE_LOCK('smartparking.device_event.partitions')");
        }
        assertThat(at(LocalDate.of(2028, 3, 1)).maintain()).isNotEmpty();
        assertThat(partitions()).contains("p202806").last().isEqualTo("p_future");
    }
}
