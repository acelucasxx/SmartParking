package com.smartparking.iot.sync;

import com.smartparking.common.config.SystemSettings;
import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.codec.GateCommand;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.mqtt.DeviceLinkUnavailableException;
import com.smartparking.iot.mqtt.DownlinkSender;
import com.smartparking.iot.presence.ConnectionState;
import com.smartparking.iot.presence.DeviceConnectionEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 状态同步（PRD 8.7）：协议一问一答、闸板是否主动上报未确认，平台在以下时机主动下发查询 {@code D0 C0}。回包走正常上行链路，
 * 刷新运行态、{@code last_seen_at} 与在线状态，这里不等回包。
 *
 * <table>
 *   <caption>时机</caption>
 *   <tr><th>时机</th><th>入口</th></tr>
 *   <tr><td>设备 MQTT 连接事件</td><td>{@link #onConnection}，延迟 {@code smartparking.status-sync.connect-delay} 下发</td></tr>
 *   <tr><td>绑定成功</td><td>{@link #requestQuery}，由绑定流程（BIZ）调用</td></tr>
 *   <tr><td>下发控制前，缓存超过 30 秒</td><td>{@code CommandService}（IOT-07）同步查询，不在这里</td></tr>
 *   <tr><td>控制执行中每 300 ms</td><td>{@code CommandExecutor}（IOT-07）</td></tr>
 *   <tr><td>定时，默认每 5 分钟</td><td>{@link #pollDue}，间隔取后台参数 {@code status_poll.interval_minutes}，0 为关闭</td></tr>
 * </table>
 *
 * <p>定时查询只发给 MQTT 连接中、且一个间隔内既无上行也未查询过的设备：有上行的设备本来就是活的，不必再查，流量随之下降。
 * 每台设备的到期时刻随各自的最近上行错开，不会在同一秒集中下发。
 */
@Service
public class StatusSyncService {

    private static final Logger log = LoggerFactory.getLogger(StatusSyncService.class);
    static final int PAGE = 200;
    /** 单轮最多处理的页数 */
    static final int MAX_PAGES = 25;

    private final DeviceDirectory directory;
    private final DownlinkSender sender;
    private final StatusPollRepository repository;
    private final SystemSettings settings;
    private final TaskScheduler scheduler;
    private final Duration connectDelay;
    private final Clock clock = Clock.systemUTC();

    public StatusSyncService(
            DeviceDirectory directory,
            DownlinkSender sender,
            StatusPollRepository repository,
            SystemSettings settings,
            TaskScheduler scheduler,
            @Value("${smartparking.status-sync.connect-delay:2s}") Duration connectDelay) {
        this.directory = directory;
        this.sender = sender;
        this.repository = repository;
        this.settings = settings;
        this.scheduler = scheduler;
        this.connectDelay = connectDelay;
    }

    /**
     * 立即向设备下发一次查询，不等回包。用于绑定成功后判定“联网成功”（PRD 5.2）：回包刷新运行态后由调用方读取。
     *
     * @return 是否已发出；设备未登记或链路不可用时为 false（设备不在线时等它上线由连接事件触发）
     */
    public boolean requestQuery(long deviceId) {
        var device = directory.findById(deviceId);
        if (device.isEmpty()) {
            return false;
        }
        Instant now = clock.instant();
        if (!send(device.get().imei(), device.get().rs485Address())) {
            return false;
        }
        repository.markPolled(deviceId, now);
        return true;
    }

    /**
     * 上线后同步闸杆、车位状态。EMQX 在连接建立时即发事件，设备此刻可能还没订阅下行主题，立即下发会丢，故延迟一会儿；
     * 同时避免在 EMQX 回调线程里发布。
     */
    @EventListener
    public void onConnection(DeviceConnectionEvent event) {
        if (event.state() != ConnectionState.CONNECTED) {
            return;
        }
        scheduler.schedule(
                () -> {
                    try {
                        if (requestQuery(event.deviceId())) {
                            log.debug("{} 上线后查询状态", event.imei());
                        }
                    } catch (RuntimeException e) {
                        log.warn("{} 上线后查询状态失败：{}", event.imei(), e.getMessage());
                    }
                },
                clock.instant().plus(connectDelay));
    }

    @Scheduled(
            fixedDelayString = "${smartparking.status-sync.tick:30s}",
            initialDelayString = "${smartparking.status-sync.tick:30s}")
    public void scheduled() {
        try {
            pollDue();
        } catch (RuntimeException e) {
            log.warn("定时状态查询失败：{}", e.getMessage(), e);
        }
    }

    /** @return 本轮下发查询的设备数 */
    public int pollDue() {
        int minutes = settings.getInt(SystemSettings.Keys.STATUS_POLL_INTERVAL_MINUTES, 5);
        if (minutes <= 0 || !sender.isConnected()) {
            return 0;
        }
        Instant now = clock.instant();
        Instant dueBefore = now.minus(Duration.ofMinutes(minutes));
        int sent = 0;
        long after = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Long> due = repository.findDue(dueBefore, after, PAGE);
            for (long deviceId : due) {
                if (!repository.claim(deviceId, dueBefore, now)) {
                    continue; // 别的实例已认领，或期间收到了上行
                }
                var device = directory.findById(deviceId);
                if (device.isEmpty()) {
                    continue;
                }
                if (!send(device.get().imei(), device.get().rs485Address())) {
                    log.warn("定时状态查询中断：与 broker 的连接不可用");
                    return sent;
                }
                sent++;
            }
            if (due.size() < PAGE) {
                break;
            }
            after = due.get(due.size() - 1);
        }
        if (sent > 0) {
            log.info("定时状态查询：下发 {} 台", sent);
        }
        return sent;
    }

    private boolean send(String imei, int address) {
        try {
            sender.send(imei, Rs485Codec.encode(GateCommand.QUERY_STATUS, address));
            return true;
        } catch (DeviceLinkUnavailableException e) {
            log.debug("{} 查询未发出：{}", imei, e.getMessage());
            return false;
        }
    }
}
