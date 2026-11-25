package com.smartparking.iot.codec;

/** 状态帧第 1 个数据字节：闸杆状态。枚举名与接口契约 {@code GateStatus} 一致。 */
public enum GateStatus {
    /** {@code 09} 开闸到位：已抬杆（放行） */
    OPENED(0x09),
    /** {@code 0C} 关闸到位：已落杆（占位） */
    CLOSED(0x0C),
    /** {@code 19} 开闸中 */
    OPENING(0x19),
    /** {@code 1C} 关闸中 */
    CLOSING(0x1C),
    /** {@code 00} 中间停止：动作被打断 */
    STOPPED(0x00),
    /** {@code E3} 电机故障 */
    FAULT(0xE3),
    /** 码表外的值，可能是固件升级或误码 */
    UNKNOWN(-1);

    private final int code;

    GateStatus(int code) {
        this.code = code;
    }

    /** 协议码；{@link #UNKNOWN} 为 -1 */
    public int code() {
        return code;
    }

    /** 到位态：开闸到位或关闸到位 */
    public boolean isSettled() {
        return this == OPENED || this == CLOSED;
    }

    /** 动作中：开闸中或关闸中 */
    public boolean isMoving() {
        return this == OPENING || this == CLOSING;
    }

    public static GateStatus of(int code) {
        int c = code & 0xFF;
        for (GateStatus status : values()) {
            if (status.code == c) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
