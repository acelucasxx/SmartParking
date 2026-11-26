package com.smartparking.user;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 手机号加密存储（PRD 10 隐私合规）。
 *
 * <ul>
 *   <li>{@code phone_cipher}：AES-256-GCM，格式为 版本(1) + IV(12) + 密文与认证标签；版本号参与认证，便于日后轮换密钥。
 *   <li>{@code phone_hash}：HMAC-SHA256 的十六进制，用于后台按手机号精确查询，不能反推手机号。
 * </ul>
 *
 * <p>两把密钥由同一主密钥按用途派生，互不相同；主密钥只放在密钥管理中。
 */
public final class PhoneCipher {

    public static final int MIN_KEY_BYTES = 32;

    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String HMAC = "HmacSHA256";

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;
    private final SecureRandom random = new SecureRandom();

    public PhoneCipher(String masterKey) {
        if (masterKey == null || masterKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("用户数据密钥至少 " + MIN_KEY_BYTES + " 字节（smartparking.user.data-key）");
        }
        byte[] master = masterKey.getBytes(StandardCharsets.UTF_8);
        this.encryptionKey = new SecretKeySpec(derive(master, "smartparking/phone-enc/v1"), "AES");
        this.hashKey = new SecretKeySpec(derive(master, "smartparking/phone-hash/v1"), HMAC);
    }

    public byte[] encrypt(String phone) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(new byte[] {VERSION});
            byte[] sealed = cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length)
                    .put(VERSION)
                    .put(iv)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("手机号加密失败", e);
        }
    }

    /** 密文被篡改、版本不认识或密钥不对时抛 {@link IllegalStateException} */
    public String decrypt(byte[] stored) {
        if (stored == null || stored.length <= 1 + IV_BYTES || stored[0] != VERSION) {
            throw new IllegalStateException("无法识别的手机号密文");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(stored, 1, 1 + IV_BYTES)));
            cipher.updateAAD(new byte[] {VERSION});
            byte[] plain = cipher.doFinal(stored, 1 + IV_BYTES, stored.length - 1 - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("手机号解密失败", e);
        }
    }

    /** 64 位小写十六进制 */
    public String hash(String phone) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("手机号哈希失败", e);
        }
    }

    /** 界面脱敏：只显示前 3 位与后 4 位，如 {@code 138****5678} */
    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static byte[] derive(byte[] master, String purpose) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(master, HMAC));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
