package com.smartparking.dtusim;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.TriggerSource;
import com.smartparking.iot.codec.UplinkFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GateBoardTest {

    private static final long TRAVEL = 1200;

    private final ManualScheduler clock = new ManualScheduler();
    private final List<String> sent = new ArrayList<>();
    private GateBoard board;

    @BeforeEach
    void setUp() {
        board = new GateBoard(1, TRAVEL, clock, bytes -> sent.add(Hex.format(bytes)));
    }

    private void send(byte[] downlink) {
        board.handle(Rs485Codec.decodeDownlink(downlink));
    }

    /** 发一次查询，返回状态帧 */
    private UplinkFrame.Status query() {
        sent.clear();
        send(Rs485Codec.encodeQuery());
        assertThat(sent).hasSize(1);
        return (UplinkFrame.Status) Rs485Codec.decodeUplink(sent.get(0));
    }

    @Test
    void startsClosedAfterPowerOn() {
        var status = query();
        assertThat(status.status()).isEqualTo(GateStatus.CLOSED);
        assertThat(status.trigger()).isEqualTo(TriggerSource.POWER_ON);
        assertThat(status.occupied()).isFalse();
    }

    @Test
    void openEchoesThenMovesThenSettles() {
        send(Rs485Codec.encodeOpen());
        assertThat(sent).containsExactly("FD 00 01 03 FD FA");

        assertThat(query().status()).isEqualTo(GateStatus.OPENING);
        clock.advance(TRAVEL - 1);
        assertThat(query().status()).isEqualTo(GateStatus.OPENING);
        clock.advance(1);
        var settled = query();
        assertThat(settled.status()).isEqualTo(GateStatus.OPENED);
        assertThat(settled.trigger()).isEqualTo(TriggerSource.APP);
    }

    @Test
    void closeEchoesAndSettles() {
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL);
        sent.clear();
        send(Rs485Codec.encodeClose());
        assertThat(sent).containsExactly("FD 00 01 05 FD FA");
        assertThat(query().status()).isEqualTo(GateStatus.CLOSING);
        clock.advance(TRAVEL);
        assertThat(query().status()).isEqualTo(GateStatus.CLOSED);
    }

    @Test
    void alreadyInTargetOnlyEchoes() {
        send(Rs485Codec.encodeClose());
        assertThat(sent).containsExactly("FD 00 01 05 FD FA");
        assertThat(clock.pending()).isZero();
        assertThat(query().status()).isEqualTo(GateStatus.CLOSED);
    }

    @Test
    void echoCanBeDisabled() {
        board.setEcho(false);
        send(Rs485Codec.encodeOpen());
        assertThat(sent).isEmpty();
        assertThat(query().status()).isEqualTo(GateStatus.OPENING);
    }

    @Test
    void travelTimeIsAdjustable() {
        board.setTravelMs(5000);
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL);
        assertThat(query().status()).isEqualTo(GateStatus.OPENING);
        clock.advance(5000 - TRAVEL);
        assertThat(query().status()).isEqualTo(GateStatus.OPENED);
    }

    @Test
    void reversingMidMoveCancelsPreviousMotion() {
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL / 2);
        send(Rs485Codec.encodeClose());
        clock.advance(TRAVEL / 2);
        assertThat(query().status()).isEqualTo(GateStatus.CLOSING);
        clock.advance(TRAVEL);
        assertThat(query().status()).isEqualTo(GateStatus.CLOSED);
        assertThat(clock.pending()).isZero();
    }

    @Test
    void motorFaultPersistsUntilRepair() {
        board.inject(Fault.MOTOR_FAULT);
        send(Rs485Codec.encodeOpen());
        var fault = query();
        assertThat(fault.status()).isEqualTo(GateStatus.FAULT);
        assertThat(fault.fault()).isPresent();

        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL);
        assertThat(query().status()).isEqualTo(GateStatus.FAULT);

        board.repair();
        assertThat(query().status()).isEqualTo(GateStatus.CLOSED);
    }

    @Test
    void obstructedStopsHalfway() {
        board.inject(Fault.OBSTRUCTED);
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL / 2);
        var stopped = query();
        assertThat(stopped.status()).isEqualTo(GateStatus.STOPPED);
        assertThat(stopped.trigger()).isEqualTo(TriggerSource.OBSTRUCTED);
    }

    @Test
    void infraredStopsHalfway() {
        board.inject(Fault.INFRARED);
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL / 2);
        var stopped = query();
        assertThat(stopped.status()).isEqualTo(GateStatus.STOPPED);
        assertThat(stopped.trigger()).isEqualTo(TriggerSource.INFRARED);
    }

    @Test
    void faultIsOneShot() {
        board.inject(Fault.OBSTRUCTED);
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL);
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL);
        assertThat(query().status()).isEqualTo(GateStatus.OPENED);
    }

    @Test
    void stallNeverSettles() {
        board.inject(Fault.STALL);
        send(Rs485Codec.encodeOpen());
        clock.advance(60_000);
        assertThat(query().status()).isEqualTo(GateStatus.OPENING);
        assertThat(clock.pending()).isZero();
    }

    @Test
    void silentBoardDoesNotReply() {
        board.setSilent(true);
        send(Rs485Codec.encodeQuery());
        send(Rs485Codec.encodeOpen());
        send(Rs485Codec.encodeVersionQuery());
        assertThat(sent).isEmpty();
    }

    @Test
    void activeReportPushesEveryChange() {
        board.setActiveReport(true);
        send(Rs485Codec.encodeOpen());
        clock.advance(TRAVEL);
        assertThat(sent).containsExactly("FD 00 01 03 FD FA", "FD 00 01 19 16 00 FD FA", "FD 00 01 09 16 00 FD FA");
    }

    @Test
    void withoutActiveReportNothingIsPushed() {
        send(Rs485Codec.encodeOpen());
        sent.clear();
        clock.advance(TRAVEL);
        assertThat(sent).isEmpty();
    }

    @Test
    void repliesVersion() {
        board.setVersion(0x21);
        send(Rs485Codec.encodeVersionQuery());
        assertThat(sent).containsExactly("FD 00 01 CF 21 FD FA");
    }

    @Test
    void externalTriggerCarriesItsReason() {
        board.trigger(GateStatus.OPENED, 0x01);
        clock.advance(TRAVEL);
        var status = query();
        assertThat(status.status()).isEqualTo(GateStatus.OPENED);
        assertThat(status.trigger()).isEqualTo(TriggerSource.REMOTE_CONTROL);
    }

    @Test
    void occupancyIsReported() {
        board.setOccupied(true);
        assertThat(query().occupied()).isTrue();
    }

    @Test
    void ignoresOtherAddressesAndUnknownCommands() {
        board.handle(Rs485Codec.decodeDownlink(Rs485Codec.encode(com.smartparking.iot.codec.GateCommand.OPEN, 2)));
        board.handle(Rs485Codec.decodeDownlink("FD 00 01 AA FD FA"));
        assertThat(sent).isEmpty();
    }
}
