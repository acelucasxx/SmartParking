package com.smartparking.control.command;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.command.CommandAction;
import com.smartparking.iot.command.CommandResult;
import com.smartparking.iot.command.CommandService;
import com.smartparking.iot.command.CommandStatus;
import com.smartparking.iot.command.CommandView;
import com.smartparking.iot.command.Initiator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 远程抬杆 / 落杆（PRD 5.3，契约 control 分组）。
 *
 * <p>权限（主人或有效期内被授权人、设备启用、用户未封禁）由 {@link DeviceAccess} 判定；在线、串行、幂等、已在目标状态、故障由 iot 的
 * {@link CommandService} 判定。发起人与角色写入 {@code control_command}。
 */
@RestController
@RequestMapping("/api/app/devices/{deviceId}/commands")
public class CommandController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CommandService commands;
    private final CommandVisibility visibility;

    public CommandController(CommandService commands, CommandVisibility visibility) {
        this.commands = commands;
        this.visibility = visibility;
    }

    public record SendRequest(
            @NotNull CommandAction action, @NotBlank @Size(min = 8, max = 64) String idempotencyKey) {}

    /** 契约 {@code Command} */
    public record Command(
            long id,
            long deviceId,
            CommandAction action,
            String channel,
            CommandStatus status,
            GateStatus gateStatus,
            CommandResult resultCode,
            String resultMessage,
            OffsetDateTime issuedAt,
            OffsetDateTime finishedAt,
            Integer durationMs) {

        static Command of(CommandView v) {
            return new Command(
                    v.id(),
                    v.deviceId(),
                    v.action(),
                    v.channel(),
                    v.status(),
                    v.gateStatus(),
                    v.resultCode(),
                    ResultMessages.of(v.status(), v.resultCode()),
                    time(v.issuedAt()),
                    time(v.finishedAt()),
                    v.durationMs());
        }
    }

    @PostMapping
    @DeviceAccess(AccessLevel.CONTROL)
    public ApiResponse<Command> send(
            @PathVariable long deviceId, DeviceGrant grant, @Valid @RequestBody SendRequest request) {
        CommandView view = commands.submit(
                deviceId,
                request.action(),
                request.idempotencyKey(),
                Initiator.user(grant.userId(), grant.role().name()));
        // 幂等键命中了别人发起的指令：不返回他人的操作
        if (!visibility.visible(grant, view.id())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "idempotencyKey 已被使用，请重新生成");
        }
        return ApiResponse.ok(Command.of(view));
    }

    @GetMapping("/{commandId}")
    @DeviceAccess(AccessLevel.VIEW)
    public ApiResponse<Command> get(@PathVariable long deviceId, @PathVariable long commandId, DeviceGrant grant) {
        if (!visibility.visible(grant, commandId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "指令不存在");
        }
        return ApiResponse.ok(Command.of(commands.get(deviceId, commandId)));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atZone(ZONE).toOffsetDateTime();
    }
}
