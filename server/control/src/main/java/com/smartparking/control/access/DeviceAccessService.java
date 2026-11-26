package com.smartparking.control.access;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 统一权限判定（PRD 9）：用户未被封禁，且是设备主人，或是状态为生效且当前时间在 [valid_from, valid_to) 内的被授权人；
 * 再按 {@link AccessLevel} 要求主人身份、设备启用。所有设备类接口都必须经过这里，见 {@link DeviceAccess}。
 *
 * <p>判定顺序与错误码（以接口契约为准）：
 *
 * <ol>
 *   <li>用户已封禁 → 10010；用户不存在 → 10002。
 *   <li>设备不存在，或用户与设备从无关系 → 10004。两种情况返回同一错误码，不向无关的人透露设备是否存在。
 *   <li>曾是 / 将是被授权人但当前不在有效期内（已过期、已撤销、已退出、尚未开始）→ 主人级接口 30004，其余 30005。
 *   <li>主人级接口，调用人是被授权人 → 30004。
 *   <li>要求启用而设备已禁用 → 20002。放在关系判定之后，无关的人看不到设备是否被禁用。
 * </ol>
 *
 * <p>有效期以 {@code valid_from / valid_to} 为准，不依赖到期任务（BIZ-12）先把状态改成 EXPIRED：到期那一刻起就拒绝。
 * 主人被封禁不影响被授权人（PRD 5.8②）。
 */
public class DeviceAccessService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbc;
    private final Clock clock;

    public DeviceAccessService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private record Row(
            String userStatus,
            boolean deviceFound,
            boolean enabled,
            boolean owner,
            Long memberId,
            LocalDateTime validTo,
            boolean everMember,
            boolean notStarted) {}

    /** 判定通过返回结果，否则抛 {@link BizException}（错误码见类注释） */
    public DeviceGrant check(long userId, long deviceId, AccessLevel level) {
        Row row = load(userId, deviceId);

        if (row.userStatus() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if ("BANNED".equals(row.userStatus())) {
            throw new BizException(ErrorCode.USER_BANNED);
        }
        if (!row.deviceFound()) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }

        DeviceGrant grant;
        if (row.owner()) {
            grant = new DeviceGrant(deviceId, userId, DeviceGrant.Role.OWNER, null, null, row.enabled());
        } else if (row.memberId() != null) {
            Instant validTo =
                    row.validTo() == null ? null : row.validTo().atZone(ZONE).toInstant();
            grant = new DeviceGrant(deviceId, userId, DeviceGrant.Role.MEMBER, row.memberId(), validTo, row.enabled());
        } else if (row.everMember()) {
            if (level.ownerOnly()) {
                throw new BizException(ErrorCode.NOT_OWNER);
            }
            throw row.notStarted()
                    ? new BizException(ErrorCode.ACCESS_EXPIRED, "授权尚未生效")
                    : new BizException(ErrorCode.ACCESS_EXPIRED);
        } else {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
        }

        if (level.ownerOnly() && !grant.isOwner()) {
            throw new BizException(ErrorCode.NOT_OWNER);
        }
        if (level.requiresEnabled() && !grant.deviceEnabled()) {
            throw new BizException(ErrorCode.DEVICE_DISABLED);
        }
        return grant;
    }

    private Row load(long userId, long deviceId) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZONE);
        return jdbc.sql(
                        """
                        SELECT u.status AS user_status,
                               d.id IS NOT NULL AS device_found,
                               COALESCE(d.enabled, 0) AS enabled,
                               COALESCE(d.bind_status = 'BOUND' AND d.owner_user_id = u.id, 0) AS is_owner,
                               m.id AS member_id,
                               m.valid_to,
                               EXISTS (SELECT 1 FROM device_member h
                                        WHERE h.device_id = :device AND h.user_id = :user) AS ever_member,
                               EXISTS (SELECT 1 FROM device_member f
                                        WHERE f.device_id = :device AND f.user_id = :user
                                          AND f.status = 'ACTIVE' AND f.valid_from > :now) AS not_started
                          FROM (SELECT :user AS id) q
                          LEFT JOIN app_user u ON u.id = q.id
                          LEFT JOIN device d ON d.id = :device
                          LEFT JOIN device_member m
                                 ON m.device_id = d.id AND m.user_id = u.id AND m.status = 'ACTIVE'
                                AND m.valid_from <= :now AND (m.valid_to IS NULL OR m.valid_to > :now)""")
                .param("user", userId)
                .param("device", deviceId)
                .param("now", now)
                .query((rs, n) -> new Row(
                        rs.getString("user_status"),
                        rs.getBoolean("device_found"),
                        rs.getBoolean("enabled"),
                        rs.getBoolean("is_owner"),
                        rs.getObject("member_id") == null ? null : rs.getLong("member_id"),
                        rs.getObject("valid_to", LocalDateTime.class),
                        rs.getBoolean("ever_member"),
                        rs.getBoolean("not_started")))
                .single();
    }
}
