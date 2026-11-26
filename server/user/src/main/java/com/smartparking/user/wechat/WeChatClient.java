package com.smartparking.user.wechat;

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

    /** @param unionid 小程序未绑定开放平台时为空 */
    record WeChatSession(String openid, String unionid) {}
}
