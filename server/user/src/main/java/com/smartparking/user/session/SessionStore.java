package com.smartparking.user.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 小程序会话，存 Redis。
 *
 * <p>token 是 256 位随机数（URL 安全 Base64，无填充），不是 JWT：退出登录、封禁要立即生效，自包含令牌做不到。Redis 里只存
 * token 的 SHA-256，Redis 数据泄露也拿不到可用的 token。
 *
 * <ul>
 *   <li>{@code sp:app-session:<sha256>} → 用户 ID，TTL 为会话有效期。
 *   <li>{@code sp:app-user-sessions:<userId>} → 该用户全部会话哈希的集合，封禁时据此一次作废。集合里可能留有已过期的哈希，作废时一并删除即可。
 * </ul>
 */
public class SessionStore {

    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public SessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Session(String token, Instant expiresAt) {}

    public Session create(long userId, Duration ttl, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token);
        redis.opsForValue().set(sessionKey(hash), Long.toString(userId), ttl);
        String index = userKey(userId);
        redis.opsForSet().add(index, hash);
        redis.expire(index, ttl);
        return new Session(token, now.plus(ttl));
    }

    /** 会话对应的用户；token 格式不对、不存在或已过期时为空 */
    public Optional<Long> find(String token) {
        if (!wellFormed(token)) {
            return Optional.empty();
        }
        String value = redis.opsForValue().get(sessionKey(hash(token)));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public void revoke(String token) {
        if (!wellFormed(token)) {
            return;
        }
        String hash = hash(token);
        String value = redis.opsForValue().getAndDelete(sessionKey(hash));
        if (value != null) {
            redis.opsForSet().remove(userKey(Long.parseLong(value)), hash);
        }
    }

    /** 作废该用户全部会话（封禁、注销）；返回作废的会话数 */
    public int revokeAll(long userId) {
        String index = userKey(userId);
        Set<String> hashes = redis.opsForSet().members(index);
        int revoked = 0;
        if (hashes != null && !hashes.isEmpty()) {
            List<String> keys = new ArrayList<>();
            hashes.forEach(h -> keys.add(sessionKey(h)));
            Long deleted = redis.delete(keys);
            revoked = deleted == null ? 0 : deleted.intValue();
        }
        redis.delete(index);
        return revoked;
    }

    static String sessionKey(String hash) {
        return "sp:app-session:" + hash;
    }

    static String userKey(long userId) {
        return "sp:app-user-sessions:" + userId;
    }

    private static boolean wellFormed(String token) {
        return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]+");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
