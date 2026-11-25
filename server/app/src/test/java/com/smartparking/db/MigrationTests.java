package com.smartparking.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.TestcontainersConfiguration;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 校验 Flyway 迁移在 MySQL 8.4 上完整执行，且关键约束按 PRD 生效。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            // 断言的是迁移脚本建出的初始分区，不让启动时的分区维护介入
            "smartparking.device-event.partitions.enabled=false"
        })
@Import(TestcontainersConfiguration.class)
@Transactional
class MigrationTests {

    private static final List<String> TABLES = List.of(
            // 13 张业务表
            "device",
            "device_runtime",
            "device_part_log",
            "app_user",
            "device_member",
            "share_invite",
            "device_transfer",
            "ownership_log",
            "control_command",
            "device_event",
            "ble_ticket",
            "app_message",
            "alarm",
            // 5 张后台表
            "admin_user",
            "role",
            "operation_log",
            "system_config",
            "import_task");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allMigrationsApplied() {
        var failed = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        var applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL", Integer.class);
        assertThat(failed).isZero();
        assertThat(applied).isEqualTo(migrationScripts());
    }

    private static int migrationScripts() {
        try {
            return new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*__*.sql")
                    .length;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createsAllPrdTables() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    void deviceEventIsPartitionedByMonth() {
        var partitions = jdbc.queryForList(
                "SELECT partition_name FROM information_schema.partitions"
                        + " WHERE table_schema = DATABASE() AND table_name = 'device_event'"
                        + " ORDER BY partition_ordinal_position",
                String.class);
        assertThat(partitions).hasSize(15).startsWith("p202611", "p202612").endsWith("p202712", "p_future");

        // 开闸到位 / 平台指令 / 有车（PRD 8.2 示例帧）
        jdbc.update("INSERT INTO device_event (device_id, gate_status, raw_frame, at)"
                + " VALUES (1, 'OPENED', UNHEX('FD0001091611FDFA'), '2027-01-15 08:00:00')");
        var hit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_event PARTITION (p202701) WHERE device_id = 1", Integer.class);
        assertThat(hit).isEqualTo(1);
    }

    @Test
    void seedsRolesAndSystemConfig() {
        assertThat(jdbc.queryForList("SELECT code FROM role", String.class))
                .containsExactlyInAnyOrder("SUPER_ADMIN", "OPS", "SUPPORT");
        assertThat(jdbc.queryForObject(
                        "SELECT config_value FROM system_config WHERE config_key = 'member.max_per_device'",
                        String.class))
                .isEqualTo("5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class))
                .as("不预置后台账号")
                .isZero();
    }

    @Test
    void memberIsUniquePerDeviceOnlyWhileActive() {
        var insert = "INSERT INTO device_member (device_id, user_id, valid_from, granted_by) VALUES (1, 2, NOW(3), 9)";
        jdbc.update(insert);
        assertThatThrownBy(() -> jdbc.update(insert)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE device_member SET status = 'REVOKED' WHERE device_id = 1 AND user_id = 2");
        jdbc.update(insert);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_member", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void deviceAllowsOnlyOneInflightCommand() {
        var insert = "INSERT INTO control_command (device_id, user_id, role, channel, action)"
                + " VALUES (1, 2, 'OWNER', 'REMOTE', ?)";
        jdbc.update(insert, "OPEN");
        assertThatThrownBy(() -> jdbc.update(insert, "CLOSE")).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE control_command SET status = 'SUCCESS' WHERE device_id = 1");
        jdbc.update(insert, "CLOSE");
    }

    @Test
    void deviceAllowsOnlyOnePendingTransfer() {
        var insert = "INSERT INTO device_transfer (token_hash, device_id, from_user, expire_at)"
                + " VALUES (?, 1, 2, NOW(3) + INTERVAL 1 DAY)";
        jdbc.update(insert, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(insert, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void boundDeviceRequiresOwner() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO device (sn, imei, model, batch_no, activate_code_hash,"
                        + " bind_status) VALUES ('SN1', '860000000000001', 'M1', 'B1', 'h', 'BOUND')"))
                // MySQL 3819 = CHECK 约束不满足；Spring 未把它归入 DataIntegrityViolationException
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(3819));
    }
}
