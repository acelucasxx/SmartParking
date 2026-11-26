package com.smartparking.dtubench;

import java.util.concurrent.BlockingQueue;

/** 与一台设备的收发通道。真机联调时为 {@link CloudLink}（经 EMQX），测试可替换。 */
public interface Link {

    /** 向设备下发一帧 */
    void send(byte[] frame) throws Exception;

    /** 按到达顺序排列的上行 */
    BlockingQueue<Received> uplinks();
}
