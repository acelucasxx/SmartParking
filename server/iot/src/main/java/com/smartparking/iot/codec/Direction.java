package com.smartparking.iot.codec;

/** 帧的传输方向。 */
public enum Direction {
    /** 云端 → DTU → 闸板：{@code FD 00 | 地址 | 命令码 | 数据(0–1) | FD FA} */
    DOWNLINK,
    /** 闸板 → DTU → 云端：{@code FD 00 | 地址 | 数据(0–3) | FD FA}，没有命令码 */
    UPLINK
}
