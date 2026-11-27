package com.smartparking.control.command;

import com.smartparking.iot.command.CommandResult;
import com.smartparking.iot.command.CommandStatus;

/** 契约 {@code Command.resultMessage}：失败或超时时可直接展示的说明；成功与未终结时为空 */
final class ResultMessages {

    private ResultMessages() {}

    static String of(CommandStatus status, CommandResult result) {
        if (status == CommandStatus.SUCCESS || !status.isTerminal()) {
            return null;
        }
        if (result == null) {
            return status == CommandStatus.TIMEOUT ? timeout() : "操作失败，请重试";
        }
        return switch (result) {
            case ALREADY_IN_TARGET -> null;
            case MOTOR_FAULT -> "设备电机故障，请联系客服";
            case OBSTRUCTED -> "闸杆遇阻，请检查现场";
            case INFRARED -> "检测到障碍物，闸杆已停止";
            case INTERRUPTED -> "闸杆中途停止，请重试";
            case TIMEOUT -> timeout();
            case OFFLINE -> "设备连接中断，指令未送达";
        };
    }

    private static String timeout() {
        return "设备长时间未到位，请查看现场";
    }
}
