package com.smartparking.dtubench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatenciesTest {

    @Test
    void nearestRankPercentiles() {
        var l = new Latencies();
        for (long v = 1; v <= 20; v++) {
            l.add(v * 10);
        }
        assertThat(l.percentile(50)).isEqualTo(100);
        assertThat(l.percentile(95)).isEqualTo(190);
        assertThat(l.max()).isEqualTo(200);
        assertThat(l.summary()).isEqualTo("100 / 190 / 200");
    }

    @Test
    void singleAndEmpty() {
        var l = new Latencies();
        assertThat(l.summary()).isEqualTo("—");
        l.add(42);
        assertThat(l.percentile(95)).isEqualTo(42);
    }
}
