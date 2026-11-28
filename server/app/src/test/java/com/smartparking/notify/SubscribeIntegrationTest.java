package com.smartparking.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.iot.DbTime;
import com.smartparking.notify.message.MessageCreatedEvent;
import com.smartparking.notify.message.MessageStore;
import com.smartparking.notify.message.MessageType;
import com.smartparking.notify.subscribe.SubscribeProperties;
import com.smartparking.notify.subscribe.SubscribePusher;
import com.smartparking.user.session.SessionStore;
import com.smartparking.user.wechat.MockWeChatClient;
import com.smartparking.user.wechat.WeChatClient;
import com.smartparking.user.wechat.WeChatClient.SubscribeMessage;
import com.smartparking.user.wechat.WeChatException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** 微信订阅消息（BIZ-18）：站内消息写入后按配置的模板推送。微信接口为 mock，模板按测试配置。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.member.expire-interval=1h",
            "smartparking.invite.expire-interval=1h",
            "smartparking.transfer.expire-interval=1h",
            "smartparking.wechat.mode=mock",
            "smartparking.wechat.subscribe.enabled=true",
            "smartparking.wechat.subscribe.state=trial",
            "smartparking.wechat.subscribe.templates.GRANT.template-id=TPL-GRANT",
            "smartparking.wechat.subscribe.templates.GRANT.page=pages/messages/index",
            "smartparking.wechat.subscribe.templates.GRANT.data.thing1={device}",
            "smartparking.wechat.subscribe.templates.GRANT.data.thing2={title}",
            "smartparking.wechat.subscribe.templates.GRANT.data.time3={time}",
            "smartparking.wechat.subscribe.templates.REVOKE.template-id=TPL-REVOKE",
            "smartparking.wechat.subscribe.templates.REVOKE.data.thing1={content}",
            "smartparking.wechat.subscribe.templates.REVOKE.data.phrase2=授权已被撤销",
            "smartparking.wechat.subscribe.templates.FAULT.template-id= ",
            "smartparking.wechat.subscribe.templates.FAULT.data.thing1={title}"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SubscribeIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private MessageStore store;

    @Autowired
    private WeChatClient wechat;

    @Autowired
    private SubscribeProperties properties;

    private MockWeChatClient mock;
    private long owner;
    private long member;
    private long device;
    private String memberOpenid;

    @BeforeEach
    void setUp() {
        mock = (MockWeChatClient) wechat;
        mock.sent().clear();
        owner = user("it-" + UUID.randomUUID());
        memberOpenid = "it-" + UUID.randomUUID();
        member = user(memberOpenid);
        device = device("家里车位");
    }

    private long user(String openid) {
        jdbc.sql("INSERT IGNORE INTO app_user (openid, nickname) VALUES (?, '用户')")
                .param(openid)
                .update();
        return jdbc.sql("SELECT id FROM app_user WHERE openid = ?")
                .param(openid)
                .query(Long.class)
                .single();
    }

    private long device(String name) {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since,"
                                + " name) VALUES (?, ?, 'SPG-100', 'IT', 'code-hash', 'BOUND', ?, NOW(3) - INTERVAL 1 DAY, ?)")
                .params("SN" + imei, imei, owner, name)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private List<SubscribeMessage> sentTo(String openid) {
        return mock.sent().stream().filter(m -> m.openid().equals(openid)).toList();
    }

    /** 只实现发送订阅消息的微信客户端 */
    private static WeChatClient sender(java.util.function.Consumer<SubscribeMessage> send) {
        return new WeChatClient() {
            @Override
            public WeChatSession code2Session(String code) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String phoneNumber(String code) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void sendSubscribeMessage(SubscribeMessage message) {
                send.accept(message);
            }
        };
    }

    /** 同步执行的推送器：用于断言“没有发送” */
    private SubscribePusher direct(SubscribeProperties props, WeChatClient client) {
        return new SubscribePusher(props, client, jdbc, Runnable::run);
    }

    private MessageCreatedEvent event(long userId, MessageType type, Long deviceId) {
        return new MessageCreatedEvent(userId, type, deviceId, "标题", "内容", Instant.now());
    }

    // ------------------------------------------------------------ 推送

    @Test
    void messagesWithATemplateArePushedWithRenderedFields() {
        store.write(member, MessageType.GRANT, device, "你已获得「家里车位」的使用权限", "主人 张先生 授权你使用该车位，永久有效。");

        await().atMost(Duration.ofSeconds(5)).until(() -> sentTo(memberOpenid).size() == 1);
        SubscribeMessage m = sentTo(memberOpenid).get(0);
        assertThat(m.templateId()).isEqualTo("TPL-GRANT");
        assertThat(m.page()).isEqualTo("pages/messages/index");
        assertThat(m.state()).isEqualTo("trial");
        assertThat(m.data()).containsOnlyKeys("thing1", "thing2", "time3");
        assertThat(m.data().get("thing1")).isEqualTo("家里车位");
        assertThat(m.data().get("thing2")).isEqualTo("你已获得「家里车位」的使用权限");
        String createdAt = jdbc.sql("SELECT created_at FROM app_message WHERE user_id = ?")
                .param(member)
                .query(java.time.LocalDateTime.class)
                .single()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        assertThat(m.data().get("time3")).isEqualTo(createdAt);
    }

    @Test
    void revokeThroughTheApiPushesATruncatedNotice() throws Exception {
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, status, granted_by) VALUES"
                        + " (?, ?, NOW(3) - INTERVAL 1 DAY, 'ACTIVE', ?)")
                .params(device, member, owner)
                .update();
        long memberId = jdbc.sql("SELECT id FROM device_member WHERE device_id = ? AND user_id = ?")
                .params(device, member)
                .query(Long.class)
                .single();
        String token =
                sessions.create(owner, Duration.ofMinutes(5), Instant.now()).token();
        mvc.perform(delete("/api/app/devices/{d}/members/{m}", device, memberId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).until(() -> sentTo(memberOpenid).size() == 1);
        SubscribeMessage m = sentTo(memberOpenid).get(0);
        assertThat(m.templateId()).isEqualTo("TPL-REVOKE");
        assertThat(m.page()).isNull();
        String content = jdbc.sql("SELECT content FROM app_message WHERE user_id = ? AND type = 'REVOKE'")
                .param(member)
                .query(String.class)
                .single();
        assertThat(content.codePointCount(0, content.length())).isGreaterThan(20);
        assertThat(m.data().get("thing1")).hasSize(20).endsWith("…").startsWith(content.substring(0, 19));
        assertThat(m.data().get("phrase2")).isEqualTo("授权已被…");
    }

    @Test
    void deviceFallsBackToSnAndMissingDeviceIsEmpty() {
        long unnamed = device(null);
        String sn = jdbc.sql("SELECT sn FROM device WHERE id = ?")
                .param(unnamed)
                .query(String.class)
                .single();
        List<SubscribeMessage> sent = new ArrayList<>();
        SubscribePusher pusher = direct(properties, sender(sent::add));
        pusher.onMessage(event(member, MessageType.GRANT, unnamed));
        pusher.onMessage(event(member, MessageType.GRANT, null));
        pusher.onMessage(event(member, MessageType.GRANT, 9_000_000_000L));
        assertThat(sent).extracting(s -> s.data().get("thing1")).containsExactly(sn, "", "");
    }

    // ------------------------------------------------------------ 不发送

    @Test
    void nothingIsPushedWithoutTemplateSwitchOrActiveUser() {
        List<SubscribeMessage> sent = new ArrayList<>();
        SubscribePusher pusher = direct(properties, sender(sent::add));
        // 没有配置模板、模板 ID 为空
        pusher.onMessage(event(member, MessageType.TRANSFER, device));
        pusher.onMessage(event(member, MessageType.FAULT, device));
        // 用户不存在、已封禁
        pusher.onMessage(event(9_000_000_000L, MessageType.GRANT, device));
        jdbc.sql("UPDATE app_user SET status = 'BANNED' WHERE id = ?")
                .param(member)
                .update();
        pusher.onMessage(event(member, MessageType.GRANT, device));
        // 总开关关闭
        SubscribeProperties off = new SubscribeProperties(false, properties.state(), properties.templates());
        direct(off, sender(sent::add)).onMessage(event(owner, MessageType.GRANT, device));
        assertThat(sent).isEmpty();

        // 对照：同样的推送器，正常用户会发
        pusher.onMessage(event(owner, MessageType.GRANT, device));
        assertThat(sent).hasSize(1);
    }

    @Test
    void wechatFailuresAndFullQueueNeverReachTheCaller() {
        List<String> attempts = new ArrayList<>();
        for (RuntimeException failure : List.of(
                new WeChatException(43101, "未订阅"),
                new WeChatException(47003, "参数错误"),
                new IllegalStateException("意外"))) {
            direct(properties, sender(m -> {
                        attempts.add(m.templateId());
                        throw failure;
                    }))
                    .onMessage(event(member, MessageType.GRANT, device));
        }
        assertThat(attempts).hasSize(3);

        new SubscribePusher(properties, sender(m -> attempts.add("queued")), jdbc, r -> {
                    throw new RejectedExecutionException("full");
                })
                .onMessage(event(member, MessageType.GRANT, device));
        assertThat(attempts).hasSize(3);

        // 未订阅的真实路径：mock 客户端抛 43101，站内消息照常写入
        long unsubscribed = user("mock_unsubscribed");
        jdbc.sql("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?")
                .param(unsubscribed)
                .update();
        store.write(unsubscribed, MessageType.GRANT, device, "标题", "内容");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM app_message WHERE user_id = ? AND created_at >= ?")
                        .params(unsubscribed, DbTime.toDb(Instant.now().minusSeconds(60)))
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    @Test
    void fieldsAreCutByTemplateFieldType() {
        List<SubscribeMessage> sent = new ArrayList<>();
        var template = new SubscribeProperties.Template(
                "TPL",
                null,
                Map.of(
                        "thing9",
                        "一二三四五六七八九十一二三四五六七八九十",
                        "thing10",
                        "一二三四五六七八九十一二三四五六七八九十多",
                        "name1",
                        "一二三四五六七八九十多",
                        "character_string2",
                        "A".repeat(33),
                        "letter3",
                        "B".repeat(33),
                        "number4",
                        "1".repeat(33),
                        "symbol5",
                        "123456",
                        "time6",
                        "很长很长很长很长很长很长很长很长很长很长很长很长",
                        "thing7",
                        "😀".repeat(21),
                        "thing8",
                        "😀".repeat(20)));
        var props = new SubscribeProperties(true, "formal", Map.of(MessageType.SYSTEM, template));
        direct(props, sender(sent::add)).onMessage(event(member, MessageType.SYSTEM, null));
        Map<String, String> d = sent.get(0).data();
        assertThat(d.get("thing9")).isEqualTo("一二三四五六七八九十一二三四五六七八九十");
        assertThat(d.get("thing10")).isEqualTo("一二三四五六七八九十一二三四五六七八九…");
        assertThat(d.get("name1")).isEqualTo("一二三四五六七八九…");
        assertThat(d.get("character_string2")).isEqualTo("A".repeat(31) + "…");
        assertThat(d.get("letter3")).isEqualTo("B".repeat(31) + "…");
        assertThat(d.get("number4")).isEqualTo("1".repeat(31) + "…");
        assertThat(d.get("symbol5")).isEqualTo("1234…");
        assertThat(d.get("time6")).isEqualTo("很长很长很长很长很长很长很长很长很长很长很长很长");
        assertThat(d.get("thing7")).isEqualTo("😀".repeat(19) + "…");
        // 按字符数而不是 UTF-16 长度计：20 个 emoji 不截断
        assertThat(d.get("thing8")).isEqualTo("😀".repeat(20));
        assertThat(sent.get(0).state()).isEqualTo("formal");
    }
}
