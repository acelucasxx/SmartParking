package com.smartparking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.account.Passwords;
import com.smartparking.common.config.SystemSettings;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.DbTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 后台登录、会话、锁定、账号管理、操作日志与业务参数（BIZ-08）。走真实会话，不用 TestAdminIdentity。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminAuthIntegrationTest {

    private static final String PASSWORD = "Correct-horse-1";
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final List<String> CONFIG_KEYS = List.of(
            "member.max_per_device", "online.sleep_minutes", "status_poll.interval_minutes", "online.offline_hours");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private Passwords passwords;

    @Autowired
    private AdminAccounts accounts;

    @Autowired
    private SystemSettings settings;

    private Map<String, String> savedConfigs;

    @BeforeEach
    void saveConfigs() {
        savedConfigs = new HashMap<>();
        for (String key : CONFIG_KEYS) {
            savedConfigs.put(
                    key,
                    jdbc.sql("SELECT config_value FROM system_config WHERE config_key = ?")
                            .param(key)
                            .query(String.class)
                            .single());
        }
    }

    @AfterEach
    void restoreConfigs() {
        savedConfigs.forEach((k, v) -> jdbc.sql("UPDATE system_config SET config_value = ? WHERE config_key = ?")
                .params(v, k)
                .update());
    }

    // ------------------------------------------------------------ 造数据

    private static String username() {
        return "it_" + Integer.toString(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE), 36);
    }

    /** 已改过密码的账号 */
    private String account(String role, String displayName) {
        String username = username();
        jdbc.sql("INSERT INTO admin_user (username, password_hash, display_name, role_id, password_changed_at)"
                        + " SELECT ?, ?, ?, id, NOW(3) FROM role WHERE code = ?")
                .params(username, passwords.hash(PASSWORD), displayName, role)
                .update();
        return username;
    }

    private long id(String username) {
        return jdbc.sql("SELECT id FROM admin_user WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String token(String username) throws Exception {
        return token(username, PASSWORD);
    }

    private String token(String username, String password) throws Exception {
        String body = login(username, password)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).at("/data/token").asText();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return as(token, request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private static void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    private static String sessionKey(String token) throws Exception {
        return "sp:admin-session:"
                + HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    }

    // ------------------------------------------------------------ 登录与会话

    @Test
    void loginIssuesSessionWithProfileAndPermissions() throws Exception {
        String ops = account("OPS", "运维小王");
        Instant before = Instant.now();
        JsonNode result = data(login("  " + ops.toUpperCase() + " ", PASSWORD).andExpect(status().isOk()));
        assertThat(result.get("token").asText()).hasSize(43);
        Instant expiresAt =
                OffsetDateTime.parse(result.get("expiresAt").asText()).toInstant();
        assertThat(expiresAt)
                .isBetween(before.plus(Duration.ofMinutes(29)), Instant.now().plus(Duration.ofMinutes(31)));
        assertThat(result.get("expiresAt").asText()).endsWith("+08:00");
        assertThat(result.at("/admin/username").asText()).isEqualTo(ops);
        assertThat(result.at("/admin/displayName").asText()).isEqualTo("运维小王");
        assertThat(result.at("/admin/role").asText()).isEqualTo("OPS");
        assertThat(result.at("/admin/mustChangePassword").asBoolean()).isFalse();
        assertThat(json.convertValue(result.at("/admin/permissions"), STRINGS))
                .contains("device:import", "alarm:handle")
                .doesNotContain("user:ban", "*");
        assertThat(jdbc.sql("SELECT last_login_at IS NOT NULL FROM admin_user WHERE username = ?")
                        .param(ops)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        String token = result.get("token").asText();
        as(token, get("/api/admin/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(ops));
        // 角色判定照常生效
        as(token, get("/api/admin/alarms")).andExpect(status().isOk());
        expectError(as(token, get("/api/admin/admin-users")), ErrorCode.FORBIDDEN);

        // 空闲超时每次请求顺延
        Long ttl = redis.getExpire(sessionKey(token));
        assertThat(ttl)
                .isBetween(
                        Duration.ofMinutes(29).toSeconds(),
                        Duration.ofMinutes(30).toSeconds());

        String superAdmin = account("SUPER_ADMIN", "超管");
        assertThat(json.convertValue(data(login(superAdmin, PASSWORD)).at("/admin/permissions"), STRINGS))
                .containsExactly("*");
    }

    @Test
    void missingOrInvalidTokenIsUnauthorized() throws Exception {
        expectError(as(null, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        expectError(as("x".repeat(43), get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        expectError(
                mvc.perform(get("/api/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Basic abc")),
                ErrorCode.UNAUTHORIZED);
        expectError(login(" ", PASSWORD), ErrorCode.BAD_REQUEST);
        expectError(login(username(), ""), ErrorCode.BAD_REQUEST);
    }

    @Test
    void logoutRevokesOnlyThatSession() throws Exception {
        String ops = account("OPS", "运维");
        String first = token(ops);
        String second = token(ops);
        as(first, post("/api/admin/auth/logout")).andExpect(status().isOk());
        expectError(as(first, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        as(second, get("/api/admin/auth/me")).andExpect(status().isOk());
    }

    @Test
    void sessionExpiresAfterAbsoluteLimit() throws Exception {
        String ops = account("OPS", "运维");
        String token = token(ops);
        String key = sessionKey(token);
        long loggedIn =
                Instant.now().minus(Duration.ofHours(12)).minusSeconds(1).toEpochMilli();
        redis.opsForValue().set(key, id(ops) + ":" + loggedIn, Duration.ofMinutes(30));
        expectError(as(token, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        assertThat(redis.hasKey(key)).isFalse();

        // 临近绝对超时：空闲顺延不超过剩余时间
        String other = token(ops);
        long almost = Instant.now().minus(Duration.ofHours(12)).plusSeconds(120).toEpochMilli();
        redis.opsForValue().set(sessionKey(other), id(ops) + ":" + almost, Duration.ofMinutes(30));
        as(other, get("/api/admin/auth/me")).andExpect(status().isOk());
        assertThat(redis.getExpire(sessionKey(other))).isLessThanOrEqualTo(120);
    }

    // ------------------------------------------------------------ 锁定与停用

    @Test
    void consecutiveFailuresLockTheAccount() throws Exception {
        String ops = account("OPS", "运维");
        login(ops, "wrong-1")
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(containsString("还可尝试 4 次")));
        for (int i = 2; i <= 4; i++) {
            expectError(login(ops, "wrong-" + i), ErrorCode.LOGIN_FAILED);
        }
        login(ops, "wrong-5")
                .andExpect(jsonPath("$.code").value(40002))
                .andExpect(jsonPath("$.message").value(containsString("15 分钟")));

        // 锁定期间正确密码也拒绝，错误也不再计次、不延长锁定
        String lockedUntil = jdbc.sql("SELECT CAST(locked_until AS CHAR) FROM admin_user WHERE username = ?")
                .param(ops)
                .query(String.class)
                .single();
        expectError(login(ops, PASSWORD), ErrorCode.ACCOUNT_LOCKED);
        expectError(login(ops, "wrong-6"), ErrorCode.ACCOUNT_LOCKED);
        assertThat(jdbc.sql("SELECT CAST(locked_until AS CHAR) FROM admin_user WHERE username = ?")
                        .param(ops)
                        .query(String.class)
                        .single())
                .isEqualTo(lockedUntil);

        // 锁定过期：错误从 1 重新计数
        jdbc.sql("UPDATE admin_user SET locked_until = ? WHERE username = ?")
                .params(DbTime.toDb(Instant.now().minusSeconds(1)), ops)
                .update();
        login(ops, "wrong-7").andExpect(jsonPath("$.message").value(containsString("还可尝试 4 次")));
        // 登录成功清零
        token(ops);
        assertThat(jdbc.sql("SELECT failed_attempts FROM admin_user WHERE username = ?")
                        .param(ops)
                        .query(Integer.class)
                        .single())
                .isZero();
        login(ops, "wrong-8").andExpect(jsonPath("$.message").value(containsString("还可尝试 4 次")));
    }

    /** 并发：另一次错误刚触发锁定后，本次错误不再计次，也不延长锁定（登录流程里先判锁定，这里直接调用计数） */
    @Test
    void failureDuringLockIsNotCountedAndDoesNotExtendIt() {
        String ops = account("OPS", "运维");
        long id = id(ops);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant until = now.plus(Duration.ofMinutes(15));
        jdbc.sql("UPDATE admin_user SET failed_attempts = 5, locked_until = ? WHERE id = ?")
                .params(DbTime.toDb(until), id)
                .update();
        assertThat(accounts.recordFailure(id, now, 5, now.plus(Duration.ofHours(1))))
                .isEmpty();
        AdminAccounts.Account after = accounts.find(id).orElseThrow();
        assertThat(after.failedAttempts()).isEqualTo(5);
        assertThat(after.lockedUntil()).isEqualTo(until);
    }

    @Test
    void unknownUserAndDisabledAccount() throws Exception {
        login(username(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        String ops = account("OPS", "运维");
        String token = token(ops);
        jdbc.sql("UPDATE admin_user SET status = 'DISABLED' WHERE username = ?")
                .param(ops)
                .update();
        // 已登录的会话下一次请求即失效（每个请求按库里的状态判定）
        expectError(as(token, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        assertThat(redis.hasKey(sessionKey(token))).isFalse();
        // 密码错误时不透露已停用
        expectError(login(ops, "wrong"), ErrorCode.LOGIN_FAILED);
        expectError(login(ops, PASSWORD), ErrorCode.ACCOUNT_DISABLED);
    }

    // ------------------------------------------------------------ 临时密码与修改密码

    @Test
    void temporaryPasswordMustBeChangedFirst() throws Exception {
        String superToken = token(account("SUPER_ADMIN", "超管"));
        String username = username();
        JsonNode created = data(send(
                        superToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", username, "displayName", "客服小李", "role", "SUPPORT"))
                .andExpect(status().isOk()));
        String temp = created.get("tempPassword").asText();
        assertThat(temp).hasSize(12).matches(".*[A-Za-z].*").matches(".*[0-9].*");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode login = data(login(username, temp).andExpect(status().isOk()));
        assertThat(login.at("/admin/mustChangePassword").asBoolean()).isTrue();
        String token = login.get("token").asText();
        String other = token(username, temp);

        as(token, get("/api/admin/auth/me"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
        as(token, get("/api/admin/users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("请先修改密码"));

        expectError(
                send(
                        token,
                        put("/api/admin/auth/password"),
                        Map.of("oldPassword", "nope", "newPassword", "Brand-new-42")),
                ErrorCode.LOGIN_FAILED);
        expectError(
                send(token, put("/api/admin/auth/password"), Map.of("oldPassword", temp, "newPassword", "short1")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(token, put("/api/admin/auth/password"), Map.of("oldPassword", temp, "newPassword", "onlyletters")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(token, put("/api/admin/auth/password"), Map.of("oldPassword", temp, "newPassword", "1234567890")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(token, put("/api/admin/auth/password"), Map.of("oldPassword", temp, "newPassword", temp)),
                ErrorCode.BAD_REQUEST);
        // 原密码错误不计入登录锁定
        assertThat(jdbc.sql("SELECT failed_attempts FROM admin_user WHERE username = ?")
                        .param(username)
                        .query(Integer.class)
                        .single())
                .isZero();

        send(token, put("/api/admin/auth/password"), Map.of("oldPassword", temp, "newPassword", "Brand-new-42"))
                .andExpect(status().isOk());
        // 当前会话保留并解除限制，其他会话作废
        as(token, get("/api/admin/auth/me"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
        expectError(as(token, get("/api/admin/alarms")), ErrorCode.FORBIDDEN);
        expectError(as(other, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        expectError(login(username, temp), ErrorCode.LOGIN_FAILED);
        token(username, "Brand-new-42");
    }

    // ------------------------------------------------------------ 账号管理

    @Test
    void superAdminManagesAccounts() throws Exception {
        String me = account("SUPER_ADMIN", "超管");
        String superToken = token(me);
        long myId = id(me);

        String username = username();
        expectError(
                send(
                        superToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", "Bad", "displayName", "x", "role", "OPS")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(superToken, post("/api/admin/admin-users"), Map.of("username", username, "displayName", "x")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(
                        superToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", username, "displayName", "x", "role", "ROOT")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(
                        superToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", username, "displayName", "  ", "role", "OPS")),
                ErrorCode.BAD_REQUEST);
        long opsId = data(send(
                                superToken,
                                post("/api/admin/admin-users"),
                                Map.of("username", username, "displayName", " 运维小赵 ", "role", "OPS"))
                        .andExpect(jsonPath("$.data.displayName").value("运维小赵"))
                        .andExpect(jsonPath("$.data.role").value("OPS")))
                .get("id")
                .asLong();
        expectError(
                send(
                        superToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", username, "displayName", "y", "role", "SUPPORT")),
                ErrorCode.USERNAME_EXISTS);

        // 改自己：名称可以，角色与停用不行
        send(superToken, patch("/api/admin/admin-users/{id}", myId), Map.of("displayName", "超管老王"))
                .andExpect(jsonPath("$.data.displayName").value("超管老王"));
        expectError(
                send(superToken, patch("/api/admin/admin-users/{id}", myId), Map.of("role", "OPS")),
                ErrorCode.FORBIDDEN);
        expectError(
                send(superToken, patch("/api/admin/admin-users/{id}", myId), Map.of("status", "DISABLED")),
                ErrorCode.FORBIDDEN);
        expectError(send(superToken, patch("/api/admin/admin-users/{id}", opsId), Map.of()), ErrorCode.BAD_REQUEST);
        expectError(
                send(superToken, patch("/api/admin/admin-users/{id}", opsId), Map.of("username", "x")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(superToken, patch("/api/admin/admin-users/{id}", opsId), Map.of("status", "GONE")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(superToken, patch("/api/admin/admin-users/{id}", 9_000_000_000L), Map.of("displayName", "x")),
                ErrorCode.NOT_FOUND);

        // 改角色即时生效：已登录的会话下一次请求按新角色判定
        String temp = data(send(superToken, post("/api/admin/admin-users/{id}/reset-password", opsId), Map.of()))
                .get("tempPassword")
                .asText();
        String opsToken = token(username, temp);
        send(opsToken, put("/api/admin/auth/password"), Map.of("oldPassword", temp, "newPassword", "Ops-pass-2024"))
                .andExpect(status().isOk());
        as(opsToken, get("/api/admin/alarms")).andExpect(status().isOk());
        send(superToken, patch("/api/admin/admin-users/{id}", opsId), Map.of("role", "SUPPORT"))
                .andExpect(jsonPath("$.data.role").value("SUPPORT"));
        expectError(as(opsToken, get("/api/admin/alarms")), ErrorCode.FORBIDDEN);

        // 停用：会话立即作废
        String idleToken = token(username, "Ops-pass-2024");
        send(superToken, patch("/api/admin/admin-users/{id}", opsId), Map.of("status", "DISABLED"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        expectError(as(opsToken, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        expectError(login(username, "Ops-pass-2024"), ErrorCode.ACCOUNT_DISABLED);
        send(superToken, patch("/api/admin/admin-users/{id}", opsId), Map.of("status", "ACTIVE"))
                .andExpect(status().isOk());
        // 停用时已作废全部会话：重新启用后，停用期间没用过的旧会话也不能复活
        expectError(as(idleToken, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);

        // 重置密码：解除锁定、旧密码失效、旧会话作废、必须修改
        String active = token(username, "Ops-pass-2024");
        jdbc.sql("UPDATE admin_user SET failed_attempts = 5, locked_until = ? WHERE id = ?")
                .params(DbTime.toDb(Instant.now().plus(Duration.ofMinutes(10))), opsId)
                .update();
        as(superToken, get("/api/admin/admin-users").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == " + opsId + ")].lockedUntil")
                        .value(Matchers.everyItem(endsWith("+08:00"))));
        String reset = data(as(superToken, post("/api/admin/admin-users/{id}/reset-password", opsId))
                        .andExpect(jsonPath("$.data.lockedUntil").isEmpty()))
                .get("tempPassword")
                .asText();
        expectError(as(active, get("/api/admin/auth/me")), ErrorCode.UNAUTHORIZED);
        expectError(login(username, "Ops-pass-2024"), ErrorCode.LOGIN_FAILED);
        assertThat(data(login(username, reset)).at("/admin/mustChangePassword").asBoolean())
                .isTrue();
        expectError(
                as(superToken, post("/api/admin/admin-users/{id}/reset-password", 9_000_000_000L)),
                ErrorCode.NOT_FOUND);

        // 列表与角色
        as(superToken, get("/api/admin/admin-users").param("page", "1").param("size", "100"))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.items[?(@.id == " + myId + ")].displayName")
                        .value(Matchers.contains("超管老王")));
        as(superToken, get("/api/admin/roles"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data[0].permissions[0]").value("*"))
                .andExpect(jsonPath("$.data[2].code").value("SUPPORT"))
                .andExpect(jsonPath("$.data[2].permissions").value(Matchers.hasItem("user:ban")));
    }

    // ------------------------------------------------------------ 操作日志

    @Test
    void writesAreAuditedIncludingRejectedOnes() throws Exception {
        String me = account("SUPER_ADMIN", "超管");
        String superToken = token(me);
        long myId = id(me);
        String opsName = account("OPS", "运维");
        String opsToken = token(opsName);
        Instant from = Instant.now().minusSeconds(1);

        String username = username();
        long created = data(send(
                        superToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", username, "displayName", "新人", "role", "OPS")))
                .get("id")
                .asLong();
        send(superToken, patch("/api/admin/admin-users/{id}", created), Map.of("role", "SUPPORT"))
                .andExpect(status().isOk());
        // 越权：运维调用超管接口，记为 FAIL
        expectError(
                send(
                        opsToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", username(), "displayName", "x", "role", "OPS")),
                ErrorCode.FORBIDDEN);
        // 业务失败也记
        expectError(
                send(
                        superToken,
                        post("/api/admin/admin-users"),
                        Map.of("username", username, "displayName", "x", "role", "OPS")),
                ErrorCode.USERNAME_EXISTS);
        // 只读接口不记
        as(superToken, get("/api/admin/admin-users")).andExpect(status().isOk());

        JsonNode mine = data(as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("adminId", String.valueOf(myId))
                                .param(
                                        "from",
                                        from.atOffset(ZoneOffset.ofHours(8)).toString()))
                .andExpect(status().isOk()));
        assertThat(mine.get("total").asLong()).isEqualTo(3);
        JsonNode items = mine.get("items");
        // 时间倒序
        assertThat(items.get(0).get("action").asText()).isEqualTo("ADMIN_USER_CREATE");
        assertThat(items.get(0).get("result").asText()).isEqualTo("FAIL");
        assertThat(items.get(1).get("action").asText()).isEqualTo("ADMIN_USER_UPDATE");
        assertThat(items.get(1).get("targetType").asText()).isEqualTo("ADMIN_USER");
        assertThat(items.get(1).get("targetId").asText()).isEqualTo(String.valueOf(created));
        assertThat(items.get(1).at("/detail/role").asText()).isEqualTo("OPS → SUPPORT");
        assertThat(items.get(2).get("result").asText()).isEqualTo("SUCCESS");
        assertThat(items.get(2).get("targetId").asText()).isEqualTo(String.valueOf(created));
        assertThat(items.get(2).get("adminName").asText()).isEqualTo(me);
        assertThat(items.get(2).get("ip").asText()).isNotBlank();
        assertThat(items.get(2).get("createdAt").asText()).endsWith("+08:00");
        // to 不含：恰好等于最早一条的时刻时不返回它
        OffsetDateTime earliest =
                OffsetDateTime.parse(items.get(2).get("createdAt").asText());
        as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("adminId", String.valueOf(myId))
                                .param("to", earliest.toString()))
                .andExpect(jsonPath("$.data.total").value(0));
        as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("adminId", String.valueOf(myId))
                                .param("from", earliest.toString())
                                .param("to", earliest.plusNanos(1_000_000).toString()))
                .andExpect(jsonPath("$.data.total").value(1));
        // 临时密码不进日志
        assertThat(items.toString()).doesNotContain("tempPassword");

        as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("adminId", String.valueOf(id(opsName)))
                                .param("action", "ADMIN_USER_CREATE"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].result").value("FAIL"))
                .andExpect(jsonPath("$.data.items[0].adminName").value(opsName));
        as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("targetId", String.valueOf(created))
                                .param("adminId", String.valueOf(myId)))
                .andExpect(jsonPath("$.data.total").value(2));
        as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("adminId", String.valueOf(myId))
                                .param(
                                        "to",
                                        from.atOffset(ZoneOffset.ofHours(8)).toString()))
                .andExpect(jsonPath("$.data.total").value(0));
        expectError(as(superToken, get("/api/admin/operation-logs").param("from", "yesterday")), ErrorCode.BAD_REQUEST);
        expectError(
                as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("from", "2026-11-27T10:00:00+08:00")
                                .param("to", "2026-11-27T09:00:00+08:00")),
                ErrorCode.BAD_REQUEST);
        expectError(as(opsToken, get("/api/admin/operation-logs")), ErrorCode.FORBIDDEN);
    }

    // ------------------------------------------------------------ 业务参数

    @Test
    void configsAreValidatedAndTakeEffect() throws Exception {
        String me = account("SUPER_ADMIN", "超管老李");
        String superToken = token(me);

        as(superToken, get("/api/admin/configs"))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[?(@.key == 'member.max_per_device')].valueType")
                        .value(Matchers.contains("INT")));

        // 先读一次进缓存（默认缓存 30 秒），修改后本实例不等缓存过期
        settings.getInt(SystemSettings.Keys.MEMBER_MAX_PER_DEVICE, -1);
        send(superToken, put("/api/admin/configs/{key}", "member.max_per_device"), Map.of("value", " 06 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value("6"))
                .andExpect(jsonPath("$.data.updatedBy").value("超管老李"))
                .andExpect(jsonPath("$.data.updatedAt").value(endsWith("+08:00")));
        // 本实例立即生效
        assertThat(settings.getInt(SystemSettings.Keys.MEMBER_MAX_PER_DEVICE, -1))
                .isEqualTo(6);

        expectError(
                send(superToken, put("/api/admin/configs/{key}", "member.max_per_device"), Map.of("value", "21")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(superToken, put("/api/admin/configs/{key}", "member.max_per_device"), Map.of("value", "0")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(superToken, put("/api/admin/configs/{key}", "member.max_per_device"), Map.of("value", "five")),
                ErrorCode.BAD_REQUEST);
        expectError(
                send(superToken, put("/api/admin/configs/{key}", "no.such_key"), Map.of("value", "1")),
                ErrorCode.NOT_FOUND);
        // 休眠窗口须大于定时查询间隔（当前 5 分钟）；离线窗口须大于休眠窗口
        expectError(
                send(superToken, put("/api/admin/configs/{key}", "online.sleep_minutes"), Map.of("value", "5")),
                ErrorCode.BAD_REQUEST);
        send(superToken, put("/api/admin/configs/{key}", "online.sleep_minutes"), Map.of("value", "6"))
                .andExpect(status().isOk());
        expectError(
                send(superToken, put("/api/admin/configs/{key}", "status_poll.interval_minutes"), Map.of("value", "6")),
                ErrorCode.BAD_REQUEST);
        send(superToken, put("/api/admin/configs/{key}", "online.sleep_minutes"), Map.of("value", "120"))
                .andExpect(status().isOk());
        expectError(
                send(superToken, put("/api/admin/configs/{key}", "online.offline_hours"), Map.of("value", "2")),
                ErrorCode.BAD_REQUEST);

        as(
                        superToken,
                        get("/api/admin/operation-logs")
                                .param("adminId", String.valueOf(id(me)))
                                .param("action", "CONFIG_UPDATE")
                                .param("targetId", "member.max_per_device"))
                .andExpect(jsonPath("$.data.items[?(@.result == 'SUCCESS')].detail.to")
                        .value(Matchers.contains("6")));

        String ops = token(account("OPS", "运维"));
        expectError(as(ops, get("/api/admin/configs")), ErrorCode.FORBIDDEN);
    }

    @Test
    void existingAdminWritesAreAudited() throws Exception {
        String me = account("OPS", "运维");
        String token = token(me);
        // 告警不存在：照样记一条 FAIL，对象 ID 取路径变量
        expectError(
                send(token, post("/api/admin/alarms/{id}/handle", 9_000_000_123L), Map.of("note", "x")),
                ErrorCode.NOT_FOUND);
        as(token, get("/api/admin/alarms")).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT CONCAT(action, ' ', target_type, ' ', target_id, ' ', result) FROM operation_log"
                                + " WHERE admin_id = ?")
                        .param(id(me))
                        .query(String.class)
                        .list())
                .containsExactly("ALARM_HANDLE ALARM 9000000123 FAIL");
    }
}
