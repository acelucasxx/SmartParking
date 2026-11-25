package com.smartparking.common.error;

import org.springframework.http.HttpStatus;

/**
 * 错误码。按区段划分：1xxxx 通用，2xxxx 设备与控制，3xxxx 归属与授权，4xxxx 后台。
 *
 * <p>以接口契约 {@code docs/api/common.yaml} 中的 ErrorCode 为准，{@code ErrorCodeContractTest} 校验两者一致；
 * 新增错误码时先改契约，再改这里。
 */
public enum ErrorCode {
    // 通用
    OK(0, HttpStatus.OK, "成功"),
    BAD_REQUEST(10001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    UNAUTHORIZED(10002, HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN(10003, HttpStatus.FORBIDDEN, "无权执行该操作"),
    NOT_FOUND(10004, HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(10009, HttpStatus.CONFLICT, "状态冲突，请刷新后重试"),
    USER_BANNED(10010, HttpStatus.FORBIDDEN, "账号已停用"),
    TOO_MANY_REQUESTS(10011, HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试"),
    WX_LOGIN_FAILED(10020, HttpStatus.BAD_GATEWAY, "微信登录失败，请重试"),
    WX_PHONE_FAILED(10021, HttpStatus.BAD_GATEWAY, "获取手机号失败，请重试"),
    INTERNAL_ERROR(19999, HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试"),

    // 设备与控制
    DEVICE_NOT_FOUND(20001, HttpStatus.NOT_FOUND, "设备不存在"),
    DEVICE_DISABLED(20002, HttpStatus.FORBIDDEN, "设备已停用，请联系客服"),
    DEVICE_OFFLINE(20003, HttpStatus.CONFLICT, "设备离线，可在现场使用蓝牙"),
    DEVICE_SLEEPING(20004, HttpStatus.CONFLICT, "设备暂时无法远程控制，可用蓝牙"),
    COMMAND_IN_PROGRESS(20005, HttpStatus.CONFLICT, "设备正在执行上一条指令"),
    DEVICE_FAULT(20006, HttpStatus.CONFLICT, "设备故障，请联系客服"),
    BLE_NOT_AVAILABLE(20010, HttpStatus.CONFLICT, "蓝牙控制暂未开放"),
    BLE_TICKET_INVALID(20011, HttpStatus.BAD_REQUEST, "蓝牙票据无效或已过期"),

    // 归属与授权
    ACTIVATE_CODE_WRONG(30001, HttpStatus.BAD_REQUEST, "激活码错误"),
    ACTIVATE_LOCKED(30002, HttpStatus.TOO_MANY_REQUESTS, "错误次数过多，请 30 分钟后再试"),
    DEVICE_ALREADY_BOUND(30003, HttpStatus.CONFLICT, "该设备已有主人"),
    NOT_OWNER(30004, HttpStatus.FORBIDDEN, "仅主人可执行该操作"),
    ACCESS_EXPIRED(30005, HttpStatus.FORBIDDEN, "授权已到期或已被撤销"),
    MEMBER_LIMIT_REACHED(30010, HttpStatus.CONFLICT, "成员名额已满"),
    INVALID_PERIOD(30011, HttpStatus.BAD_REQUEST, "有效期不合法"),
    INVITE_EXPIRED(30012, HttpStatus.CONFLICT, "邀请已过期"),
    INVITE_USED(30013, HttpStatus.CONFLICT, "邀请已被他人接受"),
    INVITE_CANCELLED(30014, HttpStatus.CONFLICT, "邀请已撤销"),
    ALREADY_MEMBER(30015, HttpStatus.CONFLICT, "你已是该设备的成员"),
    CANNOT_ACCEPT_OWN(30016, HttpStatus.BAD_REQUEST, "不能接受自己发出的邀请或转让"),
    TRANSFER_EXPIRED(30020, HttpStatus.CONFLICT, "转让卡片已过期"),
    TRANSFER_USED(30021, HttpStatus.CONFLICT, "转让卡片已被他人接受"),
    TRANSFER_CANCELLED(30022, HttpStatus.CONFLICT, "转让已取消"),
    TRANSFER_PENDING_EXISTS(30023, HttpStatus.CONFLICT, "已有进行中的转让"),

    // 后台
    LOGIN_FAILED(40001, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    ACCOUNT_LOCKED(40002, HttpStatus.FORBIDDEN, "账号已锁定，请稍后再试"),
    ACCOUNT_DISABLED(40003, HttpStatus.FORBIDDEN, "账号已停用"),
    SN_DUPLICATE(40010, HttpStatus.CONFLICT, "SN 已存在"),
    IMEI_INVALID(40011, HttpStatus.BAD_REQUEST, "IMEI 不合法"),
    IMEI_DUPLICATE(40012, HttpStatus.CONFLICT, "IMEI 已存在"),
    MAC_DUPLICATE(40013, HttpStatus.CONFLICT, "蓝牙 MAC 已存在"),
    IMPORT_FILE_INVALID(40014, HttpStatus.BAD_REQUEST, "导入文件格式不正确"),
    PRINT_ALREADY_EXPORTED(40015, HttpStatus.CONFLICT, "印刷文件已导出过"),
    REASON_REQUIRED(40020, HttpStatus.BAD_REQUEST, "必须填写原因"),
    DEVICE_NOT_BOUND(40021, HttpStatus.CONFLICT, "设备未绑定"),
    USERNAME_EXISTS(40030, HttpStatus.CONFLICT, "用户名已存在");

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
