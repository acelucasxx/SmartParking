package com.smartparking.iot.presence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartparking.iot.auth.EmqxHookToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * EMQX 规则引擎的 HTTP 动作回调：客户端连接 / 断开事件（规则见 deploy/local/emqx/emqx.conf）。只在内网供 EMQX 调用。
 *
 * <p>除令牌错误外一律返回 200：忽略的事件（非设备、未登记、过期）不需要 EMQX 重试。
 */
@RestController
public class EmqxEventController {

    private static final Logger log = LoggerFactory.getLogger(EmqxEventController.class);

    private final EmqxHookToken hookToken;
    private final ConnectionEventService service;

    public EmqxEventController(EmqxHookToken hookToken, ConnectionEventService service) {
        this.hookToken = hookToken;
        this.service = service;
    }

    /** 规则 SQL 选出的字段；{@code reason} 只有断开事件才有 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventRequest(String event, String clientid, Long timestamp, String reason) {}

    /** EMQX 规则对不存在的字段输出字符串 {@code undefined}，连接事件的 reason 即如此 */
    static String reasonOf(String reason) {
        return reason == null || reason.isBlank() || "undefined".equals(reason) ? null : reason;
    }

    @PostMapping("/internal/emqx/events")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = EmqxHookToken.HEADER, required = false) String token,
            @RequestBody EventRequest request) {
        if (!hookToken.matches(token)) {
            log.warn("拒绝 EMQX 事件回调：X-Emqx-Token 不正确");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request.timestamp() == null) {
            log.warn("忽略缺少时间戳的 EMQX 事件：{} {}", request.event(), request.clientid());
            return ResponseEntity.ok().build();
        }
        service.handle(request.event(), request.clientid(), request.timestamp(), reasonOf(request.reason()));
        return ResponseEntity.ok().build();
    }
}
