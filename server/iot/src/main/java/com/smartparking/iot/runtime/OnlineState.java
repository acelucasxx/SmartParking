package com.smartparking.iot.runtime;

/** 在线状态，取值与接口契约 {@code OnlineState}、{@code device_runtime.online_state} 一致；判定规则见 PRD 8.5（IOT-09）。 */
public enum OnlineState {
    ONLINE,
    SLEEP,
    OFFLINE
}
