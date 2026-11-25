package com.smartparking.iot.mqtt;

import com.smartparking.iot.codec.UplinkFrame;
import java.time.Instant;

/**
 * 已登记设备的一条上行帧，已完成解码。运行态（IOT-06）、指令状态机（IOT-07）与在线判定（IOT-09）订阅此事件。
 *
 * <p>任意上行帧都表示设备在线说话，按 PRD 8.5 都应刷新 {@code last_seen_at}；回显帧不能当作指令成功。
 */
public record DeviceUplinkEvent(long deviceId, String imei, UplinkFrame frame, Instant receivedAt) {}
