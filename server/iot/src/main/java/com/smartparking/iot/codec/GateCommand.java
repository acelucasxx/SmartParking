package com.smartparking.iot.codec;

import java.util.Optional;

/** 下行命令码。 */
public enum GateCommand {
    /** {@code D0} 查询状态，带 1 字节充电板状态，固定 {@link #CHARGER_STANDBY} */
    QUERY_STATUS(0xD0, 1),
    /** {@code 03} 开闸（抬杆） */
    OPEN(0x03, 0),
    /** {@code 05} 关闸（落杆） */
    CLOSE(0x05, 0),
    /** {@code CF} 查询闸板版本 */
    VERSION(0xCF, 0);

    /**
     * 充电板“待机”。协议规定闸杆抬起时若收到“已插枪”（{@code CC}），道闸不执行落杆，直到收到待机；
     * 本项目不是充电场景，查询必须固定发 {@code C0}。
     */
    public static final int CHARGER_STANDBY = 0xC0;

    private final int code;
    private final int dataLength;

    GateCommand(int code, int dataLength) {
        this.code = code;
        this.dataLength = dataLength;
    }

    public int code() {
        return code;
    }

    /** 命令码之后的数据字节数 */
    public int dataLength() {
        return dataLength;
    }

    public static Optional<GateCommand> of(int code) {
        int c = code & 0xFF;
        for (GateCommand command : values()) {
            if (command.code == c) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
