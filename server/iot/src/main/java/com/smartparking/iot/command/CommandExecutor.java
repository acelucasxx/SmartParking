package com.smartparking.iot.command;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import com.smartparking.iot.codec.GateCommand;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.command.CommandJudge.Baseline;
import com.smartparking.iot.mqtt.DeviceLinkUnavailableException;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.mqtt.DownlinkSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 执行中的指令（PRD 8.4）：发布下行帧后每 300 ms 查询一次状态，按 {@link CommandJudge} 推进，超时未到位判 TIMEOUT。
 *
 * <p>只跟踪本实例发起的指令。服务重启后内存中的跟踪丢失，未终结指令由对账（IOT-08）按超时补齐。
 */
@Component
public class CommandExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);
    /** 执行中的查询间隔（PRD 8.4③） */
    static final Duration POLL_INTERVAL = Duration.ofMillis(300);

    private final CommandRepository repository;
    private final DownlinkSender sender;
    private final SystemSettings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "command-poll");
        t.setDaemon(true);
        return t;
    });
    /** 按设备：同一设备同时最多一条未终结指令 */
    private final Map<Long, Active> active = new ConcurrentHashMap<>();

    public CommandExecutor(
            CommandRepository repository,
            DownlinkSender sender,
            SystemSettings settings,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.sender = sender;
        this.settings = settings;
        this.events = events;
    }

    private final class Active {
        final long commandId;
        final RegisteredDevice device;
        final CommandAction action;
        final Baseline baseline;
        final Instant deadline;
        ScheduledFuture<?> poll;
        boolean executing;
        boolean done;

        Active(long commandId, RegisteredDevice device, CommandAction action, Baseline baseline, Instant deadline) {
            this.commandId = commandId;
            this.device = device;
            this.action = action;
            this.baseline = baseline;
            this.deadline = deadline;
        }
    }

    /** 指令超时（系统设置 {@code command.timeout_seconds}，默认 15 秒） */
    Duration timeout() {
        return Duration.ofSeconds(Math.max(1, settings.getInt(SystemSettings.Keys.COMMAND_TIMEOUT_SECONDS, 15)));
    }

    /**
     * 发布下行帧并开始跟踪。先登记再发布，避免回包早于登记到达。
     *
     * @throws DeviceLinkUnavailableException 发布失败；指令已置为 FAILED / OFFLINE
     */
    void launch(long commandId, RegisteredDevice device, CommandAction action, Baseline baseline, Instant issuedAt) {
        var a = new Active(commandId, device, action, baseline, issuedAt.plus(timeout()));
        active.put(device.deviceId(), a);
        try {
            sender.send(device.imei(), Rs485Codec.encode(action.command(), device.rs485Address()));
        } catch (DeviceLinkUnavailableException e) {
            finish(a, CommandStatus.FAILED, CommandResult.OFFLINE, null);
            throw e;
        }
        repository.markSent(commandId);
        synchronized (a) {
            if (!a.done) {
                long ms = POLL_INTERVAL.toMillis();
                a.poll = scheduler.scheduleAtFixedRate(() -> tick(a), ms, ms, TimeUnit.MILLISECONDS);
            }
        }
        log.info("指令 {} 已下发：设备 {} {}", commandId, device.imei(), action);
    }

    /**
     * 接管一条已下发但本实例未跟踪的指令（服务重启后恢复），按原截止时刻继续轮询。不知道下发前的状态，基线为空。
     *
     * @return 是否开始跟踪；该设备已有跟踪中的指令时返回 false
     */
    boolean resume(long commandId, RegisteredDevice device, CommandAction action, Instant issuedAt) {
        var a = new Active(commandId, device, action, Baseline.NONE, issuedAt.plus(timeout()));
        if (active.putIfAbsent(device.deviceId(), a) != null) {
            return false;
        }
        synchronized (a) {
            long ms = POLL_INTERVAL.toMillis();
            a.poll = scheduler.scheduleAtFixedRate(() -> tick(a), 0, ms, TimeUnit.MILLISECONDS);
        }
        log.info("恢复跟踪指令 {}：设备 {} {}", commandId, device.imei(), action);
        return true;
    }

    /** 本实例是否正在跟踪该指令 */
    public boolean isTracking(long commandId) {
        return active.values().stream().anyMatch(a -> a.commandId == commandId);
    }

    /** 当前跟踪中的指令数，便于监控与测试 */
    public int activeCount() {
        return active.size();
    }

    @EventListener
    public void onUplink(DeviceUplinkEvent event) {
        Active a = active.get(event.deviceId());
        if (a == null || !(event.frame() instanceof UplinkFrame.Status status)) {
            return; // 回显帧只说明命令到了串口，不参与判定
        }
        synchronized (a) {
            if (a.done) {
                return;
            }
            var verdict = CommandJudge.judge(a.action, a.baseline, status);
            switch (verdict.kind()) {
                case SUCCESS -> finish(a, CommandStatus.SUCCESS, null, status);
                case FAILED -> finish(a, CommandStatus.FAILED, verdict.result(), status);
                case EXECUTING -> {
                    if (!a.executing) {
                        a.executing = true;
                        repository.markExecuting(a.commandId);
                    }
                }
                case IGNORE -> {}
            }
        }
    }

    private void tick(Active a) {
        try {
            synchronized (a) {
                if (a.done) {
                    return;
                }
                if (!clock.instant().isBefore(a.deadline)) {
                    finish(a, CommandStatus.TIMEOUT, CommandResult.TIMEOUT, null);
                    return;
                }
            }
            sender.send(a.device.imei(), Rs485Codec.encode(GateCommand.QUERY_STATUS, a.device.rs485Address()));
        } catch (DeviceLinkUnavailableException e) {
            log.debug("指令 {} 查询下发失败，等待下一轮：{}", a.commandId, e.getMessage());
        } catch (RuntimeException e) {
            // 定时任务抛异常会终止该任务的后续执行
            log.error("指令 {} 轮询出错", a.commandId, e);
        }
    }

    private void finish(Active a, CommandStatus status, CommandResult result, UplinkFrame.Status frame) {
        synchronized (a) {
            if (a.done) {
                return;
            }
            a.done = true;
            if (a.poll != null) {
                a.poll.cancel(false);
            }
        }
        active.remove(a.device.deviceId(), a);
        Instant now = clock.instant();
        byte[] raw = frame == null ? null : Hex.parse(frame.rawFrame());
        if (repository.finish(a.commandId, status, result, raw, now)) {
            log.info("指令 {} {}{}", a.commandId, status, result == null ? "" : "（" + result + "）");
            events.publishEvent(
                    new CommandFinishedEvent(a.commandId, a.device.deviceId(), a.action, status, result, now));
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
