package com.smartparking.iot.command;

import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.codec.UplinkFrame;
import com.smartparking.iot.mqtt.DeviceLinkUnavailableException;
import com.smartparking.iot.mqtt.DeviceUplinkEvent;
import com.smartparking.iot.mqtt.DownlinkSender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 同步查询一次设备状态：下发 {@code D0 C0}，等待该设备的下一帧状态帧。用于缓存超过 30 秒时“先查询再判定”（PRD 5.3②）。
 */
@Component
public class StatusProbe {

    private final DownlinkSender sender;
    private final Map<Long, List<CompletableFuture<UplinkFrame.Status>>> waiting = new ConcurrentHashMap<>();

    public StatusProbe(DownlinkSender sender) {
        this.sender = sender;
    }

    /** @return 超时、链路不可用或线程被中断时为空 */
    public Optional<UplinkFrame.Status> query(long deviceId, String imei, int address, Duration timeout) {
        var future = new CompletableFuture<UplinkFrame.Status>();
        var list = waiting.computeIfAbsent(deviceId, k -> new CopyOnWriteArrayList<>());
        list.add(future);
        try {
            sender.send(imei, Rs485Codec.encode(com.smartparking.iot.codec.GateCommand.QUERY_STATUS, address));
            return Optional.of(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException | DeviceLinkUnavailableException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            list.remove(future);
            waiting.computeIfPresent(deviceId, (k, v) -> v.isEmpty() ? null : v);
        }
    }

    @EventListener
    public void onUplink(DeviceUplinkEvent event) {
        if (event.frame() instanceof UplinkFrame.Status status) {
            var list = waiting.get(event.deviceId());
            if (list != null) {
                list.forEach(f -> f.complete(status));
            }
        }
    }
}
