package com.smartparking.iot.selfcheck;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.DeviceDirectory.RegisteredDevice;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.mqtt.DownlinkSender;
import com.smartparking.iot.runtime.DeviceRuntimeService;
import com.smartparking.iot.runtime.OnlineState;
import com.smartparking.iot.runtime.OnlineStateService;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 后台设备自检（BIZ-10）：下发查询状态或查询版本，异步等待回包，结果写入 {@code control_command}，后台按指令 ID 轮询。
 *
 * <ul>
 *   <li>与 App 抬杆 / 落杆共用“同一设备同时最多一条未终结指令”：有未终结指令时 20005，自检进行中 App 指令同样 20005。
 *   <li>离线 20003、休眠 20004（与远程控制同一判定）。
 *   <li>超时取后台参数 {@code command.timeout_seconds}（默认 15 秒）；进程重启丢失的自检由定时收尾记为超时。
 *   <li>查询版本成功时记录版本号，并更新设备主档的 {@code fw_version}。
 *   <li>不发布 {@code CommandFinishedEvent}：自检结果不参与“连续 3 次超时”告警与操作记录。
 * </ul>
 */
public class SelfCheckService {

    private static final Logger log = LoggerFactory.getLogger(SelfCheckService.class);
    static final String ACTIONS = "action IN ('QUERY_STATUS', 'QUERY_VERSION')";
    private static final String INFLIGHT = "status IN ('PENDING', 'SENT', 'EXECUTING')";
    /** 收尾宽限：超过“超时 + 宽限”仍未终结的自检由定时任务记为超时 */
    static final Duration GRACE = Duration.ofSeconds(5);

    private record Waiting(long commandId, SelfCheckAction action, CompletableFuture<UplinkFrame> future) {}

    private final JdbcClient jdbc;
    private final DeviceDirectory directory;
    private final DeviceRuntimeService runtime;
    private final OnlineStateService onlineStates;
    private final DownlinkSender sender;
    private final SystemSettings settings;
    private final Executor executor;
    private final Clock clock;
    private final Map<Long, Waiting> waiting = new ConcurrentHashMap<>();

    public SelfCheckService(
            JdbcClient jdbc,
            DeviceDirectory directory,
            DeviceRuntimeService runtime,
            OnlineStateService onlineStates,
            DownlinkSender sender,
            SystemSettings settings,
            Executor executor,
            Clock clock) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.runtime = runtime;
        this.onlineStates = onlineStates;
        this.sender = sender;
        this.settings = settings;
        this.executor = executor;
        this.clock = clock;
    }

    Duration timeout() {
        return Duration.ofSeconds(Math.max(1, settings.getInt(SystemSettings.Keys.COMMAND_TIMEOUT_SECONDS, 15)));
    }

    /** @return 指令 ID；结果异步写入，用指令审计接口轮询 */
    public long submit(long deviceId, SelfCheckAction action, long adminId) {
        RegisteredDevice device =
                directory.findById(deviceId).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "设备不存在"));
        OnlineState online = onlineStates.judge(runtime.find(deviceId).orElse(null));
        if (online == OnlineState.OFFLINE || !sender.isConnected()) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE);
        }
        if (online == OnlineState.SLEEP) {
            throw new BizException(ErrorCode.DEVICE_SLEEPING);
        }
        byte[] frame = Rs485Codec.encode(action.command(), device.rs485Address());
        Instant issuedAt = clock.instant();
        long id;
        try {
            var keys = new GeneratedKeyHolder();
            jdbc.sql(
                            """
                            INSERT INTO control_command (device_id, admin_id, role, channel, action, status, down_frame, issued_at)
                            VALUES (?, ?, 'ADMIN', 'REMOTE', ?, 'PENDING', ?, ?)""")
                    .params(deviceId, adminId, action.name(), frame, DbTime.toDb(issuedAt))
                    .update(keys, "id");
            id = keys.getKeyAs(BigInteger.class).longValueExact();
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.COMMAND_IN_PROGRESS);
        }
        // 先登记再发布，避免回包早于登记到达
        var w = new Waiting(id, action, new CompletableFuture<>());
        waiting.put(deviceId, w);
        try {
            sender.send(device.imei(), frame);
        } catch (RuntimeException e) {
            waiting.remove(deviceId, w);
            finish(id, "FAILED", "OFFLINE", null, null, clock.instant());
            throw new BizException(ErrorCode.DEVICE_OFFLINE);
        }
        jdbc.sql("UPDATE control_command SET status = 'SENT' WHERE id = ? AND status = 'PENDING'")
                .param(id)
                .update();
        log.info("设备 {} 后台自检 {}（指令 {}，账号 {}）", device.imei(), action, id, adminId);
        Duration timeout = timeout();
        executor.execute(() -> await(deviceId, w, timeout));
        return id;
    }

    private void await(long deviceId, Waiting w, Duration timeout) {
        try {
            UplinkFrame frame = w.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Instant at = clock.instant();
            String version = frame instanceof UplinkFrame.Version v ? String.format("%02X", v.version()) : null;
            if (finish(w.commandId(), "SUCCESS", null, frame.rawFrame(), version, at) && version != null) {
                jdbc.sql("UPDATE device SET fw_version = ? WHERE id = ?")
                        .params(version, deviceId)
                        .update();
            }
        } catch (TimeoutException e) {
            finish(w.commandId(), "TIMEOUT", "TIMEOUT", null, null, clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("自检指令 {} 收尾失败", w.commandId(), e);
        } finally {
            waiting.remove(deviceId, w);
        }
    }

    /** 状态帧完成查询状态，版本帧完成查询版本；回显帧只说明命令到了串口，不算 */
    @EventListener
    public void onUplink(DeviceUplinkEvent event) {
        Waiting w = waiting.get(event.deviceId());
        if (w == null) {
            return;
        }
        boolean matches =
                switch (w.action()) {
                    case QUERY_STATUS -> event.frame() instanceof UplinkFrame.Status;
                    case QUERY_VERSION -> event.frame() instanceof UplinkFrame.Version;
                };
        if (matches) {
            w.future().complete(event.frame());
        }
    }

    /** 进程重启等原因没有收尾的自检，过了“超时 + 宽限”记为超时 */
    @Scheduled(
            fixedDelayString = "${smartparking.command.reconcile-interval:15s}",
            initialDelayString = "${smartparking.command.reconcile-interval:15s}")
    public void sweep() {
        try {
            Instant now = clock.instant();
            int n = jdbc.sql("UPDATE control_command SET status = 'TIMEOUT', result_code = 'TIMEOUT', finished_at = ?,"
                            + " duration_ms = TIMESTAMPDIFF(MICROSECOND, issued_at, ?) DIV 1000"
                            + " WHERE " + ACTIONS + " AND " + INFLIGHT + " AND issued_at < ?")
                    .params(
                            DbTime.toDb(now),
                            DbTime.toDb(now),
                            DbTime.toDb(now.minus(timeout()).minus(GRACE)))
                    .update();
            if (n > 0) {
                log.warn("自检收尾：{} 条未终结的自检记为超时", n);
            }
        } catch (RuntimeException e) {
            log.error("自检收尾失败", e);
        }
    }

    private boolean finish(long id, String status, String result, String rawFrame, String version, Instant at) {
        return jdbc.sql(
                                """
                        UPDATE control_command
                        SET status = :status, result_code = :result, raw_frame = :raw, fw_version = :version,
                            finished_at = :at, duration_ms = TIMESTAMPDIFF(MICROSECOND, issued_at, :at) DIV 1000
                        WHERE id = :id AND status IN ('PENDING', 'SENT', 'EXECUTING')""")
                        .param("status", status)
                        .param("result", result)
                        .param("raw", rawFrame == null ? null : Hex.parse(rawFrame))
                        .param("version", version)
                        .param("at", DbTime.toDb(at))
                        .param("id", id)
                        .update()
                > 0;
    }
}
