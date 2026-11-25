package com.smartparking.iot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeviceCredentialsTest {

    private static final String KEY = "device_secret_key_dev_only_0123456789";
    private static final String IMEI = "860000000000009";

    private final DeviceCredentials credentials = new DeviceCredentials(KEY);

    @Test
    void passwordIsDeterministic32Hex() {
        String password = credentials.passwordFor(IMEI);
        assertThat(password).matches("[0-9a-f]{32}").isEqualTo(new DeviceCredentials(KEY).passwordFor(IMEI));
    }

    @Test
    void passwordDiffersPerImeiAndKey() {
        assertThat(credentials.passwordFor(IMEI)).isNotEqualTo(credentials.passwordFor("860000000000017"));
        assertThat(credentials.passwordFor(IMEI)).isNotEqualTo(new DeviceCredentials(KEY + "x").passwordFor(IMEI));
    }

    @Test
    void matchesOnlyTheDerivedPassword() {
        assertThat(credentials.matches(IMEI, credentials.passwordFor(IMEI))).isTrue();
        assertThat(credentials.matches(IMEI, credentials.passwordFor("860000000000017")))
                .isFalse();
        assertThat(credentials.matches(IMEI, "")).isFalse();
        assertThat(credentials.matches(IMEI, null)).isFalse();
        assertThat(credentials.matches(null, "x")).isFalse();
    }

    @Test
    void keyIdIsShortFingerprintThatDoesNotLeakPasswords() {
        assertThat(credentials.keyId()).matches("[0-9a-f]{8}");
        assertThat(credentials.passwordFor(IMEI)).doesNotContain(credentials.keyId());
        assertThat(new DeviceCredentials(KEY + "x").keyId()).isNotEqualTo(credentials.keyId());
    }

    @Test
    void rejectsShortOrMissingKey() {
        assertThatThrownBy(() -> new DeviceCredentials("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeviceCredentials(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
