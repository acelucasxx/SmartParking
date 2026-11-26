package com.smartparking.dtubench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DtuBenchArgsTest {

    private static final String IMEI = "860000000000009";

    @Test
    void defaultsMatchLocalEnvironment() {
        var a = DtuBench.parse(new String[] {"run", "--imei", IMEI}, Map.of());
        assertThat(a.broker()).isEqualTo("tcp://127.0.0.1:1883");
        assertThat(a.username()).isEqualTo("smartparking-server");
        assertThat(a.password()).isEqualTo(DtuBench.LOCAL_SERVER_PASSWORD);
        assertThat(a.rounds()).isEqualTo(10);
        assertThat(a.timeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(a.poll()).isEqualTo(Duration.ofMillis(300));
        assertThat(a.out().toString()).startsWith("bench-" + IMEI + "-");
    }

    @Test
    void environmentAndOptionsOverrideDefaults() {
        var a = DtuBench.parse(
                new String[] {"run", "--imei", IMEI, "--rounds", "3", "--poll-ms", "200"},
                Map.of("BROKER", "tcp://emqx:1883", "MQTT_SERVER_PASSWORD", "p"));
        assertThat(a.broker()).isEqualTo("tcp://emqx:1883");
        assertThat(a.password()).isEqualTo("p");
        assertThat(a.rounds()).isEqualTo(3);
        assertThat(a.poll()).isEqualTo(Duration.ofMillis(200));

        var anon = DtuBench.parse(new String[] {"listen", "--imei", IMEI, "--anonymous"}, Map.of());
        assertThat(anon.username()).isNull();
        assertThat(anon.password()).isNull();
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> DtuBench.parse(new String[] {}, Map.of())).hasMessageContaining("子命令");
        assertThatThrownBy(() -> DtuBench.parse(new String[] {"fly", "--imei", IMEI}, Map.of()))
                .hasMessageContaining("未知子命令");
        assertThatThrownBy(() -> DtuBench.parse(new String[] {"run", "--imei", "123"}, Map.of()))
                .hasMessageContaining("15 位");
        assertThatThrownBy(() -> DtuBench.parse(new String[] {"run", "--imei", IMEI, "--rounds", "0"}, Map.of()))
                .hasMessageContaining("正整数");
        assertThatThrownBy(() -> DtuBench.parse(new String[] {"run", "--imei", IMEI, "--bogus", "1"}, Map.of()))
                .hasMessageContaining("--bogus");
    }
}
