package com.smartparking.dtusim;

import com.smartparking.iot.codec.DownlinkFrame;
import com.smartparking.iot.codec.GateCommand;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Rs485Codec;
import java.util.function.Consumer;

/**
 * 道闸板行为模拟：接收下行命令，按协议回包并推进闸杆状态。
 *
 * <p>默认行为按“闸板不主动上报”建模（PRD 12.1 第 10 项未确认）：
 *
 * <ul>
 *   <li>查询 → 回当前状态帧（状态 + 最近一次动作原因 + 车位）。
 *   <li>抬杆 / 落杆 → 先回 6 字节回显帧，状态变为“开闸中 / 关闸中”，经过动作耗时后到位；结果只能靠云端查询发现。
 *   <li>已在目标状态 → 只回显，状态不变。
 *   <li>查版本 → 回版本帧。
 * </ul>
 *
 * <p>开启 {@link #setActiveReport 主动上报} 后，每次状态变化都会推送一帧状态帧，用于验证硬件确认主动上报后的路径。
 */
public final class GateBoard {

    /** 平台指令 */
    static final int REASON_APP = 0x16;

    static final int REASON_OBSTRUCTED = 0x11;
    static final int REASON_INFRARED = 0x12;
    private static final int CAR_NONE = 0x00;
    private static final int CAR_PRESENT = 0x11;

    private final int address;
    private final Scheduler scheduler;
    private final Consumer<byte[]> uplink;

    private int status = GateStatus.CLOSED.code();
    private int reason = 0x15; // 上电找零
    private boolean occupied;
    private int version = 0x16;

    private long travelMs;
    private boolean echo = true;
    private boolean silent;
    private boolean activeReport;
    private Fault pendingFault;
    private Scheduler.Cancellable motion;
    /** 每次开始或取消动作时递增；取消与到期并发时，过期的定时任务据此作废 */
    private long generation;

    /**
     * @param uplink 上行帧出口，由 DTU 层发布到 MQTT
     */
    public GateBoard(int address, long travelMs, Scheduler scheduler, Consumer<byte[]> uplink) {
        this.address = address;
        this.travelMs = travelMs;
        this.scheduler = scheduler;
        this.uplink = uplink;
    }

    /** 处理一条下行命令 */
    public synchronized void handle(DownlinkFrame frame) {
        if (silent || frame.address() != address || frame.command().isEmpty()) {
            return;
        }
        switch (frame.command().get()) {
            case QUERY_STATUS -> sendStatus();
            case VERSION -> uplink.accept(Rs485Codec.encodeVersion(address, version));
            case OPEN -> {
                if (echo) {
                    uplink.accept(Rs485Codec.encodeEcho(address, GateCommand.OPEN.code()));
                }
                move(GateStatus.OPENED, REASON_APP);
            }
            case CLOSE -> {
                if (echo) {
                    uplink.accept(Rs485Codec.encodeEcho(address, GateCommand.CLOSE.code()));
                }
                move(GateStatus.CLOSED, REASON_APP);
            }
        }
    }

    /**
     * 模拟非平台触发的动作，如遥控器（01 / 02）、地感（03 / 04）、外部开关（06 / 07）、延时自动关（10）。
     */
    public synchronized void trigger(GateStatus target, int reasonCode) {
        if (!target.isSettled()) {
            throw new IllegalArgumentException("目标只能是 OPENED 或 CLOSED：" + target);
        }
        move(target, reasonCode);
    }

    private void move(GateStatus target, int reasonCode) {
        if (status == GateStatus.FAULT.code()) {
            return; // 电机故障时不动作，等待 repair
        }
        Fault fault = pendingFault;
        pendingFault = null;
        if (fault == Fault.MOTOR_FAULT) {
            cancelMotion();
            change(GateStatus.FAULT.code(), reasonCode);
            return;
        }
        if (fault == null && status == target.code()) {
            return;
        }
        cancelMotion();
        GateStatus moving = target == GateStatus.OPENED ? GateStatus.OPENING : GateStatus.CLOSING;
        change(moving.code(), reasonCode);
        if (fault == Fault.STALL) {
            return;
        }
        if (fault == Fault.OBSTRUCTED || fault == Fault.INFRARED) {
            int stopReason = fault == Fault.OBSTRUCTED ? REASON_OBSTRUCTED : REASON_INFRARED;
            long gen = generation;
            motion = scheduler.schedule(() -> settle(gen, GateStatus.STOPPED.code(), stopReason), travelMs / 2);
            return;
        }
        long gen = generation;
        motion = scheduler.schedule(() -> settle(gen, target.code(), reasonCode), travelMs);
    }

    private synchronized void settle(long gen, int statusCode, int reasonCode) {
        if (gen != generation) {
            return;
        }
        motion = null;
        change(statusCode, reasonCode);
    }

    private void change(int statusCode, int reasonCode) {
        status = statusCode;
        reason = reasonCode;
        if (activeReport) {
            sendStatus();
        }
    }

    private void cancelMotion() {
        generation++;
        if (motion != null) {
            motion.cancel();
            motion = null;
        }
    }

    private void sendStatus() {
        uplink.accept(Rs485Codec.encodeStatus(address, status, reason, occupied ? CAR_PRESENT : CAR_NONE));
    }

    /* ---------------- 故障注入与参数 ---------------- */

    /** 下一次动作注入异常 */
    public synchronized void inject(Fault fault) {
        this.pendingFault = fault;
    }

    /** 清除电机故障与未触发的异常，闸杆回到关闸到位 */
    public synchronized void repair() {
        pendingFault = null;
        cancelMotion();
        if (status == GateStatus.FAULT.code()) {
            change(GateStatus.CLOSED.code(), 0x15);
        }
    }

    public synchronized void setTravelMs(long travelMs) {
        this.travelMs = travelMs;
    }

    /** 关闭后抬杆 / 落杆不回显 */
    public synchronized void setEcho(boolean echo) {
        this.echo = echo;
    }

    /** 开启后不回任何帧，模拟闸板无响应或 RS485 接线故障 */
    public synchronized void setSilent(boolean silent) {
        this.silent = silent;
    }

    public synchronized void setActiveReport(boolean activeReport) {
        this.activeReport = activeReport;
    }

    public synchronized void setOccupied(boolean occupied) {
        this.occupied = occupied;
    }

    public synchronized void setVersion(int version) {
        this.version = version;
    }

    public synchronized GateStatus status() {
        return GateStatus.of(status);
    }

    public synchronized String describe() {
        return String.format(
                "%s 原因 %02X %s 耗时 %dms%s%s%s%s",
                GateStatus.of(status),
                reason,
                occupied ? "有车" : "无车",
                travelMs,
                echo ? "" : " 无回显",
                silent ? " 静默" : "",
                activeReport ? " 主动上报" : "",
                pendingFault == null ? "" : " 待触发 " + pendingFault);
    }
}
