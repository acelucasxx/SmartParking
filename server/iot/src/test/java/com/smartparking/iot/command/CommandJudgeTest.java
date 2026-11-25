package com.smartparking.iot.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.command.CommandJudge.Baseline;
import com.smartparking.iot.command.CommandJudge.Verdict.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommandJudgeTest {

    private static UplinkFrame.Status frame(int status, int reason) {
        return (UplinkFrame.Status) Rs485Codec.decodeUplink(Rs485Codec.encodeStatus(1, status, reason, 0));
    }

    /** 下发前：关闸到位、上电找零 */
    private static final Baseline CLOSED = new Baseline(0x0C, 0x15);

    @ParameterizedTest(name = "{0} 收到 {1}/{2} → {3} {4}")
    @CsvSource(
            nullValues = "-",
            value = {
                // 到位才算成功
                "OPEN,  09, 16, SUCCESS,   -",
                "CLOSE, 0C, 16, SUCCESS,   -",
                // 与下发前相同：闸板还没动，忽略
                "OPEN,  0C, 15, IGNORE,    -",
                // 动作中
                "OPEN,  19, 16, EXECUTING, -",
                "CLOSE, 1C, 16, EXECUTING, -",
                // 失败
                "OPEN,  E3, 16, FAILED,    MOTOR_FAULT",
                "CLOSE, 00, 11, FAILED,    OBSTRUCTED",
                "CLOSE, 09, 11, FAILED,    OBSTRUCTED",
                "CLOSE, 00, 12, FAILED,    INFRARED",
                "OPEN,  00, 01, FAILED,    INTERRUPTED",
                // 反方向、未知：等待
                "OPEN,  1C, 02, IGNORE,    -",
                "OPEN,  7F, 16, IGNORE,    -",
            })
    void judges(CommandAction action, String status, String reason, Kind kind, CommandResult result) {
        var verdict =
                CommandJudge.judge(action, CLOSED, frame(Integer.parseInt(status, 16), Integer.parseInt(reason, 16)));
        assertThat(verdict.kind()).isEqualTo(kind);
        assertThat(verdict.result()).isEqualTo(result);
    }

    @ParameterizedTest
    @CsvSource({"00, 01", "0C, 16"})
    void withoutBaselineEveryFrameCounts(String status, String reason) {
        var verdict = CommandJudge.judge(
                CommandAction.OPEN, Baseline.NONE, frame(Integer.parseInt(status, 16), Integer.parseInt(reason, 16)));
        assertThat(verdict.kind()).isIn(Kind.FAILED, Kind.IGNORE);
    }

    @ParameterizedTest
    @CsvSource({"00, 01"})
    void stoppedBaselineIsNotMistakenForNewInterruption(String status, String reason) {
        // 下发前闸杆停在半途：同一帧再次出现不能判 INTERRUPTED
        var stopped = new Baseline(0x00, 0x01);
        var verdict = CommandJudge.judge(
                CommandAction.OPEN, stopped, frame(Integer.parseInt(status, 16), Integer.parseInt(reason, 16)));
        assertThat(verdict.kind()).isEqualTo(Kind.IGNORE);
    }
}
