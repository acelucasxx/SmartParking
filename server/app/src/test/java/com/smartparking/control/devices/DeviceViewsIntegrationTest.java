package com.smartparking.control.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.runtime.RuntimeCache;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

/** 我的车位、设备详情、实时状态、改名称与车位号（BIZ-06）。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DeviceViewsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private RuntimeCache cache;

    private long owner;
    private long member;
    private long device;

    @BeforeEach
    void setUp() {
        owner = user("张先生");
        member = user("小张");
        device = device(owner, "1 DAY");
        grant(device, member, "ACTIVE", "2 DAY", null, "爸爸");
    }

    // ------------------------------------------------------------ 造数据

    private long user(String nickname) {
        String openid = "it-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (openid, nickname) VALUES (?, ?)")
                .params(openid, nickname)
                .update();
        return jdbc.sql("SELECT id FROM app_user WHERE openid = ?")
                .param(openid)
                .query(Long.class)
                .single();
    }

    /** @param ownerSinceAgo 例如 "1 DAY"；ownerId 为空时为待激活 */
    private long device(Long ownerId, String ownerSinceAgo) {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, fw_version, bind_status, owner_user_id,"
                                + " owner_since) VALUES (?, ?, 'SPG-100', 'IT', 'x', '1.0.3', ?, ?, "
                                + (ownerId == null ? "NULL" : "NOW(3) - INTERVAL " + ownerSinceAgo) + ")")
                .params("SN" + imei, imei, ownerId == null ? "PENDING" : "BOUND", ownerId)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private void grant(long deviceId, long userId, String status, String fromAgo, String validTo, String remark) {
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by, remark)"
                        + " VALUES (?, ?, NOW(3) - INTERVAL " + fromAgo + ", " + (validTo == null ? "NULL" : validTo)
                        + ", ?, ?, ?)")
                .params(deviceId, userId, status, owner, remark)
                .update();
    }

    private long command(long deviceId, long userId, String role, String action, String status, String issuedAgo) {
        jdbc.sql(
                        "INSERT INTO control_command (device_id, user_id, role, channel, action, status, result_code, duration_ms,"
                                + " issued_at) VALUES (?, ?, ?, 'REMOTE', ?, ?, ?, ?, NOW(3) - INTERVAL " + issuedAgo
                                + ")")
                .params(
                        deviceId,
                        userId,
                        role,
                        action,
                        status,
                        status.equals("FAILED") ? "OBSTRUCTED" : null,
                        status.equals("SUCCESS") ? 1240 : null)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM control_command WHERE device_id = ?")
                .param(deviceId)
                .query(Long.class)
                .single();
    }

    private void runtime(long deviceId, String stored, String connEvent, String lastSeenAgo) {
        jdbc.sql(
                        """
                        INSERT INTO device_runtime (device_id, gate_status, occupied, online_state, last_conn_event,
                                                    last_conn_at, last_seen_at, status_refreshed_at)
                        VALUES (?, 'CLOSED', 1, ?, ?, NOW(3) - INTERVAL %s, NOW(3) - INTERVAL %s, NOW(3) - INTERVAL %s)"""
                                .formatted(lastSeenAgo, lastSeenAgo, lastSeenAgo))
                .params(deviceId, stored, connEvent)
                .update();
        cache.evict(deviceId);
    }

    private String token(long userId) {
        return sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
    }

    private ResultActions getAs(long userId, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId)));
    }

    private ResultActions patchAs(long userId, long deviceId, String body) throws Exception {
        return mvc.perform(patch("/api/app/devices/" + deviceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    // ------------------------------------------------------------ 列表

    @Test
    void listShowsOwnedFirstThenValidGrants() throws Exception {
        long other = user("李女士");
        long older = device(member, "3 DAY"); // member 作为主人，较早
        long newer = device(member, "1 HOUR");
        long shared = device(other, "1 DAY");
        grant(shared, member, "ACTIVE", "1 DAY", "NOW(3) + INTERVAL 7 DAY", null);
        long expired = device(other, "1 DAY");
        grant(expired, member, "ACTIVE", "3 DAY", "NOW(3) - INTERVAL 1 MINUTE", null);
        long revoked = device(other, "1 DAY");
        grant(revoked, member, "REVOKED", "1 DAY", null, null);
        long notStarted = device(other, "1 DAY");
        grant(notStarted, member, "ACTIVE", "-1 DAY", null, null);
        long strangers = device(other, "1 DAY");

        JsonNode list = data(getAs(member, "/api/app/devices"));
        // device（setUp 中授权于 2 天前）早于 shared（1 天前）
        assertThat(ids(list)).containsExactly(older, newer, device, shared);
        assertThat(ids(list)).doesNotContain(expired, revoked, notStarted, strangers);

        assertThat(list.get(0).get("role").asText()).isEqualTo("OWNER");
        assertThat(list.get(0).get("validTo").isNull()).isTrue();
        assertThat(list.get(2).get("role").asText()).isEqualTo("MEMBER");
        assertThat(list.get(2).get("validTo").isNull()).as("永久授权").isTrue();
        assertThat(OffsetDateTime.parse(list.get(3).get("validTo").asText())
                        .getOffset()
                        .getTotalSeconds())
                .isEqualTo(8 * 3600);
    }

    @Test
    void listIsEmptyForNewUserAndIncludesDisabledDevices() throws Exception {
        assertThat(data(getAs(user("新用户"), "/api/app/devices"))).isEmpty();

        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        JsonNode card = data(getAs(owner, "/api/app/devices")).get(0);
        assertThat(card.get("id").asLong()).isEqualTo(device);
        assertThat(card.get("enabled").asBoolean()).isFalse();
    }

    @Test
    void cardUsesLiveOnlineStateAndRuntime() throws Exception {
        JsonNode fresh = data(getAs(owner, "/api/app/devices")).get(0);
        assertThat(fresh.get("onlineState").asText()).as("从未上报").isEqualTo("OFFLINE");
        assertThat(fresh.get("gateStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(fresh.get("occupied").isNull()).isTrue();
        assertThat(fresh.get("batteryPct").isNull()).isTrue();

        // 库里还存着 ONLINE，但已沉默 2 小时：按当前时刻判定为休眠
        runtime(device, "ONLINE", "CONNECTED", "2 HOUR");
        JsonNode card = data(getAs(owner, "/api/app/devices")).get(0);
        assertThat(card.get("onlineState").asText()).isEqualTo("SLEEP");
        assertThat(card.get("gateStatus").asText()).isEqualTo("CLOSED");
        assertThat(card.get("occupied").asBoolean()).isTrue();

        long pending = command(device, member, "MEMBER", "OPEN", "SENT", "1 SECOND");
        assertThat(data(getAs(owner, "/api/app/devices"))
                        .get(0)
                        .get("pendingCommandId")
                        .asLong())
                .isEqualTo(pending);
    }

    // ------------------------------------------------------------ 详情与状态

    @Test
    void detailForOwnerShowsMemberRemarkInLastOperation() throws Exception {
        runtime(device, "ONLINE", "CONNECTED", "1 MINUTE");
        command(device, owner, "OWNER", "CLOSE", "SUCCESS", "10 MINUTE");
        long latest = command(device, member, "MEMBER", "OPEN", "SUCCESS", "1 MINUTE");

        JsonNode d = data(getAs(owner, "/api/app/devices/" + device));
        assertThat(d.get("role").asText()).isEqualTo("OWNER");
        assertThat(d.get("model").asText()).isEqualTo("SPG-100");
        assertThat(d.get("fwVersion").asText()).isEqualTo("1.0.3");
        assertThat(d.get("ownerNickname").asText()).isEqualTo("张先生");
        assertThat(d.get("bleAvailable").asBoolean()).isFalse();
        assertThat(d.get("onlineState").asText()).isEqualTo("ONLINE");
        assertThat(d.get("statusRefreshedAt").asText()).endsWith("+08:00");
        assertThat(d.get("signalLevel").isNull()).isTrue();

        JsonNode op = d.get("lastOperation");
        assertThat(op.get("id").asText()).isEqualTo("c-" + latest);
        assertThat(op.get("operatorName").asText()).as("主人看被授权人：备注优先").isEqualTo("爸爸");
        assertThat(op.get("operatorRole").asText()).isEqualTo("MEMBER");
        assertThat(op.get("source").asText()).isEqualTo("APP");
        assertThat(op.get("approx").asBoolean()).isFalse();
        assertThat(op.get("action").asText()).isEqualTo("OPEN");
        assertThat(op.get("channel").asText()).isEqualTo("REMOTE");
        assertThat(op.get("result").asText()).isEqualTo("SUCCESS");
        assertThat(op.get("durationMs").asInt()).isEqualTo(1240);
    }

    @Test
    void memberSeesOnlyOwnOperationsAndOwnNickname() throws Exception {
        command(device, owner, "OWNER", "CLOSE", "SUCCESS", "1 MINUTE");
        JsonNode d = data(getAs(member, "/api/app/devices/" + device));
        assertThat(d.get("role").asText()).isEqualTo("MEMBER");
        assertThat(d.get("ownerNickname").asText()).isEqualTo("张先生");
        assertThat(d.get("lastOperation").isNull()).as("看不到主人的操作").isTrue();

        long own = command(device, member, "MEMBER", "OPEN", "FAILED", "30 SECOND");
        JsonNode op = data(getAs(member, "/api/app/devices/" + device)).get("lastOperation");
        assertThat(op.get("id").asText()).isEqualTo("c-" + own);
        assertThat(op.get("operatorName").asText()).as("被授权人看自己：昵称").isEqualTo("小张");
        assertThat(op.get("result").asText()).isEqualTo("FAILED");
        assertThat(op.get("resultCode").asText()).isEqualTo("OBSTRUCTED");
    }

    @Test
    void ownerDoesNotSeeOperationsBeforeOwnershipAndInflightHasNoResult() throws Exception {
        long previous = user("前主人");
        command(device, previous, "OWNER", "OPEN", "SUCCESS", "2 DAY"); // 成为主人之前
        assertThat(data(getAs(owner, "/api/app/devices/" + device))
                        .get("lastOperation")
                        .isNull())
                .isTrue();

        command(device, member, "MEMBER", "OPEN", "EXECUTING", "1 SECOND");
        JsonNode op = data(getAs(owner, "/api/app/devices/" + device)).get("lastOperation");
        assertThat(op.get("result").isNull()).isTrue();
    }

    @Test
    void ownerWithoutNicknameShowsDefault() throws Exception {
        jdbc.sql("UPDATE app_user SET nickname = NULL WHERE id = ?")
                .param(owner)
                .update();
        assertThat(data(getAs(member, "/api/app/devices/" + device))
                        .get("ownerNickname")
                        .asText())
                .isEqualTo("微信用户");
    }

    @Test
    void statusIsLightweightAndReportsRefresh() throws Exception {
        JsonNode before = data(getAs(owner, "/api/app/devices/" + device + "/status"));
        assertThat(before.get("statusRefreshedAt").isNull()).as("尚未联网").isTrue();
        assertThat(before.get("enabled").asBoolean()).isTrue();

        runtime(device, "ONLINE", "CONNECTED", "5 SECOND");
        JsonNode after = data(getAs(member, "/api/app/devices/" + device + "/status"));
        assertThat(after.get("id").asLong()).isEqualTo(device);
        assertThat(after.get("statusRefreshedAt").isNull()).isFalse();
        assertThat(after.get("lastSeenAt").isNull()).isFalse();
        assertThat(after.get("onlineState").asText()).isEqualTo("ONLINE");
        assertThat(after.get("gateStatus").asText()).isEqualTo("CLOSED");
        assertThat(after.has("pendingCommandId")).isTrue();
    }

    @Test
    void detailAndStatusFollowAccessRules() throws Exception {
        long expired = user("过期");
        grant(device, expired, "ACTIVE", "3 DAY", "NOW(3) - INTERVAL 1 MINUTE", null);
        expectError(getAs(expired, "/api/app/devices/" + device), ErrorCode.ACCESS_EXPIRED);
        expectError(getAs(expired, "/api/app/devices/" + device + "/status"), ErrorCode.ACCESS_EXPIRED);
        expectError(getAs(user("路人"), "/api/app/devices/" + device), ErrorCode.NOT_FOUND);

        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        assertThat(data(getAs(member, "/api/app/devices/" + device))
                        .get("enabled")
                        .asBoolean())
                .as("禁用后仍可查看")
                .isFalse();
    }

    // ------------------------------------------------------------ 修改

    private ResultActions postAs(long userId, long deviceId, String override, String body) throws Exception {
        var request = post("/api/app/devices/" + deviceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (override != null) {
            request.header("X-HTTP-Method-Override", override);
        }
        return mvc.perform(request);
    }

    /** 小程序 wx.request 不支持 PATCH：POST + X-HTTP-Method-Override: PATCH 按 PATCH 处理，其他取值不改写 */
    @Test
    void miniProgramPatchesThroughPostWithOverrideHeader() throws Exception {
        JsonNode card = data(postAs(owner, device, "patch", "{\"name\":\"小程序改名\"}"));
        assertThat(card.get("name").asText()).isEqualTo("小程序改名");

        for (String other : new String[] {null, "DELETE", "PUT", "GET"}) {
            int status = postAs(owner, device, other, "{\"name\":\"不应生效\"}")
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as("override=" + other).isGreaterThanOrEqualTo(400);
        }
        assertThat(jdbc.sql("SELECT name FROM device WHERE id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("小程序改名");
        // 只改写 POST：PUT 带同样的头不按 PATCH 处理
        int put = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/app/devices/" + device)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(owner))
                        .header("X-HTTP-Method-Override", "PATCH")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PUT 不应生效\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(put).isGreaterThanOrEqualTo(400);
        // 改写同样经过登录与权限判定
        expectError(postAs(member, device, "PATCH", "{\"name\":\"被授权人\"}"), ErrorCode.NOT_OWNER);
    }

    @Test
    void ownerUpdatesNameAndSpaceNoIndependently() throws Exception {
        JsonNode card = data(patchAs(owner, device, "{\"name\":\"  家里车位 \",\"spaceNo\":\"B2-108\"}"));
        assertThat(card.get("name").asText()).isEqualTo("家里车位");
        assertThat(card.get("spaceNo").asText()).isEqualTo("B2-108");
        assertThat(card.get("role").asText()).isEqualTo("OWNER");

        card = data(patchAs(owner, device, "{\"spaceNo\":\"B2-109\"}"));
        assertThat(card.get("name").asText()).as("未出现的字段不改").isEqualTo("家里车位");
        assertThat(card.get("spaceNo").asText()).isEqualTo("B2-109");

        card = data(patchAs(owner, device, "{\"spaceNo\":\"\"}"));
        assertThat(card.get("spaceNo").isNull()).as("空串清空车位号").isTrue();
        assertThat(data(patchAs(owner, device, "{\"name\":\"" + "长".repeat(32) + "\"}"))
                        .get("name")
                        .asText())
                .hasSize(32);

        // 被授权人看到同样的名称
        assertThat(data(getAs(member, "/api/app/devices")).get(0).get("name").asText())
                .hasSize(32);
    }

    @Test
    void invalidUpdatesAreRejected() throws Exception {
        for (String body : new String[] {
            "{}",
            "{\"name\":\"\"}",
            "{\"name\":\"   \"}",
            "{\"name\":null}",
            "{\"name\":123}",
            "{\"name\":\"" + "长".repeat(33) + "\"}",
            "{\"spaceNo\":\"" + "号".repeat(33) + "\"}",
            "[]"
        }) {
            expectError(patchAs(owner, device, body), ErrorCode.BAD_REQUEST);
        }
        assertThat(jdbc.sql("SELECT name IS NULL FROM device WHERE id = ?")
                        .param(device)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void onlyOwnerMayUpdateEvenWhenDisabled() throws Exception {
        expectError(patchAs(member, device, "{\"name\":\"x\"}"), ErrorCode.NOT_OWNER);
        expectError(patchAs(user("路人"), device, "{\"name\":\"x\"}"), ErrorCode.NOT_FOUND);

        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        patchAs(owner, device, "{\"name\":\"停用中\"}").andExpect(status().isOk());
    }
}
