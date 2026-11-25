package com.smartparking.iot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.presence.ConnectionState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OnlinePolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Duration SLEEP = Duration.ofMinutes(10);
    private static final Duration OFFLINE = Duration.ofHours(24);

    private static DeviceRuntime seen(Duration ago, ConnectionState conn) {
        return new DeviceRuntime(
                1,
                GateStatus.CLOSED,
                0x0C,
                0x16,
                false,
                OnlineState.ONLINE,
                NOW.minus(ago),
                NOW.minus(ago),
                conn,
                null,
                1);
    }

    private static OnlineState eval(DeviceRuntime r) {
        return OnlinePolicy.evaluate(r, NOW, SLEEP, OFFLINE);
    }

    @Test
    void neverSeenIsOffline() {
        assertThat(eval(null)).isEqualTo(OnlineState.OFFLINE);
        assertThat(eval(new DeviceRuntime(
                        1, GateStatus.UNKNOWN, null, null, null, OnlineState.OFFLINE, null, null, null, null, 0)))
                .isEqualTo(OnlineState.OFFLINE);
    }

    @Test
    void offlineHasHighestPriority() {
        assertThat(eval(seen(Duration.ofHours(25), ConnectionState.CONNECTED))).isEqualTo(OnlineState.OFFLINE);
        assertThat(eval(seen(Duration.ofHours(25), ConnectionState.DISCONNECTED)))
                .isEqualTo(OnlineState.OFFLINE);
    }

    @Test
    void disconnectOrSilenceIsSleep() {
        assertThat(eval(seen(Duration.ofSeconds(5), ConnectionState.DISCONNECTED)))
                .isEqualTo(OnlineState.SLEEP);
        assertThat(eval(seen(Duration.ofMinutes(11), ConnectionState.CONNECTED)))
                .isEqualTo(OnlineState.SLEEP);
    }

    @Test
    void recentUplinkWhileConnectedIsOnline() {
        assertThat(eval(seen(Duration.ofMinutes(9), ConnectionState.CONNECTED))).isEqualTo(OnlineState.ONLINE);
        assertThat(eval(seen(Duration.ofSeconds(1), null))).as("未收到过连接事件时只看上行").isEqualTo(OnlineState.ONLINE);
    }

    @Test
    void boundariesAreExclusive() {
        assertThat(eval(seen(SLEEP, ConnectionState.CONNECTED))).isEqualTo(OnlineState.ONLINE);
        assertThat(eval(seen(OFFLINE, ConnectionState.CONNECTED))).isEqualTo(OnlineState.SLEEP);
    }
}
