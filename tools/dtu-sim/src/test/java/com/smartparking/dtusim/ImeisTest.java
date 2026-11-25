package com.smartparking.dtusim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ImeisTest {

    @Test
    void knownImeiIsValid() {
        // 公开示例 IMEI，校验位 8
        assertThat(Imeis.isValid("490154203237518")).isTrue();
        assertThat(Imeis.isValid("490154203237517")).isFalse();
    }

    @Test
    void sequenceIsConsecutiveAndValid() {
        var imeis = Imeis.sequence("86000000000000", 12);
        assertThat(imeis).hasSize(12).doesNotHaveDuplicates().allMatch(Imeis::isValid);
        assertThat(imeis.get(0)).startsWith("86000000000000");
        assertThat(imeis.get(11)).startsWith("86000000000011");
    }

    @Test
    void rejectsBadPrefix() {
        assertThatThrownBy(() -> Imeis.sequence("8600", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Imeis.sequence("99999999999999", 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
