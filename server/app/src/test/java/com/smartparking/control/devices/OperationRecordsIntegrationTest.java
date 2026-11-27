package com.smartparking.control.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.Hex;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;

/** 操作记录（BIZ-14）：App 指令与非 App 动作合并、可见范围、去重、按日筛选。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OperationRecordsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    private long owner;
    private long member;
    private long previousOwner;
    private long device;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        owner = user("张先生");
        member = user("张大爷");
        previousOwner = user("前任");
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        "INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since)"
                                + " VALUES (?, ?, 'SPG-100', 'IT', 'x', 'BOUND', ?, ?)")
                .params("SN" + imei, imei, owner, DbTime.toDb(now.minus(Duration.ofDays(2))))
                .update();
        device = jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO device_member (device_id, user_id, valid_from, status, granted_by, remark) VALUES"
                        + " (?, ?, ?, 'ACTIVE', ?, '爸爸')")
                .params(device, member, DbTime.toDb(now.minus(Duration.ofDays(5))), owner)
                .update();
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

    private long command(Long userId, String role, String channel, String action, String status, Instant at) {
        boolean terminal = !status.equals("SENT");
        jdbc.sql(
                        "INSERT INTO control_command (device_id, user_id, admin_id, role, channel, action, status, result_code,"
                                + " issued_at, finished_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        device,
                        userId,
                        userId == null ? 1L : null,
                        role,
                        channel,
                        action,
                        status,
                        status.equals("FAILED") ? "OBSTRUCTED" : null,
                        DbTime.toDb(at),
                        terminal ? DbTime.toDb(at.plusMillis(1240)) : null,
                        terminal ? 1240 : null)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM control_command WHERE device_id = ?")
                .param(device)
                .query(Long.class)
                .single();
    }

    private long event(String gate, int code, int reason, boolean occupied, boolean approx, Instant at) {
        jdbc.sql(
                        "INSERT INTO device_event (device_id, gate_status, status_code, reason, occupied, raw_frame, approx, at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        device,
                        gate,
                        code,
                        reason,
                        occupied,
                        Hex.parse("FD 00 01 09 16 11 FD FA"),
                        approx,
                        DbTime.toDb(at))
                .update();
        return jdbc.sql("SELECT MAX(id) FROM device_event WHERE device_id = ?")
                .param(device)
                .query(Long.class)
                .single();
    }

    private ResultActions as(long userId, String path, String... params) throws Exception {
        String token =
                sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
        var request = get(path, device).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private JsonNode records(long userId, String... params) throws Exception {
        return json.readTree(as(userId, "/api/app/devices/{d}/records", params)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    private static Instant at(JsonNode record) {
        return OffsetDateTime.parse(record.get("at").asText()).toInstant();
    }

    // ------------------------------------------------------------

    @Test
    void ownerSeesAppAndNonAppActionsSinceBecomingOwner() throws Exception {
        long beforeOwnership =
                command(previousOwner, "OWNER", "REMOTE", "OPEN", "SUCCESS", now.minus(Duration.ofDays(3)));
        event("CLOSED", 0x0C, 0x02, false, false, now.minus(Duration.ofDays(3)));
        long ownOpen = command(owner, "OWNER", "REMOTE", "OPEN", "SUCCESS", now.minus(Duration.ofMinutes(60)));
        long dadClose = command(member, "MEMBER", "BLE", "CLOSE", "FAILED", now.minus(Duration.ofMinutes(50)));
        long dadPending = command(member, "MEMBER", "REMOTE", "OPEN", "SENT", now.minus(Duration.ofMinutes(45)));
        command(null, "ADMIN", "REMOTE", "QUERY_STATUS", "SUCCESS", now.minus(Duration.ofMinutes(44)));
        event("OPENING", 0x19, 0x01, false, false, now.minus(Duration.ofMinutes(40)));
        long remote = event("OPENED", 0x09, 0x01, false, false, now.minus(Duration.ofMinutes(39)));
        // 只是车位变化：闸杆与原因都没变，不算新动作
        event("OPENED", 0x09, 0x01, true, false, now.minus(Duration.ofMinutes(38)));
        long autoClose = event("CLOSED", 0x0C, 0x10, true, true, now.minus(Duration.ofMinutes(30)));
        // 平台指令的到位帧来自指令表，不重复出现
        event("OPENED", 0x09, 0x16, true, false, now.minus(Duration.ofMinutes(20)));
        long sensor = event("CLOSED", 0x0C, 0x03, false, false, now.minus(Duration.ofMinutes(10)));
        // 原因码 00：不是动作
        event("CLOSED", 0x0C, 0x00, false, false, now.minus(Duration.ofMinutes(5)));

        JsonNode page = records(owner);
        assertThat(ids(page))
                .containsExactly(
                        "e-" + sensor,
                        "e-" + autoClose,
                        "e-" + remote,
                        "c-" + dadPending,
                        "c-" + dadClose,
                        "c-" + ownOpen)
                .doesNotContain("c-" + beforeOwnership);
        assertThat(page.get("total").asLong()).isEqualTo(6);

        JsonNode items = page.get("items");
        JsonNode s = items.get(0);
        assertThat(s.get("source").asText()).isEqualTo("GROUND_SENSOR");
        assertThat(s.get("action").asText()).isEqualTo("CLOSE");
        assertThat(s.get("approx").asBoolean()).isFalse();
        assertThat(s.get("operatorName").isNull()).isTrue();
        assertThat(s.get("result").isNull()).isTrue();
        assertThat(s.get("channel").isNull()).isTrue();
        assertThat(at(s)).isEqualTo(now.minus(Duration.ofMinutes(10)));
        JsonNode auto = items.get(1);
        assertThat(auto.get("source").asText()).isEqualTo("AUTO_CLOSE");
        assertThat(auto.get("approx").asBoolean()).isTrue();
        assertThat(items.get(2).get("source").asText()).isEqualTo("REMOTE_CONTROL");
        assertThat(items.get(2).get("action").asText()).isEqualTo("OPEN");

        // 主人看被授权人的指令：备注优先；未终结的 result 为 null
        JsonNode pending = items.get(3);
        assertThat(pending.get("operatorName").asText()).isEqualTo("爸爸");
        assertThat(pending.get("operatorRole").asText()).isEqualTo("MEMBER");
        assertThat(pending.get("result").isNull()).isTrue();
        JsonNode failed = items.get(4);
        assertThat(failed.get("source").asText()).isEqualTo("APP");
        assertThat(failed.get("channel").asText()).isEqualTo("BLE");
        assertThat(failed.get("result").asText()).isEqualTo("FAILED");
        assertThat(failed.get("resultCode").asText()).isEqualTo("OBSTRUCTED");
        assertThat(failed.get("durationMs").asInt()).isEqualTo(1240);
        JsonNode mine = items.get(5);
        assertThat(mine.get("operatorName").asText()).isEqualTo("张先生");
        assertThat(mine.get("operatorRole").asText()).isEqualTo("OWNER");
        assertThat(mine.get("result").asText()).isEqualTo("SUCCESS");

        // 分页
        JsonNode second = records(owner, "page", "2", "size", "2");
        assertThat(second.get("total").asLong()).isEqualTo(6);
        assertThat(ids(second)).containsExactly("e-" + remote, "c-" + dadPending);

        // 控制页“最近一次操作”同一口径：主人能看到地感落杆
        String token =
                sessions.create(owner, Duration.ofMinutes(10), Instant.now()).token();
        mvc.perform(get("/api/app/devices/{d}", device).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.lastOperation.id").value("e-" + sensor))
                .andExpect(jsonPath("$.data.lastOperation.source").value("GROUND_SENSOR"));
    }

    /** 闸杆状态没变、原因变了也是新动作：落杆到位后断电重启，上电找零仍报关闸到位 */
    @Test
    void sameGateStatusWithNewReasonIsANewAction() throws Exception {
        long autoClose = event("CLOSED", 0x0C, 0x10, false, false, now.minus(Duration.ofMinutes(30)));
        long powerOn = event("CLOSED", 0x0C, 0x15, false, false, now.minus(Duration.ofMinutes(25)));
        JsonNode page = records(owner);
        assertThat(ids(page)).containsExactly("e-" + powerOn, "e-" + autoClose);
        assertThat(page.get("items").get(0).get("source").asText()).isEqualTo("POWER_ON");
    }

    @Test
    void memberSeesOnlyOwnAppCommands() throws Exception {
        long ownerOpen = command(owner, "OWNER", "REMOTE", "OPEN", "SUCCESS", now.minus(Duration.ofMinutes(30)));
        // 本人发起的都可见，包括主人变更之前的
        long early = command(member, "MEMBER", "REMOTE", "CLOSE", "SUCCESS", now.minus(Duration.ofDays(3)));
        long mine = command(member, "MEMBER", "REMOTE", "OPEN", "TIMEOUT", now.minus(Duration.ofMinutes(20)));
        event("OPENED", 0x09, 0x01, false, false, now.minus(Duration.ofMinutes(10)));

        JsonNode page = records(member);
        assertThat(ids(page)).containsExactly("c-" + mine, "c-" + early).doesNotContain("c-" + ownerOpen);
        JsonNode first = page.get("items").get(0);
        // 被授权人看自己的：显示昵称而不是主人写的备注
        assertThat(first.get("operatorName").asText()).isEqualTo("张大爷");
        assertThat(first.get("result").asText()).isEqualTo("TIMEOUT");

        // 设备禁用时也能看
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        assertThat(ids(records(member))).hasSize(2);
    }

    @Test
    void filterByDayInShanghaiTime() throws Exception {
        LocalDate today = LocalDate.ofInstant(now, DbTime.ZONE);
        LocalDate yesterday = today.minusDays(1);
        Instant yesterdayNoon = yesterday.atTime(12, 0).atZone(DbTime.ZONE).toInstant();
        Instant yesterdayLate = yesterday.atTime(23, 59, 59).atZone(DbTime.ZONE).toInstant();
        Instant todayStart = today.atStartOfDay(DbTime.ZONE).toInstant();
        long a = command(owner, "OWNER", "REMOTE", "OPEN", "SUCCESS", yesterdayNoon);
        long b = event("CLOSED", 0x0C, 0x02, false, false, yesterdayLate);
        long c = command(owner, "OWNER", "REMOTE", "CLOSE", "SUCCESS", todayStart);

        assertThat(ids(records(owner, "date", yesterday.toString()))).containsExactly("e-" + b, "c-" + a);
        assertThat(ids(records(owner, "date", today.toString())))
                .contains("c-" + c)
                .doesNotContain("c-" + a, "e-" + b);
        assertThat(records(owner, "date", today.minusDays(10).toString())
                        .get("total")
                        .asLong())
                .isZero();

        as(owner, "/api/app/devices/{d}/records", "date", "2026/12/01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
        as(owner, "/api/app/devices/{d}/records", "size", "101")
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void accessFollowsDevicePermission() throws Exception {
        as(user("路人"), "/api/app/devices/{d}/records")
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
        jdbc.sql("UPDATE device_member SET status = 'REVOKED' WHERE device_id = ? AND user_id = ?")
                .params(device, member)
                .update();
        as(member, "/api/app/devices/{d}/records").andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_EXPIRED.code()));
    }
}
