package com.smartparking.iot.mqtt;

/** 云端与 broker 之间不可用，指令无法下发。调用方按“设备离线”处理，不重试排队。 */
public class DeviceLinkUnavailableException extends RuntimeException {

    public DeviceLinkUnavailableException(String message) {
        super(message);
    }

    public DeviceLinkUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
