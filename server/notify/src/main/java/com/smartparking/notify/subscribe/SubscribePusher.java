package com.smartparking.notify.subscribe;

import com.smartparking.iot.DbTime;
import com.smartparking.notify.message.MessageCreatedEvent;
import com.smartparking.notify.subscribe.SubscribeProperties.Template;
import com.smartparking.user.wechat.WeChatClient;
import com.smartparking.user.wechat.WeChatClient.SubscribeMessage;
import com.smartparking.user.wechat.WeChatException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 微信订阅消息（BIZ-18，PRD 6.1 消息通知）：站内消息写入后，若该类型配置了模板，再给接收人推一条订阅消息。
 *
 * <ul>
 *   <li>站内消息是准，订阅消息只是提醒：在独立线程池里异步发送，失败只记日志、不重试，不影响业务与站内消息。
 *   <li>用户未订阅或一次性订阅次数用完（43101）是常态，只记 debug。订阅由小程序在授权、邀请等页面用
 *       {@code wx.requestSubscribeMessage} 申请。
 *   <li>接收人已封禁或不存在则不发。
 *   <li>字段值按模板字段类型截断：{@code thing} 20 字、{@code name} 10 字、{@code phrase} 5 字、
 *       {@code character_string} / {@code letter} / {@code number} 32 字，超长时微信会拒收（47003）。
 * </ul>
 */
public class SubscribePusher {

    private static final Logger log = LoggerFactory.getLogger(SubscribePusher.class);

    static final int NOT_SUBSCRIBED = 43101;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(DbTime.ZONE);

    private final SubscribeProperties properties;
    private final WeChatClient wechat;
    private final JdbcClient jdbc;
    private final Executor executor;

    public SubscribePusher(SubscribeProperties properties, WeChatClient wechat, JdbcClient jdbc, Executor executor) {
        this.properties = properties;
        this.wechat = wechat;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    @EventListener
    public void onMessage(MessageCreatedEvent e) {
        if (!properties.enabled()) {
            return;
        }
        Template template = properties.templates().get(e.type());
        if (template == null
                || template.templateId() == null
                || template.templateId().isBlank()) {
            return;
        }
        try {
            executor.execute(() -> push(e, template));
        } catch (RejectedExecutionException ex) {
            log.warn("订阅消息队列已满，丢弃发给用户 {} 的 {} 消息", e.userId(), e.type());
        }
    }

    private void push(MessageCreatedEvent e, Template template) {
        try {
            Optional<String> openid = jdbc.sql("SELECT openid FROM app_user WHERE id = ? AND status = 'ACTIVE'")
                    .param(e.userId())
                    .query(String.class)
                    .optional();
            if (openid.isEmpty()) {
                return;
            }
            Map<String, String> data = new LinkedHashMap<>();
            template.data().forEach((key, value) -> data.put(key, fit(key, render(value, e))));
            wechat.sendSubscribeMessage(new SubscribeMessage(
                    openid.get(), template.templateId(), template.page(), properties.state(), data));
        } catch (WeChatException ex) {
            if (ex.errcode() == NOT_SUBSCRIBED) {
                log.debug("用户 {} 未订阅 {} 消息或次数已用完", e.userId(), e.type());
            } else {
                log.warn("给用户 {} 推送 {} 订阅消息失败：{}", e.userId(), e.type(), ex.getMessage());
            }
        } catch (RuntimeException ex) {
            log.error("给用户 {} 推送 {} 订阅消息失败", e.userId(), e.type(), ex);
        }
    }

    private String render(String value, MessageCreatedEvent e) {
        String out = value == null ? "" : value;
        out = out.replace("{title}", e.title()).replace("{content}", e.content());
        out = out.replace("{time}", TIME.format(e.createdAt()));
        if (out.contains("{device}")) {
            out = out.replace("{device}", device(e.deviceId()));
        }
        return out;
    }

    private String device(Long deviceId) {
        if (deviceId == null) {
            return "";
        }
        return jdbc.sql("SELECT COALESCE(NULLIF(name, ''), sn) FROM device WHERE id = ?")
                .param(deviceId)
                .query(String.class)
                .optional()
                .orElse("");
    }

    /** 按字段类型（字段名去掉末尾数字）截断，超出时末位换成省略号 */
    static String fit(String key, String value) {
        int max =
                switch (key.replaceAll("\\d+$", "")) {
                    case "thing" -> 20;
                    case "name" -> 10;
                    case "phrase", "symbol" -> 5;
                    case "character_string", "letter", "number" -> 32;
                    default -> Integer.MAX_VALUE;
                };
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        int end = value.offsetByCodePoints(0, max - 1);
        return value.substring(0, end) + "…";
    }
}
