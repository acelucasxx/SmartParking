package com.smartparking.control.ble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.smartparking.control.ble.BleTicketService.FailReason;
import com.smartparking.control.ble.BleTicketService.Report;
import com.smartparking.control.ble.BleTicketService.Result;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.command.CommandAction;
import com.smartparking.iot.command.CommandFinishedEvent;
import com.smartparking.iot.command.CommandStatus;
import com.smartparking.user.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.transaction.support.TransactionTemplate;

/** 蓝牙票据（BIZ-17，PRD 5.4）：签发、上报、补报幂等。总开关在本测试中打开，模拟固件已支持票据校验。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.settings.cache-ttl=0s",
            "smartparking.ble.enabled=true",
            "smartparking.member.expire-interval=1h",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BleTicketIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private ApplicationEventPublisher events;

    private long owner;
    private long member;
    private long device;
    private String sn;
    private String mac;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE system_config SET config_value = '20' WHERE config_key = 'member.max_per_device'")
                .update();
        owner = user("主人");
        member = user("家人");
        mac = mac();
        device = device(owner, mac);
        sn = jdbc.sql("SELECT sn FROM device WHERE id = ?")
                .param(device)
                .query(String.class)
                .single();
        member(member, "NOW(3) - INTERVAL 1 DAY", "NULL");
    }

    // ------------------------------------------------------------ 工具

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

    private static String mac() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(i > 0 ? ":" : "")
                    .append(String.format("%02X", ThreadLocalRandom.current().nextInt(256)));
        }
        return sb.toString();
    }

    private long device(long ownerId, String bleMac) {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        "INSERT INTO device (sn, imei, ble_mac, model, batch_no, activate_code_hash, bind_status, owner_user_id,"
                                + " owner_since, name) VALUES (?, ?, ?, 'SPG-100', 'IT', 'code-hash', 'BOUND', ?,"
                                + " NOW(3) - INTERVAL 30 DAY, '家里车位')")
                .params("SN" + imei, imei, bleMac, ownerId)
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private void member(long userId, String from, String to) {
        jdbc.sql(
                        "INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by) VALUES (?, ?, "
                                + from + ", " + to + ", 'ACTIVE', ?)")
                .params(device, userId, owner)
                .update();
    }

    private ResultActions as(long userId, MockHttpServletRequestBuilder request) throws Exception {
        String token =
                sessions.create(userId, Duration.ofMinutes(10), Instant.now()).token();
        return mvc.perform(
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON));
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

    private ResultActions issue(long userId, long deviceId, String action) throws Exception {
        return as(
                userId, post("/api/app/devices/{d}/ble-tickets", deviceId).content("{\"action\":\"" + action + "\"}"));
    }

    private String ticket(long userId, String action) throws Exception {
        return data(issue(userId, device, action)).get("ticketId").asText();
    }

    private static Map<String, Object> report(String ticketId, String action, String result, String failReason) {
        Map<String, Object> r = new HashMap<>();
        r.put("ticketId", ticketId);
        r.put("action", action);
        r.put("result", result);
        r.put("failReason", failReason);
        r.put("at", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        r.put("durationMs", 2300);
        return r;
    }

    private ResultActions send(long userId, List<?> reports) throws Exception {
        return as(userId, post("/api/app/ble-reports").content(json.writeValueAsString(Map.of("reports", reports))));
    }

    private List<String> commands() {
        return jdbc.sql("SELECT CONCAT(channel, ' ', action, ' ', status, ' ', role, ' ', user_id, ' ',"
                        + " COALESCE(result_code, '-'), ' ', COALESCE(duration_ms, '-')) FROM control_command"
                        + " WHERE device_id = ? ORDER BY id")
                .param(device)
                .query(String.class)
                .list();
    }

    private String ticketRow(String ticketId) {
        return jdbc.sql("SELECT CONCAT(used, ' ', COALESCE(result, '-'), ' ', COALESCE(fail_reason, '-'), ' ',"
                        + " command_id IS NOT NULL, ' ', reported_at IS NOT NULL) FROM ble_ticket WHERE ticket_id = ?")
                .param(ticketId)
                .query(String.class)
                .single();
    }

    // ------------------------------------------------------------ 签发

    @Test
    void ownerAndMemberGetOneMinuteTickets() throws Exception {
        Instant before = Instant.now();
        JsonNode t = data(issue(owner, device, "OPEN"));
        assertThat(t.get("ticketId").asText()).matches("[0-9a-f]{32}");
        assertThat(t.get("bleMac").asText()).isEqualTo(mac);
        assertThat(t.get("sn").asText()).isEqualTo(sn);
        assertThat(t.get("action").asText()).isEqualTo("OPEN");
        assertThat(t.has("payload")).isFalse();
        Instant expireAt = OffsetDateTime.parse(t.get("expireAt").asText()).toInstant();
        assertThat(Duration.between(before, expireAt)).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(61));

        String m = ticket(member, "CLOSE");
        assertThat(m).isNotEqualTo(t.get("ticketId").asText());
        assertThat(jdbc.sql(
                                "SELECT CONCAT(user_id, ' ', action, ' ', role, ' ', used,"
                                        + " TIMESTAMPDIFF(SECOND, created_at, expire_at)) FROM ble_ticket WHERE device_id = ? ORDER BY id")
                        .param(device)
                        .query(String.class)
                        .list())
                .containsExactly(owner + " OPEN OWNER 060", member + " CLOSE MEMBER 060");
        assertThat(data(as(owner, get("/api/app/devices/{d}", device)))
                        .get("bleAvailable")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void issueChecksPermissionDeviceAndAction() throws Exception {
        expectError(issue(user("路人"), device, "OPEN"), ErrorCode.NOT_FOUND);
        long expired = user("过期");
        member(expired, "NOW(3) - INTERVAL 2 DAY", "NOW(3) - INTERVAL 1 DAY");
        expectError(issue(expired, device, "OPEN"), ErrorCode.ACCESS_EXPIRED);
        expectError(issue(owner, device, "QUERY_STATUS"), ErrorCode.BAD_REQUEST);
        expectError(issue(owner, device, "JUMP"), ErrorCode.BAD_REQUEST);
        expectError(as(owner, post("/api/app/devices/{d}/ble-tickets", device).content("{}")), ErrorCode.BAD_REQUEST);

        long noMac = device(owner, null);
        expectError(issue(owner, noMac, "OPEN"), ErrorCode.BLE_NOT_AVAILABLE);
        assertThat(data(as(owner, get("/api/app/devices/{d}", noMac)))
                        .get("bleAvailable")
                        .asBoolean())
                .isFalse();

        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        expectError(issue(member, device, "OPEN"), ErrorCode.DEVICE_DISABLED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ble_ticket WHERE device_id IN (?, ?)")
                        .params(device, noMac)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void switchOffMeansNotAvailable() {
        var off = new BleTicketService(jdbc, tx, false, Clock.systemUTC());
        var grant = new DeviceGrant(device, owner, DeviceGrant.Role.OWNER, null, null, true);
        assertThatThrownBy(() -> off.issue(grant, CommandAction.OPEN))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BLE_NOT_AVAILABLE));
    }

    // ------------------------------------------------------------ 上报

    @Test
    void successGoesIntoRecordsAndRepeatsAreIgnored() throws Exception {
        String t = ticket(member, "OPEN");
        JsonNode out = data(send(member, List.of(report(t, "OPEN", "SUCCESS", null))));
        assertThat(out.get("accepted").asInt()).isEqualTo(1);
        assertThat(out.get("rejected")).isEmpty();
        assertThat(commands()).containsExactly("BLE OPEN SUCCESS MEMBER " + member + " - 2300");
        assertThat(ticketRow(t)).isEqualTo("1 SUCCESS - 1 1");
        assertThat(jdbc.sql(
                                "SELECT CONCAT(ble_ticket_id, ' ', idempotency_key) FROM control_command WHERE device_id = ?")
                        .param(device)
                        .query(String.class)
                        .single())
                .isEqualTo(t + " ble:" + t);

        // 主人、本人的操作记录里都能看到，通道为蓝牙
        JsonNode first = data(as(owner, get("/api/app/devices/{d}/records", device)))
                .get("items")
                .get(0);
        assertThat(first.get("channel").asText()).isEqualTo("BLE");
        assertThat(first.get("result").asText()).isEqualTo("SUCCESS");
        assertThat(first.get("operatorName").asText()).isEqualTo("家人");
        assertThat(data(as(member, get("/api/app/devices/{d}/records", device)))
                        .get("items")
                        .get(0)
                        .get("channel")
                        .asText())
                .isEqualTo("BLE");

        // 补报重复：忽略但计入 accepted；同一批里重复也一样
        out = data(
                send(member, List.of(report(t, "OPEN", "SUCCESS", null), report(t, "OPEN", "FAILED", "DEVICE_ERROR"))));
        assertThat(out.get("accepted").asInt()).isEqualTo(2);
        assertThat(commands()).hasSize(1);
        assertThat(ticketRow(t)).isEqualTo("1 SUCCESS - 1 1");
    }

    @Test
    void onlyFailuresThatReachedTheDeviceAreRecorded() throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, String> reasons = new java.util.LinkedHashMap<>();
        for (FailReason reason : FailReason.values()) {
            String t = ticket(owner, "CLOSE");
            reasons.put(t, reason.name());
            batch.add(report(t, "CLOSE", "FAILED", reason.name()));
        }
        assertThat(data(send(owner, batch)).get("accepted").asInt()).isEqualTo(5);
        assertThat(commands())
                .containsExactly(
                        "BLE CLOSE FAILED OWNER " + owner + " - 2300", "BLE CLOSE FAILED OWNER " + owner + " - 2300");
        reasons.forEach((t, reason) -> assertThat(ticketRow(t))
                .isEqualTo("1 FAILED " + reason + " "
                        + (reason.equals("TICKET_REJECTED") || reason.equals("DEVICE_ERROR") ? "1" : "0") + " 1"));
    }

    @Test
    void foreignUnknownAndMismatchedTicketsAreRejected() throws Exception {
        String mine = ticket(member, "OPEN");
        String others = ticket(owner, "OPEN");
        String unknown = "0".repeat(32);
        JsonNode out = data(send(
                member,
                List.of(
                        report(others, "OPEN", "SUCCESS", null),
                        report(unknown, "OPEN", "SUCCESS", null),
                        report(mine, "CLOSE", "SUCCESS", null))));
        assertThat(out.get("accepted").asInt()).isZero();
        assertThat(json.convertValue(out.get("rejected"), List.class)).containsExactly(others, unknown, mine);
        assertThat(commands()).isEmpty();
        assertThat(ticketRow(mine)).isEqualTo("0 - - 0 0");
        assertThat(ticketRow(others)).isEqualTo("0 - - 0 0");

        // 拒绝的不占用票据：动作对了仍可上报
        out = data(send(member, List.of(report(mine, "OPEN", "SUCCESS", null))));
        assertThat(out.get("accepted").asInt()).isEqualTo(1);
    }

    @Test
    void lateReportsAreAcceptedAfterAccessIsGone() throws Exception {
        String t = ticket(member, "OPEN");
        jdbc.sql("UPDATE device_member SET status = 'REVOKED', ended_at = NOW(3) WHERE device_id = ? AND user_id = ?")
                .params(device, member)
                .update();
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
        jdbc.sql("UPDATE ble_ticket SET expire_at = NOW(3) - INTERVAL 1 HOUR, created_at = NOW(3) - INTERVAL 61 MINUTE"
                        + " WHERE ticket_id = ?")
                .param(t)
                .update();
        assertThat(data(send(member, List.of(report(t, "OPEN", "SUCCESS", null))))
                        .get("accepted")
                        .asInt())
                .isEqualTo(1);
        assertThat(commands()).hasSize(1);
    }

    @Test
    void reportBatchIsValidated() throws Exception {
        String t = ticket(owner, "OPEN");
        expectError(send(owner, List.of()), ErrorCode.BAD_REQUEST);
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(report(t, "OPEN", "SUCCESS", null));
        }
        expectError(send(owner, tooMany), ErrorCode.BAD_REQUEST);
        expectError(as(owner, post("/api/app/ble-reports").content("{}")), ErrorCode.BAD_REQUEST);
        expectError(send(owner, List.of(report(t, "OPEN", "SUCCESS", "BLE_OFF"))), ErrorCode.BAD_REQUEST);
        expectError(send(owner, List.of(report(t, "OPEN", "FAILED", null))), ErrorCode.BAD_REQUEST);
        expectError(send(owner, List.of(report(t, "QUERY_STATUS", "SUCCESS", null))), ErrorCode.BAD_REQUEST);
        expectError(
                send(owner, List.of(report(t, "OPEN", "SUCCESS", null), report("XYZ", "OPEN", "SUCCESS", null))),
                ErrorCode.BAD_REQUEST);
        expectError(send(owner, List.of(report(t, "OPEN", "MAYBE", null))), ErrorCode.BAD_REQUEST);
        for (String field : List.of("ticketId", "action", "result", "at")) {
            Map<String, Object> r = report(t, "OPEN", "SUCCESS", null);
            r.remove(field);
            expectError(send(owner, List.of(r)), ErrorCode.BAD_REQUEST);
        }
        // 整批拒绝，什么都没写
        assertThat(ticketRow(t)).isEqualTo("0 - - 0 0");
        assertThat(commands()).isEmpty();
    }

    @Test
    void phoneTimeIsClampedIntoTheTicketWindowAndOddDurationsDropped() {
        var service = new BleTicketService(jdbc, tx, true, Clock.systemUTC());
        var grant = new DeviceGrant(device, owner, DeviceGrant.Role.OWNER, null, null, true);
        String early = service.issue(grant, CommandAction.OPEN).ticketId();
        String late = service.issue(grant, CommandAction.OPEN).ticketId();
        String inside = service.issue(grant, CommandAction.OPEN).ticketId();
        Instant now = Instant.now();
        var outcome = service.report(
                owner,
                List.of(
                        new Report(early, CommandAction.OPEN, Result.SUCCESS, null, now.minusSeconds(3600), 60_001),
                        new Report(late, CommandAction.OPEN, Result.SUCCESS, null, now.plusSeconds(3600), -1),
                        new Report(inside, CommandAction.OPEN, Result.SUCCESS, null, now.plusSeconds(10), 60_000)));
        assertThat(outcome.accepted()).isEqualTo(3);

        assertThat(times(early)).containsExactly("created", "-");
        assertThat(times(late)).containsExactly("expire", "-");
        assertThat(times(inside)).containsExactly("inside", "60000");
    }

    /** 指令的 issued_at 落在票据窗口的哪里，以及耗时 */
    private List<String> times(String ticketId) {
        return jdbc.sql(
                        """
                        SELECT CASE WHEN c.issued_at = t.created_at THEN 'created'
                                    WHEN c.issued_at = t.expire_at THEN 'expire'
                                    WHEN c.issued_at > t.created_at AND c.issued_at < t.expire_at
                                         AND t.used_at = c.issued_at THEN 'inside'
                                    ELSE 'other' END,
                               COALESCE(c.duration_ms, '-'),
                               TIMESTAMPDIFF(MICROSECOND, c.issued_at, c.finished_at) DIV 1000 = COALESCE(c.duration_ms, 0)
                          FROM ble_ticket t JOIN control_command c ON c.id = t.command_id WHERE t.ticket_id = ?""")
                .param(ticketId)
                .query((rs, n) -> {
                    assertThat(rs.getBoolean(3))
                            .as("finished_at = issued_at + duration")
                            .isTrue();
                    return List.of(rs.getString(1), rs.getString(2));
                })
                .single();
    }

    // ------------------------------------------------------------ 告警

    @Test
    void bleResultsDoNotBreakTheRemoteTimeoutStreak() throws Exception {
        Instant base = Instant.now().minusSeconds(600);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            jdbc.sql(
                            "INSERT INTO control_command (device_id, user_id, role, channel, action, status, issued_at, finished_at)"
                                    + " VALUES (?, ?, 'OWNER', 'REMOTE', 'OPEN', 'TIMEOUT', ?, ?)")
                    .params(
                            device,
                            owner,
                            DbTime.toDb(base.plusSeconds(i * 60L)),
                            DbTime.toDb(base.plusSeconds(i * 60L + 15)))
                    .update();
            if (i < 2) {
                // 两次远程超时之间用蓝牙成功开过闸
                String t = ticket(owner, "OPEN");
                jdbc.sql("UPDATE ble_ticket SET created_at = ?, expire_at = ? WHERE ticket_id = ?")
                        .params(
                                DbTime.toDb(base.plusSeconds(i * 60L + 20)),
                                DbTime.toDb(base.plusSeconds(i * 60L + 80)),
                                t)
                        .update();
                Map<String, Object> r = report(t, "OPEN", "SUCCESS", null);
                r.put(
                        "at",
                        base.plusSeconds(i * 60L + 30).atOffset(ZoneOffset.UTC).toString());
                send(owner, List.of(r)).andExpect(status().isOk());
            }
        }
        long last = jdbc.sql("SELECT MAX(id) FROM control_command WHERE device_id = ? AND channel = 'REMOTE'")
                .param(device)
                .query(Long.class)
                .single();
        events.publishEvent(new CommandFinishedEvent(
                last, device, CommandAction.OPEN, CommandStatus.TIMEOUT, null, base.plusSeconds(135)));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM alarm WHERE device_id = ? AND type = 'CMD_TIMEOUT'")
                        .param(device)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(commands()).hasSize(5);
    }
}
