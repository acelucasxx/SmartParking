package com.smartparking.common.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return respond(e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(ErrorCode.BAD_REQUEST, message);
    }

    /** 请求体缺失或不是合法 JSON */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(ErrorCode.BAD_REQUEST, "请求体缺失或格式不正确");
    }

    /** 缺少必填参数、上传文件，或参数类型不对（如 page=abc） */
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadParameter(Exception e) {
        return respond(ErrorCode.BAD_REQUEST, null);
    }

    /** 上传文件超过 spring.servlet.multipart 限制；目前只有设备批量导入接受上传 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
        return respond(ErrorCode.IMPORT_FILE_INVALID, "文件超过大小限制（5 MB）");
    }

    /** 没有对应接口的路径 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return respond(ErrorCode.INTERNAL_ERROR, null);
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, message));
    }
}
