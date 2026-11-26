package com.smartparking.dtubench;

import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.ProtocolException;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 判定一条 MQTT 上行载荷与 RS485 帧的对应关系，用于发现 DTU 串口打包造成的粘包 / 拆包（PRD 12.1 第 6 项）以及
 * 混在上行主题里的 DTU 心跳 / 注册包（第 11 项）。
 *
 * <p>云端 {@code UplinkDispatcher} 假定“一条消息恰好一帧”，不满足时整条丢弃。本类只做观察和归类，不改变云端行为；
 * 联调确认存在粘包 / 拆包后再决定云端是否拆帧、或调整 DTU 打包参数（EXT-07）。
 *
 * <p>有状态：上一条载荷末尾的半帧暂存，下一条到达时尝试拼接。非线程安全，由调用方串行调用。
 */
public final class PayloadAnalyzer {

    public enum Kind {
        /** 恰好一帧，云端可正常处理 */
        FRAME,
        /** 多帧首尾相接（粘包），云端会整条丢弃 */
        CONCATENATED,
        /** 末尾是不完整的帧（拆包前半），暂存等待下一条 */
        FRAGMENT,
        /** 与上一条暂存的半帧拼接后成为完整帧（拆包后半） */
        REASSEMBLED,
        /** 不以帧头开始、且基本是可打印字符：疑似 DTU 心跳 / 注册包 */
        NON_RS485,
        /** 其他无法识别的字节 */
        INVALID
    }

    /**
     * @param frames 本条载荷（拼接时含暂存部分）中解出的完整帧
     * @param detail 人能读的说明，如残余字节、ASCII 内容
     */
    public record Analysis(Kind kind, List<UplinkFrame> frames, String detail) {

        public Analysis {
            frames = List.copyOf(frames);
        }

        /** 云端当前实现能否处理这条载荷 */
        public boolean acceptedByServer() {
            return kind == Kind.FRAME;
        }
    }

    private static final int HEAD_HI = 0xFD;
    private static final int HEAD_LO = 0x00;
    private static final int TAIL_HI = 0xFD;
    private static final int TAIL_LO = 0xFA;
    private static final int MIN = 5;
    private static final int MAX = 8;

    private byte[] pending;

    public Analysis accept(byte[] payload) {
        if (pending != null) {
            byte[] combined = concat(pending, payload);
            pending = null;
            Scan joined = scan(combined);
            if (joined.complete() && !joined.frames.isEmpty()) {
                return new Analysis(Kind.REASSEMBLED, joined.frames, "与上一条的半帧拼接：" + Hex.format(combined));
            }
        }
        Scan scan = scan(payload);
        if (scan.complete()) {
            if (scan.frames.isEmpty()) {
                return new Analysis(Kind.INVALID, List.of(), "空载荷");
            }
            return scan.frames.size() == 1
                    ? new Analysis(Kind.FRAME, scan.frames, "")
                    : new Analysis(Kind.CONCATENATED, scan.frames, scan.frames.size() + " 帧粘连");
        }
        byte[] rest = Arrays.copyOfRange(payload, scan.consumed(), payload.length);
        if (startsWithHead(rest) && rest.length < MAX) {
            pending = rest;
            return new Analysis(Kind.FRAGMENT, scan.frames, "末尾半帧 " + Hex.format(rest));
        }
        if (scan.frames.isEmpty() && printable(payload)) {
            return new Analysis(
                    Kind.NON_RS485, List.of(), "ASCII：" + new String(payload, StandardCharsets.US_ASCII).strip());
        }
        return new Analysis(Kind.INVALID, scan.frames, "无法识别的字节 " + Hex.format(rest));
    }

    /**
     * @param leftover 未能解析的字节起始位置；等于长度表示全部解析
     */
    private record Scan(List<UplinkFrame> frames, int leftover, int length) {

        boolean complete() {
            return leftover == length;
        }

        int consumed() {
            return leftover;
        }
    }

    private static Scan scan(byte[] bytes) {
        var frames = new ArrayList<UplinkFrame>();
        int i = 0;
        outer:
        while (i < bytes.length) {
            if (!startsWithHead(bytes, i)) {
                break;
            }
            // 从最长的候选往短找：数据段里恰好出现 FD FA 时，不会截成一个短帧
            for (int end = Math.min(i + MAX, bytes.length); end >= i + MIN; end--) {
                if ((bytes[end - 2] & 0xFF) == TAIL_HI && (bytes[end - 1] & 0xFF) == TAIL_LO) {
                    try {
                        frames.add(Rs485Codec.decodeUplink(Arrays.copyOfRange(bytes, i, end)));
                        i = end;
                        continue outer;
                    } catch (ProtocolException e) {
                        // 长度不合法，换更短的候选
                    }
                }
            }
            break;
        }
        return new Scan(frames, i, bytes.length);
    }

    private static boolean startsWithHead(byte[] bytes) {
        return startsWithHead(bytes, 0);
    }

    private static boolean startsWithHead(byte[] bytes, int i) {
        return i + 1 < bytes.length
                ? (bytes[i] & 0xFF) == HEAD_HI && (bytes[i + 1] & 0xFF) == HEAD_LO
                : i < bytes.length && (bytes[i] & 0xFF) == HEAD_HI;
    }

    private static boolean printable(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        int ok = 0;
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 0x20 && c < 0x7F) || c == '\r' || c == '\n' || c == '\t') {
                ok++;
            }
        }
        return ok * 10 >= bytes.length * 9;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
