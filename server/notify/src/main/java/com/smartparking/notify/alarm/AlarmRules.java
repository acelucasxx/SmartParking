package com.smartparking.notify.alarm;

import com.smartparking.iot.codec.GateFault;
import com.smartparking.iot.codec.GateStatus;
import com.smartparking.iot.codec.TriggerSource;
import com.smartparking.iot.command.CommandFinishedEvent;
import com.smartparking.iot.command.CommandStatus;
import com.smartparking.iot.runtime.DeviceRuntime;
import com.smartparking.iot.runtime.DeviceStateChangedEvent;
import com.smartparking.iot.runtime.OnlineState;
import com.smartparking.iot.runtime.OnlineStateChangedEvent;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 告警规则（PRD 6.2）：从 iot 的事件判定何时写入一次告警，合并由 {@link AlarmService} 负责。
 *
 * <p>事件在设备上行、在线扫描、指令收尾的链路里同步发布：这里的任何失败只记日志，不向发布方抛出。
 */
public class AlarmRules {

    private static final Logger log = LoggerFactory.getLogger(AlarmRules.class);

    /** 连续几次超时触发 */
    public static final int TIMEOUT_STREAK = 3;

    private final AlarmService alarms;
    private final JdbcClient jdbc;

    public AlarmRules(AlarmService alarms, JdbcClient jdbc) {
        this.alarms = alarms;
        this.jdbc = jdbc;
    }

    /**
     * E3、遇阻、红外：本帧表达的异常与变化前不同才算一次新的发生。同一次遇阻，闸板反转、到位的后续帧仍带原因码 11，不重复计数。
     * App 指令与遥控器、地感等非 App 动作都走这里，指令失败不再单独计一次。
     */
    @EventListener
    public void onStateChanged(DeviceStateChangedEvent event) {
        Optional<GateFault> fault = event.frame().fault();
        if (fault.isEmpty() || fault.equals(faultOf(event.previous()))) {
            return;
        }
        raise(event.deviceId(), AlarmType.valueOf(fault.get().name()), event);
    }

    /** 连续 3 次超时：该设备最近 3 条已终结的 App 抬杆 / 落杆都是 TIMEOUT（后台自检不计）。之后每多一次连续超时，合并计数 +1；中间有一次成功或失败即重新计数。 */
    @EventListener
    public void onCommandFinished(CommandFinishedEvent event) {
        if (event.status() != CommandStatus.TIMEOUT) {
            return;
        }
        try {
            List<String> recent = jdbc.sql(
                            """
                            SELECT status FROM control_command
                            WHERE device_id = ? AND action IN ('OPEN', 'CLOSE') AND status IN ('SUCCESS', 'FAILED', 'TIMEOUT')
                            ORDER BY issued_at DESC, id DESC LIMIT ?""")
                    .params(event.deviceId(), TIMEOUT_STREAK)
                    .query(String.class)
                    .list();
            if (recent.size() == TIMEOUT_STREAK && recent.stream().allMatch("TIMEOUT"::equals)) {
                alarms.raise(event.deviceId(), AlarmType.CMD_TIMEOUT, event.at());
            }
        } catch (RuntimeException e) {
            log.error("设备 {} 指令 {} 超时告警判定失败", event.deviceId(), event.commandId(), e);
        }
    }

    /**
     * 离线超 24 小时：在线状态变为 OFFLINE（PRD 8.5 判据即 {@code last_seen_at} 超过 24 小时）。每次离线只发生一次；
     * 只针对已绑定的设备，库存中未激活的设备长期不上电是正常的。
     */
    @EventListener
    public void onOnlineStateChanged(OnlineStateChangedEvent event) {
        if (event.current() != OnlineState.OFFLINE) {
            return;
        }
        try {
            boolean bound = jdbc.sql("SELECT COUNT(*) FROM device WHERE id = ? AND bind_status = 'BOUND'")
                            .param(event.deviceId())
                            .query(Long.class)
                            .single()
                    > 0;
            if (bound) {
                alarms.raise(event.deviceId(), AlarmType.OFFLINE_24H, event.at());
            }
        } catch (RuntimeException e) {
            log.error("设备 {} 离线告警写入失败", event.deviceId(), e);
        }
    }

    private void raise(long deviceId, AlarmType type, DeviceStateChangedEvent event) {
        try {
            alarms.raise(deviceId, type, event.at());
        } catch (RuntimeException e) {
            log.error("设备 {} {} 告警写入失败：{}", deviceId, type, event.frame().rawFrame(), e);
        }
    }

    /** 变化前运行态表达的异常；与 {@link com.smartparking.iot.codec.UplinkFrame.Status#fault()} 同一优先级 */
    static Optional<GateFault> faultOf(DeviceRuntime previous) {
        if (previous == null) {
            return Optional.empty();
        }
        if (previous.gateStatus() == GateStatus.FAULT) {
            return Optional.of(GateFault.MOTOR_FAULT);
        }
        if (previous.lastReason() == null) {
            return Optional.empty();
        }
        return switch (TriggerSource.of(previous.lastReason())) {
            case OBSTRUCTED -> Optional.of(GateFault.OBSTRUCTED);
            case INFRARED -> Optional.of(GateFault.INFRARED);
            default -> Optional.empty();
        };
    }
}
