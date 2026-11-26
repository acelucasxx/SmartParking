package com.smartparking.iot.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import com.smartparking.iot.Devices;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.UplinkFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UplinkDispatcherTest {

    private static final String IMEI = "860000000000009";
    private static final Instant NOW = Instant.parse("2026-11-26T02:00:00Z");

    private final Devices directory = Devices.only(new RegisteredDevice(42, IMEI, 1));
    private final List<Object> events = new ArrayList<>();
    private final UplinkDispatcher dispatcher =
            new UplinkDispatcher(directory, events::add, Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

    @Test
    void registeredDeviceStatusFrameIsPublished() {
        assertThat(dispatcher.dispatch("parking/up/" + IMEI, Hex.parse("FD 00 01 09 16 11 FD FA")))
                .isTrue();
        assertThat(events).singleElement().isInstanceOfSatisfying(DeviceUplinkEvent.class, e -> {
            assertThat(e.deviceId()).isEqualTo(42);
            assertThat(e.imei()).isEqualTo(IMEI);
            assertThat(e.receivedAt()).isEqualTo(NOW);
            assertThat(((UplinkFrame.Status) e.frame()).status()).isEqualTo(GateStatus.OPENED);
        });
    }

    @Test
    void echoAndVersionFramesAreAlsoPublished() {
        // 任意上行都要刷新 last_seen_at（PRD 8.5），是否算成功由状态机判断
        dispatcher.dispatch("parking/up/" + IMEI, Hex.parse("FD 00 01 03 FD FA"));
        dispatcher.dispatch("parking/up/" + IMEI, Hex.parse("FD 00 01 CF 16 FD FA"));
        assertThat(events).hasSize(2);
    }

    @Test
    void unregisteredImeiIsDropped() {
        assertThat(dispatcher.dispatch("parking/up/860000000000017", Hex.parse("FD 00 01 09 16 11 FD FA")))
                .isFalse();
        assertThat(dispatcher.dispatch("parking/up/860000000000017", Hex.parse("FD 00 01 09 16 11 FD FA")))
                .isFalse();
        assertThat(events).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "parking/up/",
                "parking/up/12345",
                "parking/up/86000000000000x",
                "parking/down/860000000000009",
                "other"
            })
    void malformedTopicIsDroppedWithoutLookup(String topic) {
        assertThat(dispatcher.dispatch(topic, Hex.parse("FD 00 01 09 16 11 FD FA")))
                .isFalse();
        assertThat(directory.lookups).hasValue(0);
        assertThat(events).isEmpty();
    }

    @Test
    void nullTopicIsDropped() {
        assertThat(dispatcher.dispatch(null, new byte[0])).isFalse();
    }

    @Test
    void malformedFrameIsDropped() {
        assertThat(dispatcher.dispatch("parking/up/" + IMEI, "hello".getBytes()))
                .isFalse();
        assertThat(dispatcher.dispatch("parking/up/" + IMEI, new byte[0])).isFalse();
        assertThat(dispatcher.dispatch("parking/up/" + IMEI, null)).isFalse();
        assertThat(events).isEmpty();
    }
}
