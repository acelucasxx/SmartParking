package com.smartparking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminRole;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.Hex;
import com.smartparking.user.PhoneCipher;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 后台查询（BIZ-09）：设备、事件流、C 端用户、指令审计与导出、仪表盘。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.online.sweep-interval=1h",
            "smartparking.wechat.mode=mock",
            "smartparking.admin.command-export-max-rows=3"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestAdminIdentity.class})
class AdminQueryIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PhoneCipher phones;

    private String ops;
    private String support;
    private String superAdmin;
    /** 本测试造的设备 SN 共用的随机前缀，列表筛选只看到本测试的数据 */
    private String prefix;

    @BeforeEach
    void setUp() {
        ops = TestAdminIdentity.header(1, AdminRole.OPS, "运维");
        support = TestAdminIdentity.header(2, AdminRole.SUPPORT, "客服");
        superAdmin = TestAdminIdentity.header(3, AdminRole.SUPER_ADMIN, "超管");
        prefix = "Q"
                + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36)
                        .toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ 造数据

    private static String phone() {
        return "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private long user(String nickname, String phone) {
        String openid = "it-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (openid, nickname, phone_cipher, phone_hash) VALUES (?, ?, ?, ?)")
                .params(
                        openid,
                        nickname,
                        phone == null ? null : phones.encrypt(phone),
                        phone == null ? null : phones.hash(phone))
                .update();
        return jdbc.sql("SELECT id FROM app_user WHERE openid = ?")
                .param(openid)
                .query(Long.class)
                .single();
    }

    private String imei() {
        return "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }

    private long device(String suffix, Long owner, boolean enabled) {
        return device(suffix, imei(), owner, enabled);
    }

    private long device(String suffix, String imei, Long owner, boolean enabled) {
        jdbc.sql("INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id,"
                        + " owner_since, enabled, name, space_no, iccid, fw_version) VALUES (?, ?, 'SPG-100', 'B1', 'x', ?, ?,"
                        + " ?, ?, ?, 'B2-108', '8986', '1.0.3')")
                .params(
                        prefix + suffix,
                        imei,
                        owner == null ? "PENDING" : "BOUND",
                        owner,
                        owner == null ? null : DbTime.toDb(Instant.now().minus(Duration.ofDays(3))),
                        enabled,
                        owner == null ? null : "家里车位")
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private void runtime(long deviceId, String onlineState, String gate, Duration seenAgo) {
        jdbc.sql(
                        """
                        INSERT INTO device_runtime (device_id, gate_status, online_state, occupied, battery_pct, signal_level,
                                                    last_seen_at, status_refreshed_at, last_conn_event, last_conn_at)
                        VALUES (?, ?, ?, 1, 86, 24, ?, ?, 'CONNECTED', ?)""")
                .params(
                        deviceId,
                        gate,
                        onlineState,
                        DbTime.toDb(Instant.now().minus(seenAgo)),
                        DbTime.toDb(Instant.now().minus(seenAgo)),
                        DbTime.toDb(Instant.now().minus(seenAgo)))
                .update();
    }

    private void member(
            long deviceId, long userId, String status, Duration fromAgo, Duration toFromNow, String remark) {
        Instant now = Instant.now();
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by, remark)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?)")
                .params(
                        deviceId,
                        userId,
                        DbTime.toDb(now.minus(fromAgo)),
                        toFromNow == null ? null : DbTime.toDb(now.plus(toFromNow)),
                        status,
                        remark)
                .update();
    }

    private long command(
            long deviceId,
            Long userId,
            Long adminId,
            String role,
            String channel,
            String action,
            String status,
            Instant at) {
        jdbc.sql(
                        """
                        INSERT INTO control_command (device_id, user_id, admin_id, role, channel, action, status, result_code,
                                                     down_frame, raw_frame, issued_at, finished_at, duration_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        deviceId,
                        userId,
                        adminId,
                        role,
                        channel,
                        action,
                        status,
                        status.equals("FAILED") ? "OBSTRUCTED" : null,
                        Hex.parse("FD 00 01 01 16 00 FD FA"),
                        status.equals("SUCCESS") ? Hex.parse("FD 00 01 09 16 11 FD FA") : null,
                        DbTime.toDb(at),
                        status.equals("PENDING") ? null : DbTime.toDb(at.plusMillis(1240)),
                        status.equals("PENDING") ? null : 1240)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM control_command WHERE device_id = ?")
                .param(deviceId)
                .query(Long.class)
                .single();
    }

    private ResultActions as(String identity, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(TestAdminIdentity.HEADER, identity));
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

    private static String at(Instant instant) {
        return instant.atZone(DbTime.ZONE).toOffsetDateTime().toString();
    }

    // ------------------------------------------------------------ 设备

    @Test
    void deviceListFiltersByEveryConditionAndMasksOwnerPhone() throws Exception {
        String ownerPhone = phone();
        long owner = user("张先生", ownerPhone);
        long online = device("A1", owner, true);
        runtime(online, "ONLINE", "CLOSED", Duration.ofSeconds(30));
        long sleeping = device("A2", owner, true);
        runtime(sleeping, "SLEEP", "OPENED", Duration.ofMinutes(20));
        long disabled = device("B1", user("李四", phone()), false);
        runtime(disabled, "OFFLINE", "FAULT", Duration.ofDays(2));
        String stockImei = imei();
        long stock = device("C1", stockImei, null, true);

        // SN 前缀，不区分大小写；按 ID 倒序；没有运行态的为 OFFLINE / UNKNOWN
        JsonNode all = data(as(ops, get("/api/admin/devices").param("sn", prefix.toLowerCase(Locale.ROOT))));
        assertThat(all.get("total").asLong()).isEqualTo(4);
        assertThat(all.get("items"))
                .extracting(n -> n.get("id").asLong())
                .containsExactly(stock, disabled, sleeping, online);
        JsonNode first = all.get("items").get(3);
        assertThat(first.get("sn").asText()).isEqualTo(prefix + "A1");
        assertThat(first.get("bindStatus").asText()).isEqualTo("BOUND");
        assertThat(first.get("onlineState").asText()).isEqualTo("ONLINE");
        assertThat(first.get("gateStatus").asText()).isEqualTo("CLOSED");
        assertThat(first.get("batteryPct").asInt()).isEqualTo(86);
        assertThat(first.get("ownerPhoneMasked").asText())
                .isEqualTo(ownerPhone.substring(0, 3) + "****" + ownerPhone.substring(7));
        assertThat(first.get("lastSeenAt").asText()).endsWith("+08:00");
        JsonNode stockRow = all.get("items").get(0);
        assertThat(stockRow.get("onlineState").asText()).isEqualTo("OFFLINE");
        assertThat(stockRow.get("gateStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(stockRow.get("ownerPhoneMasked").isNull()).isTrue();

        as(support, get("/api/admin/devices").param("sn", prefix + "A"))
                .andExpect(jsonPath("$.data.total").value(2));
        as(ops, get("/api/admin/devices").param("imei", stockImei))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) stock)));
        as(ops, get("/api/admin/devices").param("phone", ownerPhone).param("sn", prefix))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) sleeping, (int) online)));
        as(ops, get("/api/admin/devices").param("sn", prefix).param("bindStatus", "PENDING"))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) stock)));
        as(ops, get("/api/admin/devices").param("sn", prefix).param("onlineState", "OFFLINE"))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) stock, (int) disabled)));
        as(ops, get("/api/admin/devices").param("sn", prefix).param("onlineState", "SLEEP"))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) sleeping)));
        as(ops, get("/api/admin/devices").param("sn", prefix).param("enabled", "false"))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) disabled)));
        as(ops, get("/api/admin/devices").param("sn", prefix).param("size", "1").param("page", "2"))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) disabled)));

        expectError(as(ops, get("/api/admin/devices").param("phone", "1380013800")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/devices").param("sn", "A%")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/devices").param("imei", "86x")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/devices").param("bindStatus", "GONE")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/devices").param("onlineState", "AWAY")), ErrorCode.BAD_REQUEST);
        expectError(mvc.perform(get("/api/admin/devices")), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void deviceDetailAggregatesOwnerMembersAndLiveState() throws Exception {
        String ownerPhone = phone();
        long owner = user(null, ownerPhone);
        long device = device("D1", owner, true);
        // 库里仍是 ONLINE，但已 20 分钟没有上行：详情按当前时刻判定为休眠
        runtime(device, "ONLINE", "OPENED", Duration.ofMinutes(20));
        String memberPhone = phone();
        long dad = user("张大爷", memberPhone);
        long expired = user("过期的", null);
        long future = user("未开始", null);
        long revoked = user("已撤销", null);
        member(device, dad, "ACTIVE", Duration.ofDays(2), null, "爸爸");
        member(device, expired, "ACTIVE", Duration.ofDays(5), Duration.ofDays(-1), null);
        member(device, future, "ACTIVE", Duration.ofDays(-1), Duration.ofDays(3), null);
        member(device, revoked, "REVOKED", Duration.ofDays(1), null, null);

        JsonNode d = data(as(support, get("/api/admin/devices/{id}", device)));
        assertThat(d.get("sn").asText()).isEqualTo(prefix + "D1");
        assertThat(d.get("onlineState").asText()).isEqualTo("SLEEP");
        assertThat(d.get("gateStatus").asText()).isEqualTo("OPENED");
        assertThat(d.get("occupied").asBoolean()).isTrue();
        assertThat(d.get("signalLevel").asInt()).isEqualTo(24);
        assertThat(d.get("workMode").asText()).isEqualTo("KEEP_ALIVE");
        assertThat(d.get("fwVersion").asText()).isEqualTo("1.0.3");
        assertThat(d.get("iccid").asText()).isEqualTo("8986");
        assertThat(d.get("name").asText()).isEqualTo("家里车位");
        assertThat(d.get("spaceNo").asText()).isEqualTo("B2-108");
        assertThat(d.get("statusRefreshedAt").asText()).endsWith("+08:00");
        assertThat(d.at("/owner/userId").asLong()).isEqualTo(owner);
        assertThat(d.at("/owner/nickname").asText()).isEqualTo("微信用户");
        assertThat(d.at("/owner/phoneMasked").asText())
                .isEqualTo(ownerPhone.substring(0, 3) + "****" + ownerPhone.substring(7));
        assertThat(d.at("/owner/since").asText()).endsWith("+08:00");

        // 只列生效中的授权（含未开始），按开始时间；已过有效期但到期任务还没处理的显示 EXPIRED
        JsonNode members = d.get("members");
        assertThat(members).extracting(m -> m.get("userId").asLong()).containsExactly(expired, dad, future);
        assertThat(members.get(0).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(members.get(1).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(members.get(1).get("remark").asText()).isEqualTo("爸爸");
        assertThat(members.get(1).get("validTo").isNull()).isTrue();
        assertThat(members.get(1).get("phoneMasked").asText())
                .isEqualTo(memberPhone.substring(0, 3) + "****" + memberPhone.substring(7));
        assertThat(members.get(2).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(members.get(2).get("phoneMasked").isNull()).isTrue();

        // 待激活设备没有主人
        long stock = device("D2", null, true);
        JsonNode s = data(as(ops, get("/api/admin/devices/{id}", stock)));
        assertThat(s.get("owner").isNull()).isTrue();
        assertThat(s.get("members")).isEmpty();
        assertThat(s.get("onlineState").asText()).isEqualTo("OFFLINE");

        expectError(as(ops, get("/api/admin/devices/{id}", 9_000_000_000L)), ErrorCode.NOT_FOUND);
    }

    @Test
    void eventsAreNewestFirstWithinRange() throws Exception {
        long device = device("E1", null, true);
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(1));
        String[][] rows = {
            {"CLOSED", "12", "22", "0", "FD 00 01 0C 16 00 FD FA"},
            {"OPENING", "25", "1", "1", "FD 00 01 19 01 11 FD FA"},
            {"OPENED", "9", "17", "1", "FD 00 01 09 11 11 FD FA"}
        };
        for (int i = 0; i < rows.length; i++) {
            jdbc.sql(
                            "INSERT INTO device_event (device_id, gate_status, status_code, reason, occupied, raw_frame, approx, at)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            device,
                            rows[i][0],
                            Integer.parseInt(rows[i][1]),
                            Integer.parseInt(rows[i][2]),
                            rows[i][3].equals("1"),
                            Hex.parse(rows[i][4]),
                            i == 1,
                            DbTime.toDb(t.plusSeconds(i * 60L)))
                    .update();
        }
        JsonNode page = data(as(ops, get("/api/admin/devices/{id}/events", device)));
        assertThat(page.get("total").asLong()).isEqualTo(3);
        JsonNode newest = page.get("items").get(0);
        assertThat(newest.get("gateStatus").asText()).isEqualTo("OPENED");
        assertThat(newest.get("statusCode").asText()).isEqualTo("09");
        assertThat(newest.get("reasonCode").asText()).isEqualTo("11");
        assertThat(newest.get("source").asText()).isEqualTo("OBSTRUCTED");
        assertThat(newest.get("occupied").asBoolean()).isTrue();
        assertThat(newest.get("rawFrame").asText()).isEqualTo("FD 00 01 09 11 11 FD FA");
        assertThat(OffsetDateTime.parse(newest.get("at").asText()).toInstant()).isEqualTo(t.plusSeconds(120));
        JsonNode middle = page.get("items").get(1);
        assertThat(middle.get("source").asText()).isEqualTo("REMOTE_CONTROL");
        assertThat(middle.get("approx").asBoolean()).isTrue();
        assertThat(page.get("items").get(2).get("source").asText()).isEqualTo("APP");

        // from 含、to 不含
        as(
                        ops,
                        get("/api/admin/devices/{id}/events", device)
                                .param("from", at(t.plusSeconds(60)))
                                .param("to", at(t.plusSeconds(120))))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].gateStatus").value("OPENING"));
        expectError(
                as(
                        ops,
                        get("/api/admin/devices/{id}/events", device)
                                .param("from", at(t))
                                .param("to", at(t))),
                ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/devices/{id}/events", 9_000_000_000L)), ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------ C 端用户

    @Test
    void usersAreListedMaskedWithDeviceCounts() throws Exception {
        String p = phone();
        long user = user("王女士", p);
        long other = user("赵先生", phone());
        long owned1 = device("U1", user, true);
        long owned2 = device("U2", user, true);
        long shared = device("U3", other, true);
        long old = device("U4", other, true);
        member(shared, user, "ACTIVE", Duration.ofDays(1), null, "女儿");
        member(old, user, "REVOKED", Duration.ofDays(30), null, null);
        jdbc.sql("UPDATE app_user SET status = 'BANNED' WHERE id = ?")
                .param(other)
                .update();

        JsonNode list = data(as(support, get("/api/admin/users").param("phone", p)));
        assertThat(list.get("total").asLong()).isEqualTo(1);
        JsonNode u = list.get("items").get(0);
        assertThat(u.get("id").asLong()).isEqualTo(user);
        assertThat(u.get("nickname").asText()).isEqualTo("王女士");
        assertThat(u.get("phoneMasked").asText()).isEqualTo(p.substring(0, 3) + "****" + p.substring(7));
        assertThat(u.toString()).doesNotContain(p);
        assertThat(u.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(u.get("ownedCount").asInt()).isEqualTo(2);
        assertThat(u.get("memberCount").asInt()).isEqualTo(1);

        JsonNode banned = data(
                as(superAdmin, get("/api/admin/users").param("status", "BANNED").param("size", "100")));
        assertThat(banned.get("items"))
                .extracting(n -> n.get("id").asLong())
                .contains(other)
                .doesNotContain(user);

        JsonNode detail = data(as(support, get("/api/admin/users/{id}", user)));
        assertThat(detail.get("ownedDevices"))
                .extracting(n -> n.get("deviceId").asLong())
                .containsExactly(owned1, owned2);
        assertThat(detail.at("/ownedDevices/0/sn").asText()).isEqualTo(prefix + "U1");
        assertThat(detail.at("/ownedDevices/0/name").asText()).isEqualTo("家里车位");
        // 授权记录含历史，最近开始的在前
        assertThat(detail.get("memberDevices"))
                .extracting(n -> n.get("deviceId").asLong())
                .containsExactly(shared, old);
        assertThat(detail.at("/memberDevices/1/status").asText()).isEqualTo("REVOKED");

        expectError(as(support, get("/api/admin/users/{id}", 9_000_000_000L)), ErrorCode.NOT_FOUND);
        expectError(as(support, get("/api/admin/users").param("phone", "139")), ErrorCode.BAD_REQUEST);
        expectError(as(support, get("/api/admin/users").param("status", "DELETED")), ErrorCode.BAD_REQUEST);
        // 运维不能查看用户（契约 x-roles）
        expectError(as(ops, get("/api/admin/users")), ErrorCode.FORBIDDEN);
        expectError(as(ops, get("/api/admin/users/{id}", user)), ErrorCode.FORBIDDEN);
    }

    // ------------------------------------------------------------ 指令审计

    @Test
    void commandAuditFiltersAndShowsOperators() throws Exception {
        String p = phone();
        long owner = user("张先生", p);
        long member = user(null, phone());
        long device = device("K1", owner, true);
        long otherDevice = device("K2", owner, true);
        String adminName = "运维小王" + ThreadLocalRandom.current().nextInt(1000);
        String username = "it_q" + ThreadLocalRandom.current().nextInt(1_000_000);
        jdbc.sql("INSERT INTO admin_user (username, password_hash, display_name, role_id) VALUES (?, 'x', ?, 2)")
                .params(username, adminName)
                .update();
        long adminId = jdbc.sql("SELECT id FROM admin_user WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(2));
        long c1 = command(device, owner, null, "OWNER", "REMOTE", "OPEN", "SUCCESS", t);
        long c2 = command(device, member, null, "MEMBER", "BLE", "CLOSE", "FAILED", t.plusSeconds(60));
        long c3 = command(device, null, adminId, "ADMIN", "REMOTE", "QUERY_STATUS", "TIMEOUT", t.plusSeconds(120));
        long c4 = command(otherDevice, owner, null, "OWNER", "REMOTE", "OPEN", "PENDING", t.plusSeconds(180));

        JsonNode page = data(as(ops, get("/api/admin/commands").param("deviceId", String.valueOf(device))));
        assertThat(page.get("items")).extracting(n -> n.get("id").asLong()).containsExactly(c3, c2, c1);
        JsonNode admin = page.get("items").get(0);
        assertThat(admin.at("/operator/type").asText()).isEqualTo("ADMIN");
        assertThat(admin.at("/operator/id").asLong()).isEqualTo(adminId);
        assertThat(admin.at("/operator/name").asText()).isEqualTo(adminName);
        assertThat(admin.at("/operator/phoneMasked").isNull()).isTrue();
        assertThat(admin.get("role").asText()).isEqualTo("ADMIN");
        JsonNode viaBle = page.get("items").get(1);
        assertThat(viaBle.at("/operator/name").asText()).isEqualTo("微信用户");
        assertThat(viaBle.get("resultCode").asText()).isEqualTo("OBSTRUCTED");
        JsonNode ok = page.get("items").get(2);
        assertThat(ok.get("sn").asText()).isEqualTo(prefix + "K1");
        assertThat(ok.at("/operator/type").asText()).isEqualTo("USER");
        assertThat(ok.at("/operator/phoneMasked").asText()).isEqualTo(p.substring(0, 3) + "****" + p.substring(7));
        assertThat(ok.get("downFrame").asText()).isEqualTo("FD 00 01 01 16 00 FD FA");
        assertThat(ok.get("rawFrame").asText()).isEqualTo("FD 00 01 09 16 11 FD FA");
        assertThat(ok.get("durationMs").asInt()).isEqualTo(1240);
        assertThat(ok.get("fwVersion").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(ok.get("issuedAt").asText()).toInstant())
                .isEqualTo(t);

        as(ops, get("/api/admin/commands").param("sn", (prefix + "k2").toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) c4)));
        as(ops, get("/api/admin/commands").param("phone", p))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) c4, (int) c1)));
        as(
                        ops,
                        get("/api/admin/commands")
                                .param("deviceId", String.valueOf(device))
                                .param("channel", "BLE"))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) c2)));
        as(
                        ops,
                        get("/api/admin/commands")
                                .param("deviceId", String.valueOf(device))
                                .param("status", "TIMEOUT"))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) c3)));
        as(
                        ops,
                        get("/api/admin/commands")
                                .param("deviceId", String.valueOf(device))
                                .param("from", at(t.plusSeconds(60)))
                                .param("to", at(t.plusSeconds(120))))
                .andExpect(jsonPath("$.data.items[*].id").value(contains((int) c2)));

        as(support, get("/api/admin/commands/{id}", c4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.finishedAt").isEmpty())
                .andExpect(jsonPath("$.data.rawFrame").isEmpty());
        expectError(as(ops, get("/api/admin/commands/{id}", 9_000_000_000L)), ErrorCode.NOT_FOUND);
        expectError(as(ops, get("/api/admin/commands").param("channel", "SMS")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/commands").param("status", "DONE")), ErrorCode.BAD_REQUEST);
    }

    @Test
    void exportIsBoundedAndAudited() throws Exception {
        long owner = user("张先生", phone());
        long device = device("X1", owner, true);
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(1));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(command(
                    device,
                    owner,
                    null,
                    "OWNER",
                    "REMOTE",
                    i % 2 == 0 ? "OPEN" : "CLOSE",
                    "SUCCESS",
                    t.plusSeconds(i)));
        }
        String from = at(t.minusSeconds(1));
        String to = at(t.plus(Duration.ofHours(1)));

        expectError(
                as(ops, get("/api/admin/commands/export").param("deviceId", String.valueOf(device))),
                ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/commands/export").param("from", from)), ErrorCode.BAD_REQUEST);
        expectError(
                as(
                        ops,
                        get("/api/admin/commands/export")
                                .param("from", at(t.minus(Duration.ofDays(31))))
                                .param("to", at(t.plusSeconds(1)))),
                ErrorCode.BAD_REQUEST);

        byte[] file = as(
                        ops,
                        get("/api/admin/commands/export")
                                .param("deviceId", String.valueOf(device))
                                .param("from", from)
                                .param("to", to))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")))
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(file))) {
            List<Row> rows = wb.getFirstSheet().read();
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).getCellText(0)).isEqualTo("指令ID");
            // 导出按时间正序
            assertThat(rows.subList(1, 4))
                    .extracting(r -> Long.parseLong(r.getCellText(0)))
                    .containsExactlyElementsOf(ids);
            assertThat(rows.get(1).getCellText(1)).isEqualTo(prefix + "X1");
            assertThat(rows.get(1).getCellText(4)).contains("****");
            assertThat(rows.get(1).getCellText(7)).isEqualTo("OPEN");
            assertThat(rows.get(1).getCellText(14)).isEqualTo("FD 00 01 09 16 11 FD FA");
        }
        assertThat(jdbc.sql("SELECT JSON_EXTRACT(detail, '$.rows') FROM operation_log WHERE admin_id = 1"
                                + " AND action = 'COMMAND_EXPORT' ORDER BY id DESC LIMIT 1")
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);

        // 超过行数上限（测试配置为 3）
        command(device, owner, null, "OWNER", "REMOTE", "OPEN", "SUCCESS", t.plusSeconds(10));
        as(
                        ops,
                        get("/api/admin/commands/export")
                                .param("deviceId", String.valueOf(device))
                                .param("from", from)
                                .param("to", to))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("超过上限 3 行")));
    }

    // ------------------------------------------------------------ 仪表盘

    private JsonNode dashboard() throws Exception {
        return data(as(support, get("/api/admin/dashboard")));
    }

    @Test
    void dashboardCountsBoundDevicesCommandsAndAlarms() throws Exception {
        JsonNode before = dashboard();
        long owner = user("张先生", null);
        long online = device("H1", owner, true);
        runtime(online, "ONLINE", "CLOSED", Duration.ofSeconds(10));
        long sleeping = device("H2", owner, true);
        runtime(sleeping, "SLEEP", "FAULT", Duration.ofMinutes(20));
        long noRuntime = device("H3", owner, true);
        long stock = device("H4", null, true);
        runtime(stock, "ONLINE", "CLOSED", Duration.ofSeconds(10));
        Instant now = Instant.now();
        command(online, owner, null, "OWNER", "REMOTE", "OPEN", "SUCCESS", now);
        command(online, owner, null, "OWNER", "REMOTE", "CLOSE", "TIMEOUT", now);
        command(sleeping, owner, null, "OWNER", "REMOTE", "OPEN", "PENDING", now);
        command(sleeping, null, 1L, "ADMIN", "REMOTE", "QUERY_STATUS", "SUCCESS", now);
        command(noRuntime, owner, null, "OWNER", "REMOTE", "OPEN", "SUCCESS", now.minus(Duration.ofDays(2)));
        jdbc.sql(
                        "INSERT INTO alarm (device_id, type, level, first_at, last_at) VALUES (?, 'MOTOR_FAULT', 'CRITICAL', NOW(3),"
                                + " NOW(3)), (?, 'OBSTRUCTED', 'WARNING', NOW(3), NOW(3))")
                .params(sleeping, online)
                .update();
        jdbc.sql("UPDATE alarm SET status = 'HANDLED' WHERE device_id = ? AND type = 'OBSTRUCTED'")
                .param(online)
                .update();
        JsonNode after = dashboard();

        assertThat(delta(before, after, "/devices/total")).isEqualTo(4);
        assertThat(delta(before, after, "/devices/bound")).isEqualTo(3);
        // 库存设备即使在线也不计入分布；没有运行态的已绑定设备计为离线；故障与在线状态重叠计数
        assertThat(delta(before, after, "/devices/online")).isEqualTo(1);
        assertThat(delta(before, after, "/devices/sleep")).isEqualTo(1);
        assertThat(delta(before, after, "/devices/offline")).isEqualTo(1);
        assertThat(delta(before, after, "/devices/fault")).isEqualTo(1);
        // 今日已终结的 App 抬杆 / 落杆：不含未终结、后台自检与昨天之前的
        assertThat(delta(before, after, "/commandsToday/total")).isEqualTo(2);
        assertThat(delta(before, after, "/commandsToday/success")).isEqualTo(1);
        assertThat(delta(before, after, "/openAlarms")).isEqualTo(1);
        long total = after.at("/commandsToday/total").asLong();
        long success = after.at("/commandsToday/success").asLong();
        assertThat(after.at("/commandsToday/successRate").asDouble())
                .isCloseTo((double) success / total, org.assertj.core.data.Offset.offset(0.0001));
    }

    private static long delta(JsonNode before, JsonNode after, String path) {
        return after.at(path).asLong() - before.at(path).asLong();
    }
}
