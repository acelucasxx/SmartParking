package com.smartparking.user.wechat;

import java.util.Map;

/** 微信小程序服务端接口。实现不得在日志中输出 AppSecret、access_token 与 session_key。 */
public interface WeChatClient {

    /**
     * code2session：用 {@code wx.login} 的临时 code 换 openid。
     *
     * @throws WeChatException code 无效、已使用、频率限制或网络错误
     */
    WeChatSession code2Session(String code);

    /**
     * 用手机号快速验证组件的动态 code 换手机号。中国大陆号码返回 11 位纯号码，其他地区返回 {@code +区号号码}。
     *
     * @throws WeChatException code 无效或网络错误
     */
    String phoneNumber(String code);

    /**
     * 发送订阅消息（{@code POST /cgi-bin/message/subscribe/send}）。用户未订阅或一次性订阅次数已用完时微信返回 43101。
     *
     * @throws WeChatException 微信返回错误码或网络错误
     */
    void sendSubscribeMessage(SubscribeMessage message);

    /**
     * @param openid 接收人
     * @param templateId 审核通过的模板 ID（EXT-15）
     * @param page 点击消息进入的小程序页面，可空
     * @param state 跳转的小程序版本：{@code formal} 正式版 / {@code trial} 体验版 / {@code developer} 开发版
     * @param data 模板字段 → 值，如 {@code thing1 → 家里车位}
     */
    record SubscribeMessage(String openid, String templateId, String page, String state, Map<String, String> data) {}

    /** @param unionid 小程序未绑定开放平台时为空 */
    record WeChatSession(String openid, String unionid) {}
}
