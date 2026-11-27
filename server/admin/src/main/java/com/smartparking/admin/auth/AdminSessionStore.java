package com.smartparking.admin.auth;

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
 * 后台会话，存 Redis；与小程序会话（{@code SessionStore}）同样不用 JWT：退出、停用、重置密码要立即生效。
 *
 * <ul>
 *   <li>{@code sp:admin-session:<sha256>} → {@code <adminId>:<登录时刻毫秒>}，TTL 为空闲超时，每次请求顺延，但不超过绝对超时。
 *   <li>{@code sp:admin-user-sessions:<adminId>} → 该账号全部会话哈希，停用、重置密码、修改密码时据此作废。
 * </ul>
 */
public class AdminSessionStore {

    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final Duration idle;
    private final Duration max;
    private final SecureRandom random = new SecureRandom();

    public AdminSessionStore(StringRedisTemplate redis, Duration idle, Duration max) {
        this.redis = redis;
        this.idle = idle;
        this.max = max;
    }

    public record Session(String token, Instant expiresAt) {}

    public Session create(long adminId, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token);
        redis.opsForValue().set(sessionKey(hash), adminId + ":" + now.toEpochMilli(), idle);
        String index = userKey(adminId);
        redis.opsForSet().add(index, hash);
        redis.expire(index, max);
        return new Session(token, now.plus(idle));
    }

    /**
     * 会话对应的账号，并顺延空闲超时。token 格式不对、不存在、空闲超时或已过绝对超时都为空。
     */
    public Optional<Long> touch(String token, Instant now) {
        if (!wellFormed(token)) {
            return Optional.empty();
        }
        String key = sessionKey(hash(token));
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        int sep = value.indexOf(':');
        long adminId;
        Instant createdAt;
        try {
            adminId = Long.parseLong(value.substring(0, sep));
            createdAt = Instant.ofEpochMilli(Long.parseLong(value.substring(sep + 1)));
        } catch (RuntimeException e) {
            redis.delete(key);
            return Optional.empty();
        }
        Duration left = Duration.between(now, createdAt.plus(max));
        if (left.isNegative() || left.isZero()) {
            redis.delete(key);
            return Optional.empty();
        }
        redis.expire(key, left.compareTo(idle) < 0 ? left : idle);
        return Optional.of(adminId);
    }

    public void revoke(String token) {
        if (!wellFormed(token)) {
            return;
        }
        String hash = hash(token);
        String value = redis.opsForValue().getAndDelete(sessionKey(hash));
        if (value != null && value.indexOf(':') > 0) {
            redis.opsForSet().remove(userKey(Long.parseLong(value.substring(0, value.indexOf(':')))), hash);
        }
    }

    /** 作废该账号全部会话，{@code keepToken} 除外（修改密码时保留当前会话）；返回作废数 */
    public int revokeAll(long adminId, String keepToken) {
        String index = userKey(adminId);
        Set<String> hashes = redis.opsForSet().members(index);
        if (hashes == null || hashes.isEmpty()) {
            return 0;
        }
        String keep = wellFormed(keepToken) ? hash(keepToken) : null;
        List<String> keys = new ArrayList<>();
        for (String h : hashes) {
            if (!h.equals(keep)) {
                keys.add(sessionKey(h));
                redis.opsForSet().remove(index, h);
            }
        }
        Long deleted = keys.isEmpty() ? 0L : redis.delete(keys);
        return deleted == null ? 0 : deleted.intValue();
    }

    static String sessionKey(String hash) {
        return "sp:admin-session:" + hash;
    }

    static String userKey(long adminId) {
        return "sp:admin-user-sessions:" + adminId;
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
