package com.smartparking.ownership;

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
import com.smartparking.device.registry.DeviceRegistry;
import com.smartparking.device.registry.DeviceRules;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.command.InMemoryDeviceLink;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
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

/** 扫码绑定（BIZ-04）：预检、三重校验、错误锁定（含并发）、ownership_log、绑定后状态查询。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, InMemoryDeviceLink.class})
class BindIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private DeviceRegistry registry;

    @Autowired
    private InMemoryDeviceLink bridge;

    @Autowired
    private StringRedisTemplate redis;

    private String sn;
    private String imei;
    private String code;
    private long deviceId;
    private long alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        sn = "B" + String.format("%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L));
        imei = imei();
        DeviceRegistry.Registered r = registry.register(sn, imei, null, null, "SPG-100", "B1");
        code = r.activateCode();
        deviceId = r.id();
        alice = user(true);
        aliceToken = token(alice);
        bridge.connected = true;
        bridge.sent.clear();
    }

    // ------------------------------------------------------------ 工具

    private static String imei() {
        while (true) {
            String candidate =
                    "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(10_000_000_000_000L));
            if (DeviceRules.luhn(candidate)) {
                return candidate;
            }
        }
    }

    private long user(boolean phoneBound) {
        String openid = "it-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (openid, phone_cipher) VALUES (?, ?)")
                .params(openid, phoneBound ? new byte[] {1, 2, 3} : null)
                .update();
        return jdbc.sql("SELECT id FROM app_user WHERE openid = ?")
                .param(openid)
                .query(Long.class)
                .single();
    }

    private String token(long userId) {
        return sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
    }

    private ResultActions bind(String token, String sn, String code) throws Exception {
        return mvc.perform(post("/api/app/bind")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sn", sn, "activateCode", code))));
    }

    private ResultActions preview(String token, String sn) throws Exception {
        return mvc.perform(get("/api/app/bind/" + sn).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    /** 与激活码不同的一个合法格式的码 */
    private String wrongCode() {
        return code.equals("22222222") ? "33333333" : "22222222";
    }

    private Map<String, Object> deviceRow() {
        return jdbc.sql("SELECT bind_status, owner_user_id, owner_since FROM device WHERE id = ?")
                .param(deviceId)
                .query()
                .singleRow();
    }

    private int ownershipLogs() {
        return jdbc.sql("SELECT COUNT(*) FROM ownership_log WHERE device_id = ?")
                .param(deviceId)
                .query(Integer.class)
                .single();
    }

    private String failures() {
        return redis.opsForValue().get("sp:bind-fail:" + sn);
    }

    // ------------------------------------------------------------ 预检

    @Test
    void previewReflectsBindState() throws Exception {
        preview(aliceToken, sn.toLowerCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sn").value(sn))
                .andExpect(jsonPath("$.data.model").value("SPG-100"))
                .andExpect(jsonPath("$.data.state").value("BINDABLE"))
                .andExpect(jsonPath("$.data.deviceId").isEmpty());

        bind(aliceToken, sn, code).andExpect(status().isOk());
        preview(aliceToken, sn)
                .andExpect(jsonPath("$.data.state").value("BOUND_BY_ME"))
                .andExpect(jsonPath("$.data.deviceId").value(deviceId));
        preview(token(user(true)), sn)
                .andExpect(jsonPath("$.data.state").value("BOUND_BY_OTHER"))
                .andExpect(jsonPath("$.data.deviceId").isEmpty());

        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(deviceId).update();
        preview(token(user(true)), sn).andExpect(jsonPath("$.data.state").value("DISABLED"));
        // 主人自己仍进入控制页，由控制页展示“已停用”
        preview(aliceToken, sn).andExpect(jsonPath("$.data.state").value("BOUND_BY_ME"));

        expectError(preview(aliceToken, "NOSUCHSN0001"), ErrorCode.DEVICE_NOT_FOUND);
        expectError(preview(aliceToken, "bad-sn"), ErrorCode.BAD_REQUEST);
    }

    // ------------------------------------------------------------ 绑定成功

    @Test
    void correctCodeBindsWritesLogAndQueriesDevice() throws Exception {
        JsonNode card = json.readTree(bind(aliceToken, sn, code)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("data");
        assertThat(card.get("id").asLong()).isEqualTo(deviceId);
        assertThat(card.get("sn").asText()).isEqualTo(sn);
        assertThat(card.get("role").asText()).isEqualTo("OWNER");
        assertThat(card.get("enabled").asBoolean()).isTrue();
        assertThat(card.get("onlineState").asText()).isEqualTo("OFFLINE");
        assertThat(card.get("gateStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(card.has("validTo")).isTrue();
        assertThat(card.get("pendingCommandId").isNull()).isTrue();

        Map<String, Object> row = deviceRow();
        assertThat(row.get("bind_status")).isEqualTo("BOUND");
        assertThat(((Number) row.get("owner_user_id")).longValue()).isEqualTo(alice);
        assertThat(row.get("owner_since")).isNotNull();

        Map<String, Object> log = jdbc.sql(
                        "SELECT type, from_user, to_user, operator_type, operator_id FROM ownership_log WHERE device_id = ?")
                .param(deviceId)
                .query()
                .singleRow();
        assertThat(log.get("type")).isEqualTo("BIND");
        assertThat(log.get("from_user")).isNull();
        assertThat(((Number) log.get("to_user")).longValue()).isEqualTo(alice);
        assertThat(log.get("operator_type")).isEqualTo("USER");
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(alice);

        // 绑定成功立即下发一次状态查询（PRD 5.2⑤）
        await().atMost(Duration.ofSeconds(2))
                .until(() -> bridge.sent.contains(imei + " " + Hex.format(Rs485Codec.encodeQuery())));
    }

    @Test
    void codeIsCaseInsensitiveAndIgnoresSpaces() throws Exception {
        String typed =
                code.substring(0, 4).toLowerCase() + " " + code.substring(4).toLowerCase();
        bind(aliceToken, " " + sn.toLowerCase() + " ", typed).andExpect(status().isOk());
    }

    @Test
    void rebindingOwnDeviceIsIdempotent() throws Exception {
        bind(aliceToken, sn, code).andExpect(status().isOk());
        bind(aliceToken, sn, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(deviceId));
        // 自己的设备，激活码对错都不再计较
        bind(aliceToken, sn, wrongCode()).andExpect(status().isOk());
        assertThat(ownershipLogs()).isEqualTo(1);
        // 占用的机会已归还（键可能留着值 0，30 分钟后过期）
        assertThat(failures()).isIn(null, "0");
    }

    @Test
    void bindWorksWhenLinkIsDown() throws Exception {
        bridge.connected = false;
        bind(aliceToken, sn, code).andExpect(status().isOk());
        assertThat(deviceRow().get("bind_status")).isEqualTo("BOUND");
    }

    // ------------------------------------------------------------ 拒绝

    @Test
    void wrongCodeCountsDownThenLocks() throws Exception {
        for (int left = 4; left >= 1; left--) {
            bind(aliceToken, sn, wrongCode())
                    .andExpect(jsonPath("$.code").value(ErrorCode.ACTIVATE_CODE_WRONG.code()))
                    .andExpect(jsonPath("$.message").value("激活码错误，还可尝试 " + left + " 次"));
        }
        bind(aliceToken, sn, wrongCode())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACTIVATE_LOCKED.code()))
                .andExpect(jsonPath("$.message").value("错误次数过多，请 30 分钟后再试"));

        // 锁定期间正确的激活码也不行，换账号也不行
        expectError(bind(aliceToken, sn, code), ErrorCode.ACTIVATE_LOCKED);
        expectError(bind(token(user(true)), sn, code), ErrorCode.ACTIVATE_LOCKED);
        assertThat(deviceRow().get("bind_status")).isEqualTo("PENDING");

        Long ttl = redis.getExpire("sp:bind-fail:" + sn);
        assertThat(ttl).isBetween(29L * 60, 30L * 60);

        // 30 分钟后（键过期）可以再试
        redis.delete("sp:bind-fail:" + sn);
        bind(aliceToken, sn, code).andExpect(status().isOk());
    }

    @Test
    void fifthFailureLocksForFullThirtyMinutesAndLockIsNotExtended() throws Exception {
        for (int i = 0; i < 4; i++) {
            bind(aliceToken, sn, wrongCode());
        }
        // 模拟前 4 次错误发生在 29 分钟前：计数窗口只剩 1 分钟
        redis.expire("sp:bind-fail:" + sn, Duration.ofMinutes(1));
        expectError(bind(aliceToken, sn, wrongCode()), ErrorCode.ACTIVATE_LOCKED);
        assertThat(redis.getExpire("sp:bind-fail:" + sn)).as("第 5 次起满 30 分钟").isGreaterThan(29L * 60);

        // 锁定期间再试不延长锁定
        redis.expire("sp:bind-fail:" + sn, Duration.ofMinutes(10));
        expectError(bind(aliceToken, sn, code), ErrorCode.ACTIVATE_LOCKED);
        assertThat(redis.getExpire("sp:bind-fail:" + sn)).isLessThanOrEqualTo(10L * 60);
    }

    @Test
    void successResetsFailureCount() throws Exception {
        bind(aliceToken, sn, wrongCode());
        bind(aliceToken, sn, wrongCode());
        assertThat(failures()).isEqualTo("2");
        bind(aliceToken, sn, code).andExpect(status().isOk());
        assertThat(failures()).isNull();
    }

    @Test
    void concurrentGuessesCannotExceedFiveAttempts() throws Exception {
        String token = token(user(true));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String guess =
                        String.format("%08d", 22222222 + i).replace('0', '2').replace('1', '3');
                Callable<Integer> attempt = () -> {
                    String body =
                            bind(token, sn, guess).andReturn().getResponse().getContentAsString();
                    return json.readTree(body).get("code").asInt();
                };
                results.add(pool.submit(attempt));
            }
            int wrong = 0;
            int locked = 0;
            for (Future<Integer> f : results) {
                int c = f.get();
                if (c == ErrorCode.ACTIVATE_CODE_WRONG.code()) {
                    wrong++;
                } else if (c == ErrorCode.ACTIVATE_LOCKED.code()) {
                    locked++;
                } else if (c != 0) {
                    throw new AssertionError("unexpected code " + c);
                }
            }
            // 第 1–4 次 30001，第 5 次起 30002：并发也只有 5 次机会真正校验激活码
            assertThat(wrong).isLessThanOrEqualTo(4);
            assertThat(wrong + locked)
                    .isEqualTo(16 - (deviceRow().get("bind_status").equals("BOUND") ? 1 : 0));
            assertThat(Long.parseLong(failures())).isGreaterThanOrEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deviceBoundByOtherIsRejectedWithoutConsumingAttempts() throws Exception {
        bind(aliceToken, sn, code).andExpect(status().isOk());
        String bob = token(user(true));
        expectError(bind(bob, sn, code), ErrorCode.DEVICE_ALREADY_BOUND);
        expectError(bind(bob, sn, wrongCode()), ErrorCode.DEVICE_ALREADY_BOUND);
        assertThat(failures()).isIn(null, "0");
        assertThat(((Number) deviceRow().get("owner_user_id")).longValue()).isEqualTo(alice);
    }

    @Test
    void concurrentCorrectBindsHaveExactlyOneWinner() throws Exception {
        String bob = token(user(true));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> json.readTree(
                            bind(aliceToken, sn, code).andReturn().getResponse().getContentAsString())
                    .get("code")
                    .asInt());
            Future<Integer> b = pool.submit(() -> json.readTree(
                            bind(bob, sn, code).andReturn().getResponse().getContentAsString())
                    .get("code")
                    .asInt());
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(0, ErrorCode.DEVICE_ALREADY_BOUND.code());
        } finally {
            pool.shutdownNow();
        }
        assertThat(ownershipLogs()).isEqualTo(1);
    }

    @Test
    void disabledDeviceCannotBeBound() throws Exception {
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(deviceId).update();
        expectError(bind(aliceToken, sn, code), ErrorCode.DEVICE_DISABLED);
        assertThat(failures()).isIn(null, "0");
    }

    @Test
    void invalidInputsAreRejectedWithoutCounting() throws Exception {
        expectError(bind(aliceToken, "NOSUCHSN0001", code), ErrorCode.DEVICE_NOT_FOUND);
        expectError(bind(aliceToken, "bad", code), ErrorCode.BAD_REQUEST);
        expectError(bind(aliceToken, sn, "1234567"), ErrorCode.BAD_REQUEST);
        expectError(bind(aliceToken, sn, "ABCD-EFG"), ErrorCode.BAD_REQUEST);
        expectError(
                mvc.perform(post("/api/app/bind")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sn\":\"" + sn + "\"}")),
                ErrorCode.BAD_REQUEST);
        assertThat(failures()).isNull();
    }

    @Test
    void phoneMustBeAuthorizedFirst() throws Exception {
        String noPhone = token(user(false));
        bind(noPhone, sn, code)
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()))
                .andExpect(jsonPath("$.message").value("请先授权手机号"));
        assertThat(deviceRow().get("bind_status")).isEqualTo("PENDING");
        assertThat(failures()).isNull();
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/app/bind/" + sn)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/app/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
