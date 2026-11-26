package com.smartparking.iot;

import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用的内存设备登记表 */
public final class Devices implements DeviceDirectory {

    private final List<RegisteredDevice> devices;
    private final boolean any;
    public final AtomicInteger lookups = new AtomicInteger();

    private Devices(List<RegisteredDevice> devices, boolean any) {
        this.devices = devices;
        this.any = any;
    }

    /** 只登记给定设备 */
    public static Devices only(RegisteredDevice... devices) {
        return new Devices(List.of(devices), false);
    }

    /** 任意 IMEI 都视为已登记，设备 ID 为 1 */
    public static Devices any() {
        return new Devices(List.of(), true);
    }

    public static Devices none() {
        return new Devices(List.of(), false);
    }

    @Override
    public Optional<RegisteredDevice> findByImei(String imei) {
        lookups.incrementAndGet();
        if (any) {
            return Optional.of(new RegisteredDevice(1, imei, 1));
        }
        return devices.stream().filter(d -> d.imei().equals(imei)).findFirst();
    }

    @Override
    public Optional<RegisteredDevice> findById(long deviceId) {
        return devices.stream().filter(d -> d.deviceId() == deviceId).findFirst();
    }
}
