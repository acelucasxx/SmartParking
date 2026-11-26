package com.smartparking.iot.command;

import com.smartparking.dtusim.ExecutorScheduler;
import com.smartparking.dtusim.GateBoard;
import com.smartparking.iot.codec.GateCommand;
import com.smartparking.iot.codec.Hex;
import com.smartparking.iot.codec.Rs485Codec;
import com.smartparking.iot.mqtt.DeviceLinkUnavailableException;
import com.smartparking.iot.mqtt.DownlinkSender;
import com.smartparking.iot.mqtt.UplinkDispatcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;

/**
 * 测试用设备链路：下行直接交给内存中的 DTU 模拟器闸板，上行由单线程异步送回 {@link UplinkDispatcher}，
 * 与 MQTT 回调线程的时序一致。不经 EMQX。
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryDeviceLink {

    public final Map<String, GateBoard> boards = new ConcurrentHashMap<>();
    public final List<String> sent = new CopyOnWriteArrayList<>();
    final List<CommandFinishedEvent> finished = new CopyOnWriteArrayList<>();
    final ExecutorService uplinkThread = Executors.newSingleThreadExecutor();
    public volatile boolean connected = true;
    volatile boolean failNextOpenOrClose;
    DownlinkSender sender;
    private ObjectProvider<UplinkDispatcher> dispatcher;

    public GateBoard attach(String imei, long travelMs, ExecutorScheduler scheduler) {
        var board = new GateBoard(
                1,
                travelMs,
                scheduler,
                bytes -> uplinkThread.execute(() -> dispatcher.getObject().dispatch("parking/up/" + imei, bytes)));
        boards.put(imei, board);
        return board;
    }

    @Bean
    @Primary
    DownlinkSender inMemorySender(ObjectProvider<UplinkDispatcher> dispatcher) {
        this.dispatcher = dispatcher;
        this.sender = new DownlinkSender() {
            @Override
            public void send(String imei, byte[] frame) {
                if (!connected) {
                    throw new DeviceLinkUnavailableException("测试：链路断开");
                }
                var decoded = Rs485Codec.decodeDownlink(frame);
                boolean motion = decoded.command()
                        .filter(c -> c != GateCommand.QUERY_STATUS)
                        .isPresent();
                if (motion && failNextOpenOrClose) {
                    failNextOpenOrClose = false;
                    throw new DeviceLinkUnavailableException("测试：发布失败");
                }
                sent.add(imei + " " + Hex.format(frame));
                GateBoard board = boards.get(imei);
                if (board != null) {
                    board.handle(decoded);
                }
            }

            @Override
            public boolean isConnected() {
                return connected;
            }
        };
        return sender;
    }

    @EventListener
    void on(CommandFinishedEvent event) {
        finished.add(event);
    }
}
