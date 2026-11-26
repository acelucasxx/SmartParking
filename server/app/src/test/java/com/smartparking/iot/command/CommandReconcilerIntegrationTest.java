package com.smartparking.iot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.Fault;
import com.smartparking.dtusim.GateBoard;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.RuntimeCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 对账与重启恢复：直接在库里放“执行器丢失”的未终结指令，验证按运行态补齐结果、按时间窗口恢复跟踪。
 * 与 {@link CommandStateMachineIntegrationTest} 共用 Spring 上下文与容器。
 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.command.reconcile-interval=1h"
        })
@Import({TestcontainersConfiguration.class, InMemoryDeviceLink.class})
class CommandReconcilerIntegrationTest {

    private static final long TRAVEL_MS = 300;

    @Autowired
    private CommandReconciler reconciler;

    @Autowired
    private CommandExecutor executor;

    @Autowired
    private CommandService commands;

    @Autowired
    private DeviceRuntimeService runtime;

    @Autowired
    private RuntimeCache cache;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InMemoryDeviceLink link;

    private ExecutorScheduler scheduler;
    private long deviceId;
    private String imei;
    private GateBoard board;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE system_config SET config_value = '2' WHERE config_key = 'command.timeout_seconds'")
                .update();
        // 清掉其他用例遗留的未终结指令，避免影响本用例的计数
        jdbc.sql("UPDATE control_command SET status = 'TIMEOUT', result_code = 'TIMEOUT', finished_at = NOW(3)"
                        + " WHERE status IN ('PENDING', 'SENT', 'EXECUTING')")
                .update();
        scheduler = new ExecutorScheduler(2);
        deviceId = newDevice();
        imei = imeiOf(deviceId);
        board = link.attach(imei, TRAVEL_MS, scheduler);
        link.connected = true;
        link.failNextOpenOrClose = false;
        query();
        link.finished.clear();
    }

    @AfterEach
    void tearDown() {
        await().atMost(Duration.ofSeconds(5)).until(() -> executor.activeCount() == 0);
        scheduler.close();
    }

    private long newDevice() {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql("INSERT INTO device (sn, imei, model, batch_no, activate_code_hash) VALUES (?, ?, 'SP-G1', 'IT', 'x')")
                .params("SN" + imei, imei)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private String imeiOf(long id) {
        return jdbc.sql("SELECT imei FROM device WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    /** 下发一次查询，等运行态由新状态帧刷新 */
    private void query() {
        Instant before = Instant.now();
        link.sender.send(imei, Rs485Codec.encodeQuery());
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.find(deviceId)
                .map(r ->
                        r.statusRefreshedAt() != null && !r.statusRefreshedAt().isBefore(before.minusMillis(5)))
                .orElse(false));
    }

    /** 模拟执行器丢失：直接写一条未终结指令 */
    private long orphan(long device, CommandAction action, CommandStatus status, Instant issuedAt) {
        jdbc.sql(
                        """
                        INSERT INTO control_command (device_id, user_id, role, channel, action, status, idempotency_key, issued_at)
                        VALUES (?, 1, 'OWNER', 'REMOTE', ?, ?, ?, ?)""")
                .params(device, action.name(), status.name(), UUID.randomUUID().toString(), DbTime.toDb(issuedAt))
                .update();
        return jdbc.sql("SELECT id FROM control_command WHERE device_id = ? ORDER BY id DESC LIMIT 1")
                .param(device)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql("SELECT status, result_code, finished_at, duration_ms FROM control_command WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    @Test
    void lostCommandWhoseTargetWasReachedIsSuccess() {
        long id = orphan(
                deviceId,
                CommandAction.OPEN,
                CommandStatus.EXECUTING,
                Instant.now().minusSeconds(30));
        board.trigger(GateStatus.OPENED, 0x16);
        await().atMost(Duration.ofSeconds(2)).until(() -> board.status() == GateStatus.OPENED);
        query();

        assertThat(reconciler.reconcile(Instant.now())).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "SUCCESS").containsEntry("result_code", null);
        assertThat(((Number) row(id).get("duration_ms")).longValue()).isGreaterThanOrEqualTo(30_000);
        assertThat(link.finished).anyMatch(e -> e.commandId() == id && e.status() == CommandStatus.SUCCESS);
    }

    @Test
    void lostCommandWithoutProgressTimesOut() {
        long id = orphan(
                deviceId, CommandAction.OPEN, CommandStatus.SENT, Instant.now().minusSeconds(30));
        query();
        assertThat(reconciler.reconcile(Instant.now())).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "TIMEOUT").containsEntry("result_code", "TIMEOUT");
    }

    @Test
    void faultSeenAfterIssueFailsTheCommand() {
        long id = orphan(
                deviceId,
                CommandAction.OPEN,
                CommandStatus.EXECUTING,
                Instant.now().minusSeconds(30));
        board.inject(Fault.MOTOR_FAULT);
        board.trigger(GateStatus.OPENED, 0x16);
        query();
        assertThat(reconciler.reconcile(Instant.now())).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("result_code", "MOTOR_FAULT");
    }

    @Test
    void statusRefreshedBeforeIssueIsNotTrusted() {
        board.trigger(GateStatus.OPENED, 0x16);
        await().atMost(Duration.ofSeconds(2)).until(() -> board.status() == GateStatus.OPENED);
        query();
        // 指令在最后一次状态刷新之后才下发：运行态不能说明这条指令的结果
        long id = orphan(
                deviceId, CommandAction.OPEN, CommandStatus.SENT, Instant.now().plusMillis(10));
        assertThat(reconciler.reconcile(Instant.now().plusSeconds(30))).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "TIMEOUT");
    }

    @Test
    void commandsWithinTimeoutWindowAreLeftToTheExecutor() {
        long id = orphan(
                deviceId, CommandAction.OPEN, CommandStatus.SENT, Instant.now().minusSeconds(3));
        // 超时 2 秒 + 宽限 5 秒，3 秒前下发的指令还不归对账处理
        assertThat(reconciler.reconcile(Instant.now())).isZero();
        assertThat(row(id)).containsEntry("status", "SENT");
        jdbc.sql("UPDATE control_command SET status = 'TIMEOUT', finished_at = NOW(3) WHERE id = ?")
                .param(id)
                .update();
    }

    @Test
    void recoverResumesTrackingOfRecentCommand() {
        // 进程重启前已把“抬杆”发到设备，闸杆正在动作
        long id = orphan(deviceId, CommandAction.OPEN, CommandStatus.SENT, Instant.now());
        board.trigger(GateStatus.OPENED, 0x16);
        cache.evict(deviceId);

        reconciler.recover();
        assertThat(executor.isTracking(id)).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> "SUCCESS".equals(row(id).get("status")));
        assertThat(executor.isTracking(id)).isFalse();
    }

    @Test
    void recoverFinishesExpiredAndSkipsTracked() {
        long expired = orphan(
                deviceId,
                CommandAction.OPEN,
                CommandStatus.PENDING,
                Instant.now().minusSeconds(60));

        long other = newDevice();
        String otherImei = imeiOf(other);
        link.attach(otherImei, TRAVEL_MS, scheduler).inject(Fault.STALL);
        link.sender.send(otherImei, Rs485Codec.encodeQuery());
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.find(other).isPresent());
        CommandView tracked =
                commands.submit(other, CommandAction.OPEN, UUID.randomUUID().toString(), Initiator.user(1, "OWNER"));

        reconciler.recover();
        assertThat(row(expired)).containsEntry("status", "TIMEOUT");
        assertThat(executor.isTracking(tracked.id())).as("本实例已在跟踪的指令不重复接管").isTrue();
        assertThat(executor.activeCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> commands.get(other, tracked.id()).status() == CommandStatus.TIMEOUT);
    }

    @Test
    void backlogLargerThanOnePageIsDrained() {
        int count = CommandReconciler.PAGE + 20;
        Instant old = Instant.now().minusSeconds(120);
        for (int i = 0; i < count; i++) {
            orphan(newDevice(), CommandAction.CLOSE, CommandStatus.PENDING, old);
        }
        assertThat(reconciler.reconcile(Instant.now())).isEqualTo(count);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM control_command WHERE status IN ('PENDING', 'SENT', 'EXECUTING')")
                        .query(Integer.class)
                        .single())
                .isZero();
    }
}
