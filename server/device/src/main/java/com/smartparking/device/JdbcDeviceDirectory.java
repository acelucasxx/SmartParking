package com.smartparking.device;

import com.smartparking.iot.DeviceDirectory;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 按 IMEI 查询已登记设备，走唯一索引 {@code uk_device_imei}。
 *
 * <p>禁用的设备（{@code enabled = 0}）仍视为已登记：禁用只阻止控制（PRD 5.8），设备照常上报状态。
 */
@Component
class JdbcDeviceDirectory implements DeviceDirectory {

    private final JdbcClient jdbc;

    JdbcDeviceDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegisteredDevice> findByImei(String imei) {
        return jdbc.sql("SELECT id, imei, rs485_addr FROM device WHERE imei = ?")
                .param(imei)
                .query((rs, n) -> new RegisteredDevice(rs.getLong("id"), rs.getString("imei"), rs.getInt("rs485_addr")))
                .optional();
    }

    @Override
    public Optional<RegisteredDevice> findById(long deviceId) {
        return jdbc.sql("SELECT id, imei, rs485_addr FROM device WHERE id = ?")
                .param(deviceId)
                .query((rs, n) -> new RegisteredDevice(rs.getLong("id"), rs.getString("imei"), rs.getInt("rs485_addr")))
                .optional();
    }
}
