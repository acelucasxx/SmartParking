package com.smartparking.device.registry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 激活码（PRD 5.1④、5.2③、10 账号安全）。
 *
 * <ul>
 *   <li>8 位，字符集去掉易混的 0 / O / 1 / I / L，共 31 个字符，约 2<sup>39.6</sup> 种；配合绑定时 5 次错误锁 30 分钟，在线猜不中。
 *   <li>库里只存 {@code h1$} + HMAC-SHA256(服务端密钥, 激活码) 的十六进制。用带密钥的 HMAC 而不是慢哈希：没有密钥时离线穷举无从下手，
 *       批量导入 5,000 台也只要毫秒级。
 *   <li>印刷文件导出前，明文以 AES-256-GCM 加密暂存在导入任务上，导出即清除（{@link #seal} / {@link #open}）。
 * </ul>
 *
 * <p>哈希密钥与加密密钥由同一主密钥按用途派生。
 */
public final class ActivateCodes {

    public static final int MIN_KEY_BYTES = 32;
    public static final int LENGTH = 8;
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private static final String PREFIX = "h1$";
    private static final String HMAC = "HmacSHA256";
    private static final byte SEAL_VERSION = 1;
    private static final int IV_BYTES = 12;

    private final SecretKeySpec hashKey;
    private final SecretKeySpec sealKey;
    private final SecureRandom random = new SecureRandom();

    public ActivateCodes(String masterKey) {
        if (masterKey == null || masterKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "激活码密钥至少 " + MIN_KEY_BYTES + " 字节（smartparking.device.activate-code-key）");
        }
        byte[] master = masterKey.getBytes(StandardCharsets.UTF_8);
        this.hashKey = new SecretKeySpec(derive(master, "smartparking/activate-code-hash/v1"), HMAC);
        this.sealKey = new SecretKeySpec(derive(master, "smartparking/print-payload/v1"), "AES");
    }

    public String generate() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }

    public String hash(String code) {
        return PREFIX + HexFormat.of().formatHex(hmac(normalize(code)));
    }

    /** 绑定时校验（BIZ-04）。输入不区分大小写、忽略空白；恒定时间比较 */
    public boolean matches(String input, String storedHash) {
        if (input == null || storedHash == null || !storedHash.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(storedHash.substring(PREFIX.length()));
        return MessageDigest.isEqual(expected, hmac(normalize(input)));
    }

    /** 把输入规整为大写、去空白，如用户输入 “k7q2 m9xa” */
    public static String normalize(String input) {
        return input == null ? "" : input.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    public byte[] seal(byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(new byte[] {SEAL_VERSION});
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length)
                    .put(SEAL_VERSION)
                    .put(iv)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("印刷数据加密失败", e);
        }
    }

    public byte[] open(byte[] stored) {
        if (stored == null || stored.length <= 1 + IV_BYTES || stored[0] != SEAL_VERSION) {
            throw new IllegalStateException("无法识别的印刷数据");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    sealKey,
                    new GCMParameterSpec(128, Arrays.copyOfRange(stored, 1, 1 + IV_BYTES)));
            cipher.updateAAD(new byte[] {SEAL_VERSION});
            return cipher.doFinal(stored, 1 + IV_BYTES, stored.length - 1 - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("印刷数据解密失败（密钥是否被更换？）", e);
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(hashKey);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
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
