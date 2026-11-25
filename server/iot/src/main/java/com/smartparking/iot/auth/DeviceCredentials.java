package com.smartparking.iot.auth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 一机一密（PRD 8.1）：设备 MQTT 密码 = HMAC-SHA256(平台密钥, IMEI) 的前 16 字节，32 位小写十六进制。
 *
 * <p>为什么用派生而不是逐台随机生成：产线在设备入库前就要预置 DTU 参数（PRD 5.1 第 1 步），派生方式只需 IMEI 即可算出密码，
 * 不依赖设备先入库，平台也不必存储任何设备密码。平台密钥泄露会影响全部设备，必须只放在密钥管理中，并通过
 * {@link #keyId()} 区分版本以便轮换。
 */
public final class DeviceCredentials {

    /** 密钥最少 32 字节 */
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final String CONTEXT = "smartparking/dtu-mqtt-password/v1:";
    private static final int PASSWORD_BYTES = 16;

    private final SecretKeySpec key;
    private final String keyId;

    public DeviceCredentials(String secretKey) {
        if (secretKey == null || secretKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "设备密钥至少 " + MIN_KEY_BYTES + " 字节（smartparking.mqtt.auth.device-secret-key）");
        }
        this.key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        // 密钥指纹，只用于日志与运维核对，不能反推密钥
        this.keyId = HexFormat.of().formatHex(Arrays.copyOf(hmac("key-id"), 4));
    }

    /** 该 IMEI 的 MQTT 密码，供产线预置与模拟器使用 */
    public String passwordFor(String imei) {
        return HexFormat.of().formatHex(Arrays.copyOf(hmac(CONTEXT + imei), PASSWORD_BYTES));
    }

    /** 恒定时间比较，避免按响应时间猜测密码 */
    public boolean matches(String imei, String password) {
        if (imei == null || password == null) {
            return false;
        }
        return MessageDigest.isEqual(
                passwordFor(imei).getBytes(StandardCharsets.US_ASCII), password.getBytes(StandardCharsets.UTF_8));
    }

    /** 密钥指纹（8 位十六进制） */
    public String keyId() {
        return keyId;
    }

    private byte[] hmac(String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
