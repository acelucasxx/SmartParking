package com.smartparking.member.invite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 邀请 token（PRD 9：随机 ≥ 128 位、一次性、24 小时过期）：256 位随机数，URL 安全 Base64 无填充（43 字符）。库里只存 SHA-256，
 * 明文只出现在创建邀请的响应与分享卡片里。
 */
final class InviteTokens {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private final SecureRandom random = new SecureRandom();

    String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean wellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
