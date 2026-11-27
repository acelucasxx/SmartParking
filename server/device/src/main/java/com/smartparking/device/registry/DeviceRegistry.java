package com.smartparking.device.registry;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.device.registry.DeviceRepository.Inserted;
import com.smartparking.device.registry.DeviceRules.DeviceDraft;
import org.springframework.dao.DuplicateKeyException;

/** 单台录入（PRD 5.1、7 设备入库）。新设备为待激活、启用；激活码明文只随本次结果返回一次 */
public class DeviceRegistry {

    private final DeviceRepository devices;
    private final ActivateCodes codes;

    public DeviceRegistry(DeviceRepository devices, ActivateCodes codes) {
        this.devices = devices;
        this.codes = codes;
    }

    public record Registered(long id, DeviceDraft device, java.time.LocalDateTime createdAt, String activateCode) {}

    /**
     * @throws BizException 字段不合法 10001 / 40011；SN、IMEI、MAC 已存在 40010 / 40012 / 40013
     */
    public Registered register(String sn, String imei, String bleMac, String iccid, String model, String batchNo) {
        DeviceDraft draft;
        try {
            draft = DeviceRules.validate(sn, imei, bleMac, iccid, model, batchNo);
        } catch (DeviceRules.Invalid e) {
            throw new BizException(e.code(), e.getMessage());
        }
        if (devices.snExists(draft.sn())) {
            throw new BizException(ErrorCode.SN_DUPLICATE);
        }
        if (devices.imeiExists(draft.imei())) {
            throw new BizException(ErrorCode.IMEI_DUPLICATE);
        }
        if (devices.macExists(draft.bleMac())) {
            throw new BizException(ErrorCode.MAC_DUPLICATE);
        }
        String code = codes.generate();
        try {
            Inserted inserted = devices.insert(draft, codes.hash(code));
            return new Registered(inserted.id(), draft, inserted.createdAt(), code);
        } catch (DuplicateKeyException e) {
            throw new BizException(duplicateCode(e));
        }
    }

    /** 并发录入时由唯一索引兜底，按索引名对应错误码 */
    static ErrorCode duplicateCode(DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uk_device_imei")) {
            return ErrorCode.IMEI_DUPLICATE;
        }
        if (message.contains("uk_device_ble_mac")) {
            return ErrorCode.MAC_DUPLICATE;
        }
        return ErrorCode.SN_DUPLICATE;
    }
}
