package com.smartparking.user.wechat;

/**
 * 不出网的微信接口，供小程序资质（EXT-10）下来之前的本地联调与自动化测试使用，生产不得启用。
 *
 * <ul>
 *   <li>登录：code 为 {@code test:<名字>} 时 openid 为 {@code mock_<名字>}，便于造多个用户；其余 code（如开发者工具里
 *       {@code wx.login} 的真实 code）一律视为同一个用户 {@code mock_devtools}；code 为 {@code fail} 时模拟微信登录失败。
 *   <li>手机号：code 本身是 1 开头的 11 位数字时返回该号码，{@code fail} 模拟失败，其余返回 {@code 13800138000}。
 * </ul>
 */
public class MockWeChatClient implements WeChatClient {

    static final String FAIL = "fail";
    static final String DEFAULT_PHONE = "13800138000";

    @Override
    public WeChatSession code2Session(String code) {
        if (FAIL.equals(code)) {
            throw new WeChatException(40029, "mock: invalid code");
        }
        if (code.startsWith("test:") && code.length() > "test:".length()) {
            return new WeChatSession("mock_" + code.substring("test:".length()), null);
        }
        return new WeChatSession("mock_devtools", null);
    }

    @Override
    public String phoneNumber(String code) {
        if (FAIL.equals(code)) {
            throw new WeChatException(40029, "mock: invalid code");
        }
        return code.matches("1\\d{10}") ? code : DEFAULT_PHONE;
    }
}
