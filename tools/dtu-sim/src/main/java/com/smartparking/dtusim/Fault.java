package com.smartparking.dtusim;

/** 可注入的动作异常，作用于下一次抬杆 / 落杆，触发后自动清除。 */
public enum Fault {
    /** 电机故障：状态立即变为 {@code E3}，并保持到执行 {@code repair} */
    MOTOR_FAULT,
    /** 遇阻：动作进行到一半停止，状态 {@code 00}、原因 {@code 11} */
    OBSTRUCTED,
    /** 红外防砸：动作进行到一半停止，状态 {@code 00}、原因 {@code 12} */
    INFRARED,
    /** 卡死：一直停在“开闸中 / 关闸中”，不到位，用于验证 15 秒超时 */
    STALL
}
