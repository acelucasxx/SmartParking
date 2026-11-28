package com.smartparking.ownership.transfer;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.devices.DeviceViews;
import com.smartparking.control.devices.DeviceViews.DeviceCard;
import com.smartparking.iot.DbTime;
import com.smartparking.ownership.unbind.OwnershipRelease;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 转让设备（PRD 5.6）：发起、查看、取消、预览、接收。
 *
 * <ul>
 *   <li>token 与邀请同样：256 位随机、库里只存 SHA-256、一次性；卡片有效期取 {@code card.ttl_hours}（默认 24 小时）。
 *   <li>同一设备同时只有一张有效卡片（表上的唯一约束兜底）。发起不影响现有被授权人，接收时才清空。
 *   <li>发起与接收都先锁设备行，再动转让卡片，与解绑、邀请的加锁顺序一致。
 * </ul>
 */
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    static final String SHARE_PATH = "/pages/transfer/accept?token=";
    static final String DEFAULT_NICKNAME = "微信用户";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final SystemSettings settings;
    private final OwnershipRelease release;
    private final DeviceViews views;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TransferService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            SystemSettings settings,
            OwnershipRelease release,
            DeviceViews views,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.release = release;
        this.views = views;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------ 发起、查看、取消

    public record ShareCard(long id, String token, Instant expireAt, String path, String title) {}

    public record Pending(long id, Instant expireAt, Instant createdAt) {}

    /** 调用方已按 OWNER_ENABLED 判定。已有进行中的转让 30023 */
    public ShareCard create(DeviceGrant owner) {
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expireAt =
                now.plus(Duration.ofHours(Math.max(1, settings.getInt(SystemSettings.Keys.CARD_TTL_HOURS, 24))));
        long id = Objects.requireNonNull(tx.execute(status -> {
            lockDevice(owner.deviceId());
            // 过期而未被标记的卡片仍占着唯一约束：先置为过期
            expire(owner.deviceId(), now);
            try {
                var keys = new GeneratedKeyHolder();
                jdbc.sql(
                                "INSERT INTO device_transfer (token_hash, device_id, from_user, expire_at) VALUES (?, ?, ?, ?)")
                        .params(hash(token), owner.deviceId(), owner.userId(), DbTime.toDb(expireAt))
                        .update(keys, "id");
                return keys.getKeyAs(BigInteger.class).longValueExact();
            } catch (DuplicateKeyException e) {
                throw new BizException(ErrorCode.TRANSFER_PENDING_EXISTS);
            }
        }));
        Device device = device(owner.deviceId()).orElseThrow();
        log.info("用户 {} 发起转让设备 {}（{}）", owner.userId(), device.sn(), id);
        return new ShareCard(
                id,
                token,
                expireAt,
                SHARE_PATH + token,
                nickname(device.ownerNickname()) + "向你转让「" + name(device) + "」");
    }

    /** 进行中（未过期）的转让；没有时为空 */
    public Optional<Pending> pending(DeviceGrant owner) {
        return jdbc.sql(
                        "SELECT id, expire_at, created_at FROM device_transfer WHERE device_id = ? AND status = 'PENDING'"
                                + " AND expire_at > ?")
                .params(owner.deviceId(), DbTime.toDb(clock.instant()))
                .query((rs, n) -> new Pending(
                        rs.getLong("id"),
                        DbTime.fromDb(rs.getObject("expire_at", LocalDateTime.class)),
                        DbTime.fromDb(rs.getObject("created_at", LocalDateTime.class))))
                .optional();
    }

    /** 取消进行中的转让；没有进行中的转让 10004 */
    public void cancel(DeviceGrant owner) {
        int n = jdbc.sql("UPDATE device_transfer SET status = 'CANCELLED' WHERE device_id = ? AND status = 'PENDING'"
                        + " AND expire_at > ?")
                .params(owner.deviceId(), DbTime.toDb(clock.instant()))
                .update();
        if (n == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有进行中的转让");
        }
        log.info("用户 {} 取消转让设备 {}", owner.userId(), owner.deviceId());
    }

    // ------------------------------------------------------------ 预览与接收

    public enum State {
        VALID,
        EXPIRED,
        ACCEPTED,
        CANCELLED,
        OWN_TRANSFER
    }

    public record Preview(String deviceName, String sn, String fromNickname, Instant expireAt, State state) {}

    /** token 不存在 10004；失效时仍返回，由 {@code state} 区分 */
    public Preview preview(long viewerId, String token) {
        Card card = find(token).orElseThrow(TransferService::notFound);
        Device device = device(card.deviceId()).orElseThrow(TransferService::notFound);
        return new Preview(
                name(device),
                device.sn(),
                nickname(nicknameOf(card.fromUser())),
                card.expireAt(),
                judge(viewerId, card, device, clock.instant()));
    }

    private State judge(long viewerId, Card card, Device device, Instant now) {
        if (card.fromUser() == viewerId || device.isOwner(viewerId)) {
            return State.OWN_TRANSFER;
        }
        return switch (card.status()) {
            case "ACCEPTED" -> State.ACCEPTED;
            case "CANCELLED", "VOIDED" -> State.CANCELLED;
            case "EXPIRED" -> State.EXPIRED;
            default -> !card.expireAt().isAfter(now)
                    ? State.EXPIRED
                    // 发起人已不是主人，或主人被封禁（PRD 5.8②，解封后恢复）
                    : device.isOwner(card.fromUser()) && !device.ownerBanned() ? State.VALID : State.CANCELLED;
        };
    }

    /**
     * 同一事务内校验后接收（PRD 5.6⑥⑦）：
     *
     * <ol>
     *   <li>token 不存在 10004
     *   <li>自己发起的、或自己已是主人 30016
     *   <li>已取消 / 已作废 30022；已被接收 30021；已过期 30020
     *   <li>发起人已不是当前主人，或其账号已被封禁 30022
     *   <li>设备已禁用 20002
     * </ol>
     *
     * 通过后：卡片置为已接收；作废全部待接受邀请、全部被授权人置为已撤销（接收人若原是被授权人，其授权一并结束）；设备换主人，
     * {@code owner_since} 为此刻；写 {@code ownership_log}。返回新主人视角的车位卡片。
     */
    public DeviceCard accept(long userId, String token) {
        Card peek = find(token).orElseThrow(TransferService::notFound);
        Instant now = clock.instant();
        OwnershipRelease.Released released = Objects.requireNonNull(tx.execute(status -> {
            Device device = lockDevice(peek.deviceId());
            Card card = jdbc.sql(SELECT_CARD + " WHERE id = ? FOR UPDATE")
                    .param(peek.id())
                    .query(TransferService::mapCard)
                    .single();
            if (card.fromUser() == userId || device.isOwner(userId)) {
                throw new BizException(ErrorCode.CANNOT_ACCEPT_OWN);
            }
            switch (card.status()) {
                case "CANCELLED", "VOIDED" -> throw new BizException(ErrorCode.TRANSFER_CANCELLED);
                case "ACCEPTED" -> throw new BizException(ErrorCode.TRANSFER_USED);
                case "EXPIRED" -> throw new BizException(ErrorCode.TRANSFER_EXPIRED);
                default -> {}
            }
            if (!card.expireAt().isAfter(now)) {
                throw new BizException(ErrorCode.TRANSFER_EXPIRED);
            }
            if (!device.isOwner(card.fromUser())) {
                throw new BizException(ErrorCode.TRANSFER_CANCELLED, "设备主人已变更，转让已失效");
            }
            if (device.ownerBanned()) {
                throw new BizException(ErrorCode.TRANSFER_CANCELLED, "设备主人账号已停用，转让暂不可用");
            }
            if (!device.enabled()) {
                throw new BizException(ErrorCode.DEVICE_DISABLED);
            }
            jdbc.sql("UPDATE device_transfer SET status = 'ACCEPTED', to_user = ?, accepted_at = ? WHERE id = ?")
                    .params(userId, DbTime.toDb(now), card.id())
                    .update();
            return release.transfer(device.id(), card.fromUser(), userId, now);
        }));
        log.info(
                "设备 {} 由用户 {} 转让给 {}，撤销授权 {} 个",
                peek.deviceId(),
                released.formerOwner(),
                userId,
                released.revokedMembers().size());
        events.publishEvent(new DeviceTransferredEvent(
                peek.deviceId(), released.formerOwner(), userId, released.revokedMembers(), now));
        return views.card(userId, peek.deviceId());
    }

    // ------------------------------------------------------------ 过期

    /** 把已过期的进行中转让标为 EXPIRED（判定都按时间，这里让数据与状态一致） */
    @Scheduled(fixedDelayString = "${smartparking.transfer.expire-interval:5m}", initialDelayString = "1m")
    public void expireAll() {
        try {
            int n = jdbc.sql(
                            "UPDATE device_transfer SET status = 'EXPIRED' WHERE status = 'PENDING' AND expire_at <= ?")
                    .param(DbTime.toDb(clock.instant()))
                    .update();
            if (n > 0) {
                log.info("{} 张转让卡片已过期", n);
            }
        } catch (RuntimeException e) {
            log.error("标记过期转让失败", e);
        }
    }

    private void expire(long deviceId, Instant now) {
        jdbc.sql("UPDATE device_transfer SET status = 'EXPIRED' WHERE device_id = ? AND status = 'PENDING'"
                        + " AND expire_at <= ?")
                .params(deviceId, DbTime.toDb(now))
                .update();
    }

    // ------------------------------------------------------------

    private record Device(
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

    private record Card(long id, long deviceId, long fromUser, Instant expireAt, String status) {}

    private static final String SELECT_CARD = "SELECT id, device_id, from_user, expire_at, status FROM device_transfer";

    private static Card mapCard(ResultSet rs, int n) throws SQLException {
        return new Card(
                rs.getLong("id"),
                rs.getLong("device_id"),
                rs.getLong("from_user"),
                DbTime.fromDb(rs.getObject("expire_at", LocalDateTime.class)),
                rs.getString("status"));
    }

    private Optional<Card> find(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT_CARD + " WHERE token_hash = ?")
                .param(hash(token))
                .query(TransferService::mapCard)
                .optional();
    }

    private Device lockDevice(long deviceId) {
        jdbc.sql("SELECT id FROM device WHERE id = ? FOR UPDATE")
                .param(deviceId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "设备不存在"));
        return device(deviceId).orElseThrow();
    }

    private Optional<Device> device(long deviceId) {
        return jdbc.sql(
                        """
                        SELECT d.id, d.sn, d.name, d.enabled, d.bind_status, d.owner_user_id, u.nickname,
                               u.status AS owner_status
                        FROM device d LEFT JOIN app_user u ON u.id = d.owner_user_id WHERE d.id = ?""")
                .param(deviceId)
                .query((rs, n) -> new Device(
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

    private String nicknameOf(long userId) {
        return jdbc.sql("SELECT nickname FROM app_user WHERE id = ?").param(userId).query(String.class).list().stream()
                .findFirst()
                .orElse(null);
    }

    private static String name(Device d) {
        return d.name() == null || d.name().isBlank() ? "车位 " + d.sn() : d.name();
    }

    private static String nickname(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT_NICKNAME : raw;
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "转让卡片不存在");
    }
}
