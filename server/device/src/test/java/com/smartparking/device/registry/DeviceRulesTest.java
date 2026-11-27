package com.smartparking.device.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.common.error.ErrorCode;
import com.smartparking.device.registry.DeviceRules.DeviceDraft;
import com.smartparking.device.registry.DeviceRules.Invalid;
import org.junit.jupiter.api.Test;

class DeviceRulesTest {

    static final String IMEI = "490154203237518";

    private static DeviceDraft ok(String sn, String imei, String mac) {
        return DeviceRules.validate(sn, imei, mac, null, "SPG-100", "B20261201");
    }

    private static void rejects(ErrorCode code, String sn, String imei, String mac, String model, String batch) {
        assertThatThrownBy(() -> DeviceRules.validate(sn, imei, mac, null, model, batch))
                .isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void luhn() {
        assertThat(DeviceRules.luhn("490154203237518")).isTrue();
        assertThat(DeviceRules.luhn("356938035643809")).isTrue();
        assertThat(DeviceRules.luhn("490154203237517")).isFalse();
        assertThat(DeviceRules.luhn("861234567890128")).isFalse();
    }

    @Test
    void normalizesFields() {
        DeviceDraft d = DeviceRules.validate(
                " sp2612000123 ", " 4901 5420 3237 518", "aa-bb-cc-dd-ee-ff", "8986 0012", " SPG-100 ", "B1");
        assertThat(d.sn()).isEqualTo("SP2612000123");
        assertThat(d.imei()).isEqualTo(IMEI);
        assertThat(d.bleMac()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(d.iccid()).isEqualTo("89860012");
        assertThat(d.model()).isEqualTo("SPG-100");

        assertThat(ok("SP2612000123", IMEI, "aabbccddeeff").bleMac()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(ok("SP2612000123", IMEI, "AA:BB:CC:DD:EE:FF").bleMac()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(ok("SP2612000123", IMEI, "  ").bleMac()).isNull();
        assertThat(ok("SP2612000123", IMEI, null).bleMac()).isNull();
    }

    @Test
    void imeiErrorsUseImeiInvalid() {
        rejects(ErrorCode.IMEI_INVALID, "SP2612000123", "49015420323751", null, "M", "B");
        rejects(ErrorCode.IMEI_INVALID, "SP2612000123", "4901542032375180", null, "M", "B");
        rejects(ErrorCode.IMEI_INVALID, "SP2612000123", "49015420323751X", null, "M", "B");
        rejects(ErrorCode.IMEI_INVALID, "SP2612000123", "490154203237517", null, "M", "B");
        rejects(ErrorCode.IMEI_INVALID, "SP2612000123", null, null, "M", "B");
    }

    @Test
    void otherFieldErrorsAreBadRequest() {
        rejects(ErrorCode.BAD_REQUEST, "", IMEI, null, "M", "B");
        rejects(ErrorCode.BAD_REQUEST, "SHORT1", IMEI, null, "M", "B");
        rejects(ErrorCode.BAD_REQUEST, "SP-2612-000123", IMEI, null, "M", "B");
        rejects(ErrorCode.BAD_REQUEST, "S".repeat(33), IMEI, null, "M", "B");
        rejects(ErrorCode.BAD_REQUEST, "SP2612000123", IMEI, "AA:BB:CC:DD:EE", "M", "B");
        rejects(ErrorCode.BAD_REQUEST, "SP2612000123", IMEI, "GG:BB:CC:DD:EE:FF", "M", "B");
        rejects(ErrorCode.BAD_REQUEST, "SP2612000123", IMEI, null, " ", "B");
        rejects(ErrorCode.BAD_REQUEST, "SP2612000123", IMEI, null, "M", null);
        rejects(ErrorCode.BAD_REQUEST, "SP2612000123", IMEI, null, "型".repeat(33), "B");
        assertThat(ok("S".repeat(32), IMEI, null).sn()).hasSize(32);
        assertThat(ok("A1234567", IMEI, null).sn()).isEqualTo("A1234567");
    }
}
