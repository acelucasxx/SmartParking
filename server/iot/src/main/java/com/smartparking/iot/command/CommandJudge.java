package com.smartparking.iot.command;

import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.TriggerSource;
import com.smartparking.iot.codec.UplinkFrame;

/**
 * 依据状态帧判定指令进展（PRD 5.3⑤、8.4），纯函数。
 *
 * <p>下发后的头几次查询可能还是下发前的状态（闸板尚未动作），所以与下发前的基线帧（状态码 + 原因码）相同的帧一律忽略，
 * 只认新出现的状态。判定顺序：
 *
 * <ol>
 *   <li>到位（开闸到位 / 关闸到位）→ 成功
 *   <li>与基线相同 → 忽略
 *   <li>电机故障 E3 → 失败 MOTOR_FAULT
 *   <li>原因码 11 遇阻 / 12 红外 → 失败 OBSTRUCTED / INFRARED
 *   <li>朝目标动作中 → 执行中
 *   <li>中间停止 00 → 失败 INTERRUPTED
 *   <li>其他（反方向动作、未知码）→ 忽略，等待超时
 * </ol>
 *
 * <p>回显帧不是状态帧，不参与判定：它只说明命令到了串口。
 */
public final class CommandJudge {

    private CommandJudge() {}

    /** 判定结果；{@code result} 仅在失败时有值 */
    public record Verdict(Kind kind, CommandResult result) {

        public enum Kind {
            SUCCESS,
            EXECUTING,
            FAILED,
            IGNORE
        }

        static final Verdict SUCCESS = new Verdict(Kind.SUCCESS, null);
        static final Verdict EXECUTING = new Verdict(Kind.EXECUTING, null);
        static final Verdict IGNORE = new Verdict(Kind.IGNORE, null);

        static Verdict failed(CommandResult result) {
            return new Verdict(Kind.FAILED, result);
        }
    }

    /** 下发前的状态，用于识别“还没变”的帧；从未收到状态帧时为空 */
    public record Baseline(Integer statusCode, Integer reasonCode) {

        static final Baseline NONE = new Baseline(null, null);

        boolean sameAs(UplinkFrame.Status frame) {
            return statusCode != null
                    && statusCode == frame.statusCode()
                    && reasonCode != null
                    && reasonCode == frame.reasonCode();
        }
    }

    public static Verdict judge(CommandAction action, Baseline baseline, UplinkFrame.Status frame) {
        if (frame.status() == action.target()) {
            return Verdict.SUCCESS;
        }
        if (baseline.sameAs(frame)) {
            return Verdict.IGNORE;
        }
        if (frame.status() == GateStatus.FAULT) {
            return Verdict.failed(CommandResult.MOTOR_FAULT);
        }
        if (frame.trigger() == TriggerSource.OBSTRUCTED) {
            return Verdict.failed(CommandResult.OBSTRUCTED);
        }
        if (frame.trigger() == TriggerSource.INFRARED) {
            return Verdict.failed(CommandResult.INFRARED);
        }
        if (frame.status() == action.moving()) {
            return Verdict.EXECUTING;
        }
        if (frame.status() == GateStatus.STOPPED) {
            return Verdict.failed(CommandResult.INTERRUPTED);
        }
        return Verdict.IGNORE;
    }
}
