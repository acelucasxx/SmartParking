package com.smartparking.member.invite;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.devices.DeviceViews;
import com.smartparking.control.devices.DeviceViews.DeviceCard;
import com.smartparking.iot.DbTime;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 邀请家人（PRD 5.5）：创建、撤回、预览、接受。
 *
 * <ul>
 *   <li>名额 = 生效中的被授权人（未到期）+ 未过期的待接受邀请，上限为后台参数 {@code member.max_per_device}（默认 5）。
 *   <li>邀请卡片有效期为后台参数 {@code card.ttl_hours}（默认 24 小时），过期即释放名额（按时间判定，不依赖定时任务）。
 *   <li>创建与接受都先锁设备行：同一设备的并发邀请、接受串行，名额不会超；与解绑（先锁设备、再改邀请）加锁顺序一致，不会死锁。
 * </ul>
 */
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    public static final int REMARK_MAX = 32;
    static final String DEFAULT_NICKNAME = "微信用户";
    static final String SHARE_PATH = "/pages/invite/accept?token=";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SystemSettings settings;
    private final DeviceViews views;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final InviteTokens tokens = new InviteTokens();

    public InviteService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            SystemSettings settings,
            DeviceViews views,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.views = views;
        this.events = events;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    // ------------------------------------------------------------ 创建

    /** 契约 ShareCard；token 明文只在这里出现一次 */
    public record ShareCard(long id, String token, Instant expireAt, String path, String title) {}

    /** 调用方已按 OWNER_ENABLED 判定（主人、设备启用）。名额已满 30010；有效期不合法 30011；备注超长 10001 */
    public ShareCard create(DeviceGrant owner, Period period, String rawRemark) {
        String remark = remark(rawRemark);
        Instant now = clock.instant();
        String token = tokens.generate();
        Instant expireAt =
                now.plus(Duration.ofHours(Math.max(1, settings.getInt(SystemSettings.Keys.CARD_TTL_HOURS, 24))));
        int limit = limit();
        long id = Objects.requireNonNull(tx.execute(status -> {
            DeviceRow device = lockDevice(owner.deviceId());
            if (used(owner.deviceId(), now, null) >= limit) {
                throw new BizException(ErrorCode.MEMBER_LIMIT_REACHED, "成员名额已满（上限 " + limit + " 人）");
            }
            var keys = new GeneratedKeyHolder();
            jdbc.sql(
                            """
                            INSERT INTO share_invite (token_hash, device_id, inviter_id, period_type, duration_minutes,
                                                      valid_from, valid_to, remark, expire_at)
                            VALUES (:hash, :device, :inviter, :type, :minutes, :from, :to, :remark, :expire)""")
                    .param("hash", InviteTokens.hash(token))
                    .param("device", device.id())
                    .param("inviter", owner.userId())
                    .param("type", period.type().name())
                    .param("minutes", period.durationMinutes())
                    .param("from", DbTime.toDb(period.validFrom()))
                    .param("to", DbTime.toDb(period.validTo()))
                    .param("remark", remark)
                    .param("expire", DbTime.toDb(expireAt))
                    .update(keys, "id");
            return keys.getKeyAs(BigInteger.class).longValueExact();
        }));
        DeviceRow device = device(owner.deviceId()).orElseThrow();
        log.info("用户 {} 为设备 {} 发出邀请 {}（{}）", owner.userId(), device.sn(), id, period.type());
        return new ShareCard(
                id,
                token,
                expireAt,
                SHARE_PATH + token,
                nickname(device.ownerNickname()) + "邀请你使用「" + name(device) + "」");
    }

    // ------------------------------------------------------------ 撤回

    /** 只能撤回本设备待接受的邀请；已撤回的再次撤回视为成功；已接受、已过期、已作废或不存在 10004 */
    public void cancel(DeviceGrant owner, long inviteId) {
        int changed = jdbc.sql(
                        "UPDATE share_invite SET status = 'CANCELLED' WHERE id = ? AND device_id = ? AND status = 'PENDING'"
                                + " AND expire_at > ?")
                .params(inviteId, owner.deviceId(), DbTime.toDb(clock.instant()))
                .update();
        if (changed > 0) {
            log.info("用户 {} 撤回邀请 {}", owner.userId(), inviteId);
            return;
        }
        boolean cancelled =
                jdbc.sql("SELECT COUNT(*) FROM share_invite WHERE id = ? AND device_id = ? AND status = 'CANCELLED'")
                                .params(inviteId, owner.deviceId())
                                .query(Long.class)
                                .single()
                        > 0;
        if (!cancelled) {
            throw new BizException(ErrorCode.NOT_FOUND, "邀请不存在或已失效");
        }
    }

    // ------------------------------------------------------------ 预览

    public enum State {
        VALID,
        EXPIRED,
        ACCEPTED,
        CANCELLED,
        ALREADY_MEMBER,
        OWN_INVITE
    }

    /** 契约 InvitePreview */
    public record Preview(
            String deviceName, String ownerNickname, Period period, String remark, Instant expireAt, State state) {}

    /** token 不存在或格式不对 10004；邀请失效时仍返回，由 {@code state} 告诉页面显示哪种异常态 */
    public Preview preview(long viewerId, String token) {
        InviteRow invite = find(token).orElseThrow(InviteService::notFound);
        DeviceRow device = device(invite.deviceId()).orElseThrow(InviteService::notFound);
        State state = judge(viewerId, invite, device, clock.instant());
        return new Preview(
                name(device),
                nickname(device.ownerNickname()),
                invite.period(),
                invite.remark(),
                invite.expireAt(),
                state);
    }

    /** 判定顺序与接受时的校验一致，只是不抛错 */
    private State judge(long viewerId, InviteRow invite, DeviceRow device, Instant now) {
        if (device.isOwner(viewerId) || invite.inviterId() == viewerId) {
            return State.OWN_INVITE;
        }
        switch (invite.status()) {
            case "ACCEPTED" -> {
                return invite.acceptedBy() != null && invite.acceptedBy() == viewerId
                        ? State.ALREADY_MEMBER
                        : State.ACCEPTED;
            }
            case "CANCELLED", "VOIDED" -> {
                return State.CANCELLED;
            }
            case "EXPIRED" -> {
                return State.EXPIRED;
            }
            default -> {}
        }
        if (!invite.expireAt().isAfter(now) || customEnded(invite, now)) {
            return State.EXPIRED;
        }
        // 主人已变更：解绑 / 转让会作废邀请，这里兜底
        if (!device.isOwner(invite.inviterId())) {
            return State.CANCELLED;
        }
        // 主人被封禁（PRD 5.8②）：其发出的邀请暂不可接受，解封后恢复
        if (device.ownerBanned()) {
            return State.CANCELLED;
        }
        if (isActiveMember(device.id(), viewerId, now)) {
            return State.ALREADY_MEMBER;
        }
        return State.VALID;
    }

    // ------------------------------------------------------------ 接受

    /**
     * 同一事务内再次校验（PRD 5.5⑧）后成为被授权人，返回车位卡片。
     *
     * <ol>
     *   <li>token 不存在 10004
     *   <li>自己发出的邀请、或自己就是主人 30016
     *   <li>已撤回 / 已作废 30014；已被接受 30013（接受人是自己时 30015）；已过期（含自定义有效期已结束）30012
     *   <li>发出人已不是当前主人，或其账号已被封禁 30014
     *   <li>设备已禁用 20002
     *   <li>自己已是生效中的被授权人 30015
     *   <li>名额：其余生效成员与待接受邀请（不含本邀请）已达上限 30010
     * </ol>
     */
    public DeviceCard accept(long userId, String token) {
        InviteRow peek = find(token).orElseThrow(InviteService::notFound);
        Instant now = clock.instant();
        int limit = limit();
        record Accepted(long memberId, long ownerId, Instant validTo, boolean enabled) {}
        Accepted accepted = Objects.requireNonNull(tx.execute(status -> {
            // 先锁设备、再锁邀请，与解绑的加锁顺序一致
            DeviceRow device = lockDevice(peek.deviceId());
            InviteRow invite = jdbc.sql(SELECT_INVITE + " WHERE id = ? FOR UPDATE")
                    .param(peek.id())
                    .query(InviteService::mapInvite)
                    .single();
            if (device.isOwner(userId) || invite.inviterId() == userId) {
                throw new BizException(ErrorCode.CANNOT_ACCEPT_OWN);
            }
            switch (invite.status()) {
                case "CANCELLED", "VOIDED" -> throw new BizException(ErrorCode.INVITE_CANCELLED);
                case "ACCEPTED" -> throw invite.acceptedBy() != null && invite.acceptedBy() == userId
                        ? new BizException(ErrorCode.ALREADY_MEMBER)
                        : new BizException(ErrorCode.INVITE_USED);
                case "EXPIRED" -> throw new BizException(ErrorCode.INVITE_EXPIRED);
                default -> {}
            }
            if (!invite.expireAt().isAfter(now)) {
                throw new BizException(ErrorCode.INVITE_EXPIRED);
            }
            if (customEnded(invite, now)) {
                throw new BizException(ErrorCode.INVITE_EXPIRED, "邀请的有效期已结束");
            }
            if (!device.isOwner(invite.inviterId())) {
                throw new BizException(ErrorCode.INVITE_CANCELLED, "设备主人已变更，邀请已失效");
            }
            if (device.ownerBanned()) {
                throw new BizException(ErrorCode.INVITE_CANCELLED, "设备主人账号已停用，邀请暂不可用");
            }
            if (!device.enabled()) {
                throw new BizException(ErrorCode.DEVICE_DISABLED);
            }
            // 已过有效期、但到期任务（BIZ-12）还没处理的旧授权：先置为过期，不挡住新的邀请
            jdbc.sql(
                            "UPDATE device_member SET status = 'EXPIRED', ended_at = valid_to WHERE device_id = ? AND user_id = ?"
                                    + " AND status = 'ACTIVE' AND valid_to <= ?")
                    .params(device.id(), userId, DbTime.toDb(now))
                    .update();
            if (isActiveMember(device.id(), userId, now)) {
                throw new BizException(ErrorCode.ALREADY_MEMBER);
            }
            if (used(device.id(), now, invite.id()) >= limit) {
                throw new BizException(ErrorCode.MEMBER_LIMIT_REACHED, "成员名额已满（上限 " + limit + " 人）");
            }
            Instant[] range = invite.period().resolve(now);
            long memberId;
            try {
                var keys = new GeneratedKeyHolder();
                jdbc.sql(
                                """
                                INSERT INTO device_member (device_id, user_id, remark, valid_from, valid_to, granted_by, invite_id)
                                VALUES (:device, :user, :remark, :from, :to, :granter, :invite)""")
                        .param("device", device.id())
                        .param("user", userId)
                        .param("remark", invite.remark())
                        .param("from", DbTime.toDb(range[0]))
                        .param("to", DbTime.toDb(range[1]))
                        .param("granter", invite.inviterId())
                        .param("invite", invite.id())
                        .update(keys, "id");
                memberId = keys.getKeyAs(BigInteger.class).longValueExact();
            } catch (DuplicateKeyException e) {
                throw new BizException(ErrorCode.ALREADY_MEMBER);
            }
            jdbc.sql("UPDATE share_invite SET status = 'ACCEPTED', accepted_by = ?, accepted_at = ? WHERE id = ?")
                    .params(userId, DbTime.toDb(now), invite.id())
                    .update();
            return new Accepted(memberId, invite.inviterId(), range[1], device.enabled());
        }));
        log.info("用户 {} 接受邀请 {}，成为设备 {} 的被授权人", userId, peek.id(), peek.deviceId());
        events.publishEvent(new MemberGrantedEvent(
                peek.deviceId(), accepted.ownerId(), userId, accepted.memberId(), peek.id(), now));
        return views.card(new DeviceGrant(
                peek.deviceId(),
                userId,
                DeviceGrant.Role.MEMBER,
                accepted.memberId(),
                accepted.validTo(),
                accepted.enabled()));
    }

    // ------------------------------------------------------------ 过期

    /** 把已过期的待接受邀请标为 EXPIRED。名额与接受都按时间判定，这里只是让数据与状态一致 */
    @Scheduled(fixedDelayString = "${smartparking.invite.expire-interval:5m}", initialDelayString = "1m")
    public void expire() {
        try {
            int n = jdbc.sql("UPDATE share_invite SET status = 'EXPIRED' WHERE status = 'PENDING' AND expire_at <= ?")
                    .param(DbTime.toDb(clock.instant()))
                    .update();
            if (n > 0) {
                log.info("{} 条邀请已过期", n);
            }
        } catch (RuntimeException e) {
            log.error("标记过期邀请失败", e);
        }
    }

    // ------------------------------------------------------------

    private record DeviceRow(
            long id,
            String sn,
            String name,
            boolean enabled,
            String bindStatus,
            Long ownerId,
            String ownerNickname,
            boolean ownerBanned) {

        boolean isOwner(long userId) {
            return "BOUND".equals(bindStatus) && ownerId != null && ownerId == userId;
        }
    }

    private record InviteRow(
            long id,
            long deviceId,
            long inviterId,
            Period period,
            String remark,
            Instant expireAt,
            String status,
            Long acceptedBy) {}

    private static final String SELECT_DEVICE =
            """
            SELECT d.id, d.sn, d.name, d.enabled, d.bind_status, d.owner_user_id, u.nickname, u.status AS owner_status
            FROM device d LEFT JOIN app_user u ON u.id = d.owner_user_id""";

    private static final String SELECT_INVITE =
            """
            SELECT id, device_id, inviter_id, period_type, duration_minutes, valid_from, valid_to, remark, expire_at, status,
                   accepted_by
            FROM share_invite""";

    private DeviceRow lockDevice(long deviceId) {
        return jdbc.sql("SELECT id FROM device WHERE id = ? FOR UPDATE")
                .param(deviceId)
                .query(Long.class)
                .optional()
                .flatMap(id -> device(id))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "设备不存在"));
    }

    private Optional<DeviceRow> device(long deviceId) {
        return jdbc.sql(SELECT_DEVICE + " WHERE d.id = ?")
                .param(deviceId)
                .query((rs, n) -> new DeviceRow(
                        rs.getLong("id"),
                        rs.getString("sn"),
                        rs.getString("name"),
                        rs.getBoolean("enabled"),
                        rs.getString("bind_status"),
                        rs.getObject("owner_user_id", Long.class),
                        rs.getString("nickname"),
                        "BANNED".equals(rs.getString("owner_status"))))
                .optional();
    }

    private Optional<InviteRow> find(String token) {
        if (!InviteTokens.wellFormed(token)) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT_INVITE + " WHERE token_hash = ?")
                .param(InviteTokens.hash(token))
                .query(InviteService::mapInvite)
                .optional();
    }

    private static InviteRow mapInvite(ResultSet rs, int n) throws SQLException {
        return new InviteRow(
                rs.getLong("id"),
                rs.getLong("device_id"),
                rs.getLong("inviter_id"),
                Period.stored(
                        rs.getString("period_type"),
                        rs.getObject("duration_minutes", Integer.class),
                        DbTime.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
                        DbTime.fromDb(rs.getObject("valid_to", LocalDateTime.class))),
                rs.getString("remark"),
                DbTime.fromDb(rs.getObject("expire_at", LocalDateTime.class)),
                rs.getString("status"),
                rs.getObject("accepted_by", Long.class));
    }

    /** 名额占用：生效中且未到期的被授权人 + 未过期的待接受邀请（{@code exceptInvite} 除外） */
    private int used(long deviceId, Instant now, Long exceptInvite) {
        LocalDateTime at = DbTime.toDb(now);
        return jdbc.sql(
                        """
                        SELECT (SELECT COUNT(*) FROM device_member
                                WHERE device_id = :device AND status = 'ACTIVE' AND (valid_to IS NULL OR valid_to > :now))
                             + (SELECT COUNT(*) FROM share_invite
                                WHERE device_id = :device AND status = 'PENDING' AND expire_at > :now AND id <> :except)""")
                .param("device", deviceId)
                .param("now", at)
                .param("except", exceptInvite == null ? 0L : exceptInvite)
                .query(Integer.class)
                .single();
    }

    /** 生效中且未到期（含尚未开始）的授权 */
    private boolean isActiveMember(long deviceId, long userId, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM device_member WHERE device_id = ? AND user_id = ? AND status = 'ACTIVE'"
                                + " AND (valid_to IS NULL OR valid_to > ?)")
                        .params(deviceId, userId, DbTime.toDb(now))
                        .query(Long.class)
                        .single()
                > 0;
    }

    private static boolean customEnded(InviteRow invite, Instant now) {
        return invite.period().type() == Period.Type.CUSTOM
                && !invite.period().validTo().isAfter(now);
    }

    private int limit() {
        return Math.max(1, settings.getInt(SystemSettings.Keys.MEMBER_MAX_PER_DEVICE, 5));
    }

    /** 备注：去首尾空白，空串为 null，至多 32 个字符（超长 10001） */
    public static String remark(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String r = raw.strip();
        if (r.codePointCount(0, r.length()) > REMARK_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "备注至多 " + REMARK_MAX + " 个字符");
        }
        return r;
    }

    private static String name(DeviceRow device) {
        return device.name() == null || device.name().isBlank() ? "车位 " + device.sn() : device.name();
    }

    private static String nickname(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT_NICKNAME : raw;
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "邀请不存在");
    }
}
