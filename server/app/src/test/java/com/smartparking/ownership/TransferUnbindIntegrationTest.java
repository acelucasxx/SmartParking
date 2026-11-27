package com.smartparking.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.iot.DbTime;
import com.smartparking.ownership.transfer.DeviceTransferredEvent;
import com.smartparking.ownership.transfer.TransferService;
import com.smartparking.ownership.unbind.DeviceUnboundEvent;
import com.smartparking.ownership.unbind.OwnershipRelease;
import com.smartparking.ownership.unbind.UnbindService;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 转让与主人解绑（BIZ-13）。MySQL / Redis 为真实容器。 */
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
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class TransferUnbindIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private TransferService transfers;

    @Autowired
    private UnbindService unbind;

    @Autowired
    private ApplicationEvents events;

    private long owner;
    private long device;
    private String sn;

    @BeforeEach
    void setUp() {
        owner = user("张先生");
        device = device(owner);
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

    private long device(long ownerId) {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        sn = "SN" + imei;
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since,"
                                + " name, space_no) VALUES (?, ?, 'SPG-100', 'IT', 'code-hash', 'BOUND', ?, NOW(3) - INTERVAL 30 DAY,"
                                + " '家里车位', 'B2-108')")
                .params(sn, imei, ownerId)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private void member(long userId) {
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, status, granted_by) VALUES"
                        + " (?, ?, NOW(3) - INTERVAL 1 DAY, 'ACTIVE', ?)")
                .params(device, userId, owner)
                .update();
    }

    private void pendingInvite() {
        jdbc.sql("INSERT INTO share_invite (token_hash, device_id, inviter_id, period_type, expire_at) VALUES"
                        + " (?, ?, ?, 'PERMANENT', NOW(3) + INTERVAL 1 DAY)")
                .params(UUID.randomUUID().toString(), device, owner)
                .update();
    }

    private void command(long userId, Instant at) {
        jdbc.sql(
                        "INSERT INTO control_command (device_id, user_id, role, channel, action, status, issued_at, finished_at)"
                                + " VALUES (?, ?, 'OWNER', 'REMOTE', 'OPEN', 'SUCCESS', ?, ?)")
                .params(device, userId, DbTime.toDb(at), DbTime.toDb(at.plusSeconds(1)))
                .update();
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

    private JsonNode create() throws Exception {
        return data(as(owner, post("/api/app/devices/{d}/transfer", device)));
    }

    private ResultActions accept(long userId, String token) throws Exception {
        return as(userId, post("/api/app/transfers/{t}/accept", token));
    }

    private String preview(long userId, String token) throws Exception {
        return data(as(userId, get("/api/app/transfers/{t}", token)))
                .get("state")
                .asText();
    }

    private static void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    private String deviceValue(String column) {
        return jdbc.sql("SELECT " + column + " FROM device WHERE id = ?")
                .param(device)
                .query(String.class)
                .list()
                .get(0);
    }

    private List<String> memberStatuses() {
        return jdbc.sql("SELECT status FROM device_member WHERE device_id = ? ORDER BY id")
                .param(device)
                .query(String.class)
                .list();
    }

    private String transferStatus(long id) {
        return jdbc.sql("SELECT status FROM device_transfer WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    // ------------------------------------------------------------ 发起、查看、取消

    @Test
    void ownerCreatesOneTransferAtATime() throws Exception {
        as(owner, get("/api/app/devices/{d}/transfer", device))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        JsonNode card = create();
        String token = card.get("token").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(card.get("path").asText()).isEqualTo("/pages/transfer/accept?token=" + token);
        assertThat(card.get("title").asText()).isEqualTo("张先生向你转让「家里车位」");
        assertThat(jdbc.sql("SELECT token_hash FROM device_transfer WHERE id = ?")
                        .param(card.get("id").asLong())
                        .query(String.class)
                        .single())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(token.getBytes(StandardCharsets.US_ASCII))));

        JsonNode pending = data(as(owner, get("/api/app/devices/{d}/transfer", device)));
        assertThat(pending.get("id").asLong()).isEqualTo(card.get("id").asLong());
        assertThat(pending.get("expireAt").asText()).endsWith("+08:00");
        expectError(as(owner, post("/api/app/devices/{d}/transfer", device)), ErrorCode.TRANSFER_PENDING_EXISTS);

        // 发起不影响现有被授权人
        long m = user("成员");
        member(m);
        as(m, get("/api/app/devices/{d}", device)).andExpect(status().isOk());

        // 过期而未被标记的卡片不挡住新的转让
        jdbc.sql("UPDATE device_transfer SET expire_at = ? WHERE id = ?")
                .params(
                        DbTime.toDb(Instant.now().minusSeconds(1)),
                        card.get("id").asLong())
                .update();
        as(owner, get("/api/app/devices/{d}/transfer", device))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        long second = create().get("id").asLong();
        assertThat(transferStatus(card.get("id").asLong())).isEqualTo("EXPIRED");

        // 取消
        expectError(as(m, delete("/api/app/devices/{d}/transfer", device)), ErrorCode.NOT_OWNER);
        as(owner, delete("/api/app/devices/{d}/transfer", device)).andExpect(status().isOk());
        assertThat(transferStatus(second)).isEqualTo("CANCELLED");
        expectError(as(owner, delete("/api/app/devices/{d}/transfer", device)), ErrorCode.NOT_FOUND);

        // 被授权人不能发起；设备禁用时不能发起
        expectError(as(m, post("/api/app/devices/{d}/transfer", device)), ErrorCode.NOT_OWNER);
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        expectError(as(owner, post("/api/app/devices/{d}/transfer", device)), ErrorCode.DEVICE_DISABLED);
    }

    @Test
    void concurrentCreatesYieldOneCard() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return as(owner, post("/api/app/devices/{d}/transfer", device))
                        .andReturn()
                        .getResponse()
                        .getStatus();
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
    }

    // ------------------------------------------------------------ 接收

    @Test
    void acceptTransfersOwnershipAndClearsDependents() throws Exception {
        long buyer = user("李女士");
        long dad = user("张大爷");
        member(dad);
        member(buyer);
        pendingInvite();
        command(owner, Instant.now().minus(Duration.ofHours(1)));
        JsonNode card = create();
        Instant before = Instant.now().minusMillis(5);

        JsonNode accepted = data(accept(buyer, card.get("token").asText()));
        assertThat(accepted.get("id").asLong()).isEqualTo(device);
        assertThat(accepted.get("role").asText()).isEqualTo("OWNER");

        assertThat(Long.parseLong(deviceValue("owner_user_id"))).isEqualTo(buyer);
        assertThat(deviceValue("bind_status")).isEqualTo("BOUND");
        assertThat(DbTime.fromDb(LocalDateTime.parse(deviceValue("owner_since").replace(' ', 'T'))))
                .isAfter(before);
        // 名称与车位号保留，激活码不变
        assertThat(deviceValue("name")).isEqualTo("家里车位");
        assertThat(deviceValue("space_no")).isEqualTo("B2-108");
        assertThat(deviceValue("activate_code_hash")).isEqualTo("code-hash");
        // 全部被授权人（含接收人原来的授权）撤销，待接受邀请作废
        assertThat(memberStatuses()).containsExactly("REVOKED", "REVOKED");
        assertThat(jdbc.sql("SELECT status FROM share_invite WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("VOIDED");
        assertThat(jdbc.sql("SELECT CONCAT(status, ' ', to_user) FROM device_transfer WHERE id = ?")
                        .param(card.get("id").asLong())
                        .query(String.class)
                        .single())
                .isEqualTo("ACCEPTED " + buyer);
        assertThat(jdbc.sql("SELECT CONCAT(type, ' ', from_user, ' ', to_user, ' ', operator_type, ' ', operator_id)"
                                + " FROM ownership_log WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("TRANSFER " + owner + " " + buyer + " USER " + buyer);
        assertThat(events.stream(DeviceTransferredEvent.class).filter(e -> e.deviceId() == device))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.fromUser()).isEqualTo(owner);
                    assertThat(e.toUser()).isEqualTo(buyer);
                    assertThat(e.revokedMembers()).containsExactly(dad, buyer);
                });

        // 原主人与原被授权人立即失去权限；新主人看不到转让前的操作
        expectError(as(owner, get("/api/app/devices/{d}", device)), ErrorCode.NOT_FOUND);
        expectError(as(dad, get("/api/app/devices/{d}", device)), ErrorCode.ACCESS_EXPIRED);
        assertThat(data(as(buyer, get("/api/app/devices/{d}", device)))
                        .get("lastOperation")
                        .isNull())
                .isTrue();

        // 一次性
        expectError(accept(user("别人"), card.get("token").asText()), ErrorCode.TRANSFER_USED);
        assertThat(preview(user("别人"), card.get("token").asText())).isEqualTo("ACCEPTED");
        assertThat(preview(buyer, card.get("token").asText())).isEqualTo("OWN_TRANSFER");
    }

    @Test
    void acceptRechecksTheCard() throws Exception {
        expectError(accept(user("x"), "short"), ErrorCode.NOT_FOUND);
        expectError(accept(user("x"), "C".repeat(43)), ErrorCode.NOT_FOUND);

        JsonNode own = create();
        expectError(accept(owner, own.get("token").asText()), ErrorCode.CANNOT_ACCEPT_OWN);
        as(owner, delete("/api/app/devices/{d}/transfer", device)).andExpect(status().isOk());
        expectError(accept(user("x"), own.get("token").asText()), ErrorCode.TRANSFER_CANCELLED);

        JsonNode expired = create();
        jdbc.sql("UPDATE device_transfer SET expire_at = ? WHERE id = ?")
                .params(
                        DbTime.toDb(Instant.now().minusSeconds(1)),
                        expired.get("id").asLong())
                .update();
        expectError(accept(user("x"), expired.get("token").asText()), ErrorCode.TRANSFER_EXPIRED);

        JsonNode disabled = create();
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        expectError(accept(user("x"), disabled.get("token").asText()), ErrorCode.DEVICE_DISABLED);
        assertThat(Long.parseLong(deviceValue("owner_user_id"))).isEqualTo(owner);
        jdbc.sql("UPDATE device SET enabled = 1 WHERE id = ?").param(device).update();

        // 发起人已不是主人（兜底：直接改主人）
        long other = user("新主人");
        jdbc.sql("UPDATE device SET owner_user_id = ? WHERE id = ?")
                .params(other, device)
                .update();
        expectError(accept(user("x"), disabled.get("token").asText()), ErrorCode.TRANSFER_CANCELLED);
        // 当前主人打开旧卡片
        expectError(accept(other, disabled.get("token").asText()), ErrorCode.CANNOT_ACCEPT_OWN);
    }

    @Test
    void concurrentAcceptsHaveOneWinner() throws Exception {
        String token = create().get("token").asText();
        int threads = 5;
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
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ownership_log WHERE device_id = ?")
                        .param(device)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void previewShowsCardDetailsAndState() throws Exception {
        JsonNode card = create();
        String token = card.get("token").asText();
        JsonNode p = data(as(user("买家"), get("/api/app/transfers/{t}", token)));
        assertThat(p.get("deviceName").asText()).isEqualTo("家里车位");
        assertThat(p.get("sn").asText()).isEqualTo(sn);
        assertThat(p.get("fromNickname").asText()).isEqualTo("张先生");
        assertThat(OffsetDateTime.parse(p.get("expireAt").asText()).toInstant()).isAfter(Instant.now());
        assertThat(p.get("state").asText()).isEqualTo("VALID");
        assertThat(preview(owner, token)).isEqualTo("OWN_TRANSFER");

        jdbc.sql("UPDATE device_transfer SET expire_at = ? WHERE id = ?")
                .params(
                        DbTime.toDb(Instant.now().minusSeconds(1)),
                        card.get("id").asLong())
                .update();
        assertThat(preview(user("x"), token)).isEqualTo("EXPIRED");

        String cancelled = create().get("token").asText();
        as(owner, delete("/api/app/devices/{d}/transfer", device)).andExpect(status().isOk());
        assertThat(preview(user("x"), cancelled)).isEqualTo("CANCELLED");

        String stale = create().get("token").asText();
        jdbc.sql("UPDATE device SET owner_user_id = ? WHERE id = ?")
                .params(user("另一人"), device)
                .update();
        assertThat(preview(user("x"), stale)).isEqualTo("CANCELLED");

        expectError(as(user("x"), get("/api/app/transfers/{t}", "D".repeat(43))), ErrorCode.NOT_FOUND);
        expectError(mvc.perform(get("/api/app/transfers/{t}", token)), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void expiryJobMarksLapsedTransfers() throws Exception {
        long id = create().get("id").asLong();
        jdbc.sql("UPDATE device_transfer SET expire_at = ? WHERE id = ?")
                .params(DbTime.toDb(Instant.now().minusSeconds(1)), id)
                .update();
        transfers.expireAll();
        assertThat(transferStatus(id)).isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------------ 主人解绑

    @Test
    void ownerUnbindReturnsDeviceToStock() throws Exception {
        long dad = user("张大爷");
        member(dad);
        pendingInvite();
        long transferId = create().get("id").asLong();
        expectError(as(dad, post("/api/app/devices/{d}/unbind", device)), ErrorCode.NOT_OWNER);

        // 设备禁用时也可以解绑
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        as(owner, post("/api/app/devices/{d}/unbind", device)).andExpect(status().isOk());

        assertThat(deviceValue("bind_status")).isEqualTo("PENDING");
        assertThat(deviceValue("owner_user_id")).isNull();
        assertThat(deviceValue("name")).isNull();
        assertThat(deviceValue("activate_code_hash")).isEqualTo("code-hash");
        assertThat(memberStatuses()).containsExactly("REVOKED");
        assertThat(jdbc.sql("SELECT status FROM share_invite WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo("VOIDED");
        assertThat(transferStatus(transferId)).isEqualTo("VOIDED");
        Map<String, Object> log = jdbc.sql(
                        "SELECT type, from_user, operator_type, operator_id, reason FROM ownership_log"
                                + " WHERE device_id = ?")
                .param(device)
                .query()
                .singleRow();
        assertThat(log.get("type")).isEqualTo("UNBIND");
        assertThat(((Number) log.get("from_user")).longValue()).isEqualTo(owner);
        assertThat(log.get("operator_type")).isEqualTo("USER");
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(owner);
        assertThat(log.get("reason")).isNull();
        assertThat(events.stream(DeviceUnboundEvent.class).filter(e -> e.deviceId() == device))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo(OwnershipRelease.Type.UNBIND);
                    assertThat(e.revokedMembers()).containsExactly(dad);
                });

        // 权限判定之后、事务之前主人已变更（并发转让 / 强制解绑）：事务内再确认，不再级联第二次
        DeviceGrant stale = new DeviceGrant(device, owner, DeviceGrant.Role.OWNER, null, null, true);
        assertThatThrownBy(() -> unbind.unbind(stale))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ownership_log WHERE device_id = ?")
                        .param(device)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // 解绑后与设备再无关系
        expectError(as(owner, post("/api/app/devices/{d}/unbind", device)), ErrorCode.NOT_FOUND);
        expectError(as(owner, get("/api/app/devices/{d}", device)), ErrorCode.NOT_FOUND);
    }
}
