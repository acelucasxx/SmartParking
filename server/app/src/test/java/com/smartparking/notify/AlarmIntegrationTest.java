package com.smartparking.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.admin.TestAdminIdentity;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminRole;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.command.CommandAction;
import com.smartparking.iot.command.CommandFinishedEvent;
import com.smartparking.iot.command.CommandResult;
import com.smartparking.iot.command.CommandStatus;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.runtime.OnlineState;
import com.smartparking.iot.runtime.OnlineStateChangedEvent;
import com.smartparking.iot.runtime.OnlineStateService;
import com.smartparking.notify.alarm.AlarmRaisedEvent;
import com.smartparking.notify.alarm.AlarmService;
import com.smartparking.notify.alarm.AlarmType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.assertj.core.groups.Tuple;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 告警规则、合并与告警中心接口（BIZ-07）。MySQL / Redis 为真实容器，iot 事件由测试直接发布。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.online.sweep-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfiguration.class, TestAdminIdentity.class})
class AlarmIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private AlarmService alarms;

    @Autowired
    private OnlineStateService onlineStates;

    @Autowired
    private ApplicationEvents events;

    private long device;
    private String imei;
    private String sn;
    private long userId;

    @BeforeEach
    void setUp() {
        String openid = "it-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (openid) VALUES (?)").param(openid).update();
        userId = jdbc.sql("SELECT id FROM app_user WHERE openid = ?")
                .param(openid)
                .query(Long.class)
                .single();
        imei = newImei();
        sn = "SN" + imei;
        device = device(imei, true);
    }

    // ------------------------------------------------------------ 造数据

    private static String newImei() {
        return "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }

    private long device(String imei, boolean bound) {
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since)"
                                + " VALUES (?, ?, 'SPG-100', 'IT', 'x', ?, ?, ?)")
                .params(
                        "SN" + imei,
                        imei,
                        bound ? "BOUND" : "PENDING",
                        bound ? userId : null,
                        bound ? DbTime.toDb(now().minus(Duration.ofDays(1))) : null)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** 一条状态帧上行，走真实的运行态写入与变化判定 */
    private void frame(int statusCode, int reason, Instant at) {
        publisher.publishEvent(new DeviceUplinkEvent(
                device, imei, Rs485Codec.decodeUplink(Rs485Codec.encodeStatus(1, statusCode, reason, 0x00)), at));
    }

    private record Row(long id, String type, String level, int count, String status, Instant firstAt, Instant lastAt) {}

    private List<Row> rows(long deviceId) {
        return jdbc.sql(
                        "SELECT id, type, level, count, status, first_at, last_at FROM alarm WHERE device_id = ? ORDER BY id")
                .param(deviceId)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("level"),
                        rs.getInt("count"),
                        rs.getString("status"),
                        DbTime.fromDb(rs.getObject("first_at", LocalDateTime.class)),
                        DbTime.fromDb(rs.getObject("last_at", LocalDateTime.class))))
                .list();
    }

    private void commandFinished(CommandStatus status, int issuedSecondsAgo) {
        jdbc.sql(
                        "INSERT INTO control_command (device_id, user_id, role, channel, action, status, result_code, issued_at,"
                                + " finished_at) VALUES (?, ?, 'OWNER', 'REMOTE', 'OPEN', ?, ?, ?, ?)")
                .params(
                        device,
                        userId,
                        status.name(),
                        status == CommandStatus.TIMEOUT
                                ? "TIMEOUT"
                                : status == CommandStatus.FAILED ? "OBSTRUCTED" : null,
                        DbTime.toDb(now().minusSeconds(issuedSecondsAgo)),
                        DbTime.toDb(now().minusSeconds(issuedSecondsAgo - 15)))
                .update();
        long id = jdbc.sql("SELECT MAX(id) FROM control_command WHERE device_id = ?")
                .param(device)
                .query(Long.class)
                .single();
        CommandResult result = status == CommandStatus.TIMEOUT
                ? CommandResult.TIMEOUT
                : status == CommandStatus.FAILED ? CommandResult.OBSTRUCTED : null;
        publisher.publishEvent(new CommandFinishedEvent(id, device, CommandAction.OPEN, status, result, now()));
    }

    private long admin(String displayName) {
        String username = "it" + ThreadLocalRandom.current().nextInt(1_000_000_000);
        jdbc.sql("INSERT INTO admin_user (username, password_hash, display_name, role_id) VALUES (?, 'x', ?, 1)")
                .params(username, displayName)
                .update();
        return jdbc.sql("SELECT id FROM admin_user WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
    }

    private ResultActions as(String identity, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(TestAdminIdentity.HEADER, identity));
    }

    private ResultActions handle(String identity, long alarmId, String body) throws Exception {
        return as(
                identity,
                post("/api/admin/alarms/{id}/handle", alarmId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    // ------------------------------------------------------------ 规则

    @Test
    void motorFaultRaisesOnceUntilItRecovers() {
        Instant t = now();
        frame(0x0C, 0x16, t);
        assertThat(rows(device)).isEmpty();

        frame(0xE3, 0x16, t.plusSeconds(1));
        assertThat(rows(device)).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("MOTOR_FAULT");
            assertThat(r.level()).isEqualTo("CRITICAL");
            assertThat(r.count()).isEqualTo(1);
            assertThat(r.firstAt()).isEqualTo(t.plusSeconds(1));
        });

        // 故障持续：车位变化等引起的状态变化仍是 E3，不算新的发生
        publisher.publishEvent(new DeviceUplinkEvent(
                device, imei, Rs485Codec.decodeUplink(Rs485Codec.encodeStatus(1, 0xE3, 0x16, 0x11)), t.plusSeconds(2)));
        assertThat(rows(device)).singleElement().extracting(Row::count).isEqualTo(1);

        // 恢复后再次故障：30 分钟内合并
        frame(0x0C, 0x16, t.plusSeconds(60));
        frame(0xE3, 0x16, t.plusSeconds(120));
        assertThat(rows(device)).singleElement().satisfies(r -> {
            assertThat(r.count()).isEqualTo(2);
            assertThat(r.firstAt()).isEqualTo(t.plusSeconds(1));
            assertThat(r.lastAt()).isEqualTo(t.plusSeconds(120));
        });
        assertThat(events.stream(AlarmRaisedEvent.class).filter(e -> e.deviceId() == device))
                .extracting(AlarmRaisedEvent::merged)
                .containsExactly(false, true);
    }

    @Test
    void obstructionCountsOncePerIncidentAndInfraredIsSeparate() {
        Instant t = now();
        frame(0x0C, 0x16, t);
        // 落杆遇阻：闸板反转抬起，后续帧仍带原因码 11
        frame(0x19, 0x11, t.plusSeconds(1));
        frame(0x09, 0x11, t.plusSeconds(3));
        assertThat(rows(device)).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("OBSTRUCTED");
            assertThat(r.level()).isEqualTo("WARNING");
            assertThat(r.count()).isEqualTo(1);
        });

        // 遥控器再次落杆，红外防砸触发：另一类
        frame(0x1C, 0x02, t.plusSeconds(10));
        frame(0x09, 0x12, t.plusSeconds(11));
        // 原因从红外直接变为遇阻，也是新的一次
        frame(0x19, 0x11, t.plusSeconds(20));
        assertThat(rows(device))
                .extracting(Row::type, Row::count)
                .containsExactly(Tuple.tuple("OBSTRUCTED", 2), Tuple.tuple("INFRARED", 1));
    }

    @Test
    void firstEverFrameWithFaultRaises() {
        frame(0xE3, 0x00, now());
        assertThat(rows(device)).extracting(Row::type).containsExactly("MOTOR_FAULT");
    }

    @Test
    void threeConsecutiveTimeoutsRaiseAndSuccessResets() {
        commandFinished(CommandStatus.TIMEOUT, 600);
        commandFinished(CommandStatus.TIMEOUT, 500);
        assertThat(rows(device)).isEmpty();

        // 中间一次成功：重新计数
        commandFinished(CommandStatus.SUCCESS, 400);
        commandFinished(CommandStatus.TIMEOUT, 300);
        commandFinished(CommandStatus.TIMEOUT, 200);
        assertThat(rows(device)).isEmpty();

        commandFinished(CommandStatus.TIMEOUT, 100);
        assertThat(rows(device)).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("CMD_TIMEOUT");
            assertThat(r.level()).isEqualTo("WARNING");
            assertThat(r.count()).isEqualTo(1);
        });

        // 继续超时：合并计数
        commandFinished(CommandStatus.TIMEOUT, 50);
        assertThat(rows(device)).singleElement().extracting(Row::count).isEqualTo(2);

        // 失败（如遇阻）也打断连续超时
        commandFinished(CommandStatus.FAILED, 40);
        commandFinished(CommandStatus.TIMEOUT, 30);
        assertThat(rows(device)).singleElement().extracting(Row::count).isEqualTo(2);
    }

    @Test
    void offlineFor24HoursRaisesForBoundDevicesOnly() {
        Instant t = now();
        frame(0x0C, 0x16, t);
        // 设备沉默 25 小时，扫描判定为离线
        jdbc.sql("UPDATE device_runtime SET last_seen_at = ?, rev = rev + 1 WHERE device_id = ?")
                .params(DbTime.toDb(t.minus(Duration.ofHours(25))), device)
                .update();
        onlineStates.reevaluate(device);
        assertThat(rows(device)).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo("OFFLINE_24H");
            assertThat(r.level()).isEqualTo("CRITICAL");
        });

        // 休眠不告警；库存中（未绑定）的设备离线不告警
        long stock = device(newImei(), false);
        publisher.publishEvent(new OnlineStateChangedEvent(device, OnlineState.ONLINE, OnlineState.SLEEP, t, t));
        publisher.publishEvent(new OnlineStateChangedEvent(stock, OnlineState.SLEEP, OnlineState.OFFLINE, t, t));
        assertThat(rows(device)).singleElement().extracting(Row::count).isEqualTo(1);
        assertThat(rows(stock)).isEmpty();
    }

    @Test
    void mergeOnlyWithinWindowAndOnlyIntoOpenAlarms() {
        Instant t = now().minus(Duration.ofHours(3));
        alarms.raise(device, AlarmType.OBSTRUCTED, t);
        alarms.raise(device, AlarmType.OBSTRUCTED, t.plus(Duration.ofMinutes(29)));
        // 窗口按最近一次计：距上次 29 分钟，仍合并
        alarms.raise(device, AlarmType.OBSTRUCTED, t.plus(Duration.ofMinutes(58)));
        // 距上次 31 分钟：新告警
        alarms.raise(device, AlarmType.OBSTRUCTED, t.plus(Duration.ofMinutes(89)));
        // 迟到的事件早于 last_at：合并，但不把 last_at 改早
        alarms.raise(device, AlarmType.OBSTRUCTED, t.plus(Duration.ofMinutes(80)));
        // 不同类型不合并
        alarms.raise(device, AlarmType.INFRARED, t.plus(Duration.ofMinutes(90)));

        List<Row> rows = rows(device);
        assertThat(rows)
                .extracting(Row::type, Row::count)
                .containsExactly(
                        Tuple.tuple("OBSTRUCTED", 3), Tuple.tuple("OBSTRUCTED", 2), Tuple.tuple("INFRARED", 1));
        assertThat(rows.get(0).lastAt()).isEqualTo(t.plus(Duration.ofMinutes(58)));
        assertThat(rows.get(1).lastAt()).isEqualTo(t.plus(Duration.ofMinutes(89)));

        // 已处理的告警不再合并
        jdbc.sql("UPDATE alarm SET status = 'HANDLED' WHERE id = ?")
                .param(rows.get(1).id())
                .update();
        alarms.raise(device, AlarmType.OBSTRUCTED, t.plus(Duration.ofMinutes(95)));
        assertThat(rows(device)).hasSize(4).last().satisfies(r -> {
            assertThat(r.type()).isEqualTo("OBSTRUCTED");
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.count()).isEqualTo(1);
        });
    }

    @Test
    void concurrentRaisesMergeIntoOneAlarm() throws Exception {
        int threads = 8;
        Instant t = now();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                return alarms.raise(device, AlarmType.MOTOR_FAULT, t.plusMillis(n));
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(rows(device)).singleElement().extracting(Row::count).isEqualTo(threads);
    }

    @Test
    void unknownDeviceIsIgnoredWithoutBreakingThePublisher() {
        long missing = 9_000_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
        assertThat(alarms.raise(missing, AlarmType.MOTOR_FAULT, now())).isEmpty();
        publisher.publishEvent(
                new OnlineStateChangedEvent(missing, OnlineState.SLEEP, OnlineState.OFFLINE, now(), now()));
        assertThat(rows(missing)).isEmpty();
    }

    // ------------------------------------------------------------ 告警中心

    @Test
    void listFiltersAndOrdersByLastOccurrence() throws Exception {
        String ops = TestAdminIdentity.header(admin("运维小王"), AdminRole.OPS, "运维小王");
        Instant t = now().minus(Duration.ofHours(2));
        long other = device(newImei(), true);
        long obstructed =
                alarms.raise(device, AlarmType.OBSTRUCTED, t).orElseThrow().alarmId();
        long fault = alarms.raise(device, AlarmType.MOTOR_FAULT, t.plusSeconds(10))
                .orElseThrow()
                .alarmId();
        long otherFault = alarms.raise(other, AlarmType.MOTOR_FAULT, t.plusSeconds(5))
                .orElseThrow()
                .alarmId();
        alarms.raise(device, AlarmType.OBSTRUCTED, t.plusSeconds(20));
        // 编号最大、发生时间居中：排序按最近发生时间而不是编号
        long infrared = alarms.raise(device, AlarmType.INFRARED, t.plusSeconds(15))
                .orElseThrow()
                .alarmId();
        handle(ops, fault, "{\"note\": \"已更换电机\"}").andExpect(status().isOk());

        as(ops, get("/api/admin/alarms").param("deviceId", String.valueOf(device)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[0].id").value(obstructed))
                .andExpect(jsonPath("$.data.items[0].sn").value(sn))
                .andExpect(jsonPath("$.data.items[0].type").value("OBSTRUCTED"))
                .andExpect(jsonPath("$.data.items[0].level").value("WARNING"))
                .andExpect(jsonPath("$.data.items[0].count").value(2))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].handlerName").isEmpty())
                .andExpect(jsonPath("$.data.items[0].firstAt").value(Matchers.endsWith("+08:00")))
                .andExpect(jsonPath("$.data.items[1].id").value(infrared))
                .andExpect(jsonPath("$.data.items[2].id").value(fault));

        as(
                        ops,
                        get("/api/admin/alarms")
                                .param("sn", " " + sn.toLowerCase() + " ")
                                .param("status", "HANDLED"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(fault))
                .andExpect(jsonPath("$.data.items[0].handlerName").value("运维小王"))
                .andExpect(jsonPath("$.data.items[0].note").value("已更换电机"));

        as(
                        ops,
                        get("/api/admin/alarms")
                                .param("deviceId", String.valueOf(other))
                                .param("type", "MOTOR_FAULT"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(otherFault));
        as(
                        ops,
                        get("/api/admin/alarms")
                                .param("deviceId", String.valueOf(other))
                                .param("type", "INFRARED"))
                .andExpect(jsonPath("$.data.total").value(0));

        as(
                        ops,
                        get("/api/admin/alarms")
                                .param("deviceId", String.valueOf(device))
                                .param("size", "1")
                                .param("page", "2"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(infrared));

        expectError(as(ops, get("/api/admin/alarms").param("status", "CLOSED")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/alarms").param("type", "FIRE")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/alarms").param("size", "101")), ErrorCode.BAD_REQUEST);
    }

    @Test
    void handleRecordsHandlerOnceAndValidatesNote() throws Exception {
        long adminId = admin("运维小李");
        String ops = TestAdminIdentity.header(adminId, AdminRole.OPS, "运维小李");
        String superAdmin = TestAdminIdentity.header(admin("超管"), AdminRole.SUPER_ADMIN, "超管");
        long alarmId =
                alarms.raise(device, AlarmType.INFRARED, now()).orElseThrow().alarmId();

        expectError(handle(ops, alarmId, "{\"note\": \"   \"}"), ErrorCode.BAD_REQUEST);
        expectError(handle(ops, alarmId, "{}"), ErrorCode.BAD_REQUEST);
        expectError(handle(ops, alarmId, "{\"note\": \"" + "长".repeat(513) + "\"}"), ErrorCode.BAD_REQUEST);
        expectError(handle(ops, 9_000_000_000L, "{\"note\": \"x\"}"), ErrorCode.NOT_FOUND);

        handle(ops, alarmId, "{\"note\": \"  已电话联系业主，现场有杂物，已清理  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(alarmId))
                .andExpect(jsonPath("$.data.status").value("HANDLED"))
                .andExpect(jsonPath("$.data.handlerName").value("运维小李"))
                .andExpect(jsonPath("$.data.handledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.note").value("已电话联系业主，现场有杂物，已清理"));

        // 已处理过：10009，处理人与备注不被覆盖
        expectError(handle(superAdmin, alarmId, "{\"note\": \"重复处理\"}"), ErrorCode.CONFLICT);
        assertThat(jdbc.sql("SELECT handler_id FROM alarm WHERE id = ?")
                        .param(alarmId)
                        .query(Long.class)
                        .single())
                .isEqualTo(adminId);

        // 新的同类告警不并入已处理的那条
        long next =
                alarms.raise(device, AlarmType.INFRARED, now()).orElseThrow().alarmId();
        assertThat(next).isNotEqualTo(alarmId);
    }

    @Test
    void onlySuperAdminAndOpsCanUseAlarmCenter() throws Exception {
        String support = TestAdminIdentity.header(admin("客服"), AdminRole.SUPPORT, "客服");
        long alarmId =
                alarms.raise(device, AlarmType.OBSTRUCTED, now()).orElseThrow().alarmId();
        expectError(as(support, get("/api/admin/alarms")), ErrorCode.FORBIDDEN);
        expectError(handle(support, alarmId, "{\"note\": \"x\"}"), ErrorCode.FORBIDDEN);
        expectError(mvc.perform(get("/api/admin/alarms")), ErrorCode.UNAUTHORIZED);
        assertThat(rows(device)).singleElement().extracting(Row::status).isEqualTo("OPEN");
    }
}
