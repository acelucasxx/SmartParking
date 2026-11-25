package com.smartparking.dtusim;

import java.util.ArrayList;
import java.util.List;

/** 生成带正确 Luhn 校验位的 15 位 IMEI，便于与后台导入校验（PRD 5.1）配合。 */
public final class Imeis {

    private Imeis() {}

    /**
     * @param first14 起始的 14 位数字，如 {@code 86000000000000}
     */
    public static List<String> sequence(String first14, int count) {
        if (!first14.matches("\\d{14}")) {
            throw new IllegalArgumentException("IMEI 前缀必须是 14 位数字：" + first14);
        }
        long start = Long.parseLong(first14);
        var result = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            String body = String.format("%014d", start + i);
            if (body.length() > 14) {
                throw new IllegalArgumentException("IMEI 序列超出 14 位：" + body);
            }
            result.add(body + checkDigit(body));
        }
        return result;
    }

    static char checkDigit(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = first14.charAt(i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    public static boolean isValid(String imei) {
        return imei != null && imei.matches("\\d{15}") && checkDigit(imei.substring(0, 14)) == imei.charAt(14);
    }
}
