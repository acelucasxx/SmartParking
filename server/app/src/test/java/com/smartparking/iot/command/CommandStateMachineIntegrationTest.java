package com.smartparking.iot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.Fault;
import com.smartparking.dtusim.GateBoard;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.mqtt.UplinkDispatcher;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.RuntimeCache;
import java.time.Duration;
import java.util.List;
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
 * 指令状态机端到端（不经 EMQX）：下行直接交给 DTU 模拟器的闸板，上行由单线程异步送回 {@link UplinkDispatcher}，
 * 与 MQTT 回调线程的时序一致。覆盖成功、幂等、串行、已在目标态、先查询、故障、超时、离线 / 休眠与下发失败。
 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.command.reconcile-interval=1h"
        })
@Import({TestcontainersConfiguration.class, InMemoryDeviceLink.class})
class CommandStateMachineIntegrationTest {

    private static final long TRAVEL_MS = 400;
    private static final Initiator OWNER = Initiator.user(1, "OWNER");

    @Autowired
    private CommandService commands;

    @Autowired
    private CommandExecutor executor;

    @Autowired
    private DeviceRuntimeService runtime;

    @Autowired
    private RuntimeCache cache;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InMemoryDeviceLink bridge;

    private ExecutorScheduler scheduler;
    private long deviceId;
    private String imei;
    private GateBoard board;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE system_config SET config_value = '2' WHERE config_key = 'command.timeout_seconds'")
                .update();
        scheduler = new ExecutorScheduler(2);
        imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql("INSERT INTO device (sn, imei, model, batch_no, activate_code_hash) VALUES (?, ?, 'SP-G1', 'IT', 'x')")
                .params("SN" + imei, imei)
                .update();
        deviceId = jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
        board = bridge.attach(imei, TRAVEL_MS, scheduler);
        bridge.connected = true;
        bridge.failNextOpenOrClose = false;
        // 先查询一次，让运行态有一帧新鲜的状态（关闸到位）
        bridge.sender.send(imei, Rs485Codec.encodeQuery());
        await().atMost(Duration.ofSeconds(5))
                .until(() ->
                        runtime.find(deviceId).map(r -> r.statusCode() != null).orElse(false));
        bridge.sent.clear();
        bridge.finished.clear();
    }

    @AfterEach
    void tearDown() {
        await().atMost(Duration.ofSeconds(5)).until(() -> executor.activeCount() == 0);
        scheduler.close();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private CommandView awaitTerminal(CommandView started) {
        await().atMost(Duration.ofSeconds(8))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> commands.get(deviceId, started.id()).status().isTerminal());
        return commands.get(deviceId, started.id());
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql(
                        "SELECT user_id, role, channel, down_frame, raw_frame, idempotency_key FROM control_command WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    private List<String> sentCommands() {
        return bridge.sent.stream()
                .filter(s -> s.startsWith(imei))
                .map(s -> s.substring(imei.length() + 1))
                .filter(hex -> !hex.equals(Hex.format(Rs485Codec.encodeQuery())))
                .toList();
    }

    @Test
    void openSucceedsOnlyWhenSettled() {
        CommandView started = commands.submit(deviceId, CommandAction.OPEN, key(), OWNER);
        assertThat(started.status()).isIn(CommandStatus.SENT, CommandStatus.EXECUTING);
        assertThat(started.gateStatus()).isNotNull();

        // 回显帧已到，但闸杆未到位，不能算成功
        await().atMost(Duration.ofSeconds(2))
                .until(() -> commands.get(deviceId, started.id()).status() == CommandStatus.EXECUTING);

        CommandView done = awaitTerminal(started);
        assertThat(done.status()).isEqualTo(CommandStatus.SUCCESS);
        assertThat(done.resultCode()).isNull();
        assertThat(done.gateStatus()).isNull();
        assertThat(done.durationMs()).isBetween((int) TRAVEL_MS, 3_000);
        assertThat(board.status()).isEqualTo(GateStatus.OPENED);

        var row = row(done.id());
        assertThat(row).containsEntry("role", "OWNER").containsEntry("channel", "REMOTE");
        assertThat((byte[]) row.get("down_frame")).isEqualTo(Rs485Codec.encodeOpen());
        assertThat(Rs485Codec.decodeUplink((byte[]) row.get("raw_frame")))
                .isInstanceOfSatisfying(com.smartparking.iot.codec.UplinkFrame.Status.class, s -> assertThat(s.status())
                        .isEqualTo(GateStatus.OPENED));
        assertThat(bridge.finished).anyMatch(e -> e.commandId() == done.id() && e.status() == CommandStatus.SUCCESS);
    }

    @Test
    void sameIdempotencyKeyReturnsSameCommand() {
        String key = key();
        CommandView first = commands.submit(deviceId, CommandAction.OPEN, key, OWNER);
        CommandView again = commands.submit(deviceId, CommandAction.OPEN, key, OWNER);
        assertThat(again.id()).isEqualTo(first.id());
        awaitTerminal(first);
        assertThat(commands.submit(deviceId, CommandAction.OPEN, key, OWNER).status())
                .isEqualTo(CommandStatus.SUCCESS);
        assertThat(sentCommands()).hasSize(1);
    }

    @Test
    void secondCommandWhileInFlightIsRejected() {
        CommandView first = commands.submit(deviceId, CommandAction.OPEN, key(), OWNER);
        assertThatThrownBy(() -> commands.submit(deviceId, CommandAction.CLOSE, key(), OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.COMMAND_IN_PROGRESS));
        assertThat(awaitTerminal(first).status()).isEqualTo(CommandStatus.SUCCESS);
    }

    @Test
    void alreadyInTargetIsNotSent() {
        CommandView done = commands.submit(deviceId, CommandAction.CLOSE, key(), OWNER);
        assertThat(done.status()).isEqualTo(CommandStatus.SUCCESS);
        assertThat(done.resultCode()).isEqualTo(CommandResult.ALREADY_IN_TARGET);
        assertThat(done.durationMs()).isZero();
        assertThat(sentCommands()).isEmpty();
        assertThat(row(done.id()).get("down_frame")).isNull();
    }

    @Test
    void staleCacheIsVerifiedByQueryFirst() {
        // 遥控器把闸抬起了，但缓存还是 40 秒前的“关闸到位”
        board.trigger(GateStatus.OPENED, 0x01);
        await().atMost(Duration.ofSeconds(2)).until(() -> board.status() == GateStatus.OPENED);
        jdbc.sql("UPDATE device_runtime SET status_refreshed_at = NOW(3) - INTERVAL 40 SECOND WHERE device_id = ?")
                .param(deviceId)
                .update();
        cache.evict(deviceId);

        CommandView done = commands.submit(deviceId, CommandAction.OPEN, key(), OWNER);
        assertThat(done.resultCode()).as("以查询结果判定已在目标态").isEqualTo(CommandResult.ALREADY_IN_TARGET);
        assertThat(sentCommands()).isEmpty();
    }

    @Test
    void motorFaultFails() {
        board.inject(Fault.MOTOR_FAULT);
        CommandView done = awaitTerminal(commands.submit(deviceId, CommandAction.OPEN, key(), OWNER));
        assertThat(done.status()).isEqualTo(CommandStatus.FAILED);
        assertThat(done.resultCode()).isEqualTo(CommandResult.MOTOR_FAULT);

        // 缓存已是故障状态：后续指令直接拒绝
        assertThatThrownBy(() -> commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_FAULT));
    }

    @Test
    void obstructedAndInfraredFail() {
        board.inject(Fault.OBSTRUCTED);
        assertThat(awaitTerminal(commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                        .resultCode())
                .isEqualTo(CommandResult.OBSTRUCTED);
        board.inject(Fault.INFRARED);
        assertThat(awaitTerminal(commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                        .resultCode())
                .isEqualTo(CommandResult.INFRARED);
    }

    @Test
    void stallTimesOut() {
        board.inject(Fault.STALL);
        CommandView done = awaitTerminal(commands.submit(deviceId, CommandAction.OPEN, key(), OWNER));
        assertThat(done.status()).isEqualTo(CommandStatus.TIMEOUT);
        assertThat(done.resultCode()).isEqualTo(CommandResult.TIMEOUT);
        assertThat(done.durationMs()).isBetween(2_000, 3_000);
        assertThat(row(done.id()).get("raw_frame")).isNull();
    }

    @Test
    void silentBoardTimesOutDespiteNoReplies() {
        board.setSilent(true);
        CommandView done = awaitTerminal(commands.submit(deviceId, CommandAction.OPEN, key(), OWNER));
        assertThat(done.status()).isEqualTo(CommandStatus.TIMEOUT);
        assertThat(bridge.sent.stream().filter(s -> s.startsWith(imei)).count())
                .as("执行中每 300 ms 查询一次")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void offlineAndSleepingAreRejectedWithoutQueueing() {
        jdbc.sql(
                        "UPDATE device_runtime SET last_conn_event = 'DISCONNECTED', last_conn_at = NOW(3) WHERE device_id = ?")
                .param(deviceId)
                .update();
        cache.evict(deviceId);
        assertThatThrownBy(() -> commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_SLEEPING));

        jdbc.sql("UPDATE device_runtime SET last_seen_at = NOW(3) - INTERVAL 25 HOUR WHERE device_id = ?")
                .param(deviceId)
                .update();
        cache.evict(deviceId);
        assertThatThrownBy(() -> commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_OFFLINE));

        assertThat(sentCommands()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM control_command WHERE device_id = ?")
                        .param(deviceId)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void brokerLinkDownIsOffline() {
        bridge.connected = false;
        assertThatThrownBy(() -> commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_OFFLINE));
    }

    @Test
    void publishFailureAfterInsertIsRecordedAsOffline() {
        bridge.failNextOpenOrClose = true;
        assertThatThrownBy(() -> commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_OFFLINE));
        var status = jdbc.sql("SELECT status, result_code FROM control_command WHERE device_id = ?")
                .param(deviceId)
                .query()
                .singleRow();
        assertThat(status).containsEntry("status", "FAILED").containsEntry("result_code", "OFFLINE");
        // 失败的指令已终结，不阻塞下一条
        bridge.failNextOpenOrClose = false;
        assertThat(awaitTerminal(commands.submit(deviceId, CommandAction.OPEN, key(), OWNER))
                        .status())
                .isEqualTo(CommandStatus.SUCCESS);
    }

    @Test
    void unknownDeviceAndForeignCommandAreNotFound() {
        assertThatThrownBy(() -> commands.submit(-1, CommandAction.OPEN, key(), OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_NOT_FOUND));
        CommandView done = commands.submit(deviceId, CommandAction.CLOSE, key(), OWNER);
        assertThatThrownBy(() -> commands.get(deviceId + 1, done.id()))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> commands.submit(deviceId, CommandAction.OPEN, "short", OWNER))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }
}
