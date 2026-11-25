package com.smartparking.iot.mqtt;

/** 下行发送端口：指令状态机（IOT-07）与状态同步通过它向设备发帧，测试可替换为内存实现。 */
public interface DownlinkSender {

    /**
     * 向 {@code parking/down/{imei}} 发布一帧，QoS 1，带消息过期时间。
     *
     * @throws DeviceLinkUnavailableException 与 broker 未连接或发布失败
     */
    void send(String imei, byte[] frame);

    /** 与 broker 的连接是否可用 */
    boolean isConnected();
}
