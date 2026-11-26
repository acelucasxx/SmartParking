package com.smartparking.user.wechat;

/**
 * 微信接口调用失败。
 *
 * @see #errcode() 微信返回的错误码；网络错误、响应无法解析时为 {@code -1}
 */
public class WeChatException extends RuntimeException {

    private final int errcode;

    public WeChatException(int errcode, String message) {
        super(message);
        this.errcode = errcode;
    }

    public WeChatException(String message, Throwable cause) {
        super(message, cause);
        this.errcode = -1;
    }

    public int errcode() {
        return errcode;
    }
}
