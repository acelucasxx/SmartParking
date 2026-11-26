package com.smartparking.dtubench;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.dtubench.PayloadAnalyzer.Kind;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PayloadAnalyzerTest {

    private static final byte[] STATUS = Rs485Codec.encodeStatus(1, 0x0C, 0x16, 0x00);
    private static final byte[] ECHO = Rs485Codec.encodeEcho(1, 0x03);

    private final PayloadAnalyzer analyzer = new PayloadAnalyzer();

    private static byte[] concat(byte[]... parts) {
        byte[] out = new byte[0];
        for (byte[] p : parts) {
            byte[] next = Arrays.copyOf(out, out.length + p.length);
            System.arraycopy(p, 0, next, out.length, p.length);
            out = next;
        }
        return out;
    }

    @Test
    void singleFrameIsAcceptedByServer() {
        var a = analyzer.accept(STATUS);
        assertThat(a.kind()).isEqualTo(Kind.FRAME);
        assertThat(a.acceptedByServer()).isTrue();
        assertThat(a.frames()).singleElement().isInstanceOf(UplinkFrame.Status.class);
    }

    @Test
    void concatenatedFramesAreSplit() {
        var a = analyzer.accept(concat(ECHO, STATUS));
        assertThat(a.kind()).isEqualTo(Kind.CONCATENATED);
        assertThat(a.acceptedByServer()).isFalse();
        assertThat(a.frames()).hasSize(2);
        assertThat(a.frames().get(0)).isInstanceOf(UplinkFrame.Echo.class);
        assertThat(a.frames().get(1)).isInstanceOf(UplinkFrame.Status.class);
    }

    @Test
    void splitFrameIsReassembledAcrossPayloads() {
        var first = analyzer.accept(Arrays.copyOfRange(STATUS, 0, 4));
        assertThat(first.kind()).isEqualTo(Kind.FRAGMENT);
        assertThat(first.frames()).isEmpty();

        var second = analyzer.accept(Arrays.copyOfRange(STATUS, 4, STATUS.length));
        assertThat(second.kind()).isEqualTo(Kind.REASSEMBLED);
        assertThat(second.frames())
                .singleElement()
                .extracting(UplinkFrame::rawFrame)
                .isEqualTo(Hex.format(STATUS));
    }

    @Test
    void frameFollowedByHalfFrameKeepsTheCompleteOne() {
        var a = analyzer.accept(concat(ECHO, Arrays.copyOfRange(STATUS, 0, 3)));
        assertThat(a.kind()).isEqualTo(Kind.FRAGMENT);
        assertThat(a.frames()).singleElement().isInstanceOf(UplinkFrame.Echo.class);

        assertThat(analyzer.accept(Arrays.copyOfRange(STATUS, 3, STATUS.length)).kind())
                .isEqualTo(Kind.REASSEMBLED);
    }

    @Test
    void unmatchedTailWithoutPendingIsInvalid() {
        var a = analyzer.accept(Arrays.copyOfRange(STATUS, 4, STATUS.length));
        assertThat(a.kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void asciiPayloadLooksLikeDtuHeartbeat() {
        var a = analyzer.accept("HB,860000000000009,CSQ:23\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(a.kind()).isEqualTo(Kind.NON_RS485);
        assertThat(a.detail()).contains("CSQ:23");
    }

    @Test
    void tailBytesInsideDataDoNotCutTheFrameShort() {
        byte[] odd = Rs485Codec.encodeStatus(1, 0xFD, 0xFA, 0x00);
        var a = analyzer.accept(odd);
        assertThat(a.kind()).isEqualTo(Kind.FRAME);
        assertThat(a.frames()).singleElement().isInstanceOf(UplinkFrame.Status.class);
    }

    @Test
    void binaryGarbageIsInvalid() {
        assertThat(analyzer.accept(new byte[] {0x01, 0x02, (byte) 0x80}).kind()).isEqualTo(Kind.INVALID);
        assertThat(analyzer.accept(new byte[0]).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void staleFragmentIsDroppedWhenNextPayloadDoesNotComplete() {
        analyzer.accept(Arrays.copyOfRange(STATUS, 0, 4));
        assertThat(analyzer.accept(ECHO).kind()).isEqualTo(Kind.FRAME);
    }
}
