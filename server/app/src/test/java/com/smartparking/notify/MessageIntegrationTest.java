package com.smartparking.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.iot.DbTime;
import com.smartparking.member.invite.MemberGrantedEvent;
import com.smartparking.member.members.MemberEndedEvent;
import com.smartparking.member.members.MemberUpdatedEvent;
import com.smartparking.notify.alarm.AlarmLevel;
import com.smartparking.notify.alarm.AlarmRaisedEvent;
import com.smartparking.notify.alarm.AlarmType;
import com.smartparking.ownership.transfer.DeviceTransferredEvent;
import com.smartparking.ownership.unbind.DeviceUnboundEvent;
import com.smartparking.ownership.unbind.OwnershipRelease;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 站内消息（BIZ-15）：各写入点、列表、未读数、已读。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.member.expire-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MessageIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private ApplicationEventPublisher publisher;

    private long owner;
    private long member;
    private long device;
    private long memberRow;
    private String sn;

    @BeforeEach
    void setUp() {
        owner = user("张先生");
        member = user("张大爷");
        device = device(owner, "家里车位");
        memberRow = member(member, "爸爸", null);
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

    private long member(long userId, String remark, Instant validTo) {
        jdbc.sql(
                        "INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by, remark) VALUES"
                                + " (?, ?, NOW(3) - INTERVAL 1 HOUR, ?, 'ACTIVE', ?, ?)")
                .params(device, userId, DbTime.toDb(validTo), owner, remark)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM device_member WHERE device_id = ? AND user_id = ?")
                .params(device, userId)
                .query(Long.class)
                .single();
    }

    private record Msg(String type, Long deviceId, String title, String content) {}

    private List<Msg> messages(long userId) {
        return jdbc.sql("SELECT type, device_id, title, content FROM app_message WHERE user_id = ? ORDER BY id")
                .param(userId)
                .query((rs, n) -> new Msg(
                        rs.getString("type"),
                        rs.getObject("device_id", Long.class),
                        rs.getString("title"),
                        rs.getString("content")))
                .list();
    }

    private ResultActions as(long userId, MockHttpServletRequestBuilder request) throws Exception {
        String token =
                sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    // ------------------------------------------------------------ 写入点

    @Test
    void grantNotifiesMemberAndOwner() {
        Instant to = Instant.parse("2026-12-31T10:00:00Z");
        jdbc.sql("UPDATE device_member SET valid_to = ? WHERE id = ?")
                .params(DbTime.toDb(to), memberRow)
                .update();
        publisher.publishEvent(new MemberGrantedEvent(device, owner, member, memberRow, 1L, Instant.now()));

        assertThat(messages(member)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("GRANT");
            assertThat(m.deviceId()).isEqualTo(device);
            assertThat(m.title()).isEqualTo("你已获得「家里车位」的使用权限");
            assertThat(m.content()).isEqualTo("张先生邀请你使用该车位，有效期至 2026-12-31 18:00。");
        });
        assertThat(messages(owner)).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("爸爸已接受邀请");
            assertThat(m.content()).contains("「家里车位」");
        });
    }

    @Test
    void grantForFutureOrPermanentPeriod() {
        Instant from = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
        jdbc.sql("UPDATE device_member SET valid_from = ?, valid_to = NULL WHERE id = ?")
                .params(DbTime.toDb(from), memberRow)
                .update();
        publisher.publishEvent(new MemberGrantedEvent(device, owner, member, memberRow, 1L, Instant.now()));
        assertThat(messages(member).get(0).content())
                .startsWith("张先生邀请你使用该车位，自 ")
                .endsWith(" 起永久有效。");
    }

    @Test
    void periodChangeNotifiesMemberButRemarkOnlyDoesNot() {
        Instant now = Instant.now();
        publisher.publishEvent(new MemberUpdatedEvent(device, memberRow, member, false, now, null, now));
        assertThat(messages(member)).isEmpty();
        publisher.publishEvent(
                new MemberUpdatedEvent(device, memberRow, member, true, now.minusSeconds(60), null, now));
        assertThat(messages(member)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("GRANT");
            assertThat(m.title()).isEqualTo("「家里车位」的使用期限已调整");
            assertThat(m.content()).isEqualTo("主人已将你的使用期限调整为永久有效。");
        });
    }

    @Test
    void revokeExpireAndQuit() throws Exception {
        // 端到端：主人在接口上撤销
        as(owner, delete("/api/app/devices/{d}/members/{m}", device, memberRow)).andExpect(status().isOk());
        assertThat(messages(member)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("REVOKE");
            assertThat(m.title()).isEqualTo("你在「家里车位」的使用权限已被撤销");
        });

        long mom = user("妈妈");
        long momRow = member(mom, null, null);
        publisher.publishEvent(
                new MemberEndedEvent(device, momRow, mom, owner, MemberEndedEvent.Reason.EXPIRED, Instant.now()));
        assertThat(messages(mom)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("EXPIRE");
            assertThat(m.title()).isEqualTo("你在「家里车位」的使用权限已到期");
        });

        // 退出：通知主人，名称备注优先、没有备注用昵称
        long kid = user("小张");
        long kidRow = member(kid, null, null);
        as(kid, delete("/api/app/devices/{d}/membership", device)).andExpect(status().isOk());
        assertThat(messages(owner)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("REVOKE");
            assertThat(m.title()).isEqualTo("小张已退出「家里车位」的授权");
        });
        assertThat(messages(kid)).isEmpty();
        assertThat(kidRow).isPositive();
    }

    @Test
    void transferNotifiesAllParties() {
        long buyer = user("李女士");
        long mom = user("妈妈");
        publisher.publishEvent(
                new DeviceTransferredEvent(device, owner, buyer, List.of(member, mom, buyer), Instant.now()));
        assertThat(messages(owner)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("TRANSFER");
            assertThat(m.title()).isEqualTo("「家里车位」已转让");
            assertThat(m.content()).contains("李女士");
        });
        assertThat(messages(buyer)).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("你已成为「家里车位」的主人");
            assertThat(m.content()).startsWith("张先生已将该设备转让给你");
        });
        assertThat(messages(member)).singleElement().extracting(Msg::content).isEqualTo("该车位已转让给新主人，你的使用权限已失效。");
        assertThat(messages(mom)).hasSize(1);
    }

    @Test
    void unbindUsesTheNameBeforeItWasCleared() throws Exception {
        long mom = user("妈妈");
        member(mom, null, null);
        // 端到端：主人解绑，名称随后被清空，消息里仍是原名称
        as(owner, post("/api/app/devices/{d}/unbind", device)).andExpect(status().isOk());
        assertThat(messages(owner)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("UNBIND");
            assertThat(m.title()).isEqualTo("「家里车位」已解绑");
            assertThat(m.content()).startsWith("你已解绑该设备");
        });
        assertThat(messages(member)).singleElement().extracting(Msg::title).isEqualTo("「家里车位」已解绑");
        assertThat(messages(mom)).hasSize(1);

        long other = user("王先生");
        long otherDevice = device(other, null);
        publisher.publishEvent(new DeviceUnboundEvent(
                otherDevice, other, List.of(), OwnershipRelease.Type.FORCE_UNBIND, "车位 " + sn, Instant.now()));
        assertThat(messages(other)).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("「车位 " + sn + "」已解绑");
            assertThat(m.content()).startsWith("客服已解除你与该设备的绑定");
        });
    }

    @Test
    void faultNotifiesOwnerOnlyForNewSeriousAlarms() {
        Instant now = Instant.now();
        publisher.publishEvent(
                new AlarmRaisedEvent(1L, device, AlarmType.MOTOR_FAULT, AlarmLevel.CRITICAL, false, now));
        publisher.publishEvent(new AlarmRaisedEvent(1L, device, AlarmType.MOTOR_FAULT, AlarmLevel.CRITICAL, true, now));
        publisher.publishEvent(new AlarmRaisedEvent(2L, device, AlarmType.OBSTRUCTED, AlarmLevel.WARNING, false, now));
        publisher.publishEvent(new AlarmRaisedEvent(3L, device, AlarmType.CMD_TIMEOUT, AlarmLevel.WARNING, false, now));
        publisher.publishEvent(
                new AlarmRaisedEvent(4L, device, AlarmType.OFFLINE_24H, AlarmLevel.CRITICAL, false, now));
        assertThat(messages(owner))
                .extracting(Msg::type, Msg::title)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FAULT", "「家里车位」电机故障"),
                        org.assertj.core.groups.Tuple.tuple("FAULT", "「家里车位」已离线超过 24 小时"));
        assertThat(messages(member)).isEmpty();

        // 未绑定的设备没有人可通知：即使残留 owner_user_id 也不认主人
        jdbc.sql("UPDATE device SET bind_status = 'PENDING' WHERE id = ?")
                .param(device)
                .update();
        publisher.publishEvent(
                new AlarmRaisedEvent(5L, device, AlarmType.MOTOR_FAULT, AlarmLevel.CRITICAL, false, now));
        assertThat(messages(owner)).hasSize(2);
        // 也没有写给任何人
        assertThat(jdbc.sql("SELECT COUNT(*) FROM app_message WHERE device_id = ? AND type = 'FAULT'")
                        .param(device)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    // ------------------------------------------------------------ 接口

    private long seed(long userId, String title, LocalDateTime at) {
        jdbc.sql(
                        "INSERT INTO app_message (user_id, type, device_id, title, content, created_at) VALUES (?, 'SYSTEM', NULL, ?,"
                                + " '内容', ?)")
                .params(userId, title, at)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM app_message WHERE user_id = ?")
                .param(userId)
                .query(Long.class)
                .single();
    }

    @Test
    void listUnreadCountAndMarkRead() throws Exception {
        long me = user("我");
        long other = user("别人");
        LocalDateTime base = DbTime.toDb(Instant.now().minus(Duration.ofHours(1)));
        long m1 = seed(me, "一", base);
        long m2 = seed(me, "二", base.plusMinutes(1));
        long m3 = seed(me, "三", base.plusMinutes(2));
        long theirs = seed(other, "别人的", base);

        JsonNode page = data(as(me, get("/api/app/messages")));
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("items")).extracting(n -> n.get("id").asLong()).containsExactly(m3, m2, m1);
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("type").asText()).isEqualTo("SYSTEM");
        assertThat(first.get("read").asBoolean()).isFalse();
        assertThat(first.get("deviceId").isNull()).isTrue();
        assertThat(first.get("createdAt").asText()).endsWith("+08:00");
        assertThat(data(as(me, get("/api/app/messages/unread-count")))
                        .get("count")
                        .asInt())
                .isEqualTo(3);
        assertThat(data(as(me, get("/api/app/messages").param("size", "1").param("page", "2")))
                        .get("items")
                        .get(0)
                        .get("id")
                        .asLong())
                .isEqualTo(m2);

        // 指定 ids：只处理本人的，他人的静默忽略
        as(
                        me,
                        post("/api/app/messages/read")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("ids", List.of(m1, theirs)))))
                .andExpect(status().isOk());
        assertThat(data(as(me, get("/api/app/messages/unread-count")))
                        .get("count")
                        .asInt())
                .isEqualTo(2);
        assertThat(data(as(other, get("/api/app/messages/unread-count")))
                        .get("count")
                        .asInt())
                .isEqualTo(1);
        assertThat(data(as(me, get("/api/app/messages")))
                        .get("items")
                        .get(2)
                        .get("read")
                        .asBoolean())
                .isTrue();

        as(
                        me,
                        post("/api/app/messages/read")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"all\": true}"))
                .andExpect(status().isOk());
        assertThat(data(as(me, get("/api/app/messages/unread-count")))
                        .get("count")
                        .asInt())
                .isZero();
        assertThat(data(as(other, get("/api/app/messages/unread-count")))
                        .get("count")
                        .asInt())
                .isEqualTo(1);

        as(
                        me,
                        post("/api/app/messages/read")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(jsonPath("$.code").value(10001));
        as(
                        me,
                        post("/api/app/messages/read")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\": []}"))
                .andExpect(jsonPath("$.code").value(10001));
        as(
                        me,
                        post("/api/app/messages/read")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "ids",
                                        java.util.stream.LongStream.rangeClosed(1, 101)
                                                .boxed()
                                                .toList()))))
                .andExpect(jsonPath("$.code").value(10001));
        as(
                        me,
                        post("/api/app/messages/read")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\": [0]}"))
                .andExpect(jsonPath("$.code").value(10001));
        mvc.perform(get("/api/app/messages")).andExpect(status().isUnauthorized());
    }
}
