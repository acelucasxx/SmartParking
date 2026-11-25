package com.smartparking.iot.command;

import com.smartparking.iot.codec.GateStatus;
import java.time.Instant;

/**
 * 一条指令的当前状态，字段对应接口契约 {@code Command}（{@code resultMessage} 由接口层按结果码生成）。
 *
 * @param gateStatus 未终结时为设备当前闸杆状态（OPENING / CLOSING 时播放动画），终结后为空
 */
public record CommandView(
        long id,
        long deviceId,
        CommandAction action,
        String channel,
        CommandStatus status,
        GateStatus gateStatus,
        CommandResult resultCode,
        Instant issuedAt,
        Instant finishedAt,
        Integer durationMs) {}
