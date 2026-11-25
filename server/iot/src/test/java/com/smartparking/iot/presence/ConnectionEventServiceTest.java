package com.smartparking.iot.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import com.smartparking.iot.presence.ConnectionEventService.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConnectionEventServiceTest {

    private static final String IMEI = "860000000000009";
    private static final String CLIENT = "dtu-" + IMEI;

    /** 与 JDBC 实现相同的语义：只接受时刻更晚的事件 */
    private static final class MemoryStore implements ConnectionStateStore {
        final Map<Long, Instant> lastAt = new HashMap<>();
        final Map<Long, ConnectionState> state = new HashMap<>();

        @Override
        public boolean record(long deviceId, ConnectionState s, Instant at) {
            Instant last = lastAt.get(deviceId);
            if (last != null && !last.isBefore(at)) {
                return false;
            }
            lastAt.put(deviceId, at);
            state.put(deviceId, s);
            return true;
        }
    }

    private final DeviceDirectory directory =
            imei -> IMEI.equals(imei) ? Optional.of(new RegisteredDevice(7, IMEI, 1)) : Optional.empty();
    private final MemoryStore store = new MemoryStore();
    private final List<Object> published = new ArrayList<>();
    private final ConnectionEventService service = new ConnectionEventService(directory, store, published::add);

    @Test
    void connectedIsRecordedAndPublished() {
        assertThat(service.handle("client.connected", CLIENT, 1_000, null)).isEqualTo(Outcome.APPLIED);
        assertThat(store.state).containsEntry(7L, ConnectionState.CONNECTED);
        assertThat(published)
                .containsExactly(new DeviceConnectionEvent(
                        7, IMEI, ConnectionState.CONNECTED, Instant.ofEpochMilli(1_000), null));
    }

    @Test
    void disconnectedCarriesReason() {
        service.handle("client.connected", CLIENT, 1_000, null);
        assertThat(service.handle("client.disconnected", CLIENT, 2_000, "keepalive_timeout"))
                .isEqualTo(Outcome.APPLIED);
        assertThat(store.state).containsEntry(7L, ConnectionState.DISCONNECTED);
        assertThat(published)
                .last()
                .isEqualTo(new DeviceConnectionEvent(
                        7, IMEI, ConnectionState.DISCONNECTED, Instant.ofEpochMilli(2_000), "keepalive_timeout"));
    }

    @Test
    void olderEventArrivingLateIsIgnored() {
        service.handle("client.connected", CLIENT, 3_000, null);
        assertThat(service.handle("client.disconnected", CLIENT, 2_000, "sock_closed"))
                .isEqualTo(Outcome.STALE);
        assertThat(store.state).containsEntry(7L, ConnectionState.CONNECTED);
        assertThat(published).hasSize(1);
    }

    @Test
    void duplicateDeliveryIsPublishedOnce() {
        service.handle("client.connected", CLIENT, 1_000, null);
        assertThat(service.handle("client.connected", CLIENT, 1_000, null)).isEqualTo(Outcome.STALE);
        assertThat(published).hasSize(1);
    }

    @Test
    void takeoverDisconnectIsIgnored() {
        service.handle("client.connected", CLIENT, 1_000, null);
        service.handle("client.connected", CLIENT, 2_000, null);
        assertThat(service.handle("client.disconnected", CLIENT, 2_100, "takenover"))
                .isEqualTo(Outcome.REPLACED);
        assertThat(service.handle("client.disconnected", CLIENT, 2_100, "discarded"))
                .isEqualTo(Outcome.REPLACED);
        assertThat(store.state).containsEntry(7L, ConnectionState.CONNECTED);
    }

    @Test
    void nonDeviceClientsAreIgnored() {
        assertThat(service.handle("client.connected", "smartparking-server-1", 1_000, null))
                .isEqualTo(Outcome.NOT_DEVICE);
        assertThat(service.handle("client.connected", "dtu-123", 1_000, null)).isEqualTo(Outcome.NOT_DEVICE);
        assertThat(service.handle("client.connected", null, 1_000, null)).isEqualTo(Outcome.NOT_DEVICE);
        assertThat(published).isEmpty();
    }

    @Test
    void unregisteredDeviceIsIgnored() {
        assertThat(service.handle("client.connected", "dtu-860000000000017", 1_000, null))
                .isEqualTo(Outcome.UNREGISTERED);
        assertThat(published).isEmpty();
    }

    @Test
    void unsupportedEventIsIgnored() {
        assertThat(service.handle("session.subscribed", CLIENT, 1_000, null)).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(service.handle(null, CLIENT, 1_000, null)).isEqualTo(Outcome.UNSUPPORTED);
    }
}
