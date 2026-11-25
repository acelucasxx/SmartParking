package com.smartparking.iot.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartparking.iot.Devices;
import com.smartparking.iot.codec.Rs485Codec;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MqttGatewayTest {

    private final UplinkDispatcher dispatcher = new UplinkDispatcher(Devices.none(), event -> {});

    @Test
    void defaultsAreApplied() {
        var props = new MqttProperties(null, "tcp://x", null, null, null, null, null, null);
        assertThat(props.enabled()).isTrue();
        assertThat(props.downlinkExpiry()).isEqualTo(Duration.ofSeconds(15));
        assertThat(props.keepAlive()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.reconnectMaxDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void generatesUniqueClientIdWhenNotConfigured() {
        var props = new MqttProperties(true, "tcp://x", null, null, null, null, null, null);
        var a = new MqttGateway(props, dispatcher);
        var b = new MqttGateway(props, dispatcher);
        assertThat(a.clientId()).startsWith("smartparking-server-").isNotEqualTo(b.clientId());
        assertThat(new MqttGateway(
                                new MqttProperties(true, "tcp://x", "fixed", null, null, null, null, null), dispatcher)
                        .clientId())
                .isEqualTo("fixed");
    }

    @Test
    void sendWithoutConnectionFailsFast() {
        var gateway =
                new MqttGateway(new MqttProperties(true, "tcp://x", null, null, null, null, null, null), dispatcher);
        assertThat(gateway.isConnected()).isFalse();
        assertThatThrownBy(() -> gateway.send("860000000000009", Rs485Codec.encodeQuery()))
                .isInstanceOf(DeviceLinkUnavailableException.class);
        gateway.destroy();
    }

    @Test
    void topics() {
        assertThat(MqttProperties.uplinkTopic("860000000000009")).isEqualTo("parking/up/860000000000009");
        assertThat(MqttProperties.downlinkTopic("860000000000009")).isEqualTo("parking/down/860000000000009");
        assertThat(MqttProperties.UPLINK_SUBSCRIPTION).isEqualTo("parking/up/+");
    }
}
