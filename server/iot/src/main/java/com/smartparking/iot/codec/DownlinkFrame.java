package com.smartparking.iot.codec;

import java.util.Arrays;
import java.util.Optional;

/**
 * 解析后的下行帧，主要供 DTU 模拟器与日志使用。
 *
 * @param command 命令码不在码表内时为空
 * @param data 命令码之后的数据字节，查询指令为充电板状态
 */
public record DownlinkFrame(int address, int commandCode, Optional<GateCommand> command, byte[] data, String rawFrame) {

    public DownlinkFrame {
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DownlinkFrame other
                && address == other.address
                && commandCode == other.commandCode
                && command.equals(other.command)
                && Arrays.equals(data, other.data)
                && rawFrame.equals(other.rawFrame);
    }

    @Override
    public int hashCode() {
        return 31 * rawFrame.hashCode() + address;
    }

    @Override
    public String toString() {
        return "DownlinkFrame[" + rawFrame + "]";
    }
}
