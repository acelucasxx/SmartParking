package com.smartparking.device.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActivateCodesTest {

    private static final String KEY = "activate_key_for_unit_tests_0123456789";
    private final ActivateCodes codes = new ActivateCodes(KEY);

    @Test
    void generatesEightCharsWithoutLookAlikes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = codes.generate();
            assertThat(code).hasSize(8).matches("[" + ActivateCodes.ALPHABET + "]{8}");
            assertThat(code).doesNotContain("0", "O", "1", "I", "L");
            seen.add(code);
        }
        assertThat(seen).hasSizeGreaterThan(1990);
    }

    @Test
    void hashIsKeyedAndMatchesNormalizedInput() {
        String hash = codes.hash("K7Q2M9XA");
        assertThat(hash).startsWith("h1$").hasSize(3 + 64).doesNotContain("K7Q2M9XA");
        assertThat(codes.hash("K7Q2M9XA")).isEqualTo(hash);

        assertThat(codes.matches("K7Q2M9XA", hash)).isTrue();
        assertThat(codes.matches(" k7q2 m9xa ", hash)).isTrue();
        assertThat(codes.matches("K7Q2M9XB", hash)).isFalse();
        assertThat(codes.matches(null, hash)).isFalse();
        assertThat(codes.matches("K7Q2M9XA", "x")).isFalse();
        assertThat(new ActivateCodes(KEY + "x").matches("K7Q2M9XA", hash)).isFalse();
    }

    @Test
    void sealedPayloadRoundTripsAndDetectsTampering() {
        byte[] plain = "[{\"sn\":\"SP1\",\"code\":\"K7Q2M9XA\"}]".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = codes.seal(plain);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("K7Q2M9XA");
        assertThat(codes.open(sealed)).isEqualTo(plain);

        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> codes.open(sealed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ActivateCodes(KEY + "x").open(codes.seal(plain)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortKeyIsRejected() {
        assertThatThrownBy(() -> new ActivateCodes("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivateCodes(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
