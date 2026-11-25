package com.smartparking.dtusim;

import java.util.List;

/**
 * 模拟器参数。
 *
 * @param cleanSession DTU 是否用干净会话。默认 false（持久会话），可复现“离线时下发的指令在重连后被执行”的问题，见 IOT-01 评估 3.7
 * @param activeReport 闸板是否在状态变化时主动上报，PRD 12.1 第 10 项未确认，默认关闭
 * @param secretKey 一机一密的平台密钥；设置后每台设备以 IMEI 为用户名、派生密码登录，为空时匿名连接
 */
public record SimulatorConfig(
        String broker,
        List<String> imeis,
        long travelMs,
        boolean echo,
        boolean activeReport,
        boolean cleanSession,
        int keepAliveSeconds,
        long reconnectDelayMs,
        String secretKey) {

    public SimulatorConfig {
        imeis = List.copyOf(imeis);
    }

    public static SimulatorConfig defaults(String broker, List<String> imeis) {
        return new SimulatorConfig(broker, imeis, 1200, true, false, false, 60, 5000, null);
    }
}
