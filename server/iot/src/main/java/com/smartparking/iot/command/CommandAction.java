package com.smartparking.iot.command;

import com.smartparking.iot.codec.GateCommand;
import com.smartparking.iot.codec.GateStatus;

/** 远程控制动作，取值与接口契约 {@code GateAction} 一致。 */
public enum CommandAction {
    /** 抬杆（放行） */
    OPEN(GateCommand.OPEN, GateStatus.OPENED, GateStatus.OPENING),
    /** 落杆（占位） */
    CLOSE(GateCommand.CLOSE, GateStatus.CLOSED, GateStatus.CLOSING);

    private final GateCommand command;
    private final GateStatus target;
    private final GateStatus moving;

    CommandAction(GateCommand command, GateStatus target, GateStatus moving) {
        this.command = command;
        this.target = target;
        this.moving = moving;
    }

    public GateCommand command() {
        return command;
    }

    /** 到位状态：收到它才算成功 */
    public GateStatus target() {
        return target;
    }

    /** 朝目标动作中的状态 */
    public GateStatus moving() {
        return moving;
    }
}
