package com.smartparking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminRole;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 设备禁用与用户封禁的业务效果（BIZ-16，PRD 5.8）：后台操作走真实接口，效果在 C 端接口上验证。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.transfer.expire-interval=1h",
            "smartparking.invite.expire-interval=1h",
            "smartparking.member.expire-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestAdminIdentity.class})
class DisableBanIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    private final String ops = TestAdminIdentity.header(21, AdminRole.OPS, "运维");
    private final String support = TestAdminIdentity.header(22, AdminRole.SUPPORT, "客服");

    private long owner;
    private long member;
    private long memberId;
    private long device;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE system_config SET config_value = '20' WHERE config_key = 'member.max_per_device'")
                .update();
        owner = user("主人");
        member = user("家人");
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since,"
                                + " name, space_no) VALUES (?, ?, 'SPG-100', 'IT', 'code-hash', 'BOUND', ?, NOW(3) - INTERVAL 30 DAY,"
                                + " '家里车位', 'B2-108')")
                .params("SN" + imei, imei, owner)
                .update();
        device = jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, status, granted_by) VALUES"
                        + " (?, ?, NOW(3) - INTERVAL 1 DAY, 'ACTIVE', ?)")
                .params(device, member, owner)
                .update();
        memberId = jdbc.sql("SELECT id FROM device_member WHERE device_id = ? AND user_id = ?")
                .params(device, member)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------ 工具

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

    private ResultActions as(long userId, MockHttpServletRequestBuilder request) throws Exception {
        String token =
                sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
        return mvc.perform(
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON));
    }

    private ResultActions admin(String identity, String path, Object body) throws Exception {
        return mvc.perform(post(path)
                .header(TestAdminIdentity.HEADER, identity)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions ban(String identity, long userId, Object body) throws Exception {
        return admin(identity, "/api/admin/users/" + userId + "/ban", body);
    }

    private ResultActions unban(String identity, long userId, Object body) throws Exception {
        return admin(identity, "/api/admin/users/" + userId + "/unban", body);
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

    private String userStatus(long userId) {
        return jdbc.sql("SELECT status FROM app_user WHERE id = ?")
                .param(userId)
                .query(String.class)
                .single();
    }

    private List<String> logs(long userId) {
        return jdbc.sql("SELECT CONCAT(action, ' ', result, ' ', COALESCE(reason, '-'), ' ',"
                        + " COALESCE(JSON_EXTRACT(detail, '$.changed'), '-')) FROM operation_log"
                        + " WHERE target_type = 'APP_USER' AND target_id = ? ORDER BY id")
                .param(String.valueOf(userId))
                .query(String.class)
                .list();
    }

    private String command() {
        return "{\"action\":\"OPEN\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}";
    }

    private JsonNode invite() throws Exception {
        return data(as(
                owner,
                post("/api/app/devices/{d}/invites", device)
                        .content("{\"period\":{\"type\":\"PERMANENT\"},\"remark\":\"表弟\"}")));
    }

    private String invitePreview(long userId, String token) throws Exception {
        return data(as(userId, get("/api/app/invites/{t}", token))).get("state").asText();
    }

    private String transferPreview(long userId, String token) throws Exception {
        return data(as(userId, get("/api/app/transfers/{t}", token)))
                .get("state")
                .asText();
    }

    // ------------------------------------------------------------ 封禁 / 解封

    @Test
    void banBlocksTheUserEverywhereAndUnbanRestoresEverything() throws Exception {
        ban(support, member, Map.of("reason", "  恶意刷单  ")).andExpect(status().isOk());
        assertThat(userStatus(member)).isEqualTo("BANNED");

        // 被封禁的被授权人：授权不变但无法使用
        expectError(as(member, get("/api/app/devices")), ErrorCode.USER_BANNED);
        expectError(as(member, get("/api/app/devices/{d}", device)), ErrorCode.USER_BANNED);
        expectError(
                as(member, post("/api/app/devices/{d}/commands", device).content(command())), ErrorCode.USER_BANNED);
        assertThat(jdbc.sql("SELECT status FROM device_member WHERE id = ?")
                        .param(memberId)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");

        // 重复封禁视为成功
        ban(support, member, Map.of("reason", "重复封禁")).andExpect(status().isOk());

        unban(support, member, Map.of("reason", "申诉核实无误")).andExpect(status().isOk());
        assertThat(userStatus(member)).isEqualTo("ACTIVE");
        assertThat(data(as(member, get("/api/app/devices/{d}", device)))
                        .get("role")
                        .asText())
                .isEqualTo("MEMBER");
        unban(support, member, Map.of("reason", "重复解封")).andExpect(status().isOk());

        assertThat(logs(member))
                .containsExactly(
                        "USER_BAN SUCCESS 恶意刷单 true",
                        "USER_BAN SUCCESS 重复封禁 false",
                        "USER_UNBAN SUCCESS 申诉核实无误 true",
                        "USER_UNBAN SUCCESS 重复解封 false");
    }

    @Test
    void bannedOwnerDoesNotStopMembersAndKeepsTheDevice() throws Exception {
        ban(support, owner, Map.of("reason", "账号被盗用")).andExpect(status().isOk());

        expectError(as(owner, get("/api/app/devices/{d}", device)), ErrorCode.USER_BANNED);
        // 被授权人在有效期内照常查看、控制（控制被受理与否取决于设备在线，这里只要求不因封禁拒绝）
        assertThat(data(as(member, get("/api/app/devices/{d}", device)))
                        .get("role")
                        .asText())
                .isEqualTo("MEMBER");
        as(member, post("/api/app/devices/{d}/commands", device).content(command()))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(ErrorCode.USER_BANNED.code())));
        // 封禁不自动解绑
        assertThat(jdbc.sql("SELECT CONCAT(bind_status, ' ', owner_user_id) FROM device WHERE id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("BOUND " + owner);
    }

    @Test
    void banRequiresReasonRoleAndAnExistingUser() throws Exception {
        expectError(ban(support, member, Map.of()), ErrorCode.REASON_REQUIRED);
        expectError(ban(support, member, Map.of("reason", " 是 ")), ErrorCode.REASON_REQUIRED);
        expectError(ban(support, member, Map.of("reason", "长".repeat(256))), ErrorCode.BAD_REQUEST);
        expectError(ban(ops, member, Map.of("reason", "运维无权封禁")), ErrorCode.FORBIDDEN);
        expectError(unban(ops, member, Map.of("reason", "运维无权解封")), ErrorCode.FORBIDDEN);
        expectError(unban(support, member, Map.of()), ErrorCode.REASON_REQUIRED);
        assertThat(userStatus(member)).isEqualTo("ACTIVE");
        expectError(ban(support, 9_000_000_000L, Map.of("reason", "不存在的用户")), ErrorCode.NOT_FOUND);
        expectError(unban(support, 9_000_000_000L, Map.of("reason", "不存在的用户")), ErrorCode.NOT_FOUND);
        // 失败与越权也写操作日志
        assertThat(logs(member)).hasSize(6).allMatch(l -> l.contains(" FAIL "));
    }

    @Test
    void bannedOwnersInvitesAndTransfersArePausedUntilUnban() throws Exception {
        String inviteToken = invite().get("token").asText();
        String transferToken = data(as(owner, post("/api/app/devices/{d}/transfer", device)))
                .get("token")
                .asText();
        long guest = user("访客");
        long buyer = user("买家");
        assertThat(invitePreview(guest, inviteToken)).isEqualTo("VALID");
        assertThat(transferPreview(buyer, transferToken)).isEqualTo("VALID");

        ban(support, owner, Map.of("reason", "账号被盗用")).andExpect(status().isOk());
        assertThat(invitePreview(guest, inviteToken)).isEqualTo("CANCELLED");
        assertThat(transferPreview(buyer, transferToken)).isEqualTo("CANCELLED");
        expectError(as(guest, post("/api/app/invites/{t}/accept", inviteToken)), ErrorCode.INVITE_CANCELLED);
        expectError(as(buyer, post("/api/app/transfers/{t}/accept", transferToken)), ErrorCode.TRANSFER_CANCELLED);
        // 卡片本身不作废
        assertThat(jdbc.sql("SELECT status FROM share_invite WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT status FROM device_transfer WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("PENDING");

        unban(support, owner, Map.of("reason", "已找回账号")).andExpect(status().isOk());
        assertThat(invitePreview(guest, inviteToken)).isEqualTo("VALID");
        assertThat(transferPreview(buyer, transferToken)).isEqualTo("VALID");
        as(guest, post("/api/app/invites/{t}/accept", inviteToken)).andExpect(status().isOk());
        as(buyer, post("/api/app/transfers/{t}/accept", transferToken)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT owner_user_id FROM device WHERE id = ?")
                        .param(device)
                        .query(Long.class)
                        .single())
                .isEqualTo(buyer);
    }

    // ------------------------------------------------------------ 设备禁用

    @Test
    void disabledDeviceKeepsRelationsButRejectsControlInvitesAndTransfers() throws Exception {
        String inviteToken = invite().get("token").asText();
        String transferToken = data(as(owner, post("/api/app/devices/{d}/transfer", device)))
                .get("token")
                .asText();
        admin(ops, "/api/admin/devices/" + device + "/disable", Map.of("reason", "设备被投诉占道"))
                .andExpect(status().isOk());

        // 卡片显示已停用；查看、记录照常
        JsonNode cards = data(as(member, get("/api/app/devices")));
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("enabled").asBoolean()).isFalse();
        assertThat(data(as(owner, get("/api/app/devices/{d}", device)))
                        .get("enabled")
                        .asBoolean())
                .isFalse();
        as(owner, get("/api/app/devices/{d}/records", device)).andExpect(status().isOk());
        as(member, get("/api/app/devices/{d}/records", device)).andExpect(status().isOk());
        as(owner, get("/api/app/devices/{d}/members", device)).andExpect(status().isOk());

        // 控制、邀请、改有效期、转让全部拒绝
        expectError(
                as(owner, post("/api/app/devices/{d}/commands", device).content(command())), ErrorCode.DEVICE_DISABLED);
        expectError(
                as(member, post("/api/app/devices/{d}/commands", device).content(command())),
                ErrorCode.DEVICE_DISABLED);
        expectError(
                as(
                        owner,
                        post("/api/app/devices/{d}/invites", device).content("{\"period\":{\"type\":\"PERMANENT\"}}")),
                ErrorCode.DEVICE_DISABLED);
        expectError(
                as(
                        owner,
                        patch("/api/app/devices/{d}/members/{m}", device, memberId)
                                .content("{\"period\":{\"type\":\"RELATIVE\",\"durationDays\":7}}")),
                ErrorCode.DEVICE_DISABLED);
        expectError(as(owner, post("/api/app/devices/{d}/transfer", device)), ErrorCode.DEVICE_DISABLED);
        expectError(as(user("访客"), post("/api/app/invites/{t}/accept", inviteToken)), ErrorCode.DEVICE_DISABLED);
        expectError(as(user("买家"), post("/api/app/transfers/{t}/accept", transferToken)), ErrorCode.DEVICE_DISABLED);

        // 绑定与授权关系保留；撤销、取消等收尾操作仍可做
        assertThat(jdbc.sql("SELECT CONCAT(d.bind_status, ' ', d.owner_user_id, ' ', m.status) FROM device d"
                                + " JOIN device_member m ON m.device_id = d.id WHERE d.id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("BOUND " + owner + " ACTIVE");
        as(owner, delete("/api/app/devices/{d}/transfer", device)).andExpect(status().isOk());

        // 启用后恢复原状
        admin(ops, "/api/admin/devices/" + device + "/enable", Map.of("reason", "核实无误"))
                .andExpect(status().isOk());
        assertThat(data(as(member, get("/api/app/devices")))
                        .get(0)
                        .get("enabled")
                        .asBoolean())
                .isTrue();
        as(user("访客"), post("/api/app/invites/{t}/accept", inviteToken)).andExpect(status().isOk());
        as(owner, post("/api/app/devices/{d}/transfer", device)).andExpect(status().isOk());
    }
}
