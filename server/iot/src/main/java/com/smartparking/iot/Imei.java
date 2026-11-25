package com.smartparking.iot;

import java.util.regex.Pattern;

/** IMEI 格式：15 位数字。Luhn 校验在入库时做，接入层只校验格式。 */
public final class Imei {

    private static final Pattern PATTERN = Pattern.compile("^\\d{15}$");

    private Imei() {}

    public static boolean isWellFormed(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /** DTU 的 MQTT 客户端 ID（PRD 8.1） */
    public static String clientId(String imei) {
        return "dtu-" + imei;
    }
}
