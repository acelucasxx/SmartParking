package com.smartparking.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.DbTime;
import com.smartparking.member.members.MemberEndedEvent;
import com.smartparking.member.members.MemberService;
import com.smartparking.member.members.MemberUpdatedEvent;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 成员管理（BIZ-12）：列表与名额、修改、撤销、退出、到期。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.invite.expire-interval=1h",
            "smartparking.member.expire-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class MemberIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private MemberService members;

    @Autowired
    private ApplicationEvents events;

    private long owner;
    private long device;

    @BeforeEach
    void setUp() {
        setLimit("5");
        owner = user("张先生");
        device = device(owner);
    }

    @AfterEach
    void restore() {
        setLimit("5");
    }

    // ------------------------------------------------------------ 造数据

    private void setLimit(String value) {
        jdbc.sql("UPDATE system_config SET config_value = ? WHERE config_key = 'member.max_per_device'")
                .param(value)
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

    private long device(long ownerId) {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since,"
                                + " name) VALUES (?, ?, 'SPG-100', 'IT', 'x', 'BOUND', ?, NOW(3) - INTERVAL 30 DAY, '家里车位')")
                .params("SN" + imei, imei, ownerId)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    /** @return device_member.id */
    private long member(long deviceId, long userId, String status, Instant from, Instant to, String remark) {
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by, remark)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(deviceId, userId, DbTime.toDb(from), DbTime.toDb(to), status, owner, remark)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM device_member WHERE device_id = ? AND user_id = ?")
                .params(deviceId, userId)
                .query(Long.class)
                .single();
    }

    private long member(long userId, Instant to) {
        return member(device, userId, "ACTIVE", Instant.now().minus(Duration.ofDays(3)), to, null);
    }

    private void invite(String status, Instant expireAt) {
        jdbc.sql("INSERT INTO share_invite (token_hash, device_id, inviter_id, period_type, duration_minutes, remark,"
                        + " expire_at, status) VALUES (?, ?, ?, 'RELATIVE', 10080, '妈妈', ?, ?)")
                .params(UUID.randomUUID().toString(), device, owner, DbTime.toDb(expireAt), status)
                .update();
    }

    private ResultActions as(long userId, MockHttpServletRequestBuilder request) throws Exception {
        String token =
                sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions patchMember(long userId, long memberId, Object body) throws Exception {
        return as(
                userId,
                patch("/api/app/devices/{d}/members/{m}", device, memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body instanceof String s ? s : json.writeValueAsString(body)));
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

    private Map<String, Object> row(long memberId) {
        return jdbc.sql("SELECT status, valid_from, valid_to, ended_at, remark FROM device_member WHERE id = ?")
                .param(memberId)
                .query()
                .singleRow();
    }

    private static Instant instant(Object dbValue) {
        return DbTime.fromDb((LocalDateTime) dbValue);
    }

    private static String at(Instant instant) {
        return instant.atOffset(ZoneOffset.ofHours(8)).toString();
    }

    // ------------------------------------------------------------ 列表

    @Test
    void overviewListsActiveMembersAndPendingInvites() throws Exception {
        Instant now = Instant.now();
        long current = member(device, user(null), "ACTIVE", now.minus(Duration.ofDays(5)), null, "爸爸");
        long future =
                member(device, user("未开始"), "ACTIVE", now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)), null);
        member(device, user("到期未处理"), "ACTIVE", now.minus(Duration.ofDays(5)), now.minusSeconds(60), null);
        member(device, user("已撤销"), "REVOKED", now.minus(Duration.ofDays(5)), null, null);
        invite("PENDING", now.plus(Duration.ofHours(5)));
        invite("PENDING", now.minusSeconds(1));
        invite("CANCELLED", now.plus(Duration.ofHours(5)));
        setLimit("6");

        JsonNode o = data(as(owner, get("/api/app/devices/{d}/members", device)));
        assertThat(o.get("limit").asInt()).isEqualTo(6);
        assertThat(o.get("used").asInt()).isEqualTo(3);
        assertThat(o.get("members")).extracting(n -> n.get("id").asLong()).containsExactly(current, future);
        JsonNode first = o.get("members").get(0);
        assertThat(first.get("nickname").asText()).isEqualTo("微信用户");
        assertThat(first.get("remark").asText()).isEqualTo("爸爸");
        assertThat(first.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("validTo").isNull()).isTrue();
        assertThat(first.get("validFrom").asText()).endsWith("+08:00");
        assertThat(o.get("invites")).hasSize(1);
        JsonNode inv = o.get("invites").get(0);
        assertThat(inv.get("remark").asText()).isEqualTo("妈妈");
        assertThat(inv.at("/period/type").asText()).isEqualTo("RELATIVE");
        assertThat(inv.at("/period/durationDays").asInt()).isEqualTo(7);
        assertThat(inv.get("expireAt").asText()).endsWith("+08:00");
        assertThat(inv.get("createdAt").asText()).endsWith("+08:00");

        // 仅主人；设备禁用时也能看
        long m = user("成员");
        member(m, null);
        expectError(as(m, get("/api/app/devices/{d}/members", device)), ErrorCode.NOT_OWNER);
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        as(owner, get("/api/app/devices/{d}/members", device)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 修改

    @Test
    void updatePeriodTakesEffectImmediately() throws Exception {
        long dad = user("张大爷");
        Instant started = Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MILLIS);
        long id = member(device, dad, "ACTIVE", started, null, "爸爸");
        Instant before = Instant.now();

        // RELATIVE：从修改时起算，已开始的授权起点不变
        JsonNode m = data(patchMember(owner, id, Map.of("period", Map.of("type", "RELATIVE", "durationDays", 7))));
        Instant to = OffsetDateTime.parse(m.get("validTo").asText()).toInstant();
        assertThat(to)
                .isBetween(
                        before.plus(Duration.ofDays(7)).minusMillis(5),
                        Instant.now().plus(Duration.ofDays(7)));
        assertThat(OffsetDateTime.parse(m.get("validFrom").asText()).toInstant())
                .isEqualTo(started);
        assertThat(m.get("remark").asText()).isEqualTo("爸爸");

        // PERMANENT
        assertThat(data(patchMember(owner, id, Map.of("period", Map.of("type", "PERMANENT"))))
                        .get("validTo")
                        .isNull())
                .isTrue();

        // CUSTOM 起点在将来：被授权人立即失去访问，直到开始
        Instant from = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
        data(patchMember(
                owner,
                id,
                Map.of(
                        "period",
                        Map.of(
                                "type",
                                "CUSTOM",
                                "validFrom",
                                at(from),
                                "validTo",
                                at(from.plus(Duration.ofDays(1)))))));
        assertThat(instant(row(id).get("valid_from"))).isEqualTo(from);
        expectError(as(dad, get("/api/app/devices/{d}", device)), ErrorCode.ACCESS_EXPIRED);

        // 尚未开始的授权改为 RELATIVE：同时改为立即开始
        Instant beforeRelative = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        data(patchMember(owner, id, Map.of("period", Map.of("type", "RELATIVE", "durationDays", 1))));
        assertThat(instant(row(id).get("valid_from")))
                .isAfterOrEqualTo(beforeRelative)
                .isBefore(from);
        as(dad, get("/api/app/devices/{d}", device)).andExpect(status().isOk());

        assertThat(events.stream(MemberUpdatedEvent.class).filter(e -> e.memberId() == id))
                .hasSize(4)
                .allSatisfy(e -> {
                    assertThat(e.periodChanged()).isTrue();
                    assertThat(e.userId()).isEqualTo(dad);
                });
    }

    @Test
    void updateRemarkOnlyAndValidation() throws Exception {
        long id = member(device, user("甲"), "ACTIVE", Instant.now().minus(Duration.ofDays(1)), null, "旧备注");
        JsonNode m = data(patchMember(owner, id, Map.of("remark", " 女儿 ")));
        assertThat(m.get("remark").asText()).isEqualTo("女儿");
        assertThat(m.get("validTo").isNull()).isTrue();
        Map<String, Object> clear = new HashMap<>();
        clear.put("remark", null);
        assertThat(data(patchMember(owner, id, clear)).get("remark").isNull()).isTrue();
        data(patchMember(owner, id, Map.of("remark", "x")));
        assertThat(data(patchMember(owner, id, Map.of("remark", "")))
                        .get("remark")
                        .isNull())
                .isTrue();
        assertThat(events.stream(MemberUpdatedEvent.class).filter(e -> e.memberId() == id))
                .allSatisfy(e -> assertThat(e.periodChanged()).isFalse());

        expectError(patchMember(owner, id, "{}"), ErrorCode.BAD_REQUEST);
        expectError(patchMember(owner, id, Map.of("remark", "x", "status", "QUIT")), ErrorCode.BAD_REQUEST);
        assertThat(row(id).get("remark")).isNull();
        expectError(patchMember(owner, id, Map.of("remark", 5)), ErrorCode.BAD_REQUEST);
        expectError(patchMember(owner, id, Map.of("remark", "长".repeat(33))), ErrorCode.BAD_REQUEST);
        expectError(
                patchMember(owner, id, Map.of("period", Map.of("type", "RELATIVE", "durationDays", 2))),
                ErrorCode.INVALID_PERIOD);
        expectError(patchMember(owner, id, Map.of("period", "PERMANENT")), ErrorCode.INVALID_PERIOD);
        Instant now = Instant.now();
        expectError(
                patchMember(
                        owner,
                        id,
                        Map.of(
                                "period",
                                Map.of(
                                        "type",
                                        "CUSTOM",
                                        "validFrom",
                                        at(now.minusSeconds(7200)),
                                        "validTo",
                                        at(now.minusSeconds(60))))),
                ErrorCode.INVALID_PERIOD);

        // 被授权人不能改；设备禁用 20002
        long other = user("乙");
        member(other, null);
        expectError(patchMember(other, id, Map.of("remark", "x")), ErrorCode.NOT_OWNER);
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        expectError(patchMember(owner, id, Map.of("remark", "x")), ErrorCode.DEVICE_DISABLED);
        jdbc.sql("UPDATE device SET enabled = 1 WHERE id = ?").param(device).update();

        // 已结束、已到期、别的设备的授权 10004
        long revoked = member(device, user("丙"), "REVOKED", now.minus(Duration.ofDays(1)), null, null);
        expectError(patchMember(owner, revoked, Map.of("remark", "x")), ErrorCode.NOT_FOUND);
        long lapsed = member(device, user("丁"), "ACTIVE", now.minus(Duration.ofDays(2)), now.minusSeconds(1), null);
        expectError(patchMember(owner, lapsed, Map.of("remark", "x")), ErrorCode.NOT_FOUND);
        long foreign = member(device(user("隔壁")), user("戊"), "ACTIVE", now.minus(Duration.ofDays(1)), null, null);
        expectError(patchMember(owner, foreign, Map.of("remark", "x")), ErrorCode.NOT_FOUND);
        assertThat(row(foreign).get("remark")).isNull();
    }

    // ------------------------------------------------------------ 撤销与退出

    @Test
    void revokeEndsAccessAndReleasesQuota() throws Exception {
        long dad = user("张大爷");
        long id = member(dad, null);
        as(dad, get("/api/app/devices/{d}", device)).andExpect(status().isOk());
        assertThat(data(as(owner, get("/api/app/devices/{d}/members", device)))
                        .get("used")
                        .asInt())
                .isEqualTo(1);

        // 被授权人不能撤销别人；设备禁用时主人仍可撤销
        expectError(as(dad, delete("/api/app/devices/{d}/members/{m}", device, id)), ErrorCode.NOT_OWNER);
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        as(owner, delete("/api/app/devices/{d}/members/{m}", device, id)).andExpect(status().isOk());
        Map<String, Object> r = row(id);
        assertThat(r.get("status")).isEqualTo("REVOKED");
        assertThat(r.get("ended_at")).isNotNull();
        expectError(as(dad, get("/api/app/devices/{d}", device)), ErrorCode.ACCESS_EXPIRED);
        assertThat(data(as(owner, get("/api/app/devices/{d}/members", device)))
                        .get("used")
                        .asInt())
                .isZero();
        assertThat(events.stream(MemberEndedEvent.class).filter(e -> e.memberId() == id))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.reason()).isEqualTo(MemberEndedEvent.Reason.REVOKED);
                    assertThat(e.userId()).isEqualTo(dad);
                    assertThat(e.ownerId()).isEqualTo(owner);
                });

        // 再撤销视为成功；已退出、已到期、别的设备的 10004
        as(owner, delete("/api/app/devices/{d}/members/{m}", device, id)).andExpect(status().isOk());
        assertThat(events.stream(MemberEndedEvent.class).filter(e -> e.memberId() == id))
                .hasSize(1);
        Instant now = Instant.now();
        long quit = member(device, user("退出的"), "QUIT", now.minus(Duration.ofDays(1)), null, null);
        expectError(as(owner, delete("/api/app/devices/{d}/members/{m}", device, quit)), ErrorCode.NOT_FOUND);
        long lapsed = member(device, user("到期的"), "ACTIVE", now.minus(Duration.ofDays(2)), now.minusSeconds(1), null);
        expectError(as(owner, delete("/api/app/devices/{d}/members/{m}", device, lapsed)), ErrorCode.NOT_FOUND);
        long foreign = member(device(user("隔壁")), user("戊"), "ACTIVE", now.minus(Duration.ofDays(1)), null, null);
        expectError(as(owner, delete("/api/app/devices/{d}/members/{m}", device, foreign)), ErrorCode.NOT_FOUND);
        assertThat(row(foreign).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void memberCanQuitButOwnerCannot() throws Exception {
        long mom = user("妈妈");
        long id = member(mom, null);
        as(mom, delete("/api/app/devices/{d}/membership", device)).andExpect(status().isOk());
        assertThat(row(id).get("status")).isEqualTo("QUIT");
        assertThat(row(id).get("ended_at")).isNotNull();
        expectError(as(mom, get("/api/app/devices/{d}", device)), ErrorCode.ACCESS_EXPIRED);
        // 再次退出：已不在有效期内
        expectError(as(mom, delete("/api/app/devices/{d}/membership", device)), ErrorCode.ACCESS_EXPIRED);
        assertThat(events.stream(MemberEndedEvent.class).filter(e -> e.memberId() == id))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.reason()).isEqualTo(MemberEndedEvent.Reason.QUIT);
                    assertThat(e.ownerId()).isEqualTo(owner);
                });

        expectError(as(owner, delete("/api/app/devices/{d}/membership", device)), ErrorCode.BAD_REQUEST);
        expectError(as(user("路人"), delete("/api/app/devices/{d}/membership", device)), ErrorCode.NOT_FOUND);
        // 设备禁用时也能退出
        long dad = user("爸爸");
        long dadId = member(dad, null);
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        as(dad, delete("/api/app/devices/{d}/membership", device)).andExpect(status().isOk());
        assertThat(row(dadId).get("status")).isEqualTo("QUIT");
    }

    // ------------------------------------------------------------ 到期

    @Test
    void expiryJobEndsLapsedMembersOnce() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long lapsed = member(user("到期"), now.minus(Duration.ofMinutes(5)));
        long future = member(user("未到期"), now.plus(Duration.ofDays(1)));
        long permanent = member(user("永久"), null);
        long revoked =
                member(device, user("已撤销"), "REVOKED", now.minus(Duration.ofDays(3)), now.minusSeconds(60), null);

        assertThat(members.expire()).isGreaterThanOrEqualTo(1);
        Map<String, Object> r = row(lapsed);
        assertThat(r.get("status")).isEqualTo("EXPIRED");
        // ended_at 记到期时刻，而不是处理时刻
        assertThat(instant(r.get("ended_at"))).isEqualTo(now.minus(Duration.ofMinutes(5)));
        assertThat(row(future).get("status")).isEqualTo("ACTIVE");
        assertThat(row(permanent).get("status")).isEqualTo("ACTIVE");
        assertThat(row(revoked).get("status")).isEqualTo("REVOKED");
        assertThat(events.stream(MemberEndedEvent.class).filter(e -> e.memberId() == lapsed))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.reason()).isEqualTo(MemberEndedEvent.Reason.EXPIRED);
                    assertThat(e.ownerId()).isEqualTo(owner);
                    assertThat(e.deviceId()).isEqualTo(device);
                });

        members.expire();
        assertThat(events.stream(MemberEndedEvent.class).filter(e -> e.memberId() == lapsed))
                .hasSize(1);
    }
}
