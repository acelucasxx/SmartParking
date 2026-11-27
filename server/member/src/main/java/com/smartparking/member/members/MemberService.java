package com.smartparking.member.members;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.iot.DbTime;
import com.smartparking.member.invite.InviteService;
import com.smartparking.member.invite.Period;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 成员管理（PRD 5.5⑤⑥、6.1 页面 6）：列表与名额、修改有效期或备注、撤销、被授权人退出、到期自动失效。
 *
 * <p>“生效中”以 {@code status = ACTIVE} 且未过 {@code valid_to} 为准，与权限判定、名额一致：到期那一刻起就不再列出、不再占名额，
 * 到期任务只是把状态改成 {@code EXPIRED} 并发出通知。
 */
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);
    static final String DEFAULT_NICKNAME = "微信用户";
    static final int EXPIRE_BATCH = 500;

    private final JdbcClient jdbc;
    private final SystemSettings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MemberService(JdbcClient jdbc, SystemSettings settings, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    // ------------------------------------------------------------ 列表

    /** 契约 Member */
    public record Member(long id, String nickname, String remark, Instant validFrom, Instant validTo, String status) {}

    /** 契约 PendingInvite */
    public record PendingInvite(long id, String remark, Period period, Instant expireAt, Instant createdAt) {}

    /** 契约 MemberOverview；{@code used} = 生效成员 + 待接受邀请 */
    public record Overview(int limit, int used, List<Member> members, List<PendingInvite> invites) {}

    /** 生效中的被授权人（含尚未开始的）按开始时间；未过期的待接受邀请按发出时间 */
    public Overview overview(DeviceGrant owner) {
        LocalDateTime now = DbTime.toDb(clock.instant());
        List<Member> members = jdbc.sql(SELECT_MEMBER
                        + " WHERE m.device_id = ? AND m.status = 'ACTIVE' AND (m.valid_to IS NULL OR m.valid_to > ?)"
                        + " ORDER BY m.valid_from, m.id")
                .params(owner.deviceId(), now)
                .query(MemberService::mapMember)
                .list();
        List<PendingInvite> invites = jdbc.sql(
                        """
                        SELECT id, remark, period_type, duration_minutes, valid_from, valid_to, expire_at, created_at
                        FROM share_invite WHERE device_id = ? AND status = 'PENDING' AND expire_at > ?
                        ORDER BY created_at, id""")
                .params(owner.deviceId(), now)
                .query((rs, n) -> new PendingInvite(
                        rs.getLong("id"),
                        rs.getString("remark"),
                        Period.stored(
                                rs.getString("period_type"),
                                rs.getObject("duration_minutes", Integer.class),
                                read(rs, "valid_from"),
                                read(rs, "valid_to")),
                        read(rs, "expire_at"),
                        read(rs, "created_at")))
                .list();
        int limit = Math.max(1, settings.getInt(SystemSettings.Keys.MEMBER_MAX_PER_DEVICE, 5));
        return new Overview(limit, members.size() + invites.size(), members, invites);
    }

    // ------------------------------------------------------------ 修改

    /**
     * 只改传入的项（{@code period} 为 null 不改有效期；{@code hasRemark} 为 false 不改备注）。立即生效。
     *
     * <ul>
     *   <li>PERMANENT：止改为永久，起不变。
     *   <li>RELATIVE：从修改时起算 N 天；尚未开始的授权同时改为立即开始。
     *   <li>CUSTOM：起止都按新值。
     * </ul>
     *
     * 授权不属于本设备、已结束或已过期 10004。
     */
    public Member update(DeviceGrant owner, long memberId, Period period, boolean hasRemark, String rawRemark) {
        if (period == null && !hasRemark) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少修改有效期或备注中的一项");
        }
        String remark = hasRemark ? InviteService.remark(rawRemark) : null;
        Instant now = clock.instant();
        Member current = active(owner.deviceId(), memberId, now).orElseThrow(MemberService::notFound);
        Instant from = current.validFrom();
        Instant to = current.validTo();
        if (period != null) {
            switch (period.type()) {
                case PERMANENT -> to = null;
                case RELATIVE -> {
                    Instant[] r = period.resolve(now);
                    to = r[1];
                    if (from.isAfter(now)) {
                        from = now;
                    }
                }
                case CUSTOM -> {
                    from = period.validFrom();
                    to = period.validTo();
                }
            }
        }
        int updated = jdbc.sql(
                        """
                        UPDATE device_member SET valid_from = :from, valid_to = :to, remark = :remark
                        WHERE id = :id AND device_id = :device AND status = 'ACTIVE' AND (valid_to IS NULL OR valid_to > :now)""")
                .param("from", DbTime.toDb(from))
                .param("to", DbTime.toDb(to))
                .param("remark", hasRemark ? remark : current.remark())
                .param("id", memberId)
                .param("device", owner.deviceId())
                .param("now", DbTime.toDb(now))
                .update();
        if (updated == 0) {
            throw notFound();
        }
        Member after = active(owner.deviceId(), memberId, now).orElseThrow(MemberService::notFound);
        long userId = userOf(memberId);
        log.info("用户 {} 修改设备 {} 的授权 {}：{} → {}", owner.userId(), owner.deviceId(), memberId, current.validTo(), to);
        events.publishEvent(new MemberUpdatedEvent(
                owner.deviceId(), memberId, userId, period != null, after.validFrom(), after.validTo(), now));
        return after;
    }

    // ------------------------------------------------------------ 撤销与退出

    /** 立即生效、释放名额。已撤销的再撤销视为成功；不属于本设备、已过期或已退出 10004 */
    public void revoke(DeviceGrant owner, long memberId) {
        Instant now = clock.instant();
        Optional<Long> userId = end(owner.deviceId(), memberId, "REVOKED", now);
        if (userId.isPresent()) {
            log.info("用户 {} 撤销设备 {} 的授权 {}", owner.userId(), owner.deviceId(), memberId);
            events.publishEvent(new MemberEndedEvent(
                    owner.deviceId(), memberId, userId.get(), owner.userId(), MemberEndedEvent.Reason.REVOKED, now));
            return;
        }
        boolean revoked =
                jdbc.sql("SELECT COUNT(*) FROM device_member WHERE id = ? AND device_id = ? AND status = 'REVOKED'")
                                .params(memberId, owner.deviceId())
                                .query(Long.class)
                                .single()
                        > 0;
        if (!revoked) {
            throw notFound();
        }
    }

    /** 被授权人主动退出（调用方已按 VIEW 判定为有效期内的成员）。主人调用 10001 */
    public void quit(DeviceGrant grant) {
        if (grant.isOwner() || grant.memberId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "主人不能退出授权，如不再使用请解绑设备");
        }
        Instant now = clock.instant();
        if (end(grant.deviceId(), grant.memberId(), "QUIT", now).isEmpty()) {
            throw new BizException(ErrorCode.ACCESS_EXPIRED);
        }
        long ownerId = ownerOf(grant.deviceId());
        log.info("用户 {} 退出设备 {} 的授权", grant.userId(), grant.deviceId());
        events.publishEvent(new MemberEndedEvent(
                grant.deviceId(), grant.memberId(), grant.userId(), ownerId, MemberEndedEvent.Reason.QUIT, now));
    }

    // ------------------------------------------------------------ 到期

    /**
     * 到期自动失效（PRD 5.5⑥）：把已过 {@code valid_to} 的生效授权置为 {@code EXPIRED}，{@code ended_at} 记到期时刻。逐条条件更新，
     * 多实例同时执行时每条只有一方生效、只通知一次。
     *
     * @return 本轮处理的条数
     */
    @Scheduled(fixedDelayString = "${smartparking.member.expire-interval:60s}", initialDelayString = "30s")
    public int expire() {
        int done = 0;
        try {
            Instant now = clock.instant();
            record Due(long id, long deviceId, long userId, Long ownerId) {}
            List<Due> due = jdbc.sql(
                            """
                            SELECT m.id, m.device_id, m.user_id, d.owner_user_id
                            FROM device_member m JOIN device d ON d.id = m.device_id
                            WHERE m.status = 'ACTIVE' AND m.valid_to <= ?
                            ORDER BY m.valid_to, m.id LIMIT ?""")
                    .params(DbTime.toDb(now), EXPIRE_BATCH)
                    .query((rs, n) -> new Due(
                            rs.getLong("id"),
                            rs.getLong("device_id"),
                            rs.getLong("user_id"),
                            rs.getObject("owner_user_id", Long.class)))
                    .list();
            for (Due d : due) {
                int updated = jdbc.sql("UPDATE device_member SET status = 'EXPIRED', ended_at = valid_to"
                                + " WHERE id = ? AND status = 'ACTIVE' AND valid_to <= ?")
                        .params(d.id(), DbTime.toDb(now))
                        .update();
                if (updated > 0) {
                    done++;
                    events.publishEvent(new MemberEndedEvent(
                            d.deviceId(),
                            d.id(),
                            d.userId(),
                            d.ownerId() == null ? 0 : d.ownerId(),
                            MemberEndedEvent.Reason.EXPIRED,
                            now));
                }
            }
            if (done > 0) {
                log.info("{} 条授权到期失效", done);
            }
        } catch (RuntimeException e) {
            log.error("授权到期处理失败", e);
        }
        return done;
    }

    // ------------------------------------------------------------

    private static final String SELECT_MEMBER =
            """
            SELECT m.id, u.nickname, m.remark, m.valid_from, m.valid_to, m.status
            FROM device_member m JOIN app_user u ON u.id = m.user_id""";

    private Optional<Member> active(long deviceId, long memberId, Instant now) {
        return jdbc.sql(SELECT_MEMBER
                        + " WHERE m.id = ? AND m.device_id = ? AND m.status = 'ACTIVE'"
                        + " AND (m.valid_to IS NULL OR m.valid_to > ?)")
                .params(memberId, deviceId, DbTime.toDb(now))
                .query(MemberService::mapMember)
                .optional();
    }

    /** 生效中（含到期未处理）→ 结束；返回被结束的用户 */
    private Optional<Long> end(long deviceId, long memberId, String status, Instant now) {
        Optional<Long> userId = jdbc.sql(
                        "SELECT user_id FROM device_member WHERE id = ? AND device_id = ? AND status = 'ACTIVE'"
                                + " AND (valid_to IS NULL OR valid_to > ?)")
                .params(memberId, deviceId, DbTime.toDb(now))
                .query(Long.class)
                .optional();
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        int updated = jdbc.sql("UPDATE device_member SET status = ?, ended_at = ? WHERE id = ? AND status = 'ACTIVE'"
                        + " AND (valid_to IS NULL OR valid_to > ?)")
                .params(status, DbTime.toDb(now), memberId, DbTime.toDb(now))
                .update();
        return updated > 0 ? userId : Optional.empty();
    }

    private long userOf(long memberId) {
        return jdbc.sql("SELECT user_id FROM device_member WHERE id = ?")
                .param(memberId)
                .query(Long.class)
                .single();
    }

    private long ownerOf(long deviceId) {
        return jdbc.sql("SELECT COALESCE(owner_user_id, 0) FROM device WHERE id = ?")
                .param(deviceId)
                .query(Long.class)
                .single();
    }

    private static Member mapMember(ResultSet rs, int n) throws SQLException {
        String nickname = rs.getString("nickname");
        return new Member(
                rs.getLong("id"),
                nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname,
                rs.getString("remark"),
                read(rs, "valid_from"),
                read(rs, "valid_to"),
                rs.getString("status"));
    }

    private static Instant read(ResultSet rs, String column) throws SQLException {
        return DbTime.fromDb(rs.getObject(column, LocalDateTime.class));
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "授权不存在或已失效");
    }
}
