package com.smartparking.user;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.user.AppUserRepository.AppUser;
import com.smartparking.user.AppUserRepository.Status;
import com.smartparking.user.session.SessionStore;
import com.smartparking.user.session.SessionStore.Session;
import com.smartparking.user.wechat.WeChatClient;
import com.smartparking.user.wechat.WeChatClient.WeChatSession;
import com.smartparking.user.wechat.WeChatException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 小程序登录、手机号授权与个人信息（PRD 6.1 页面 1、13；5.8 封禁） */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 用户未设置昵称时展示的默认值；微信已不再向小程序提供真实昵称 */
    public static final String DEFAULT_NICKNAME = "微信用户";

    private final WeChatClient wechat;
    private final AppUserRepository users;
    private final SessionStore sessions;
    private final PhoneCipher phones;
    private final UserProperties properties;
    private final Clock clock;

    public AuthService(
            WeChatClient wechat,
            AppUserRepository users,
            SessionStore sessions,
            PhoneCipher phones,
            UserProperties properties,
            Clock clock) {
        this.wechat = wechat;
        this.users = users;
        this.sessions = sessions;
        this.phones = phones;
        this.properties = properties;
        this.clock = clock;
    }

    public record Profile(long id, String nickname, boolean phoneBound, String phoneMasked) {}

    public record LoginResult(Session session, Profile user) {}

    /** code2session → 按 openid 注册或取出用户 → 封禁的拒绝 → 签发会话 */
    public LoginResult login(String code) {
        WeChatSession session;
        try {
            session = wechat.code2Session(code);
        } catch (WeChatException e) {
            log.warn("微信登录失败：{}", e.getMessage());
            throw new BizException(ErrorCode.WX_LOGIN_FAILED);
        }
        AppUser user = users.upsertByOpenid(session.openid(), session.unionid());
        if (user.status() == Status.BANNED) {
            log.info("已封禁用户 {} 尝试登录", user.id());
            throw new BizException(ErrorCode.USER_BANNED);
        }
        users.touchLogin(user.id());
        return new LoginResult(sessions.create(user.id(), properties.sessionTtl(), clock.instant()), profile(user));
    }

    /** 用手机号快速验证组件的 code 换手机号，加密后存储；重复授权以最新号码为准 */
    public Profile bindPhone(long userId, String code) {
        String phone;
        try {
            phone = wechat.phoneNumber(code);
        } catch (WeChatException e) {
            log.warn("用户 {} 获取手机号失败：{}", userId, e.getMessage());
            throw new BizException(ErrorCode.WX_PHONE_FAILED);
        }
        users.updatePhone(userId, phones.encrypt(phone), phones.hash(phone));
        return me(userId);
    }

    public void logout(String token) {
        sessions.revoke(token);
    }

    public Profile me(long userId) {
        return profile(users.find(userId).orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED)));
    }

    public Profile updateNickname(long userId, String nickname) {
        String trimmed = nickname == null ? "" : nickname.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 32) {
            throw new BizException(ErrorCode.BAD_REQUEST, "昵称长度应为 1–32 个字符");
        }
        users.updateNickname(userId, trimmed);
        return me(userId);
    }

    private Profile profile(AppUser user) {
        String nickname = user.nickname() == null || user.nickname().isBlank() ? DEFAULT_NICKNAME : user.nickname();
        String masked = null;
        if (user.phoneCipher() != null) {
            try {
                masked = PhoneCipher.mask(phones.decrypt(user.phoneCipher()));
            } catch (IllegalStateException e) {
                // 密钥配置错误或数据损坏：不影响登录，按已授权展示，并留日志排查
                log.error("用户 {} 的手机号无法解密：{}", user.id(), e.getMessage());
                masked = "***********";
            }
        }
        return new Profile(user.id(), nickname, user.phoneCipher() != null, masked);
    }
}
