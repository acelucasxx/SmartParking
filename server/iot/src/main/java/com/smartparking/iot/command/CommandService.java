package com.smartparking.iot.command;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.command.CommandJudge.Baseline;
import com.smartparking.iot.mqtt.DeviceLinkUnavailableException;
import com.smartparking.iot.mqtt.DownlinkSender;
import com.smartparking.iot.runtime.DeviceRuntime;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.OnlinePolicy;
import com.smartparking.iot.runtime.OnlineState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 远程抬杆 / 落杆（PRD 5.3、8.4）。只负责“对设备 X 执行动作 Y”，发起人与权限由调用方（BIZ-05）校验后传入。
 *
 * <p>提交顺序：
 *
 * <ol>
 *   <li>同一幂等键已有指令 → 原样返回（连点、重试只产生一条）。
 *   <li>设备已有未终结指令 → {@code COMMAND_IN_PROGRESS}。
 *   <li>在线判定（PRD 8.5）：离线 → {@code DEVICE_OFFLINE}，休眠 → {@code DEVICE_SLEEPING}；不排队下发。
 *   <li>缓存状态超过 30 秒未由状态帧刷新 → 先同步查询一次，以查询结果为准。
 *   <li>电机故障 → {@code DEVICE_FAULT}；已在目标状态 → 不下发，直接记一条 SUCCESS / ALREADY_IN_TARGET。
 *   <li>落库 PENDING → 发布下行 → SENT，由 {@link CommandExecutor} 跟踪到终态。
 * </ol>
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);
    /** 缓存状态的新鲜度窗口（PRD 5.3②） */
    static final Duration FRESHNESS = Duration.ofSeconds(30);
    /** 先查询时等待回包的时长 */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final DeviceDirectory directory;
    private final CommandRepository repository;
    private final CommandExecutor executor;
    private final StatusProbe probe;
    private final DeviceRuntimeService runtime;
    private final DownlinkSender sender;
    private final SystemSettings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public CommandService(
            DeviceDirectory directory,
            CommandRepository repository,
            CommandExecutor executor,
            StatusProbe probe,
            DeviceRuntimeService runtime,
            DownlinkSender sender,
            SystemSettings settings,
            ApplicationEventPublisher events) {
        this.directory = directory;
        this.repository = repository;
        this.executor = executor;
        this.probe = probe;
        this.runtime = runtime;
        this.sender = sender;
        this.settings = settings;
        this.events = events;
    }

    /**
     * @param idempotencyKey 客户端每次点击生成，重试复用；8–64 个字符
     * @throws BizException DEVICE_NOT_FOUND、COMMAND_IN_PROGRESS、DEVICE_OFFLINE、DEVICE_SLEEPING、DEVICE_FAULT、BAD_REQUEST
     */
    public CommandView submit(long deviceId, CommandAction action, String idempotencyKey, Initiator initiator) {
        if (action == null || idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "action 与 8–64 位 idempotencyKey 必填");
        }
        Optional<CommandRepository.Row> existing = repository.findByIdempotencyKey(deviceId, idempotencyKey);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        RegisteredDevice device =
                directory.findById(deviceId).orElseThrow(() -> new BizException(ErrorCode.DEVICE_NOT_FOUND));
        if (repository.findInflight(deviceId).isPresent()) {
            throw new BizException(ErrorCode.COMMAND_IN_PROGRESS);
        }

        Instant now = clock.instant();
        DeviceRuntime state = runtime.find(deviceId).orElse(null);
        OnlineState online = OnlinePolicy.evaluate(state, now, sleepAfter(), offlineAfter());
        if (online == OnlineState.OFFLINE || !sender.isConnected()) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE);
        }
        if (online == OnlineState.SLEEP) {
            throw new BizException(ErrorCode.DEVICE_SLEEPING);
        }

        Baseline baseline = new Baseline(state.statusCode(), state.lastReason());
        GateStatus current = state.gateStatus();
        boolean fresh = state.statusRefreshedAt() != null
                && Duration.between(state.statusRefreshedAt(), now).compareTo(FRESHNESS) <= 0;
        if (!fresh) {
            Optional<UplinkFrame.Status> probed =
                    probe.query(deviceId, device.imei(), device.rs485Address(), PROBE_TIMEOUT);
            if (probed.isPresent()) {
                baseline = new Baseline(probed.get().statusCode(), probed.get().reasonCode());
                current = probed.get().status();
            } else {
                log.info("设备 {} 缓存状态已过期且查询无回包，直接下发 {}", device.imei(), action);
                current = GateStatus.UNKNOWN;
            }
        }
        if (current == GateStatus.FAULT) {
            throw new BizException(ErrorCode.DEVICE_FAULT);
        }

        Instant issuedAt = clock.instant();
        if (current == action.target()) {
            Inserted done = insert(
                    device,
                    action,
                    idempotencyKey,
                    initiator,
                    null,
                    CommandStatus.SUCCESS,
                    CommandResult.ALREADY_IN_TARGET,
                    issuedAt);
            if (!done.duplicate()) {
                events.publishEvent(new CommandFinishedEvent(
                        done.id(), deviceId, action, CommandStatus.SUCCESS, CommandResult.ALREADY_IN_TARGET, issuedAt));
            }
            return get(deviceId, done.id());
        }

        byte[] downFrame = Rs485Codec.encode(action.command(), device.rs485Address());
        Inserted pending =
                insert(device, action, idempotencyKey, initiator, downFrame, CommandStatus.PENDING, null, issuedAt);
        if (pending.duplicate()) {
            return get(deviceId, pending.id());
        }
        long id = pending.id();
        try {
            executor.launch(id, device, action, baseline, issuedAt);
        } catch (DeviceLinkUnavailableException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE);
        }
        return get(deviceId, id);
    }

    /** 查询指令；不属于该设备时视为不存在 */
    public CommandView get(long deviceId, long commandId) {
        return repository
                .find(commandId)
                .filter(row -> row.deviceId() == deviceId)
                .map(this::view)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "指令不存在"));
    }

    /** @param duplicate 同一幂等键已被并发请求写入，{@code id} 为那条指令 */
    private record Inserted(long id, boolean duplicate) {}

    private Inserted insert(
            RegisteredDevice device,
            CommandAction action,
            String key,
            Initiator initiator,
            byte[] downFrame,
            CommandStatus status,
            CommandResult result,
            Instant issuedAt) {
        try {
            return new Inserted(
                    repository.insert(device.deviceId(), action, initiator, key, downFrame, status, result, issuedAt),
                    false);
        } catch (DuplicateKeyException e) {
            // 并发提交：同一幂等键已被另一请求写入，或另一条指令抢先成为未终结指令
            Optional<CommandRepository.Row> same = repository.findByIdempotencyKey(device.deviceId(), key);
            if (same.isPresent()) {
                return new Inserted(same.get().id(), true);
            }
            throw new BizException(ErrorCode.COMMAND_IN_PROGRESS);
        }
    }

    private CommandView view(CommandRepository.Row row) {
        GateStatus gate = row.status().isTerminal()
                ? null
                : runtime.find(row.deviceId()).map(DeviceRuntime::gateStatus).orElse(GateStatus.UNKNOWN);
        return new CommandView(
                row.id(),
                row.deviceId(),
                row.action(),
                row.channel(),
                row.status(),
                gate,
                row.result(),
                row.issuedAt(),
                row.finishedAt(),
                row.durationMs());
    }

    private Duration sleepAfter() {
        return Duration.ofMinutes(settings.getInt(SystemSettings.Keys.ONLINE_SLEEP_MINUTES, 10));
    }

    private Duration offlineAfter() {
        return Duration.ofHours(settings.getInt(SystemSettings.Keys.ONLINE_OFFLINE_HOURS, 24));
    }
}
