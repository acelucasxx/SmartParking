package com.smartparking.iot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.presence.ConnectionState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 判定窗口取后台参数（PRD 7 系统设置），不是写死的 10 分钟 / 24 小时 */
class OnlineStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T08:00:00Z");

    private static OnlineStateService with(Map<String, Integer> values) {
        SystemSettings settings = (key, defaultValue) -> values.getOrDefault(key, defaultValue);
        return new OnlineStateService(null, null, settings, event -> {});
    }

    private static DeviceRuntime seen(Duration ago) {
        return new DeviceRuntime(
                1,
                GateStatus.CLOSED,
                0x0C,
                0x16,
                false,
                OnlineState.ONLINE,
                NOW.minus(ago),
                NOW.minus(ago),
                ConnectionState.CONNECTED,
                NOW.minus(ago),
                1);
    }

    @Test
    void defaultsAreTenMinutesAndTwentyFourHours() {
        var service = with(Map.of());
        assertThat(service.evaluate(seen(Duration.ofMinutes(10)), NOW)).isEqualTo(OnlineState.ONLINE);
        assertThat(service.evaluate(seen(Duration.ofMinutes(11)), NOW)).isEqualTo(OnlineState.SLEEP);
        assertThat(service.evaluate(seen(Duration.ofHours(24)), NOW)).isEqualTo(OnlineState.SLEEP);
        assertThat(service.evaluate(seen(Duration.ofHours(25)), NOW)).isEqualTo(OnlineState.OFFLINE);
    }

    @Test
    void windowsFollowSystemSettings() {
        var service = with(Map.of(
                SystemSettings.Keys.ONLINE_SLEEP_MINUTES, 30,
                SystemSettings.Keys.ONLINE_OFFLINE_HOURS, 2));
        assertThat(service.evaluate(seen(Duration.ofMinutes(20)), NOW)).isEqualTo(OnlineState.ONLINE);
        assertThat(service.evaluate(seen(Duration.ofMinutes(31)), NOW)).isEqualTo(OnlineState.SLEEP);
        assertThat(service.evaluate(seen(Duration.ofHours(3)), NOW)).isEqualTo(OnlineState.OFFLINE);
    }
}
