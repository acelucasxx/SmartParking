package com.smartparking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminRole;
import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.device.registry.DeviceRules;
import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.GateBoard;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.command.CommandRepository;
import com.smartparking.iot.command.CommandService;
import com.smartparking.iot.command.InMemoryDeviceLink;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.RuntimeCache;
import com.smartparking.iot.selfcheck.SelfCheckService;
import com.smartparking.ownership.unbind.DeviceUnboundEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 后台运维操作（BIZ-10）：强制解绑、重置激活码、禁用 / 启用、换件、自检。MySQL / Redis 为真实容器，设备为内存中的 DTU 模拟器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.command.reconcile-interval=1h",
            "smartparking.online.sweep-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfiguration.class, TestAdminIdentity.class, InMemoryDeviceLink.class})
class AdminOpsIntegrationTest {

    private static final String REASON = "业主持购买凭证申诉抢绑";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ActivateCodes codes;

    @Autowired
    private InMemoryDeviceLink bridge;

    @Autowired
    private DeviceRuntimeService runtime;

    @Autowired
    private RuntimeCache cache;

    @Autowired
    private DeviceDirectory directory;

    @Autowired
    private CommandService commands;

    @Autowired
    private CommandRepository commandRepository;

    @Autowired
    private SelfCheckService selfCheck;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private ApplicationEventPublisher publisher;

    private final String ops = TestAdminIdentity.header(11, AdminRole.OPS, "运维");
    private final String support = TestAdminIdentity.header(12, AdminRole.SUPPORT, "客服");

    private ExecutorScheduler scheduler;
    private GateBoard board;
    private long owner;
    private long device;
    private String imei;
    private String sn;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE system_config SET config_value = '2' WHERE config_key = 'command.timeout_seconds'")
                .update();
        owner = user();
        imei = newImei();
        sn = "SN" + imei;
        jdbc.sql(
                        """
                        INSERT INTO device (sn, imei, ble_mac, model, batch_no, activate_code_hash, bind_status, owner_user_id,
                                            owner_since, name, space_no)
                        VALUES (?, ?, ?, 'SPG-100', 'IT', ?, 'BOUND', ?, NOW(3) - INTERVAL 1 DAY, '家里车位', 'B2-108')""")
                .params(sn, imei, mac(), codes.hash("AAAA2222"), owner)
                .update();
        device = id("device", "imei", imei);
        scheduler = new ExecutorScheduler(2);
        board = bridge.attach(imei, 200, scheduler);
        bridge.connected = true;
        bridge.sender.send(imei, Rs485Codec.encodeQuery());
        await().atMost(Duration.ofSeconds(5))
                .until(() ->
                        runtime.find(device).map(r -> r.statusCode() != null).orElse(false));
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("UPDATE system_config SET config_value = '15' WHERE config_key = 'command.timeout_seconds'")
                .update();
        scheduler.close();
    }

    // ------------------------------------------------------------ 造数据

    private static String newImei() {
        // 14 位随机 + Luhn 校验位
        String body = "86" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        for (int d = 0; d <= 9; d++) {
            if (DeviceRules.luhn(body + d)) {
                return body + d;
            }
        }
        throw new IllegalStateException();
    }

    private static String mac() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", ThreadLocalRandom.current().nextInt(256)));
        }
        return sb.toString();
    }

    private long user() {
        String openid = "it-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (openid) VALUES (?)").param(openid).update();
        return id("app_user", "openid", openid);
    }

    private long id(String table, String column, String value) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE " + column + " = ?")
                .param(value)
                .query(Long.class)
                .single();
    }

    private void member(long userId, String status) {
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, status, granted_by) VALUES (?, ?,"
                        + " NOW(3) - INTERVAL 1 HOUR, ?, ?)")
                .params(device, userId, status, owner)
                .update();
    }

    private ResultActions call(String identity, String action, Object body) throws Exception {
        return mvc.perform(post("/api/admin/devices/{id}/" + action, device)
                .header(TestAdminIdentity.HEADER, identity)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private static void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    private String deviceString(String column) {
        return jdbc.sql("SELECT " + column + " FROM device WHERE id = ?")
                .param(device)
                .query(String.class)
                .list()
                .get(0);
    }

    private String lastLog(String action) {
        return jdbc.sql(
                        "SELECT CONCAT(result, ' ', COALESCE(reason, '-')) FROM operation_log WHERE action = ? AND target_id = ?"
                                + " ORDER BY id DESC LIMIT 1")
                .params(action, String.valueOf(device))
                .query(String.class)
                .single();
    }

    // ------------------------------------------------------------ 强制解绑

    @Test
    void forceUnbindCascadesInOneTransaction() throws Exception {
        long dad = user();
        long mom = user();
        long revoked = user();
        member(dad, "ACTIVE");
        member(mom, "ACTIVE");
        member(revoked, "REVOKED");
        jdbc.sql("INSERT INTO share_invite (token_hash, device_id, inviter_id, period_type, expire_at, status) VALUES"
                        + " (?, ?, ?, 'PERMANENT', NOW(3) + INTERVAL 1 DAY, 'PENDING'),"
                        + " (?, ?, ?, 'PERMANENT', NOW(3) + INTERVAL 1 DAY, 'ACCEPTED')")
                .params(
                        UUID.randomUUID().toString(),
                        device,
                        owner,
                        UUID.randomUUID().toString(),
                        device,
                        owner)
                .update();
        jdbc.sql(
                        "INSERT INTO device_transfer (token_hash, device_id, from_user, expire_at) VALUES (?, ?, ?, NOW(3) + INTERVAL 1 DAY)")
                .params(UUID.randomUUID().toString(), device, owner)
                .update();

        expectError(call(support, "force-unbind", Map.of()), ErrorCode.REASON_REQUIRED);
        expectError(call(support, "force-unbind", Map.of("reason", " x ")), ErrorCode.REASON_REQUIRED);
        expectError(call(support, "force-unbind", Map.of("reason", "长".repeat(256))), ErrorCode.BAD_REQUEST);
        assertThat(deviceString("bind_status")).isEqualTo("BOUND");

        JsonNode result = data(call(support, "force-unbind", Map.of("reason", "  " + REASON + " ")));
        assertThat(result.has("activateCode")).isTrue();
        assertThat(result.get("activateCode").isNull()).isTrue();

        assertThat(deviceString("bind_status")).isEqualTo("PENDING");
        assertThat(deviceString("owner_user_id")).isNull();
        assertThat(deviceString("owner_since")).isNull();
        assertThat(deviceString("name")).isNull();
        assertThat(deviceString("space_no")).isNull();
        // 激活码未重置
        assertThat(codes.matches("AAAA2222", deviceString("activate_code_hash")))
                .isTrue();
        assertThat(jdbc.sql("SELECT CONCAT(user_id, ':', status, ':', ended_at IS NOT NULL) FROM device_member"
                                + " WHERE device_id = ? ORDER BY id")
                        .param(device)
                        .query(String.class)
                        .list())
                .containsExactly(dad + ":REVOKED:1", mom + ":REVOKED:1", revoked + ":REVOKED:0");
        assertThat(jdbc.sql("SELECT status FROM share_invite WHERE device_id = ? ORDER BY id")
                        .param(device)
                        .query(String.class)
                        .list())
                .containsExactly("VOIDED", "ACCEPTED");
        assertThat(jdbc.sql("SELECT status FROM device_transfer WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("VOIDED");
        assertThat(jdbc.sql("SELECT CONCAT(type, ' ', from_user, ' ', operator_type, ' ', operator_id, ' ', reason)"
                                + " FROM ownership_log WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("FORCE_UNBIND " + owner + " ADMIN 12 " + REASON);
        assertThat(events.stream(DeviceUnboundEvent.class).filter(e -> e.deviceId() == device))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.formerOwner()).isEqualTo(owner);
                    assertThat(e.revokedMembers()).containsExactly(dad, mom);
                });
        assertThat(lastLog("DEVICE_FORCE_UNBIND")).isEqualTo("SUCCESS " + REASON);

        // 已是待激活
        expectError(call(support, "force-unbind", Map.of("reason", REASON)), ErrorCode.DEVICE_NOT_BOUND);
        assertThat(lastLog("DEVICE_FORCE_UNBIND")).isEqualTo("FAIL " + REASON);
        expectError(
                mvc.perform(post("/api/admin/devices/{id}/force-unbind", 9_000_000_000L)
                        .header(TestAdminIdentity.HEADER, support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + REASON + "\"}")),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void forceUnbindCanResetTheActivateCode() throws Exception {
        redis.opsForValue().set("sp:bind-fail:" + sn, "5", Duration.ofMinutes(30));
        String code = data(call(ops, "force-unbind", Map.of("reason", REASON, "resetActivateCode", true)))
                .get("activateCode")
                .asText();
        assertThat(code).hasSize(ActivateCodes.LENGTH);
        assertThat(codes.matches(code, deviceString("activate_code_hash"))).isTrue();
        assertThat(codes.matches("AAAA2222", deviceString("activate_code_hash")))
                .isFalse();
        // 重置后清除错误锁定，申诉人可以立即用新码绑定
        assertThat(redis.hasKey("sp:bind-fail:" + sn)).isFalse();
        assertThat(deviceString("bind_status")).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------ 重置激活码

    @Test
    void resetActivateCodeInvalidatesTheOldOne() throws Exception {
        redis.opsForValue().set("sp:bind-fail:" + sn, "3", Duration.ofMinutes(30));
        expectError(call(support, "reset-activate-code", Map.of("reason", "")), ErrorCode.REASON_REQUIRED);
        assertThat(redis.hasKey("sp:bind-fail:" + sn)).isTrue();

        String code = data(call(support, "reset-activate-code", Map.of("reason", "原激活码标签磨损")))
                .get("activateCode")
                .asText();
        assertThat(code).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}");
        assertThat(codes.matches(code, deviceString("activate_code_hash"))).isTrue();
        assertThat(codes.matches("AAAA2222", deviceString("activate_code_hash")))
                .isFalse();
        assertThat(redis.hasKey("sp:bind-fail:" + sn)).isFalse();
        // 绑定关系不变
        assertThat(deviceString("bind_status")).isEqualTo("BOUND");
        assertThat(lastLog("DEVICE_RESET_CODE")).isEqualTo("SUCCESS 原激活码标签磨损");
        // 明文不进操作日志
        assertThat(jdbc.sql("SELECT COUNT(*) FROM operation_log WHERE target_id = ? AND CAST(detail AS CHAR) LIKE ?")
                        .params(String.valueOf(device), "%" + code + "%")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------------------ 禁用 / 启用

    @Test
    void disableAndEnableAreIdempotent() throws Exception {
        call(ops, "disable", Map.of("reason", "设备被投诉占道")).andExpect(status().isOk());
        assertThat(deviceString("enabled")).isEqualTo("0");
        call(ops, "disable", Map.of("reason", "重复禁用")).andExpect(status().isOk());
        assertThat(deviceString("enabled")).isEqualTo("0");
        // 绑定关系保留
        assertThat(deviceString("bind_status")).isEqualTo("BOUND");
        call(ops, "enable", Map.of("reason", "核实无误")).andExpect(status().isOk());
        assertThat(deviceString("enabled")).isEqualTo("1");
        assertThat(jdbc.sql("SELECT CONCAT(action, ' ', JSON_EXTRACT(detail, '$.changed')) FROM operation_log"
                                + " WHERE target_id = ? AND action IN ('DEVICE_DISABLE', 'DEVICE_ENABLE') ORDER BY id")
                        .param(String.valueOf(device))
                        .query(String.class)
                        .list())
                .containsExactly("DEVICE_DISABLE true", "DEVICE_DISABLE false", "DEVICE_ENABLE true");

        expectError(call(support, "disable", Map.of("reason", "客服无权禁用")), ErrorCode.FORBIDDEN);
        expectError(call(ops, "disable", Map.of()), ErrorCode.REASON_REQUIRED);
        assertThat(deviceString("enabled")).isEqualTo("1");
        expectError(
                mvc.perform(post("/api/admin/devices/{id}/disable", 9_000_000_000L)
                        .header(TestAdminIdentity.HEADER, ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不存在\"}")),
                ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------ 换件

    @Test
    void replaceDtuChangesImeiAndLogsThePart() throws Exception {
        String other = newImei();
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash) VALUES (?, ?, 'SPG-100', 'IT', 'x')")
                .params("SN" + other, other)
                .update();
        String fresh = newImei();
        String badLuhn = fresh.substring(0, 14) + ((fresh.charAt(14) - '0' + 1) % 10);

        expectError(
                call(ops, "replace-part", Map.of("reason", "4G 模块损坏", "part", "DTU", "newValue", "123")),
                ErrorCode.IMEI_INVALID);
        expectError(
                call(ops, "replace-part", Map.of("reason", "4G 模块损坏", "part", "DTU", "newValue", badLuhn)),
                ErrorCode.IMEI_INVALID);
        expectError(
                call(ops, "replace-part", Map.of("reason", "4G 模块损坏", "part", "DTU", "newValue", other)),
                ErrorCode.IMEI_DUPLICATE);
        expectError(
                call(ops, "replace-part", Map.of("reason", "4G 模块损坏", "part", "DTU", "newValue", imei)),
                ErrorCode.BAD_REQUEST);
        expectError(call(ops, "replace-part", Map.of("reason", "4G 模块损坏", "newValue", fresh)), ErrorCode.BAD_REQUEST);
        expectError(call(ops, "replace-part", Map.of("part", "DTU", "newValue", fresh)), ErrorCode.REASON_REQUIRED);
        expectError(
                call(support, "replace-part", Map.of("reason", "4G 模块损坏", "part", "DTU", "newValue", fresh)),
                ErrorCode.FORBIDDEN);
        assertThat(deviceString("imei")).isEqualTo(imei);

        JsonNode d =
                data(call(ops, "replace-part", Map.of("reason", "4G 模块损坏", "part", "DTU", "newValue", " " + fresh)));
        assertThat(d.get("id").asLong()).isEqualTo(device);
        assertThat(d.get("imei").asText()).isEqualTo(fresh);
        assertThat(d.get("sn").asText()).isEqualTo(sn);
        assertThat(d.get("bindStatus").asText()).isEqualTo("BOUND");
        assertThat(deviceString("imei")).isEqualTo(fresh);
        // 旧 IMEI 立即不再登记
        assertThat(directory.findByImei(imei)).isEmpty();
        assertThat(directory.findByImei(fresh))
                .map(DeviceDirectory.RegisteredDevice::deviceId)
                .contains(device);
        assertThat(jdbc.sql("SELECT CONCAT(part, ' ', old_value, ' ', new_value, ' ', operator_id, ' ', reason)"
                                + " FROM device_part_log WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("DTU " + imei + " " + fresh + " 11 4G 模块损坏");
        assertThat(lastLog("DEVICE_REPLACE_PART")).isEqualTo("SUCCESS 4G 模块损坏");
    }

    @Test
    void replaceBoardNormalizesMac() throws Exception {
        String taken = mac();
        String other = newImei();
        jdbc.sql(
                        "INSERT INTO device (sn, imei, ble_mac, model, batch_no, activate_code_hash) VALUES (?, ?, ?, 'SPG-100', 'IT', 'x')")
                .params("SN" + other, other, taken)
                .update();
        String old = deviceString("ble_mac");
        String fresh = mac();

        expectError(
                call(ops, "replace-part", Map.of("reason", "主板进水", "part", "BOARD", "newValue", "zz:zz")),
                ErrorCode.BAD_REQUEST);
        expectError(
                call(ops, "replace-part", Map.of("reason", "主板进水", "part", "BOARD", "newValue", "")),
                ErrorCode.BAD_REQUEST);
        expectError(
                call(ops, "replace-part", Map.of("reason", "主板进水", "part", "BOARD", "newValue", taken.toLowerCase())),
                ErrorCode.MAC_DUPLICATE);
        JsonNode d = data(call(
                ops,
                "replace-part",
                Map.of(
                        "reason",
                        "主板进水",
                        "part",
                        "BOARD",
                        "newValue",
                        fresh.toLowerCase().replace(':', '-'))));
        assertThat(d.get("bleMac").asText()).isEqualTo(fresh);
        assertThat(deviceString("imei")).isEqualTo(imei);
        assertThat(jdbc.sql(
                                "SELECT CONCAT(part, ' ', old_value, ' ', new_value) FROM device_part_log WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("BOARD " + old + " " + fresh);
    }

    // ------------------------------------------------------------ 自检

    private JsonNode poll(long commandId) throws Exception {
        return data(mvc.perform(get("/api/admin/commands/{id}", commandId).header(TestAdminIdentity.HEADER, ops)));
    }

    private String commandStatus(long id) {
        return jdbc.sql("SELECT status FROM control_command WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    @Test
    void selfCheckQueriesStatusAndVersion() throws Exception {
        board.setVersion(0x21);
        JsonNode created = data(call(ops, "self-check", Map.of("action", "QUERY_STATUS")));
        long statusCheck = created.get("id").asLong();
        assertThat(created.get("action").asText()).isEqualTo("QUERY_STATUS");
        assertThat(created.at("/operator/type").asText()).isEqualTo("ADMIN");
        assertThat(created.at("/operator/id").asLong()).isEqualTo(11);
        assertThat(created.get("role").asText()).isEqualTo("ADMIN");
        assertThat(created.get("downFrame").asText()).isEqualTo("FD 00 01 D0 C0 FD FA");
        await().atMost(Duration.ofSeconds(5))
                .until(() -> commandStatus(statusCheck).equals("SUCCESS"));
        JsonNode done = poll(statusCheck);
        assertThat(done.get("rawFrame").asText()).startsWith("FD 00 01 0C");
        assertThat(done.get("resultCode").isNull()).isTrue();
        assertThat(done.get("durationMs").isNumber()).isTrue();
        assertThat(done.get("fwVersion").isNull()).isTrue();

        long versionCheck = data(call(ops, "self-check", Map.of("action", "QUERY_VERSION")))
                .get("id")
                .asLong();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> commandStatus(versionCheck).equals("SUCCESS"));
        assertThat(poll(versionCheck).get("fwVersion").asText()).isEqualTo("21");
        assertThat(deviceString("fw_version")).isEqualTo("21");

        // 不动闸杆
        assertThat(bridge.sent)
                .noneMatch(f -> f.endsWith(" 03 FD FA") || f.contains(" 01 03 ") || f.contains(" 01 05 "));
        assertThat(lastLog("DEVICE_SELF_CHECK")).isEqualTo("SUCCESS -");

        // App 侧看不到、也不会因自检指令出错
        assertThatThrownBy(() -> commands.get(device, statusCheck))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void selfCheckOccupiesTheDeviceAndTimesOut() throws Exception {
        board.setSilent(true);
        long first = data(call(ops, "self-check", Map.of("action", "QUERY_STATUS")))
                .get("id")
                .asLong();
        // 自检进行中：再自检、App 抬杆都 20005
        expectError(call(ops, "self-check", Map.of("action", "QUERY_VERSION")), ErrorCode.COMMAND_IN_PROGRESS);
        assertThat(commandRepository.hasInflight(device)).isTrue();
        // 对账只处理 App 指令，读到自检行也不出错
        assertThat(commandRepository.findInflightIssuedBefore(Instant.now().plusSeconds(60), 1000))
                .noneMatch(r -> r.id() == first);

        await().atMost(Duration.ofSeconds(6)).until(() -> commandStatus(first).equals("TIMEOUT"));
        assertThat(poll(first).get("resultCode").asText()).isEqualTo("TIMEOUT");
        board.setSilent(false);

        expectError(call(ops, "self-check", Map.of("action", "REBOOT")), ErrorCode.BAD_REQUEST);
        expectError(call(ops, "self-check", Map.of()), ErrorCode.BAD_REQUEST);
        expectError(call(support, "self-check", Map.of("action", "QUERY_STATUS")), ErrorCode.FORBIDDEN);
    }

    /** 查询版本只认版本帧、查询状态只认状态帧：期间设备上报的其他帧（如定时查询的状态帧）不能提前判成功 */
    @Test
    void selfCheckWaitsForTheMatchingFrame() throws Exception {
        board.setSilent(true);
        long version = data(call(ops, "self-check", Map.of("action", "QUERY_VERSION")))
                .get("id")
                .asLong();
        uplink(Rs485Codec.encodeStatus(1, 0x0C, 0x16, 0x00));
        Thread.sleep(300);
        assertThat(commandStatus(version)).isEqualTo("SENT");
        uplink(Rs485Codec.encodeVersion(1, 0x30));
        await().atMost(Duration.ofSeconds(3)).until(() -> commandStatus(version).equals("SUCCESS"));
        assertThat(poll(version).get("fwVersion").asText()).isEqualTo("30");

        long state = data(call(ops, "self-check", Map.of("action", "QUERY_STATUS")))
                .get("id")
                .asLong();
        uplink(Rs485Codec.encodeVersion(1, 0x31));
        Thread.sleep(300);
        assertThat(commandStatus(state)).isEqualTo("SENT");
        uplink(Rs485Codec.encodeStatus(1, 0x09, 0x01, 0x11));
        await().atMost(Duration.ofSeconds(3)).until(() -> commandStatus(state).equals("SUCCESS"));
        assertThat(poll(state).get("rawFrame").asText()).isEqualTo("FD 00 01 09 01 11 FD FA");
        board.setSilent(false);
    }

    private void uplink(byte[] frame) {
        publisher.publishEvent(new DeviceUplinkEvent(device, imei, Rs485Codec.decodeUplink(frame), Instant.now()));
    }

    @Test
    void selfCheckNeedsAnOnlineDevice() throws Exception {
        bridge.connected = false;
        expectError(call(ops, "self-check", Map.of("action", "QUERY_STATUS")), ErrorCode.DEVICE_OFFLINE);
        bridge.connected = true;
        jdbc.sql("UPDATE device_runtime SET last_seen_at = ?, rev = rev + 1 WHERE device_id = ?")
                .params(DbTime.toDb(Instant.now().minus(Duration.ofMinutes(20))), device)
                .update();
        cache.evict(device);
        expectError(call(ops, "self-check", Map.of("action", "QUERY_STATUS")), ErrorCode.DEVICE_SLEEPING);
        assertThat(commandRepository.hasInflight(device)).isFalse();
    }

    @Test
    void sweepTimesOutAbandonedSelfChecks() {
        jdbc.sql("INSERT INTO control_command (device_id, admin_id, role, channel, action, status, issued_at)"
                        + " VALUES (?, 11, 'ADMIN', 'REMOTE', 'QUERY_VERSION', 'SENT', ?)")
                .params(device, DbTime.toDb(Instant.now().minus(Duration.ofMinutes(1))))
                .update();
        long id = jdbc.sql("SELECT MAX(id) FROM control_command WHERE device_id = ?")
                .param(device)
                .query(Long.class)
                .single();
        // 同一时刻过期的 App 指令归对账（IOT-08）处理，自检收尾不碰
        long app = user();
        String other = newImei();
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash) VALUES (?, ?, 'SPG-100', 'IT', 'x')")
                .params("SN" + other, other)
                .update();
        long otherDevice = id("device", "imei", other);
        jdbc.sql("INSERT INTO control_command (device_id, user_id, role, channel, action, status, issued_at)"
                        + " VALUES (?, ?, 'OWNER', 'REMOTE', 'OPEN', 'SENT', ?)")
                .params(otherDevice, app, DbTime.toDb(Instant.now().minus(Duration.ofMinutes(1))))
                .update();
        long appCommand = jdbc.sql("SELECT MAX(id) FROM control_command WHERE device_id = ?")
                .param(otherDevice)
                .query(Long.class)
                .single();
        selfCheck.sweep();
        assertThat(commandStatus(id)).isEqualTo("TIMEOUT");
        assertThat(commandStatus(appCommand)).isEqualTo("SENT");
        jdbc.sql("UPDATE control_command SET status = 'TIMEOUT' WHERE id = ?")
                .param(appCommand)
                .update();
        assertThat(jdbc.sql("SELECT duration_ms >= 60000 FROM control_command WHERE id = ?")
                        .param(id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }
}
