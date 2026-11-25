package com.smartparking.iot.command;

import java.time.Instant;

/** 指令进入终态。告警（BIZ-07：连续 3 次超时）、操作记录、消息等订阅此事件。 */
public record CommandFinishedEvent(
        long commandId, long deviceId, CommandAction action, CommandStatus status, CommandResult result, Instant at) {}
