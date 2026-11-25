package com.smartparking.iot.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import com.smartparking.iot.auth.DeviceAuthProperties;
import com.smartparking.iot.auth.EmqxHookToken;
import com.smartparking.iot.presence.EmqxEventController.EventRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EmqxEventControllerTest {

    private final List<Object> published = new ArrayList<>();
    private final EmqxEventController controller = new EmqxEventController(
            new EmqxHookToken(new DeviceAuthProperties("hook-token", null)),
            new ConnectionEventService(
                    imei -> Optional.of(new RegisteredDevice(1, imei, 1)), (id, s, at) -> true, published::add));

    private static EventRequest connected(Long timestamp) {
        return new EventRequest("client.connected", "dtu-860000000000009", timestamp, null);
    }

    @Test
    void rejectsWrongToken() {
        assertThat(controller.receive(null, connected(1L)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.receive("nope", connected(1L)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(published).isEmpty();
    }

    @Test
    void acceptsEvent() {
        assertThat(controller.receive("hook-token", connected(1L)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(published).hasSize(1);
    }

    @Test
    void missingTimestampIsAcknowledgedButIgnored() {
        assertThat(controller.receive("hook-token", connected(null)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(published).isEmpty();
    }

    @Test
    void undefinedReasonIsTreatedAsAbsent() {
        controller.receive("hook-token", new EventRequest("client.connected", "dtu-860000000000009", 1L, "undefined"));
        assertThat(published).singleElement().extracting("reason").isNull();
        assertThat(EmqxEventController.reasonOf("tcp_closed")).isEqualTo("tcp_closed");
        assertThat(EmqxEventController.reasonOf(" ")).isNull();
    }
}
