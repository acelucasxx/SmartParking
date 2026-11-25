package com.smartparking.iot.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Rs485CodecTest {

    /** PRD 8.2 帧表中的全部示例 */
    @Nested
    class PrdExamples {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "QUERY_STATUS, FD 00 01 D0 C0 FD FA",
            "OPEN,         FD 00 01 03 FD FA",
            "CLOSE,        FD 00 01 05 FD FA",
            "VERSION,      FD 00 01 CF FD FA",
        })
        void encodesDownlinkCommands(GateCommand command, String expected) {
            assertThat(Hex.format(Rs485Codec.encode(command, Rs485Codec.DEFAULT_ADDRESS)))
                    .isEqualTo(expected);
        }

        @Test
        void shortcutsMatchPrd() {
            assertThat(Hex.format(Rs485Codec.encodeQuery())).isEqualTo("FD 00 01 D0 C0 FD FA");
            assertThat(Hex.format(Rs485Codec.encodeOpen())).isEqualTo("FD 00 01 03 FD FA");
            assertThat(Hex.format(Rs485Codec.encodeClose())).isEqualTo("FD 00 01 05 FD FA");
            assertThat(Hex.format(Rs485Codec.encodeVersionQuery())).isEqualTo("FD 00 01 CF FD FA");
        }

        @Test
        void queryAlwaysCarriesChargerStandby() {
            DownlinkFrame frame = Rs485Codec.decodeDownlink(Rs485Codec.encodeQuery());
            assertThat(frame.command()).contains(GateCommand.QUERY_STATUS);
            assertThat(frame.data()).containsExactly((byte) 0xC0);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "FD 00 01 D0 C0 FD FA, QUERY_STATUS",
            "FD 00 01 03 FD FA,    OPEN",
            "FD 00 01 05 FD FA,    CLOSE",
            "FD 00 01 CF FD FA,    VERSION",
        })
        void decodesDownlinkCommands(String hex, GateCommand expected) {
            DownlinkFrame frame = Rs485Codec.decodeDownlink(hex);
            assertThat(frame.address()).isEqualTo(1);
            assertThat(frame.command()).contains(expected);
            assertThat(frame.rawFrame()).isEqualTo(hex);
        }

        @Test
        void decodesStatusFrame() {
            var frame = (UplinkFrame.Status) Rs485Codec.decodeUplink("FD 00 01 09 16 11 FD FA");
            assertThat(frame.address()).isEqualTo(1);
            assertThat(frame.status()).isEqualTo(GateStatus.OPENED);
            assertThat(frame.trigger()).isEqualTo(TriggerSource.APP);
            assertThat(frame.occupied()).isTrue();
            assertThat(frame.fault()).isEmpty();
            assertThat(frame.rawFrame()).isEqualTo("FD 00 01 09 16 11 FD FA");
        }

        @Test
        void decodesVersionFrame() {
            assertThat(Rs485Codec.decodeUplink("FD 00 01 CF 16 FD FA"))
                    .isEqualTo(new UplinkFrame.Version(1, 0x16, "FD 00 01 CF 16 FD FA"));
        }

        @ParameterizedTest
        @CsvSource({"FD 00 01 03 FD FA, OPEN", "FD 00 01 05 FD FA, CLOSE"})
        void decodesEchoFrame(String hex, GateCommand command) {
            assertThat(Rs485Codec.decodeUplink(hex))
                    .isEqualTo(new UplinkFrame.Echo(1, command.code(), Optional.of(command), hex));
        }
    }

    /** 上下行同一长度含义不同，方向错用时不能被误判为合法帧 */
    @Nested
    class DirectionMismatch {

        @Test
        void downlinkQueryReadAsUplinkIsUnknownNotVersion() {
            assertThat(Rs485Codec.decodeUplink(Rs485Codec.encodeQuery())).isInstanceOf(UplinkFrame.Unknown.class);
        }

        @Test
        void uplinkVersionReadAsDownlinkIsRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeDownlink("FD 00 01 CF 16 FD FA"))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("VERSION");
        }

        @Test
        void uplinkStatusReadAsDownlinkIsRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeDownlink("FD 00 01 09 16 11 FD FA"))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("下行帧超过 7 字节");
        }

        @Test
        void queryWithoutChargerByteIsRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeDownlink("FD 00 01 D0 FD FA"))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("QUERY_STATUS");
        }
    }

    /** PRD 8.3 码表 */
    @Nested
    class CodeTables {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "09, OPENED",
            "0C, CLOSED",
            "19, OPENING",
            "1C, CLOSING",
            "00, STOPPED",
            "E3, FAULT",
            "7F, UNKNOWN",
            "FF, UNKNOWN",
        })
        void statusCodes(String code, GateStatus expected) {
            var frame = (UplinkFrame.Status) Rs485Codec.decodeUplink("FD 00 01 " + code + " 16 00 FD FA");
            assertThat(frame.status()).isEqualTo(expected);
            assertThat(frame.statusCode()).isEqualTo(Integer.parseInt(code, 16));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "01, REMOTE_CONTROL",
            "02, REMOTE_CONTROL",
            "03, GROUND_SENSOR",
            "04, GROUND_SENSOR",
            "06, EXTERNAL_SWITCH",
            "07, EXTERNAL_SWITCH",
            "10, AUTO_CLOSE",
            "11, OBSTRUCTED",
            "12, INFRARED",
            "15, POWER_ON",
            "16, APP",
            "00, UNKNOWN",
            "17, UNKNOWN",
            "99, UNKNOWN",
        })
        void reasonCodes(String code, TriggerSource expected) {
            var frame = (UplinkFrame.Status) Rs485Codec.decodeUplink("FD 00 01 09 " + code + " 00 FD FA");
            assertThat(frame.trigger()).isEqualTo(expected);
            assertThat(frame.reasonCode()).isEqualTo(Integer.parseInt(code, 16));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(
                value = {"00, false", "11, true", "22, null", "33, null"},
                nullValues = "null")
        void carCodes(String code, Boolean expected) {
            var frame = (UplinkFrame.Status) Rs485Codec.decodeUplink("FD 00 01 0C 16 " + code + " FD FA");
            assertThat(frame.occupied()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "状态 {0} 原因 {1} → {2}")
        @CsvSource(
                value = {
                    "E3, 16, MOTOR_FAULT",
                    "E3, 11, MOTOR_FAULT",
                    "00, 11, OBSTRUCTED",
                    "00, 12, INFRARED",
                    "09, 16, null",
                    "00, 01, null",
                },
                nullValues = "null")
        void faults(String status, String reason, GateFault expected) {
            var frame = (UplinkFrame.Status) Rs485Codec.decodeUplink("FD 00 01 " + status + " " + reason + " 00 FD FA");
            assertThat(frame.fault()).isEqualTo(Optional.ofNullable(expected));
        }

        @Test
        void settledAndMoving() {
            assertThat(GateStatus.OPENED.isSettled()).isTrue();
            assertThat(GateStatus.CLOSED.isSettled()).isTrue();
            assertThat(GateStatus.OPENING.isMoving()).isTrue();
            assertThat(GateStatus.CLOSING.isMoving()).isTrue();
            assertThat(GateStatus.STOPPED.isSettled() || GateStatus.STOPPED.isMoving())
                    .isFalse();
        }
    }

    @Nested
    class Structure {

        @ParameterizedTest
        @ValueSource(strings = {"", "FD 00 01 FD", "FD 00 01 FD FA FD"})
        void uplinkTooShortOrBadTail(String hex) {
            assertThatThrownBy(() -> Rs485Codec.decodeUplink(hex)).isInstanceOf(ProtocolException.class);
        }

        @Test
        void stickyFramesAreRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeUplink("FD 00 01 09 16 11 FD FA FD 00 01 0C 16 11 FD FA"))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("粘包");
        }

        @Test
        void badHeadIsRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeUplink("FE 00 01 09 16 11 FD FA"))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("帧头");
        }

        @Test
        void badTailIsRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeUplink("FD 00 01 09 16 11 FD FB"))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("帧尾");
        }

        @Test
        void nullFrameIsRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeUplink((byte[]) null)).isInstanceOf(ProtocolException.class);
        }

        @Test
        void downlinkWithoutCommandIsRejected() {
            assertThatThrownBy(() -> Rs485Codec.decodeDownlink("FD 00 01 FD FA"))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("长度不足");
        }

        @Test
        void uplinkWithoutDataIsUnknown() {
            assertThat(Rs485Codec.decodeUplink("FD 00 01 FD FA"))
                    .isEqualTo(new UplinkFrame.Unknown(1, "FD 00 01 FD FA"));
        }

        @Test
        void twoByteUplinkOtherThanVersionIsUnknown() {
            assertThat(Rs485Codec.decodeUplink("FD 00 01 AB 16 FD FA")).isInstanceOf(UplinkFrame.Unknown.class);
        }

        @Test
        void unknownDownlinkCommandDoesNotThrow() {
            DownlinkFrame frame = Rs485Codec.decodeDownlink("FD 00 01 AA FD FA");
            assertThat(frame.command()).isEmpty();
            assertThat(frame.commandCode()).isEqualTo(0xAA);
        }

        @Test
        void unknownEchoCommandDoesNotThrow() {
            var frame = (UplinkFrame.Echo) Rs485Codec.decodeUplink("FD 00 01 AA FD FA");
            assertThat(frame.command()).isEmpty();
        }

        @Test
        void addressIsPreserved() {
            assertThat(Rs485Codec.decodeUplink(Rs485Codec.encodeStatus(0x07, 0x0C, 0x16, 0x00))
                            .address())
                    .isEqualTo(7);
            assertThat(Hex.format(Rs485Codec.encode(GateCommand.OPEN, 0x07))).isEqualTo("FD 00 07 03 FD FA");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 256})
        void addressOutOfRange(int address) {
            assertThatThrownBy(() -> Rs485Codec.encode(GateCommand.OPEN, address))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class UplinkEncoding {

        @Test
        void statusRoundTrip() {
            byte[] bytes = Rs485Codec.encodeStatus(1, 0x0C, 0x04, 0x00);
            assertThat(Hex.format(bytes)).isEqualTo("FD 00 01 0C 04 00 FD FA");
            var frame = (UplinkFrame.Status) Rs485Codec.decodeUplink(bytes);
            assertThat(frame.status()).isEqualTo(GateStatus.CLOSED);
            assertThat(frame.trigger()).isEqualTo(TriggerSource.GROUND_SENSOR);
            assertThat(frame.occupied()).isFalse();
        }

        @Test
        void versionAndEchoRoundTrip() {
            assertThat(Hex.format(Rs485Codec.encodeVersion(1, 0x16))).isEqualTo("FD 00 01 CF 16 FD FA");
            assertThat(Hex.format(Rs485Codec.encodeEcho(1, GateCommand.OPEN.code())))
                    .isEqualTo("FD 00 01 03 FD FA");
        }
    }

    @Nested
    class HexText {

        @Test
        void parsesCompactLowercaseAndDelimited() {
            assertThat(Hex.parse("fd0001091611fdfa")).isEqualTo(Hex.parse("FD 00 01 09 16 11 FD FA"));
            assertThat(Hex.parse("FD:00:01-09 16 11 FD FA")).isEqualTo(Hex.parse("FD0001091611FDFA"));
        }

        @Test
        void formatsAsContractRawFrame() {
            assertThat(Hex.format(new byte[] {(byte) 0xFD, 0x00, (byte) 0xFA})).isEqualTo("FD 00 FA");
        }

        @ParameterizedTest
        @ValueSource(strings = {"FD 0", "ZZ"})
        void rejectsMalformedText(String text) {
            assertThatThrownBy(() -> Hex.parse(text)).isInstanceOf(ProtocolException.class);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> Hex.parse(null)).isInstanceOf(ProtocolException.class);
        }
    }
}
