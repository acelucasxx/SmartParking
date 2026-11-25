package com.smartparking.iot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import com.smartparking.iot.auth.EmqxAuthController.AuthRequest;
import com.smartparking.iot.mqtt.MqttProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EmqxAuthControllerTest {

    private static final String KEY = "device_secret_key_dev_only_0123456789";
    private static final String IMEI = "860000000000009";
    private static final String UNKNOWN = "860000000000017";

    private final DeviceCredentials credentials = new DeviceCredentials(KEY);
    private final DeviceDirectory directory =
            imei -> IMEI.equals(imei) ? Optional.of(new RegisteredDevice(1, IMEI, 1)) : Optional.empty();
    private final MqttProperties mqtt =
            new MqttProperties(true, "tcp://x", null, "smartparking-server", "cloud-pass", null, null, null);
    private final EmqxAuthController controller = new EmqxAuthController(
            directory, credentials, mqtt, new EmqxHookToken(new DeviceAuthProperties("hook-token", KEY)));

    private AuthRequest device(String imei, String clientId, String password) {
        return new AuthRequest(clientId, imei, password);
    }

    @Test
    void registeredDeviceWithDerivedPasswordIsAllowed() {
        assertThat(controller.decide(device(IMEI, "dtu-" + IMEI, credentials.passwordFor(IMEI))))
                .isEqualTo(EmqxAuthController.ALLOW);
    }

    @Test
    void wrongPasswordIsDenied() {
        assertThat(controller.decide(device(IMEI, "dtu-" + IMEI, credentials.passwordFor(UNKNOWN))))
                .isEqualTo(EmqxAuthController.DENY);
        assertThat(controller.decide(device(IMEI, "dtu-" + IMEI, null))).isEqualTo(EmqxAuthController.DENY);
    }

    @Test
    void unregisteredImeiIsDeniedEvenWithValidPassword() {
        assertThat(controller.decide(device(UNKNOWN, "dtu-" + UNKNOWN, credentials.passwordFor(UNKNOWN))))
                .isEqualTo(EmqxAuthController.DENY);
    }

    @Test
    void clientIdMustMatchImei() {
        assertThat(controller.decide(device(IMEI, "dtu-" + UNKNOWN, credentials.passwordFor(IMEI))))
                .isEqualTo(EmqxAuthController.DENY);
        assertThat(controller.decide(device(IMEI, "anything", credentials.passwordFor(IMEI))))
                .isEqualTo(EmqxAuthController.DENY);
    }

    @Test
    void nonImeiUsernameIsDenied() {
        assertThat(controller.decide(device("admin", "dtu-admin", "x"))).isEqualTo(EmqxAuthController.DENY);
        assertThat(controller.decide(device(null, "dtu-", "x"))).isEqualTo(EmqxAuthController.DENY);
    }

    @Test
    void cloudAccountIsSuperuser() {
        assertThat(controller.decide(new AuthRequest("smartparking-server-1", "smartparking-server", "cloud-pass")))
                .isEqualTo(EmqxAuthController.ALLOW_SUPERUSER);
        assertThat(controller.decide(new AuthRequest("smartparking-server-1", "smartparking-server", "wrong")))
                .isEqualTo(EmqxAuthController.DENY);
    }

    @Test
    void hookTokenIsRequired() {
        var request = device(IMEI, "dtu-" + IMEI, credentials.passwordFor(IMEI));
        assertThat(controller.authenticate(null, request).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.authenticate("wrong", request).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var ok = controller.authenticate("hook-token", request);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo(EmqxAuthController.ALLOW);
    }

    @Test
    void missingHookTokenFailsStartup() {
        assertThatThrownBy(() -> new EmqxHookToken(new DeviceAuthProperties("", KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EmqxHookToken(new DeviceAuthProperties(null, KEY)))
                .isInstanceOf(IllegalStateException.class);
    }
}
