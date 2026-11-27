package com.smartparking.device.registry;

import com.smartparking.common.error.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 入库字段的规整与校验（PRD 5.1③），单台录入与批量导入共用。
 *
 * <ul>
 *   <li>SN：去空白、转大写后为 8–32 位大写字母或数字。
 *   <li>IMEI：15 位数字且通过 Luhn 校验。
 *   <li>蓝牙 MAC：可空；接受冒号、短横线或不分隔，统一存为 {@code AA:BB:CC:DD:EE:FF}。
 *   <li>ICCID：可空，至多 20 位字母或数字。型号、批次：必填，至多 32 个字符。
 * </ul>
 */
public final class DeviceRules {

    private static final Pattern SN = Pattern.compile("[A-Z0-9]{8,32}");
    private static final Pattern IMEI = Pattern.compile("\\d{15}");
    private static final Pattern MAC_HEX = Pattern.compile("[0-9A-F]{12}");
    private static final Pattern ICCID = Pattern.compile("[0-9A-Z]{1,20}");

    private DeviceRules() {}

    /** 待入库的设备，字段均已规整 */
    public record DeviceDraft(String sn, String imei, String bleMac, String iccid, String model, String batchNo) {}

    /** 校验失败：{@code code} 用于单台录入的错误码，{@code reason} 用于提示与失败行 */
    public static final class Invalid extends RuntimeException {
        private final ErrorCode code;

        Invalid(ErrorCode code, String reason) {
            super(reason);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }

    /** @throws Invalid 任一字段不合法 */
    public static DeviceDraft validate(
            String sn, String imei, String bleMac, String iccid, String model, String batchNo) {
        String s = upper(sn);
        if (s.isEmpty()) {
            throw new Invalid(ErrorCode.BAD_REQUEST, "SN 不能为空");
        }
        if (!SN.matcher(s).matches()) {
            throw new Invalid(ErrorCode.BAD_REQUEST, "SN 应为 8–32 位大写字母或数字");
        }
        String i = strip(imei);
        if (!IMEI.matcher(i).matches()) {
            throw new Invalid(ErrorCode.IMEI_INVALID, "IMEI 应为 15 位数字");
        }
        if (!luhn(i)) {
            throw new Invalid(ErrorCode.IMEI_INVALID, "IMEI 校验位不正确");
        }
        String mac = mac(bleMac);
        String cc = upper(iccid);
        if (!cc.isEmpty() && !ICCID.matcher(cc).matches()) {
            throw new Invalid(ErrorCode.BAD_REQUEST, "ICCID 应为至多 20 位字母或数字");
        }
        String m = text(model, "型号");
        String b = text(batchNo, "批次");
        return new DeviceDraft(s, i, mac, cc.isEmpty() ? null : cc, m, b);
    }

    /** 单独校验并规整 IMEI（换 4G 模块用） @throws Invalid 40011 */
    public static String imei(String raw) {
        String i = strip(raw);
        if (!IMEI.matcher(i).matches()) {
            throw new Invalid(ErrorCode.IMEI_INVALID, "IMEI 应为 15 位数字");
        }
        if (!luhn(i)) {
            throw new Invalid(ErrorCode.IMEI_INVALID, "IMEI 校验位不正确");
        }
        return i;
    }

    /** 单独校验并规整蓝牙 MAC 为 {@code AA:BB:CC:DD:EE:FF}（换主板用）；为空时返回 null @throws Invalid 10001 */
    public static String bleMac(String raw) {
        return mac(raw);
    }

    /** 15 位 IMEI 的 Luhn 校验：从右数偶数位乘 2，各位数字之和为 10 的倍数 */
    public static boolean luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private static String mac(String raw) {
        String hex = upper(raw).replace(":", "").replace("-", "");
        if (hex.isEmpty()) {
            return null;
        }
        if (!MAC_HEX.matcher(hex).matches()) {
            throw new Invalid(ErrorCode.BAD_REQUEST, "蓝牙 MAC 格式不正确");
        }
        StringBuilder out = new StringBuilder(17);
        for (int k = 0; k < 12; k += 2) {
            if (k > 0) {
                out.append(':');
            }
            out.append(hex, k, k + 2);
        }
        return out.toString();
    }

    private static String text(String raw, String field) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) {
            throw new Invalid(ErrorCode.BAD_REQUEST, field + "不能为空");
        }
        if (v.codePointCount(0, v.length()) > 32) {
            throw new Invalid(ErrorCode.BAD_REQUEST, field + "至多 32 个字符");
        }
        return v;
    }

    private static String strip(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s", "");
    }

    private static String upper(String raw) {
        return strip(raw).toUpperCase(Locale.ROOT);
    }
}
