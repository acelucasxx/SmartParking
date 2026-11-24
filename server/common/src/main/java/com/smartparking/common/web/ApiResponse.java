package com.smartparking.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartparking.common.error.ErrorCode;

/**
 * 小程序与后台接口的统一响应体。{@code code} 为 0 表示成功，其余取值见 {@link ErrorCode}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode error, String message) {
        return new ApiResponse<>(error.code(), message == null ? error.defaultMessage() : message, null);
    }
}
