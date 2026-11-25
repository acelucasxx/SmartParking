package com.smartparking.iot.command;

/** 指令状态，取值与接口契约 {@code CommandStatus}、{@code control_command.status} 一致（PRD 8.4）。 */
public enum CommandStatus {
    /** 已落库，尚未发布下行 */
    PENDING,
    /** 已发布下行帧，尚未看到闸杆动作 */
    SENT,
    /** 查询到“开闸中 / 关闸中” */
    EXECUTING,
    SUCCESS,
    FAILED,
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMEOUT;
    }
}
