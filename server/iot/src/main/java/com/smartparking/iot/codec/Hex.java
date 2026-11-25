package com.smartparking.iot.codec;

import java.util.HexFormat;

/**
 * 帧与十六进制文本互转。MQTT 载荷始终按 {@code byte[]} 处理，只有日志、入库与接口输出才转成文本：
 * 按 UTF-8 解码会把 {@code FD}、{@code FA} 换成替换字符，帧就坏了。
 */
public final class Hex {

    private static final HexFormat FORMAT = HexFormat.ofDelimiter(" ").withUpperCase();

    private Hex() {}

    /** 契约 {@code RawFrame} 格式：大写、字节间空格，如 {@code FD 00 01 09 16 11 FD FA} */
    public static String format(byte[] bytes) {
        return FORMAT.formatHex(bytes);
    }

    /** 解析十六进制文本，忽略大小写与空白、冒号、连字符分隔。 */
    public static byte[] parse(String text) {
        if (text == null) {
            throw new ProtocolException("十六进制文本为空");
        }
        String clean = text.replaceAll("[\\s:-]", "");
        if (clean.length() % 2 != 0) {
            throw new ProtocolException("十六进制位数不是偶数：" + text);
        }
        try {
            return HexFormat.of().parseHex(clean);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("非法十六进制文本：" + text);
        }
    }
}
