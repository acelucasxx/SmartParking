package com.smartparking.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
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

/** 小程序登录、手机号授权、会话与封禁拦截（BIZ-01）。微信接口为 mock 模式，MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AppAuthIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PhoneCipher phones;

    @Autowired
    private SessionStore sessions;

    private String code;

    @BeforeEach
    void newUser() {
        code = "test:" + UUID.randomUUID();
    }

    private ResultActions postJson(String path, String token, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private JsonNode login(String code) throws Exception {
        String body = postJson("/api/app/auth/login", null, "{\"code\":\"" + code + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).get("data");
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/app/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void ban(long userId) {
        jdbc.sql("UPDATE app_user SET status = 'BANNED' WHERE id = ?")
                .param(userId)
                .update();
    }

    @Test
    void firstLoginRegistersAndReturnsSession() throws Exception {
        JsonNode data = login(code);

        assertThat(data.get("token").asText()).hasSize(43);
        OffsetDateTime expiresAt = OffsetDateTime.parse(data.get("expiresAt").asText());
        assertThat(expiresAt.getOffset().getTotalSeconds()).isEqualTo(8 * 3600);
        assertThat(Duration.between(OffsetDateTime.now(), expiresAt))
                .isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));

        JsonNode user = data.get("user");
        assertThat(user.get("nickname").asText()).isEqualTo(AuthService.DEFAULT_NICKNAME);
        assertThat(user.get("phoneBound").asBoolean()).isFalse();
        assertThat(user.has("phoneMasked")).isTrue();
        assertThat(user.get("phoneMasked").isNull()).isTrue();

        long id = user.get("id").asLong();
        assertThat(jdbc.sql("SELECT openid FROM app_user WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("mock_" + code.substring("test:".length()));
        assertThat(jdbc.sql("SELECT last_login_at IS NOT NULL FROM app_user WHERE id = ?")
                        .param(id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void sameOpenidLogsIntoSameUserWithNewToken() throws Exception {
        JsonNode first = login(code);
        JsonNode second = login(code);
        assertThat(second.get("user").get("id").asLong())
                .isEqualTo(first.get("user").get("id").asLong());
        assertThat(second.get("token").asText()).isNotEqualTo(first.get("token").asText());

        // 两个会话都有效（多端 / 重复登录不互踢）
        me(first.get("token").asText()).andExpect(status().isOk());
        me(second.get("token").asText()).andExpect(status().isOk());

        assertThat(login("test:" + UUID.randomUUID()).get("user").get("id").asLong())
                .isNotEqualTo(first.get("user").get("id").asLong());
    }

    @Test
    void appApisRequireValidToken() throws Exception {
        mvc.perform(get("/api/app/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
        me("not-a-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
        // 格式正确但不存在的 token
        me("A".repeat(43)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/app/me").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized());
        // 未实现的小程序接口同样先过登录检查
        mvc.perform(get("/api/app/devices")).andExpect(status().isUnauthorized());
    }

    @Test
    void bindPhoneStoresCipherAndHashOnly() throws Exception {
        JsonNode session = login(code);
        String token = session.get("token").asText();
        long id = session.get("user").get("id").asLong();

        postJson("/api/app/auth/phone", token, "{\"code\":\"13912341234\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneBound").value(true))
                .andExpect(jsonPath("$.data.phoneMasked").value("139****1234"));

        byte[] cipher = jdbc.sql("SELECT phone_cipher FROM app_user WHERE id = ?")
                .param(id)
                .query(byte[].class)
                .single();
        assertThat(new String(cipher, StandardCharsets.ISO_8859_1)).doesNotContain("13912341234");
        assertThat(phones.decrypt(cipher)).isEqualTo("13912341234");
        assertThat(jdbc.sql("SELECT phone_hash FROM app_user WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo(phones.hash("13912341234"));

        me(token).andExpect(jsonPath("$.data.phoneMasked").value("139****1234"));
        // 再次登录时也带出手机号授权状态
        assertThat(login(code).get("user").get("phoneBound").asBoolean()).isTrue();
    }

    @Test
    void weChatFailuresMapToContractCodes() throws Exception {
        postJson("/api/app/auth/login", null, "{\"code\":\"fail\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(10020));

        String token = login(code).get("token").asText();
        postJson("/api/app/auth/phone", token, "{\"code\":\"fail\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(10021));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        postJson("/api/app/auth/login", null, "{\"code\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
        postJson("/api/app/auth/login", null, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
        postJson("/api/app/auth/login", null, "{bad json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void nicknameCanBeChanged() throws Exception {
        String token = login(code).get("token").asText();
        mvc.perform(patch("/api/app/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"  张先生 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("张先生"));
        me(token).andExpect(jsonPath("$.data.nickname").value("张先生"));

        for (String bad : new String[] {"\"\"", "\"   \"", "\"" + "长".repeat(33) + "\"", "null"}) {
            mvc.perform(patch("/api/app/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":" + bad + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(10001));
        }
        mvc.perform(patch("/api/app/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + "长".repeat(32) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesOnlyThatSession() throws Exception {
        String first = login(code).get("token").asText();
        String second = login(code).get("token").asText();

        postJson("/api/app/auth/logout", first, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        me(first).andExpect(status().isUnauthorized());
        me(second).andExpect(status().isOk());
        postJson("/api/app/auth/logout", first, "").andExpect(status().isUnauthorized());
    }

    @Test
    void bannedUserIsBlockedEverywhereButLogout() throws Exception {
        JsonNode session = login(code);
        String token = session.get("token").asText();
        ban(session.get("user").get("id").asLong());

        // 已有会话立即失效（PRD 5.8②），不必等会话过期
        me(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(10010));
        postJson("/api/app/auth/phone", token, "{\"code\":\"13912341234\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10010));
        // 重新登录也被拒，且不签发新会话
        postJson("/api/app/auth/login", null, "{\"code\":\"" + code + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10010))
                .andExpect(jsonPath("$.data").doesNotExist());
        // 封禁用户可以退出
        postJson("/api/app/auth/logout", token, "").andExpect(status().isOk());
        me(token).andExpect(status().isUnauthorized());
    }

    @Test
    void deletedUserSessionIsRejected() throws Exception {
        JsonNode session = login(code);
        String token = session.get("token").asText();
        jdbc.sql("DELETE FROM app_user WHERE id = ?")
                .param(session.get("user").get("id").asLong())
                .update();
        me(token).andExpect(status().isUnauthorized());
        assertThat(sessions.find(token)).isEmpty();
    }

    @Test
    void revokeAllEndsEverySessionOfUser() throws Exception {
        JsonNode first = login(code);
        String second = login(code).get("token").asText();
        long id = first.get("user").get("id").asLong();

        assertThat(sessions.revokeAll(id)).isEqualTo(2);
        me(first.get("token").asText()).andExpect(status().isUnauthorized());
        me(second).andExpect(status().isUnauthorized());
        assertThat(sessions.revokeAll(id)).isZero();
    }

    @Test
    void unionidIsFilledButNeverCleared() {
        AppUserRepository repo = new AppUserRepository(jdbc);
        String openid = "o-" + UUID.randomUUID();
        long id = repo.upsertByOpenid(openid, null).id();
        repo.upsertByOpenid(openid, "union-1");
        repo.upsertByOpenid(openid, null);
        assertThat(jdbc.sql("SELECT unionid FROM app_user WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("union-1");
    }
}
