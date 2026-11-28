package com.smartparking.notify.subscribe;

import com.smartparking.notify.message.MessageType;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信订阅消息配置，前缀 {@code smartparking.wechat.subscribe}。模板审核通过（EXT-15）后填写，默认关闭。
 *
 * @param enabled 总开关
 * @param state 点击消息跳转的小程序版本：{@code formal} / {@code trial} / {@code developer}
 * @param templates 消息类型 → 模板；没有配置模板的类型只写站内消息
 */
@ConfigurationProperties("smartparking.wechat.subscribe")
public record SubscribeProperties(boolean enabled, String state, Map<MessageType, Template> templates) {

    /**
     * @param templateId 模板 ID
     * @param page 点击进入的页面，可空
     * @param data 模板字段 → 取值，取值可含占位符 {@code {title}}、{@code {content}}、{@code {device}}（设备名称，未命名时为 SN）、
     *     {@code {time}}（yyyy-MM-dd HH:mm）
     */
    public record Template(String templateId, String page, Map<String, String> data) {

        public Template {
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }

    public SubscribeProperties {
        state = state == null || state.isBlank() ? "formal" : state;
        templates = templates == null ? Map.of() : Map.copyOf(templates);
    }
}
