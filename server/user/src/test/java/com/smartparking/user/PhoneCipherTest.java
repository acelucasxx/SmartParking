package com.smartparking.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PhoneCipherTest {

    private static final String KEY = "user_data_key_for_unit_tests_0123456789";
    private final PhoneCipher cipher = new PhoneCipher(KEY);

    @Test
    void roundTripsAndDoesNotContainPlaintext() {
        byte[] sealed = cipher.encrypt("13912341234");
        assertThat(cipher.decrypt(sealed)).isEqualTo("13912341234");
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("1391234");
        // 版本 + IV + 11 字节密文 + 16 字节标签，放得进 VARBINARY(255)
        assertThat(sealed).hasSize(1 + 12 + 11 + 16);
    }

    @Test
    void sameNumberEncryptsDifferentlyButHashesTheSame() {
        assertThat(cipher.encrypt("13912341234")).isNotEqualTo(cipher.encrypt("13912341234"));
        assertThat(cipher.hash("13912341234"))
                .isEqualTo(cipher.hash("13912341234"))
                .hasSize(64)
                .isNotEqualTo(cipher.hash("13912341235"));
    }

    @Test
    void tamperedOrForeignCiphertextIsRejected() {
        byte[] sealed = cipher.encrypt("13912341234");
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(sealed)).isInstanceOf(IllegalStateException.class);

        byte[] other = new PhoneCipher(KEY + "x").encrypt("13912341234");
        assertThatThrownBy(() -> cipher.decrypt(other)).isInstanceOf(IllegalStateException.class);

        byte[] wrongVersion = cipher.encrypt("13912341234");
        wrongVersion[0] = 2;
        assertThatThrownBy(() -> cipher.decrypt(wrongVersion)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keysDependOnMasterKey() {
        assertThat(new PhoneCipher(KEY + "x").hash("13912341234")).isNotEqualTo(cipher.hash("13912341234"));
    }

    @Test
    void shortOrMissingKeyIsRejected() {
        assertThatThrownBy(() -> new PhoneCipher("too-short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhoneCipher(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masksAllButFirstThreeAndLastFour() {
        assertThat(PhoneCipher.mask("13812345678")).isEqualTo("138****5678");
        assertThat(PhoneCipher.mask("+85291234567")).isEqualTo("+85****4567");
        assertThat(PhoneCipher.mask("12345")).isEqualTo("****");
    }
}
