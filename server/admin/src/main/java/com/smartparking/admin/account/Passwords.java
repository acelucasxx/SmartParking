package com.smartparking.admin.account;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import java.security.SecureRandom;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 密码哈希（BCrypt）、密码规则与临时密码生成 */
public class Passwords {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 64;
    static final int TEMP_LENGTH = 12;

    /** 去掉易混淆的 0 O o 1 l I */
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

    private static final String DIGITS = "23456789";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    /** 账号不存在时也做一次哈希比对，响应时间不暴露账号是否存在 */
    private final String dummyHash = encoder.encode("dummy-password-0");

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        if (raw == null || hash == null) {
            return false;
        }
        // BCrypt 只取前 72 字节；超长输入直接判错，避免“前 72 字节相同即可登录”
        if (raw.length() > MAX_LENGTH) {
            return false;
        }
        return encoder.matches(raw, hash);
    }

    public void burn(String raw) {
        encoder.matches(raw == null ? "" : raw, dummyHash);
    }

    /** 新密码规则：10–64 位，至少含一个字母与一个数字；不合法时 10001 */
    public static void validate(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH || raw.length() > MAX_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码为 " + MIN_LENGTH + "–" + MAX_LENGTH + " 位");
        }
        boolean letter = raw.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
        boolean digit = raw.chars().anyMatch(c -> c >= '0' && c <= '9');
        if (!letter || !digit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码须同时包含字母与数字");
        }
        if (raw.chars().anyMatch(c -> c < 0x21 || c > 0x7E)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码只能包含字母、数字与英文符号");
        }
    }

    /** 12 位临时密码：字母与数字混合，满足密码规则 */
    public String temporary() {
        while (true) {
            StringBuilder sb = new StringBuilder(TEMP_LENGTH);
            String all = LETTERS + DIGITS;
            for (int i = 0; i < TEMP_LENGTH; i++) {
                sb.append(all.charAt(random.nextInt(all.length())));
            }
            String candidate = sb.toString();
            boolean letter = candidate.chars().anyMatch(c -> LETTERS.indexOf(c) >= 0);
            boolean digit = candidate.chars().anyMatch(c -> DIGITS.indexOf(c) >= 0);
            if (letter && digit) {
                return candidate;
            }
        }
    }
}
