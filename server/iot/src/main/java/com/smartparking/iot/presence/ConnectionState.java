package com.smartparking.iot.presence;

/** 设备最近一次 MQTT 连接事件，取值与 {@code device_runtime.last_conn_event} 一致。 */
public enum ConnectionState {
    CONNECTED,
    DISCONNECTED
}
