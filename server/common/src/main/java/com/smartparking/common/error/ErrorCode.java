package com.smartparking.common.error;

import org.springframework.http.HttpStatus;

/**
 * 错误码。按区段划分：1xxxx 通用，2xxxx 设备与控制，3xxxx 归属与授权，4xxxx 后台。
 * 完整错误码表在 INF-05 接口契约中定义，这里只放骨架阶段需要的通用码。
 */
public enum ErrorCode {
    OK(0, HttpStatus.OK, "成功"),
    BAD_REQUEST(10001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHORIZED(10002, HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN(10003, HttpStatus.FORBIDDEN, "无权执行该操作"),
    NOT_FOUND(10004, HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(10009, HttpStatus.CONFLICT, "状态冲突，请刷新后重试"),
    INTERNAL_ERROR(19999, HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
