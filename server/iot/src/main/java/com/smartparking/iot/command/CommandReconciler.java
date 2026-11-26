package com.smartparking.iot.command;

import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.command.CommandJudge.Baseline;
import com.smartparking.iot.command.CommandJudge.Verdict;
import com.smartparking.iot.runtime.DeviceRuntime;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指令对账（PRD 8.4⑤）与重启恢复（IOT-08）。
 *
 * <ul>
 *   <li><b>对账</b>：每 15 秒扫描超过“超时 + 5 秒宽限”仍未终结的指令——正常情况下执行器早已判定，留下来的说明执行器丢了
 *       （进程重启、发起实例宕机、轮询异常）。按设备最新运行态补齐：下发后刷新过的状态能判定成功或失败就用它，否则判 TIMEOUT。
 *   <li><b>恢复</b>：应用启动时，对仍在超时窗口内、本实例未跟踪的指令重新开始轮询，直到原截止时刻；已过期的直接对账。
 * </ul>
 *
 * <p>与执行器、其他实例的对账并发时，靠“仍未终结”条件更新保证只有一方生效。
 */
@Component
public class CommandReconciler {

    private static final Logger log = LoggerFactory.getLogger(CommandReconciler.class);
    /** 超时之后再等多久才由对账接手，给执行器留出自己判定的时间 */
    static final Duration GRACE = Duration.ofSeconds(5);

    static final int PAGE = 100;
    /** 单轮最多处理的页数，避免积压时一轮跑太久 */
    static final int MAX_PAGES = 50;

    private final CommandRepository repository;
    private final CommandExecutor executor;
    private final DeviceDirectory directory;
    private final DeviceRuntimeService runtime;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public CommandReconciler(
            CommandRepository repository,
            CommandExecutor executor,
            DeviceDirectory directory,
            DeviceRuntimeService runtime,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.executor = executor;
        this.directory = directory;
        this.runtime = runtime;
        this.events = events;
    }

    /** 重启恢复：在超时窗口内的接着轮询，过期的立即对账 */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            Instant now = clock.instant();
            Duration timeout = executor.timeout();
            int resumed = 0;
            for (CommandRepository.Row row : repository.findInflightIssuedBefore(now, PAGE * MAX_PAGES)) {
                if (executor.isTracking(row.id())
                        || row.issuedAt().plus(timeout).isBefore(now)) {
                    continue;
                }
                var device = directory.findById(row.deviceId());
                if (device.isPresent() && executor.resume(row.id(), device.get(), row.action(), row.issuedAt())) {
                    resumed++;
                }
            }
            int reconciled = reconcile(now);
            if (resumed > 0 || reconciled > 0) {
                log.info("启动恢复：继续跟踪 {} 条指令，对账补齐 {} 条", resumed, reconciled);
            }
        } catch (RuntimeException e) {
            log.error("启动恢复未终结指令失败", e);
        }
    }

    @Scheduled(
            fixedDelayString = "${smartparking.command.reconcile-interval:15s}",
            initialDelayString = "${smartparking.command.reconcile-interval:15s}")
    public void scheduled() {
        try {
            reconcile(clock.instant());
        } catch (RuntimeException e) {
            log.error("指令对账失败", e);
        }
    }

    /** @return 本轮补齐的指令数 */
    int reconcile(Instant now) {
        Instant cutoff = now.minus(executor.timeout()).minus(GRACE);
        int done = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<CommandRepository.Row> rows = repository.findInflightIssuedBefore(cutoff, PAGE);
            int finishedThisPage = 0;
            for (CommandRepository.Row row : rows) {
                if (reconcileOne(row, now)) {
                    finishedThisPage++;
                }
            }
            done += finishedThisPage;
            // 本页一条都没推进（都被其他方抢先，或查询结果不再变化）时停止，避免空转
            if (rows.size() < PAGE || finishedThisPage == 0) {
                break;
            }
        }
        return done;
    }

    private boolean reconcileOne(CommandRepository.Row row, Instant now) {
        Optional<Verdict> verdict = runtime.find(row.deviceId())
                .filter(r ->
                        r.statusRefreshedAt() != null && !r.statusRefreshedAt().isBefore(row.issuedAt()))
                .map(r -> CommandJudge.judge(row.action(), Baseline.NONE, frameOf(r)));

        CommandStatus status = CommandStatus.TIMEOUT;
        CommandResult result = CommandResult.TIMEOUT;
        if (verdict.isPresent() && verdict.get().kind() == Verdict.Kind.SUCCESS) {
            status = CommandStatus.SUCCESS;
            result = null;
        } else if (verdict.isPresent() && verdict.get().kind() == Verdict.Kind.FAILED) {
            status = CommandStatus.FAILED;
            result = verdict.get().result();
        }
        if (!repository.finish(row.id(), status, result, null, now)) {
            return false;
        }
        log.warn(
                "对账补齐指令 {}（设备 {}，{} 于 {} 下发）：{}{}",
                row.id(),
                row.deviceId(),
                row.action(),
                row.issuedAt(),
                status,
                result == null ? "" : "（" + result + "）");
        events.publishEvent(new CommandFinishedEvent(row.id(), row.deviceId(), row.action(), status, result, now));
        return true;
    }

    /** 用运行态还原一帧状态帧，交给与执行器相同的判定规则 */
    private static UplinkFrame.Status frameOf(DeviceRuntime r) {
        int car = r.occupied() == null ? 0x22 : r.occupied() ? 0x11 : 0x00;
        int reason = r.lastReason() == null ? 0x00 : r.lastReason();
        return (UplinkFrame.Status) Rs485Codec.decodeUplink(Rs485Codec.encodeStatus(1, r.statusCode(), reason, car));
    }
}
