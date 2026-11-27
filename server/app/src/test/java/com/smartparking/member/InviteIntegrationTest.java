package com.smartparking.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.DbTime;
import com.smartparking.member.invite.InviteService;
import com.smartparking.member.invite.MemberGrantedEvent;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 邀请家人（BIZ-11）：创建、撤回、预览、接受与名额。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.invite.expire-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class InviteIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private InviteService invites;

    @Autowired
    private ApplicationEvents events;

    private long owner;
    private long device;
    private String sn;

    @BeforeEach
    void setUp() {
        setting("member.max_per_device", "5");
        setting("card.ttl_hours", "24");
        owner = user("张先生");
        device = device(owner, "家里车位");
    }

    @AfterEach
    void restore() {
        setting("member.max_per_device", "5");
        setting("card.ttl_hours", "24");
    }

    // ------------------------------------------------------------ 造数据

    private void setting(String key, String value) {
        jdbc.sql("UPDATE system_config SET config_value = ? WHERE config_key = ?")
                .params(value, key)
                .update();
    }

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

    private long device(long ownerId, String name) {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        sn = "SN" + imei;
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since,"
                                + " name) VALUES (?, ?, 'SPG-100', 'IT', 'x', 'BOUND', ?, NOW(3) - INTERVAL 1 DAY, ?)")
                .params(sn, imei, ownerId, name)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private void member(long userId, String status, Instant validTo) {
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by) VALUES"
                        + " (?, ?, ?, ?, ?, ?)")
                .params(
                        device,
                        userId,
                        DbTime.toDb(Instant.now().minus(Duration.ofDays(10))),
                        DbTime.toDb(validTo),
                        status,
                        owner)
                .update();
    }

    private ResultActions as(long userId, MockHttpServletRequestBuilder request) throws Exception {
        String token =
                sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions createAs(long userId, Object body) throws Exception {
        return as(
                userId,
                post("/api/app/devices/{id}/invites", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> relative(int days) {
        return Map.of("period", Map.of("type", "RELATIVE", "durationDays", days));
    }

    private static Map<String, Object> permanent() {
        return Map.of("period", Map.of("type", "PERMANENT"));
    }

    private static Map<String, Object> custom(Instant from, Instant to) {
        return Map.of("period", Map.of("type", "CUSTOM", "validFrom", at(from), "validTo", at(to)));
    }

    private static String at(Instant instant) {
        return instant.atOffset(ZoneOffset.ofHours(8)).toString();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private JsonNode invite(Object body) throws Exception {
        return data(createAs(owner, body));
    }

    private ResultActions accept(long userId, String token) throws Exception {
        return as(userId, post("/api/app/invites/{token}/accept", token));
    }

    private ResultActions preview(long userId, String token) throws Exception {
        return as(userId, get("/api/app/invites/{token}", token));
    }

    private static void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    private String inviteStatus(long inviteId) {
        return jdbc.sql("SELECT status FROM share_invite WHERE id = ?")
                .param(inviteId)
                .query(String.class)
                .single();
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII)));
    }

    // ------------------------------------------------------------ 创建

    @Test
    void ownerCreatesOneTimeShareCard() throws Exception {
        setting("card.ttl_hours", "2");
        Instant before = Instant.now();
        JsonNode card = invite(Map.of("period", Map.of("type", "RELATIVE", "durationDays", 7), "remark", " 爸爸 "));
        String token = card.get("token").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(card.get("path").asText()).isEqualTo("/pages/invite/accept?token=" + token);
        assertThat(card.get("title").asText()).isEqualTo("张先生邀请你使用「家里车位」");
        Instant expireAt = OffsetDateTime.parse(card.get("expireAt").asText()).toInstant();
        assertThat(expireAt)
                .isBetween(
                        before.plus(Duration.ofHours(2)).minusSeconds(1),
                        Instant.now().plus(Duration.ofHours(2)));

        // 库里只存哈希
        Map<String, Object> row = jdbc.sql(
                        "SELECT token_hash, inviter_id, period_type, duration_minutes, remark, status"
                                + " FROM share_invite WHERE id = ?")
                .param(card.get("id").asLong())
                .query()
                .singleRow();
        assertThat(row.get("token_hash")).isEqualTo(sha256(token));
        assertThat(((Number) row.get("inviter_id")).longValue()).isEqualTo(owner);
        assertThat(row.get("period_type")).isEqualTo("RELATIVE");
        assertThat(((Number) row.get("duration_minutes")).intValue()).isEqualTo(7 * 24 * 60);
        assertThat(row.get("remark")).isEqualTo("爸爸");
        assertThat(row.get("status")).isEqualTo("PENDING");

        // 设备没有名称时用 SN
        long other = device(owner, null);
        JsonNode unnamed = data(as(
                owner,
                post("/api/app/devices/{id}/invites", other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(permanent()))));
        assertThat(unnamed.get("title").asText()).isEqualTo("张先生邀请你使用「车位 " + sn + "」");
    }

    @Test
    void onlyTheOwnerOfAnEnabledDeviceCanInvite() throws Exception {
        long member = user("小张");
        member(member, "ACTIVE", null);
        expectError(createAs(member, permanent()), ErrorCode.NOT_OWNER);
        expectError(createAs(user("路人"), permanent()), ErrorCode.NOT_FOUND);
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        expectError(createAs(owner, permanent()), ErrorCode.DEVICE_DISABLED);
        expectError(
                mvc.perform(post("/api/app/devices/{id}/invites", device)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                ErrorCode.UNAUTHORIZED);
    }

    @Test
    void periodAndRemarkAreValidated() throws Exception {
        Instant now = Instant.now();
        expectError(createAs(owner, Map.of()), ErrorCode.INVALID_PERIOD);
        expectError(createAs(owner, Map.of("period", Map.of("type", "FOREVER"))), ErrorCode.INVALID_PERIOD);
        expectError(createAs(owner, relative(3)), ErrorCode.INVALID_PERIOD);
        expectError(createAs(owner, Map.of("period", Map.of("type", "RELATIVE"))), ErrorCode.INVALID_PERIOD);
        expectError(
                createAs(owner, Map.of("period", Map.of("type", "CUSTOM", "validFrom", at(now)))),
                ErrorCode.INVALID_PERIOD);
        expectError(createAs(owner, custom(now.plusSeconds(3600), now.plusSeconds(3600))), ErrorCode.INVALID_PERIOD);
        expectError(createAs(owner, custom(now.minusSeconds(7200), now.minusSeconds(60))), ErrorCode.INVALID_PERIOD);
        expectError(
                createAs(owner, Map.of("period", Map.of("type", "PERMANENT"), "remark", "长".repeat(33))),
                ErrorCode.BAD_REQUEST);

        // 自定义起止按分钟截断；空备注存 null
        Instant from = now.plus(Duration.ofHours(1)).plusSeconds(37);
        long id = invite(Map.of(
                        "period",
                        Map.of("type", "CUSTOM", "validFrom", at(from), "validTo", at(from.plus(Duration.ofDays(2)))),
                        "remark",
                        "  "))
                .get("id")
                .asLong();
        Map<String, Object> row = jdbc.sql("SELECT valid_from, valid_to, remark FROM share_invite WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
        assertThat(row.get("remark")).isNull();
        assertThat(DbTime.fromDb((java.time.LocalDateTime) row.get("valid_from")))
                .isEqualTo(from.truncatedTo(ChronoUnit.MINUTES));
    }

    @Test
    void quotaCountsPendingInvitesAndReleasesExpiredOnes() throws Exception {
        setting("member.max_per_device", "3");
        member(user("生效"), "ACTIVE", null);
        member(user("已过期但未处理"), "ACTIVE", Instant.now().minusSeconds(60));
        member(user("已撤销"), "REVOKED", null);
        long first = invite(permanent()).get("id").asLong();
        invite(relative(1));
        createAs(owner, permanent())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30010))
                .andExpect(jsonPath("$.message").value("成员名额已满（上限 3 人）"));

        // 邀请过期即释放名额，不等定时任务
        jdbc.sql("UPDATE share_invite SET expire_at = ? WHERE id = ?")
                .params(DbTime.toDb(Instant.now().minusSeconds(1)), first)
                .update();
        invite(permanent());
        // 撤回也释放
        long third = jdbc.sql("SELECT MAX(id) FROM share_invite WHERE device_id = ?")
                .param(device)
                .query(Long.class)
                .single();
        as(owner, delete("/api/app/devices/{d}/invites/{i}", device, third)).andExpect(status().isOk());
        invite(permanent());
    }

    @Test
    void concurrentInvitesNeverExceedTheQuota() throws Exception {
        setting("member.max_per_device", "3");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return createAs(owner, permanent()).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get(30, TimeUnit.SECONDS) == 200) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM share_invite WHERE device_id = ?")
                        .param(device)
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    // ------------------------------------------------------------ 接受

    @Test
    void acceptingRelativeInviteCreatesMemberFromNow() throws Exception {
        JsonNode card = invite(Map.of("period", Map.of("type", "RELATIVE", "durationDays", 7), "remark", "爸爸"));
        String token = card.get("token").asText();
        long dad = user("张大爷");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        JsonNode accepted = data(accept(dad, token));
        assertThat(accepted.get("id").asLong()).isEqualTo(device);
        assertThat(accepted.get("role").asText()).isEqualTo("MEMBER");
        Instant validTo = OffsetDateTime.parse(accepted.get("validTo").asText()).toInstant();
        assertThat(validTo)
                .isBetween(before.plus(Duration.ofDays(7)), Instant.now().plus(Duration.ofDays(7)));

        Map<String, Object> m = jdbc.sql("SELECT id, valid_from, valid_to, remark, status, granted_by, invite_id"
                        + " FROM device_member WHERE device_id = ? AND user_id = ?")
                .params(device, dad)
                .query()
                .singleRow();
        assertThat(m.get("remark")).isEqualTo("爸爸");
        assertThat(m.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) m.get("granted_by")).longValue()).isEqualTo(owner);
        assertThat(((Number) m.get("invite_id")).longValue())
                .isEqualTo(card.get("id").asLong());
        assertThat(DbTime.fromDb((java.time.LocalDateTime) m.get("valid_from"))).isAfterOrEqualTo(before);
        assertThat(jdbc.sql("SELECT CONCAT(status, ' ', accepted_by) FROM share_invite WHERE id = ?")
                        .param(card.get("id").asLong())
                        .query(String.class)
                        .single())
                .isEqualTo("ACCEPTED " + dad);
        assertThat(events.stream(MemberGrantedEvent.class).filter(e -> e.deviceId() == device))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.ownerId()).isEqualTo(owner);
                    assertThat(e.memberUserId()).isEqualTo(dad);
                });

        // 一次性：他人再接受 30013，本人再接受 30015
        expectError(accept(user("别人"), token), ErrorCode.INVITE_USED);
        expectError(accept(dad, token), ErrorCode.ALREADY_MEMBER);
        // 车位列表里出现
        as(dad, get("/api/app/devices"))
                .andExpect(jsonPath("$.data[?(@.id == " + device + ")].role")
                        .value(org.hamcrest.Matchers.contains("MEMBER")));
    }

    @Test
    void permanentAndFutureCustomInvites() throws Exception {
        long a = user("甲");
        data(accept(a, invite(permanent()).get("token").asText()));
        assertThat(jdbc.sql("SELECT valid_to IS NULL FROM device_member WHERE device_id = ? AND user_id = ?")
                        .params(device, a)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        // 自定义起始在将来：接受成功，返回卡片（尚未生效也能拿到）
        Instant from = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
        long b = user("乙");
        JsonNode card = data(accept(
                b,
                invite(custom(from, from.plus(Duration.ofDays(3)))).get("token").asText()));
        assertThat(OffsetDateTime.parse(card.get("validTo").asText()).toInstant())
                .isEqualTo(from.plus(Duration.ofDays(3)));
        assertThat(jdbc.sql("SELECT valid_from FROM device_member WHERE device_id = ? AND user_id = ?")
                        .params(device, b)
                        .query(java.time.LocalDateTime.class)
                        .single())
                .isEqualTo(DbTime.toDb(from));
    }

    @Test
    void acceptRechecksEverything() throws Exception {
        // 本用例发出的邀请较多，放宽名额，名额规则另有用例
        setting("member.max_per_device", "20");
        expectError(accept(user("x"), "not-a-token"), ErrorCode.NOT_FOUND);
        expectError(accept(user("x"), "A".repeat(43)), ErrorCode.NOT_FOUND);

        // 自己的邀请
        expectError(accept(owner, invite(permanent()).get("token").asText()), ErrorCode.CANNOT_ACCEPT_OWN);

        // 已撤回
        JsonNode cancelled = invite(permanent());
        as(
                        owner,
                        delete(
                                "/api/app/devices/{d}/invites/{i}",
                                device,
                                cancelled.get("id").asLong()))
                .andExpect(status().isOk());
        expectError(accept(user("x"), cancelled.get("token").asText()), ErrorCode.INVITE_CANCELLED);

        // 卡片过期
        JsonNode expired = invite(permanent());
        jdbc.sql("UPDATE share_invite SET expire_at = ? WHERE id = ?")
                .params(
                        DbTime.toDb(Instant.now().minusSeconds(1)),
                        expired.get("id").asLong())
                .update();
        expectError(accept(user("x"), expired.get("token").asText()), ErrorCode.INVITE_EXPIRED);

        // 自定义有效期已结束
        Instant now = Instant.now();
        JsonNode ended = invite(custom(now.minus(Duration.ofHours(2)), now.plus(Duration.ofMinutes(2))));
        jdbc.sql("UPDATE share_invite SET valid_to = ? WHERE id = ?")
                .params(DbTime.toDb(now.minusSeconds(1)), ended.get("id").asLong())
                .update();
        expectError(accept(user("x"), ended.get("token").asText()), ErrorCode.INVITE_EXPIRED);

        // 设备已禁用
        JsonNode disabled = invite(permanent());
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        expectError(accept(user("x"), disabled.get("token").asText()), ErrorCode.DEVICE_DISABLED);
        jdbc.sql("UPDATE device SET enabled = 1 WHERE id = ?").param(device).update();

        // 已是生效中的成员
        long already = user("老成员");
        member(already, "ACTIVE", null);
        expectError(accept(already, invite(permanent()).get("token").asText()), ErrorCode.ALREADY_MEMBER);

        // 旧授权已过期但还没被到期任务处理：先置为过期，再接受成功
        long lapsed = user("过期成员");
        member(lapsed, "ACTIVE", Instant.now().minusSeconds(60));
        data(accept(lapsed, invite(permanent()).get("token").asText()));
        assertThat(jdbc.sql("SELECT status FROM device_member WHERE device_id = ? AND user_id = ? ORDER BY id")
                        .params(device, lapsed)
                        .query(String.class)
                        .list())
                .containsExactly("EXPIRED", "ACTIVE");

        // 名额：上限被调低，本邀请不重复计数，但其他占用已满
        JsonNode late = invite(permanent());
        setting("member.max_per_device", "2");
        expectError(accept(user("x"), late.get("token").asText()), ErrorCode.MEMBER_LIMIT_REACHED);
        setting("member.max_per_device", "20");

        // 主人已变更（转让 / 解绑会作废邀请，这里直接改主人验证兜底）
        JsonNode stale = invite(permanent());
        long newOwner = user("新主人");
        jdbc.sql("UPDATE device SET owner_user_id = ? WHERE id = ?")
                .params(newOwner, device)
                .update();
        expectError(accept(user("x"), stale.get("token").asText()), ErrorCode.INVITE_CANCELLED);
    }

    @Test
    void quotaAtAcceptDoesNotCountTheInviteItself() throws Exception {
        setting("member.max_per_device", "1");
        String token = invite(permanent()).get("token").asText();
        data(accept(user("唯一"), token));
    }

    @Test
    void concurrentAcceptsOfOneInviteHaveOneWinner() throws Exception {
        String token = invite(permanent()).get("token").asText();
        int threads = 6;
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            users.add(user("u" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (long u : users) {
            results.add(pool.submit(() -> {
                start.await();
                return accept(u, token).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM device_member WHERE device_id = ?")
                        .param(device)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ 预览

    @Test
    void previewShowsDetailsAndState() throws Exception {
        Instant from = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);
        JsonNode card = invite(Map.of(
                "period",
                Map.of("type", "CUSTOM", "validFrom", at(from), "validTo", at(from.plus(Duration.ofDays(1)))),
                "remark",
                "妈妈"));
        String token = card.get("token").asText();
        long mom = user("妈妈");

        JsonNode p = data(preview(mom, token));
        assertThat(p.get("deviceName").asText()).isEqualTo("家里车位");
        assertThat(p.get("ownerNickname").asText()).isEqualTo("张先生");
        assertThat(p.get("remark").asText()).isEqualTo("妈妈");
        assertThat(p.at("/period/type").asText()).isEqualTo("CUSTOM");
        assertThat(OffsetDateTime.parse(p.at("/period/validFrom").asText()).toInstant())
                .isEqualTo(from);
        assertThat(p.at("/period").has("durationDays")).isFalse();
        assertThat(p.get("expireAt").asText()).endsWith("+08:00");
        assertThat(p.get("state").asText()).isEqualTo("VALID");

        assertThat(data(preview(owner, token)).get("state").asText()).isEqualTo("OWN_INVITE");
        data(accept(mom, token));
        assertThat(data(preview(mom, token)).get("state").asText()).isEqualTo("ALREADY_MEMBER");
        assertThat(data(preview(user("别人"), token)).get("state").asText()).isEqualTo("ACCEPTED");

        JsonNode relative = invite(relative(30));
        assertThat(data(preview(user("x"), relative.get("token").asText()))
                        .at("/period/durationDays")
                        .asInt())
                .isEqualTo(30);
        as(
                        owner,
                        delete(
                                "/api/app/devices/{d}/invites/{i}",
                                device,
                                relative.get("id").asLong()))
                .andExpect(status().isOk());
        assertThat(data(preview(user("x"), relative.get("token").asText()))
                        .get("state")
                        .asText())
                .isEqualTo("CANCELLED");

        JsonNode expired = invite(permanent());
        jdbc.sql("UPDATE share_invite SET expire_at = ? WHERE id = ?")
                .params(
                        DbTime.toDb(Instant.now().minusSeconds(1)),
                        expired.get("id").asLong())
                .update();
        assertThat(data(preview(user("x"), expired.get("token").asText()))
                        .get("state")
                        .asText())
                .isEqualTo("EXPIRED");

        // 已是成员的人打开新邀请
        assertThat(data(preview(mom, invite(permanent()).get("token").asText()))
                        .get("state")
                        .asText())
                .isEqualTo("ALREADY_MEMBER");

        // 旧授权已过期（到期任务还没处理）的人打开新邀请：可以接受
        long lapsed = user("过期成员");
        member(lapsed, "ACTIVE", Instant.now().minusSeconds(60));
        assertThat(data(preview(lapsed, invite(permanent()).get("token").asText()))
                        .get("state")
                        .asText())
                .isEqualTo("VALID");

        JsonNode stale = invite(permanent());
        jdbc.sql("UPDATE device SET owner_user_id = ? WHERE id = ?")
                .params(user("新主人"), device)
                .update();
        assertThat(data(preview(user("x"), stale.get("token").asText()))
                        .get("state")
                        .asText())
                .isEqualTo("CANCELLED");

        expectError(preview(user("x"), "B".repeat(43)), ErrorCode.NOT_FOUND);
        expectError(mvc.perform(get("/api/app/invites/{t}", token)), ErrorCode.UNAUTHORIZED);
    }

    // ------------------------------------------------------------ 撤回与过期

    @Test
    void cancelOnlyPendingInvitesOfThisDevice() throws Exception {
        JsonNode card = invite(permanent());
        long id = card.get("id").asLong();
        long member = user("成员");
        member(member, "ACTIVE", null);
        expectError(as(member, delete("/api/app/devices/{d}/invites/{i}", device, id)), ErrorCode.NOT_OWNER);
        // 设备禁用时主人仍可撤回
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        as(owner, delete("/api/app/devices/{d}/invites/{i}", device, id)).andExpect(status().isOk());
        assertThat(inviteStatus(id)).isEqualTo("CANCELLED");
        as(owner, delete("/api/app/devices/{d}/invites/{i}", device, id)).andExpect(status().isOk());
        jdbc.sql("UPDATE device SET enabled = 1 WHERE id = ?").param(device).update();

        JsonNode accepted = invite(permanent());
        data(accept(user("接受者"), accepted.get("token").asText()));
        expectError(
                as(
                        owner,
                        delete(
                                "/api/app/devices/{d}/invites/{i}",
                                device,
                                accepted.get("id").asLong())),
                ErrorCode.NOT_FOUND);

        // 别的设备的邀请
        long otherOwner = user("隔壁");
        long otherDevice = device(otherOwner, "隔壁车位");
        long foreign = data(as(
                        otherOwner,
                        post("/api/app/devices/{id}/invites", otherDevice)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(permanent()))))
                .get("id")
                .asLong();
        expectError(as(owner, delete("/api/app/devices/{d}/invites/{i}", device, foreign)), ErrorCode.NOT_FOUND);
        assertThat(inviteStatus(foreign)).isEqualTo("PENDING");
    }

    @Test
    void expiryJobMarksLapsedInvites() throws Exception {
        long lapsed = invite(permanent()).get("id").asLong();
        long fresh = invite(permanent()).get("id").asLong();
        jdbc.sql("UPDATE share_invite SET expire_at = ? WHERE id = ?")
                .params(DbTime.toDb(Instant.now().minusSeconds(1)), lapsed)
                .update();
        // 已过期但还没被定时任务标记：同样不能撤回
        expectError(as(owner, delete("/api/app/devices/{d}/invites/{i}", device, lapsed)), ErrorCode.NOT_FOUND);
        assertThat(inviteStatus(lapsed)).isEqualTo("PENDING");
        invites.expire();
        assertThat(inviteStatus(lapsed)).isEqualTo("EXPIRED");
        assertThat(inviteStatus(fresh)).isEqualTo("PENDING");
        // 已过期的不能撤回
        expectError(as(owner, delete("/api/app/devices/{d}/invites/{i}", device, lapsed)), ErrorCode.NOT_FOUND);
    }
}
