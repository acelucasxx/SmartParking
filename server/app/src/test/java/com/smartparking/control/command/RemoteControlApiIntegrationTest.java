package com.smartparking.control.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.Fault;
import com.smartparking.dtusim.GateBoard;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.command.CommandExecutor;
import com.smartparking.iot.command.InMemoryDeviceLink;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.RuntimeCache;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 远程控制接口（BIZ-05）端到端：HTTP → 登录 → 权限判定 → 指令状态机 → 内存中的 DTU 模拟器 → 上行判定 → 轮询结果。MySQL / Redis 为真实容器。
 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.command.reconcile-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, InMemoryDeviceLink.class})
class RemoteControlApiIntegrationTest {

    private static final long TRAVEL_MS = 300;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private InMemoryDeviceLink bridge;

    @Autowired
    private DeviceRuntimeService runtime;

    @Autowired
    private RuntimeCache cache;

    @Autowired
    private CommandExecutor executor;

    private ExecutorScheduler scheduler;
    private GateBoard board;
    private String imei;
    private long deviceId;
    private long owner;
    private long member;
    private String ownerToken;
    private String memberToken;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE system_config SET config_value = '3' WHERE config_key = 'command.timeout_seconds'")
                .update();
        owner = user();
        member = user();
        imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        """
                        INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since)
                        VALUES (?, ?, 'SP-G1', 'IT', 'x', 'BOUND', ?, NOW(3) - INTERVAL 1 DAY)""")
                .params("SN" + imei, imei, owner)
                .update();
        deviceId = jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
        grant(member, "ACTIVE", null);
        ownerToken = token(owner);
        memberToken = token(member);

        scheduler = new ExecutorScheduler(2);
        board = bridge.attach(imei, TRAVEL_MS, scheduler);
        bridge.connected = true;
        bridge.failNextOpenOrClose = false;
        // 让运行态有一帧新鲜的状态（关闸到位）
        bridge.sender.send(imei, Rs485Codec.encodeQuery());
        await().atMost(Duration.ofSeconds(5))
                .until(() ->
                        runtime.find(deviceId).map(r -> r.statusCode() != null).orElse(false));
        bridge.sent.clear();
    }

    @AfterEach
    void tearDown() {
        await().atMost(Duration.ofSeconds(8)).until(() -> executor.activeCount() == 0);
        scheduler.close();
    }

    // ------------------------------------------------------------ 造数据与请求

    private long user() {
        String openid = "it-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (openid) VALUES (?)").param(openid).update();
        return jdbc.sql("SELECT id FROM app_user WHERE openid = ?")
                .param(openid)
                .query(Long.class)
                .single();
    }

    private void grant(long userId, String status, String validTo) {
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by)"
                        + " VALUES (?, ?, NOW(3) - INTERVAL 2 DAY, " + (validTo == null ? "NULL" : validTo)
                        + ", ?, ?)")
                .params(deviceId, userId, status, owner)
                .update();
    }

    private String token(long userId) {
        return sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
    }

    private ResultActions send(String token, String action, String key) throws Exception {
        return send(token, "{\"action\":\"" + action + "\",\"idempotencyKey\":\"" + key + "\"}");
    }

    private ResultActions send(String token, String body) throws Exception {
        return mvc.perform(post("/api/app/devices/" + deviceId + "/commands")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions poll(String token, long commandId) throws Exception {
        return mvc.perform(get("/api/app/devices/" + deviceId + "/commands/" + commandId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static JsonNode data(ResultActions result, ObjectMapper json) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private JsonNode sendOk(String token, String action) throws Exception {
        return data(
                send(token, action, key())
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0)),
                json);
    }

    /** 按前端的方式轮询到终结 */
    private JsonNode awaitTerminal(String token, long commandId) {
        await().atMost(Duration.ofSeconds(8))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    String s = data(poll(token, commandId), json).get("status").asText();
                    return s.equals("SUCCESS") || s.equals("FAILED") || s.equals("TIMEOUT");
                });
        try {
            return data(poll(token, commandId), json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql("SELECT user_id, admin_id, role, channel, action FROM control_command WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    private int commandCount() {
        return jdbc.sql("SELECT COUNT(*) FROM control_command WHERE device_id = ?")
                .param(deviceId)
                .query(Integer.class)
                .single();
    }

    private List<String> sentActions() {
        String query = Hex.format(Rs485Codec.encodeQuery());
        return bridge.sent.stream()
                .filter(s -> s.startsWith(imei + " "))
                .map(s -> s.substring(imei.length() + 1))
                .filter(hex -> !hex.equals(query))
                .toList();
    }

    private void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    // ------------------------------------------------------------ 成功路径

    @Test
    void ownerOpensAndPollsUntilArrived() throws Exception {
        JsonNode started = sendOk(ownerToken, "OPEN");
        long id = started.get("id").asLong();
        assertThat(started.get("deviceId").asLong()).isEqualTo(deviceId);
        assertThat(started.get("action").asText()).isEqualTo("OPEN");
        assertThat(started.get("channel").asText()).isEqualTo("REMOTE");
        assertThat(started.get("status").asText()).isIn("SENT", "EXECUTING");
        assertThat(started.get("finishedAt").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(started.get("issuedAt").asText())
                        .getOffset()
                        .getTotalSeconds())
                .isEqualTo(8 * 3600);

        JsonNode done = awaitTerminal(ownerToken, id);
        assertThat(done.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(done.get("resultCode").isNull()).isTrue();
        assertThat(done.get("resultMessage").isNull()).isTrue();
        assertThat(done.get("durationMs").asInt()).isPositive();
        assertThat(done.get("finishedAt").isNull()).isFalse();
        assertThat(board.status()).isEqualTo(com.smartparking.iot.codec.GateStatus.OPENED);

        Map<String, Object> row = row(id);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(owner);
        assertThat(row.get("admin_id")).isNull();
        assertThat(row.get("role")).isEqualTo("OWNER");
        assertThat(row.get("channel")).isEqualTo("REMOTE");
    }

    @Test
    void memberCommandRecordsMemberRole() throws Exception {
        JsonNode started = sendOk(memberToken, "OPEN");
        awaitTerminal(memberToken, started.get("id").asLong());
        Map<String, Object> row = row(started.get("id").asLong());
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(member);
        assertThat(row.get("role")).isEqualTo("MEMBER");
    }

    @Test
    void alreadyInTargetSucceedsWithoutSending() throws Exception {
        JsonNode done = sendOk(ownerToken, "CLOSE");
        assertThat(done.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(done.get("resultCode").asText()).isEqualTo("ALREADY_IN_TARGET");
        assertThat(done.get("resultMessage").isNull()).isTrue();
        assertThat(sentActions()).isEmpty();
    }

    @Test
    void sameIdempotencyKeyReturnsSameCommand() throws Exception {
        String k = key();
        long first = data(send(ownerToken, "OPEN", k).andExpect(status().isOk()), json)
                .get("id")
                .asLong();
        long again = data(send(ownerToken, "OPEN", k).andExpect(status().isOk()), json)
                .get("id")
                .asLong();
        assertThat(again).isEqualTo(first);
        assertThat(commandCount()).isEqualTo(1);
        awaitTerminal(ownerToken, first);
    }

    // ------------------------------------------------------------ 设备侧拒绝

    @Test
    void secondCommandWhileExecutingIsRejected() throws Exception {
        board.setTravelMs(1500);
        long first = sendOk(ownerToken, "OPEN").get("id").asLong();
        expectError(send(memberToken, "CLOSE", key()), ErrorCode.COMMAND_IN_PROGRESS);
        awaitTerminal(ownerToken, first);
    }

    @Test
    void offlineAndSleepingAreRejectedWithoutQueueing() throws Exception {
        jdbc.sql(
                        "UPDATE device_runtime SET last_conn_event = 'DISCONNECTED', last_conn_at = NOW(3) WHERE device_id = ?")
                .param(deviceId)
                .update();
        cache.evict(deviceId);
        expectError(send(ownerToken, "OPEN", key()), ErrorCode.DEVICE_SLEEPING);

        jdbc.sql("UPDATE device_runtime SET last_seen_at = NOW(3) - INTERVAL 25 HOUR WHERE device_id = ?")
                .param(deviceId)
                .update();
        cache.evict(deviceId);
        expectError(send(ownerToken, "OPEN", key()), ErrorCode.DEVICE_OFFLINE);

        assertThat(sentActions()).isEmpty();
        assertThat(commandCount()).isZero();
    }

    @Test
    void obstructionFailsWithDisplayableMessage() throws Exception {
        board.inject(Fault.OBSTRUCTED);
        JsonNode done =
                awaitTerminal(ownerToken, sendOk(ownerToken, "OPEN").get("id").asLong());
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("resultCode").asText()).isEqualTo("OBSTRUCTED");
        assertThat(done.get("resultMessage").asText()).isEqualTo("闸杆遇阻，请检查现场");
    }

    @Test
    void motorFaultBlocksFurtherCommands() throws Exception {
        board.inject(Fault.MOTOR_FAULT);
        awaitTerminal(ownerToken, sendOk(ownerToken, "OPEN").get("id").asLong());
        expectError(send(ownerToken, "OPEN", key()), ErrorCode.DEVICE_FAULT);
    }

    @Test
    void publishFailureReturnsOffline() throws Exception {
        bridge.failNextOpenOrClose = true;
        expectError(send(ownerToken, "OPEN", key()), ErrorCode.DEVICE_OFFLINE);
    }

    // ------------------------------------------------------------ 权限

    @Test
    void permissionIsCheckedBeforeAnythingIsRecorded() throws Exception {
        long expired = user();
        grant(expired, "ACTIVE", "NOW(3) - INTERVAL 1 MINUTE");
        expectError(send(token(expired), "OPEN", key()), ErrorCode.ACCESS_EXPIRED);

        expectError(send(token(user()), "OPEN", key()), ErrorCode.NOT_FOUND);

        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(deviceId).update();
        expectError(send(ownerToken, "OPEN", key()), ErrorCode.DEVICE_DISABLED);
        expectError(send(memberToken, "OPEN", key()), ErrorCode.DEVICE_DISABLED);

        mvc.perform(post("/api/app/devices/" + deviceId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"OPEN\",\"idempotencyKey\":\"" + key() + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(commandCount()).isZero();
        assertThat(sentActions()).isEmpty();
    }

    @Test
    void bannedUserCannotControl() throws Exception {
        jdbc.sql("UPDATE app_user SET status = 'BANNED' WHERE id = ?")
                .param(member)
                .update();
        expectError(send(memberToken, "OPEN", key()), ErrorCode.USER_BANNED);
        assertThat(commandCount()).isZero();
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        expectError(send(ownerToken, "JUMP", key()), ErrorCode.BAD_REQUEST);
        expectError(send(ownerToken, "OPEN", "short"), ErrorCode.BAD_REQUEST);
        expectError(send(ownerToken, "OPEN", "k".repeat(65)), ErrorCode.BAD_REQUEST);
        expectError(send(ownerToken, "{\"idempotencyKey\":\"" + key() + "\"}"), ErrorCode.BAD_REQUEST);
        expectError(send(ownerToken, ""), ErrorCode.BAD_REQUEST);
        assertThat(commandCount()).isZero();
    }

    // ------------------------------------------------------------ 可见范围

    @Test
    void membersSeeOnlyTheirOwnCommandsOwnerSeesAll() throws Exception {
        long other = user();
        grant(other, "ACTIVE", null);
        String otherToken = token(other);

        long id = sendOk(memberToken, "OPEN").get("id").asLong();
        poll(memberToken, id).andExpect(status().isOk());
        poll(ownerToken, id).andExpect(status().isOk());
        expectError(poll(otherToken, id), ErrorCode.NOT_FOUND);
        awaitTerminal(memberToken, id);

        // 同一幂等键被其他人重放，不返回他人的指令
        String k = key();
        long own = data(send(memberToken, "CLOSE", k).andExpect(status().isOk()), json)
                .get("id")
                .asLong();
        expectError(send(otherToken, "CLOSE", k), ErrorCode.BAD_REQUEST);
        awaitTerminal(memberToken, own);
    }

    @Test
    void newOwnerDoesNotSeeCommandsBeforeOwnership() throws Exception {
        long id = sendOk(memberToken, "OPEN").get("id").asLong();
        awaitTerminal(memberToken, id);
        long ownOld = sendOk(ownerToken, "CLOSE").get("id").asLong();
        awaitTerminal(ownerToken, ownOld);

        // 模拟转让：主人起算时刻晚于这两条指令
        jdbc.sql("UPDATE device SET owner_since = NOW(3) + INTERVAL 1 SECOND WHERE id = ?")
                .param(deviceId)
                .update();
        expectError(poll(ownerToken, id), ErrorCode.NOT_FOUND);
        // 本人发起的始终可见
        poll(ownerToken, ownOld).andExpect(status().isOk());
    }

    @Test
    void commandOfAnotherDeviceIsNotFound() throws Exception {
        long id = sendOk(ownerToken, "OPEN").get("id").asLong();
        awaitTerminal(ownerToken, id);
        mvc.perform(get("/api/app/devices/" + deviceId + "/commands/" + Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
    }

    @Test
    void disabledDeviceStillAllowsPollingResult() throws Exception {
        long id = sendOk(ownerToken, "OPEN").get("id").asLong();
        awaitTerminal(ownerToken, id);
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(deviceId).update();
        poll(ownerToken, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }
}
