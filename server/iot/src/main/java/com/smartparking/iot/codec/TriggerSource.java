package com.smartparking.iot.codec;

/**
 * 状态帧第 2 个数据字节：动作原因，即操作记录里的“触发方式”。枚举名与接口契约 {@code TriggerSource} 一致。
 *
 * <p>协议用两个码区分同一来源的开 / 关动作（01/02、03/04、06/07），这里归为一个值。
 * {@code 00}（无）与充电相关的 {@code 17} 不在一期范围，与码表外的值一样记为 {@link #UNKNOWN}。
 */
public enum TriggerSource {
    /** {@code 16} 平台指令 */
    APP,
    /** {@code 01} / {@code 02} 遥控器 */
    REMOTE_CONTROL,
    /** {@code 03} / {@code 04} 地感、雷达 */
    GROUND_SENSOR,
    /** {@code 06} / {@code 07} 外部开关 */
    EXTERNAL_SWITCH,
    /** {@code 10} 延时自动关 */
    AUTO_CLOSE,
    /** {@code 11} 遇阻，需告警 */
    OBSTRUCTED,
    /** {@code 12} 红外防砸，需告警 */
    INFRARED,
    /** {@code 15} 上电找零 */
    POWER_ON,
    UNKNOWN;

    public static TriggerSource of(int code) {
        return switch (code & 0xFF) {
            case 0x16 -> APP;
            case 0x01, 0x02 -> REMOTE_CONTROL;
            case 0x03, 0x04 -> GROUND_SENSOR;
            case 0x06, 0x07 -> EXTERNAL_SWITCH;
            case 0x10 -> AUTO_CLOSE;
            case 0x11 -> OBSTRUCTED;
            case 0x12 -> INFRARED;
            case 0x15 -> POWER_ON;
            default -> UNKNOWN;
        };
    }
}
