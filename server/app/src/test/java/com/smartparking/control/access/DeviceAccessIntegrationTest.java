package com.smartparking.control.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.TestcontainersConfiguration;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.user.session.SessionStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 统一权限判定（BIZ-02）：角色 × 级别 × 有效期 × 设备禁用 × 封禁的判定矩阵。MySQL / Redis 为真实容器。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DeviceAccessIntegrationTest.AccessProbe.class})
class DeviceAccessIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每个级别一个探针接口，返回判定结果 */
    @RestController
    static class AccessProbe {
        @GetMapping("/api/app/__probe/devices/{deviceId}/view")
        @DeviceAccess(AccessLevel.VIEW)
        public ApiResponse<DeviceGrant> view(@PathVariable long deviceId, DeviceGrant grant) {
            return ApiResponse.ok(grant);
        }

        @GetMapping("/api/app/__probe/devices/{deviceId}/control")
        @DeviceAccess(AccessLevel.CONTROL)
        public ApiResponse<DeviceGrant> control(DeviceGrant grant) {
            return ApiResponse.ok(grant);
        }

        @GetMapping("/api/app/__probe/devices/{deviceId}/owner")
        @DeviceAccess(AccessLevel.OWNER)
        public ApiResponse<DeviceGrant> owner(DeviceGrant grant) {
            return ApiResponse.ok(grant);
        }

        @GetMapping("/api/app/__probe/devices/{deviceId}/owner-enabled")
        @DeviceAccess(AccessLevel.OWNER_ENABLED)
        public ApiResponse<DeviceGrant> ownerEnabled(DeviceGrant grant) {
            return ApiResponse.ok(grant);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionStore sessions;

    @Autowired
    private DeviceAccessService access;

    private long owner;
    private long device;

    @BeforeEach
    void setUp() {
        owner = user();
        device = device(owner);
    }

    // ------------------------------------------------------------ 造数据

    private long user() {
        String openid = "it-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (openid) VALUES (?)").param(openid).update();
        return jdbc.sql("SELECT id FROM app_user WHERE openid = ?")
                .param(openid)
                .query(Long.class)
                .single();
    }

    private long device(Long ownerId) {
        String imei = "86" + String.format("%013d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.sql(
                        """
                        INSERT INTO device (sn, imei, model, batch_no, activate_code_hash, bind_status, owner_user_id, owner_since)
                        VALUES (?, ?, 'SP-G1', 'IT', 'x', ?, ?, ?)""")
                .params(
                        "SN" + imei,
                        imei,
                        ownerId == null ? "PENDING" : "BOUND",
                        ownerId,
                        ownerId == null ? null : LocalDateTime.now(ZONE))
                .update();
        return jdbc.sql("SELECT id FROM device WHERE imei = ?")
                .param(imei)
                .query(Long.class)
                .single();
    }

    private long member(long userId, String status, Duration fromAgo, Duration toFromNow) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        jdbc.sql(
                        """
                        INSERT INTO device_member (device_id, user_id, valid_from, valid_to, status, granted_by)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(
                        device,
                        userId,
                        now.minus(fromAgo),
                        toFromNow == null ? null : now.plus(toFromNow),
                        status,
                        owner)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM device_member WHERE device_id = ? AND user_id = ?")
                .params(device, userId)
                .query(Long.class)
                .single();
    }

    /** 生效中、永久 */
    private long activeMember() {
        long u = user();
        member(u, "ACTIVE", Duration.ofDays(1), null);
        return u;
    }

    private void disable() {
        jdbc.sql("UPDATE device SET enabled = 0 WHERE id = ?").param(device).update();
    }

    private void ban(long userId) {
        jdbc.sql("UPDATE app_user SET status = 'BANNED' WHERE id = ?")
                .param(userId)
                .update();
    }

    private ResultActions probe(long userId, Object deviceId, String level) throws Exception {
        String token =
                sessions.create(userId, Duration.ofMinutes(5), Instant.now()).token();
        return mvc.perform(get("/api/app/__probe/devices/" + deviceId + "/" + level)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void expect(long userId, String level, ErrorCode code) throws Exception {
        probe(userId, device, level)
                .andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    private static final String[] ALL = {"view", "control", "owner", "owner-enabled"};

    // ------------------------------------------------------------ 主人

    @Test
    void ownerPassesEveryLevel() throws Exception {
        for (String level : ALL) {
            expect(owner, level, ErrorCode.OK);
        }
        probe(owner, device, "control")
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.deviceId").value(device))
                .andExpect(jsonPath("$.data.userId").value(owner))
                .andExpect(jsonPath("$.data.memberId").isEmpty())
                .andExpect(jsonPath("$.data.deviceEnabled").value(true));
    }

    @Test
    void formerOwnerAfterUnbindIsStranger() throws Exception {
        jdbc.sql("UPDATE device SET bind_status = 'PENDING', owner_user_id = NULL WHERE id = ?")
                .param(device)
                .update();
        for (String level : ALL) {
            expect(owner, level, ErrorCode.NOT_FOUND);
        }
    }

    @Test
    void pendingDeviceHasNoOwnerEvenIfOwnerColumnIsStale() throws Exception {
        // 库约束允许待激活设备仍带 owner_user_id；判定以绑定状态为准，不认残留的主人
        jdbc.sql("UPDATE device SET bind_status = 'PENDING' WHERE id = ?")
                .param(device)
                .update();
        expect(owner, "view", ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------ 被授权人

    @Test
    void activeMemberMayViewAndControlButNotManage() throws Exception {
        long m = activeMember();
        expect(m, "view", ErrorCode.OK);
        expect(m, "control", ErrorCode.OK);
        expect(m, "owner", ErrorCode.NOT_OWNER);
        expect(m, "owner-enabled", ErrorCode.NOT_OWNER);
        probe(m, device, "control").andExpect(jsonPath("$.data.role").value("MEMBER"));
    }

    @Test
    void memberGrantCarriesMemberIdAndValidTo() {
        long u = user();
        long memberId = member(u, "ACTIVE", Duration.ofHours(1), Duration.ofDays(7));
        LocalDateTime validTo = jdbc.sql("SELECT valid_to FROM device_member WHERE id = ?")
                .param(memberId)
                .query(LocalDateTime.class)
                .single();

        DeviceGrant grant = access.check(u, device, AccessLevel.CONTROL);
        assertThat(grant.memberId()).isEqualTo(memberId);
        assertThat(grant.validTo()).isEqualTo(validTo.atZone(ZONE).toInstant());
        assertThat(grant.validTo()).isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void expiredWindowIsRejectedEvenIfStatusStillActive() throws Exception {
        // 到期任务（BIZ-12）还没把状态改成 EXPIRED，也必须按有效期拒绝
        long u = user();
        member(u, "ACTIVE", Duration.ofDays(2), Duration.ofDays(-1));
        expect(u, "view", ErrorCode.ACCESS_EXPIRED);
        expect(u, "control", ErrorCode.ACCESS_EXPIRED);
        expect(u, "owner", ErrorCode.NOT_OWNER);
    }

    @Test
    void endedMembershipsAreRejected() throws Exception {
        for (String status : new String[] {"REVOKED", "EXPIRED", "QUIT"}) {
            long u = user();
            member(u, status, Duration.ofDays(1), null);
            expect(u, "control", ErrorCode.ACCESS_EXPIRED);
            expect(u, "owner-enabled", ErrorCode.NOT_OWNER);
        }
    }

    @Test
    void notYetStartedMembershipSaysSo() throws Exception {
        long u = user();
        member(u, "ACTIVE", Duration.ofHours(-2), Duration.ofDays(1));
        probe(u, device, "control")
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_EXPIRED.code()))
                .andExpect(jsonPath("$.message").value("授权尚未生效"));
    }

    @Test
    void revokedThenReinvitedMemberUsesActiveRow() throws Exception {
        long u = user();
        member(u, "REVOKED", Duration.ofDays(10), null);
        long current = member(u, "ACTIVE", Duration.ofMinutes(1), Duration.ofDays(1));
        probe(u, device, "control").andExpect(jsonPath("$.data.memberId").value(current));
    }

    @Test
    void membershipOfOtherDeviceDoesNotCount() throws Exception {
        long u = activeMember();
        long other = device(user());
        probe(u, other, "view").andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
    }

    // ------------------------------------------------------------ 无关的人与不存在的设备

    @Test
    void strangerAndMissingDeviceLookTheSame() throws Exception {
        long stranger = user();
        for (String level : ALL) {
            expect(stranger, level, ErrorCode.NOT_FOUND);
        }
        probe(owner, Long.MAX_VALUE, "view").andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
        probe(owner, "abc", "view").andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
        probe(owner, 0, "view").andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
    }

    // ------------------------------------------------------------ 设备禁用（PRD 5.8①）

    @Test
    void disabledDeviceBlocksControlAndOwnerWritesOnly() throws Exception {
        long m = activeMember();
        disable();

        expect(owner, "view", ErrorCode.OK);
        expect(owner, "owner", ErrorCode.OK);
        expect(owner, "control", ErrorCode.DEVICE_DISABLED);
        expect(owner, "owner-enabled", ErrorCode.DEVICE_DISABLED);
        probe(owner, device, "view").andExpect(jsonPath("$.data.deviceEnabled").value(false));

        expect(m, "view", ErrorCode.OK);
        expect(m, "control", ErrorCode.DEVICE_DISABLED);
        // 关系判定在前：被授权人做主人级操作仍是 30004
        expect(m, "owner-enabled", ErrorCode.NOT_OWNER);

        // 无关的人看不出设备被禁用
        expect(user(), "control", ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------ 封禁（PRD 5.8②）

    @Test
    void bannedUserIsRejectedByServiceToo() {
        long m = activeMember();
        ban(m);
        assertThatThrownBy(() -> access.check(m, device, AccessLevel.VIEW))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_BANNED));
        assertThatThrownBy(() -> access.check(Long.MAX_VALUE, device, AccessLevel.VIEW))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void bannedOwnerDoesNotAffectMembers() throws Exception {
        long m = activeMember();
        ban(owner);
        expect(m, "control", ErrorCode.OK);
        expect(owner, "view", ErrorCode.USER_BANNED);
    }

    @Test
    void probeRequiresLogin() throws Exception {
        mvc.perform(get("/api/app/__probe/devices/" + device + "/view"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code()));
    }

    @Test
    void unknownPathIsNotFoundNotServerError() throws Exception {
        mvc.perform(get("/api/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
    }
}
