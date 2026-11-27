package com.smartparking.admin.query;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.user.PhoneCipher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 后台只显示脱敏手机号（PRD 10）；按手机号查询走 HMAC 哈希，完整 11 位精确匹配 */
final class Phones {

    private static final Logger log = LoggerFactory.getLogger(Phones.class);
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final PhoneCipher cipher;

    Phones(PhoneCipher cipher) {
        this.cipher = cipher;
    }

    /** 未授权手机号为空；密文无法解密时只显示 **** 并记日志，不影响整页 */
    String masked(byte[] stored) {
        if (stored == null) {
            return null;
        }
        try {
            return PhoneCipher.mask(cipher.decrypt(stored));
        } catch (IllegalStateException e) {
            log.warn("手机号密文无法解密：{}", e.getMessage());
            return "****";
        }
    }

    /** 查询参数为空时为空；不是 11 位手机号 10001 */
    String hashOf(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String p = phone.strip();
        if (!PHONE.matcher(p).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号须为完整 11 位");
        }
        return cipher.hash(p);
    }
}
