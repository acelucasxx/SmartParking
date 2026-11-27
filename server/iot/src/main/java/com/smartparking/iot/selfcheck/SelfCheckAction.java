package com.smartparking.iot.selfcheck;

import com.smartparking.iot.codec.GateCommand;

/** 后台自检动作（PRD 6.2）：只下发查询类命令，不动闸杆。取值与 {@code control_command.action} 一致 */
public enum SelfCheckAction {
    /** {@code D0} 查询状态，收到状态帧即成功 */
    QUERY_STATUS(GateCommand.QUERY_STATUS),
    /** {@code CF} 查询闸板版本，收到版本帧即成功 */
    QUERY_VERSION(GateCommand.VERSION);

    private final GateCommand command;

    SelfCheckAction(GateCommand command) {
        this.command = command;
    }

    public GateCommand command() {
        return command;
    }
}
