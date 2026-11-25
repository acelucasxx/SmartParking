package com.smartparking.iot.codec;

import java.util.Arrays;
import java.util.Optional;

/**
 * RS485 帧编解码（PRD 8.2）。
 *
 * <pre>
 * 下行：FD 00 | 地址 | 命令码 | 数据(0–1) | FD FA    6–7 字节
 * 上行：FD 00 | 地址 | 数据(0–3)          | FD FA    5–8 字节
 * </pre>
 *
 * <p>上下行同一长度含义不同：7 字节的下行是“查询 + C0”，7 字节的上行是版本帧。所以解码只提供按方向区分的
 * {@link #decodeUplink} / {@link #decodeDownlink}，不提供靠长度猜方向的入口。
 */
public final class Rs485Codec {

    /** 一个 DTU 只带一块闸板，地址固定为 1（PRD 12.3） */
    public static final int DEFAULT_ADDRESS = 0x01;

    private static final int HEAD_HI = 0xFD;
    private static final int HEAD_LO = 0x00;
    private static final int TAIL_HI = 0xFD;
    private static final int TAIL_LO = 0xFA;
    /** 帧头 2 + 地址 1 + 帧尾 2 */
    private static final int OVERHEAD = 5;

    private static final int UPLINK_MIN = 5;
    private static final int UPLINK_MAX = 8;
    private static final int DOWNLINK_MIN = 6;
    private static final int DOWNLINK_MAX = 7;

    private Rs485Codec() {}

    /* ---------------- 下行编码 ---------------- */

    /** 查询状态：{@code FD 00 01 D0 C0 FD FA} */
    public static byte[] encodeQuery() {
        return encode(GateCommand.QUERY_STATUS, DEFAULT_ADDRESS);
    }

    /** 开闸（抬杆）：{@code FD 00 01 03 FD FA} */
    public static byte[] encodeOpen() {
        return encode(GateCommand.OPEN, DEFAULT_ADDRESS);
    }

    /** 关闸（落杆）：{@code FD 00 01 05 FD FA} */
    public static byte[] encodeClose() {
        return encode(GateCommand.CLOSE, DEFAULT_ADDRESS);
    }

    /** 查询版本：{@code FD 00 01 CF FD FA} */
    public static byte[] encodeVersionQuery() {
        return encode(GateCommand.VERSION, DEFAULT_ADDRESS);
    }

    /** 编码下行命令；查询状态的数据位固定为 {@link GateCommand#CHARGER_STANDBY}，调用方无法改写。 */
    public static byte[] encode(GateCommand command, int address) {
        if (command == null) {
            throw new IllegalArgumentException("命令不能为空");
        }
        byte[] data =
                command == GateCommand.QUERY_STATUS ? new byte[] {(byte) GateCommand.CHARGER_STANDBY} : new byte[0];
        byte[] body = new byte[1 + data.length];
        body[0] = (byte) command.code();
        System.arraycopy(data, 0, body, 1, data.length);
        return frame(address, body);
    }

    /* ---------------- 上行编码（DTU 模拟器与测试用） ---------------- */

    /** 状态帧：状态码 + 原因码 + 车位码 */
    public static byte[] encodeStatus(int address, int statusCode, int reasonCode, int carCode) {
        return frame(address, new byte[] {(byte) statusCode, (byte) reasonCode, (byte) carCode});
    }

    /** 版本帧：{@code CF} + 版本号 */
    public static byte[] encodeVersion(int address, int version) {
        return frame(address, new byte[] {(byte) GateCommand.VERSION.code(), (byte) version});
    }

    /** 回显帧：命令码原样回送 */
    public static byte[] encodeEcho(int address, int commandCode) {
        return frame(address, new byte[] {(byte) commandCode});
    }

    private static byte[] frame(int address, byte[] body) {
        if (address < 0 || address > 0xFF) {
            throw new IllegalArgumentException("地址越界：" + address);
        }
        byte[] frame = new byte[OVERHEAD + body.length];
        frame[0] = (byte) HEAD_HI;
        frame[1] = (byte) HEAD_LO;
        frame[2] = (byte) address;
        System.arraycopy(body, 0, frame, 3, body.length);
        frame[frame.length - 2] = (byte) TAIL_HI;
        frame[frame.length - 1] = (byte) TAIL_LO;
        return frame;
    }

    /* ---------------- 解码 ---------------- */

    public static UplinkFrame decodeUplink(byte[] frame) {
        checkStructure(frame, Direction.UPLINK, UPLINK_MIN, UPLINK_MAX);
        int address = frame[2] & 0xFF;
        byte[] data = Arrays.copyOfRange(frame, 3, frame.length - 2);
        String raw = Hex.format(frame);
        return switch (data.length) {
            case 3 -> status(address, data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, raw);
            case 2 -> (data[0] & 0xFF) == GateCommand.VERSION.code()
                    ? new UplinkFrame.Version(address, data[1] & 0xFF, raw)
                    : new UplinkFrame.Unknown(address, raw);
            case 1 -> new UplinkFrame.Echo(address, data[0] & 0xFF, GateCommand.of(data[0]), raw);
            default -> new UplinkFrame.Unknown(address, raw);
        };
    }

    public static DownlinkFrame decodeDownlink(byte[] frame) {
        checkStructure(frame, Direction.DOWNLINK, DOWNLINK_MIN, DOWNLINK_MAX);
        int address = frame[2] & 0xFF;
        int commandCode = frame[3] & 0xFF;
        byte[] data = Arrays.copyOfRange(frame, 4, frame.length - 2);
        Optional<GateCommand> command = GateCommand.of(commandCode);
        if (command.isPresent() && command.get().dataLength() != data.length) {
            throw new ProtocolException(command.get() + " 的数据应为 "
                    + command.get().dataLength() + " 字节，实际 " + data.length + " 字节：" + Hex.format(frame));
        }
        return new DownlinkFrame(address, commandCode, command, data, Hex.format(frame));
    }

    public static UplinkFrame decodeUplink(String hex) {
        return decodeUplink(Hex.parse(hex));
    }

    public static DownlinkFrame decodeDownlink(String hex) {
        return decodeDownlink(Hex.parse(hex));
    }

    private static UplinkFrame.Status status(int address, int statusCode, int reasonCode, int carCode, String raw) {
        Boolean occupied =
                switch (carCode) {
                    case 0x00 -> Boolean.FALSE;
                    case 0x11 -> Boolean.TRUE;
                    default -> null;
                };
        return new UplinkFrame.Status(
                address,
                statusCode,
                GateStatus.of(statusCode),
                reasonCode,
                TriggerSource.of(reasonCode),
                carCode,
                occupied,
                raw);
    }

    private static void checkStructure(byte[] frame, Direction direction, int min, int max) {
        if (frame == null) {
            throw new ProtocolException("帧为空");
        }
        String side = direction == Direction.UPLINK ? "上行" : "下行";
        if (frame.length < min) {
            throw new ProtocolException(side + "帧长度不足 " + min + " 字节：" + Hex.format(frame));
        }
        if (frame.length > max) {
            throw new ProtocolException(side + "帧超过 " + max + " 字节，疑似 DTU 粘包：" + Hex.format(frame));
        }
        if ((frame[0] & 0xFF) != HEAD_HI || (frame[1] & 0xFF) != HEAD_LO) {
            throw new ProtocolException(side + "帧头不是 FD 00：" + Hex.format(frame));
        }
        if ((frame[frame.length - 2] & 0xFF) != TAIL_HI || (frame[frame.length - 1] & 0xFF) != TAIL_LO) {
            throw new ProtocolException(side + "帧尾不是 FD FA：" + Hex.format(frame));
        }
    }
}
