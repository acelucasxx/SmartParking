package com.smartparking.dtusim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DtuSimulatorArgsTest {

    @Test
    void defaults() {
        var config = DtuSimulator.parse(new String[0], null);
        assertThat(config.broker()).isEqualTo("tcp://127.0.0.1:1883");
        assertThat(config.imeis()).hasSize(1).allMatch(Imeis::isValid);
        assertThat(config.travelMs()).isEqualTo(1200);
        assertThat(config.echo()).isTrue();
        assertThat(config.cleanSession()).isFalse();
        assertThat(config.activeReport()).isFalse();
    }

    @Test
    void parsesOptions() {
        var config = DtuSimulator.parse(
                new String[] {
                    "--count",
                    "5",
                    "--travel-ms",
                    "300",
                    "--no-echo",
                    "--active-report",
                    "--clean-session",
                    "--keep-alive",
                    "10"
                },
                "tcp://emqx:1883");
        assertThat(config.broker()).isEqualTo("tcp://emqx:1883");
        assertThat(config.imeis()).hasSize(5);
        assertThat(config.travelMs()).isEqualTo(300);
        assertThat(config.echo()).isFalse();
        assertThat(config.activeReport()).isTrue();
        assertThat(config.cleanSession()).isTrue();
        assertThat(config.keepAliveSeconds()).isEqualTo(10);
    }

    @Test
    void explicitImeisWin() {
        var config =
                DtuSimulator.parse(new String[] {"--count", "9", "--imeis", "490154203237518,860000000000001"}, null);
        assertThat(config.imeis()).containsExactly("490154203237518", "860000000000001");
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> DtuSimulator.parse(new String[] {"--count"}, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DtuSimulator.parse(new String[] {"--imeis", "123"}, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DtuSimulator.parse(new String[] {"--wat"}, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
