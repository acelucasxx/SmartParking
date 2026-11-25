package com.smartparking.iot;

import java.util.Optional;

/**
 * 设备登记查询，由设备域实现。iot 只用它判断 IMEI 是否在库（PRD 8.1：未在库的 IMEI 一律拒收），不读设备域的表。
 */
public interface DeviceDirectory {

    Optional<RegisteredDevice> findByImei(String imei);

    /**
     * @param rs485Address RS485 帧地址位，一期固定为 1
     */
    record RegisteredDevice(long deviceId, String imei, int rs485Address) {}
}
