package com.smartparking.iot.codec;

/** 帧结构不合法：长度越界、首尾标志错误、缺少命令码等，通常意味着 DTU 打包参数配错或报文损坏。 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }
}
