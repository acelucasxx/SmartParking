package com.smartparking.user.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 调用微信服务端接口。
 *
 * <ul>
 *   <li>登录：{@code GET /sns/jscode2session}。session_key 不落库也不返回：手机号走新版接口，不需要它解密。
 *   <li>手机号：{@code POST /wxa/business/getuserphonenumber}，需要接口调用凭证。
 *   <li>订阅消息：{@code POST /cgi-bin/message/subscribe/send}，需要接口调用凭证。
 *   <li>接口调用凭证：{@code POST /cgi-bin/stable_token}。稳定版凭证在有效期内重复获取返回同一个，多实例各自获取互不踢掉；
 *       本地缓存到过期前 5 分钟。微信报凭证无效时强制刷新并重试一次。
 * </ul>
 *
 * <p>微信部分接口以 {@code text/plain} 返回 JSON，这里统一按字符串读取再解析。
 */
public class HttpWeChatClient implements WeChatClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWeChatClient.class);

    /** access_token 无效 / 过期 / 不合法 */
    static final Set<Integer> TOKEN_INVALID = Set.of(40001, 40014, 42001);

    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);

    private record AccessToken(String value, Instant refreshAfter) {}

    private final WeChatProperties properties;
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private AccessToken accessToken;

    public HttpWeChatClient(WeChatProperties properties, RestClient.Builder builder, ObjectMapper json, Clock clock) {
        if (isBlank(properties.appId()) || isBlank(properties.appSecret())) {
            throw new IllegalArgumentException(
                    "smartparking.wechat.mode=real 时必须配置 app-id 与 app-secret；本地开发可用 mode=mock");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.properties = properties;
        this.http =
                builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public WeChatSession code2Session(String code) {
        JsonNode body = call("jscode2session", () -> http.get()
                .uri(u -> u.path("/sns/jscode2session")
                        .queryParam("appid", properties.appId())
                        .queryParam("secret", properties.appSecret())
                        .queryParam("js_code", code)
                        .queryParam("grant_type", "authorization_code")
                        .build())
                .retrieve()
                .body(String.class));
        String openid = text(body, "openid");
        if (openid == null) {
            throw new WeChatException(-1, "jscode2session 未返回 openid");
        }
        return new WeChatSession(openid, text(body, "unionid"));
    }

    @Override
    public String phoneNumber(String code) {
        try {
            return requestPhone(code, false);
        } catch (WeChatException e) {
            if (!TOKEN_INVALID.contains(e.errcode())) {
                throw e;
            }
            log.warn("微信接口调用凭证失效（errcode {}），强制刷新后重试", e.errcode());
            return requestPhone(code, true);
        }
    }

    @Override
    public void sendSubscribeMessage(SubscribeMessage message) {
        try {
            requestSubscribe(message, false);
        } catch (WeChatException e) {
            if (!TOKEN_INVALID.contains(e.errcode())) {
                throw e;
            }
            log.warn("微信接口调用凭证失效（errcode {}），强制刷新后重试", e.errcode());
            requestSubscribe(message, true);
        }
    }

    private void requestSubscribe(SubscribeMessage message, boolean forceRefresh) {
        String token = accessToken(forceRefresh);
        Map<String, Object> data = new LinkedHashMap<>();
        message.data().forEach((key, value) -> data.put(key, Map.of("value", value)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", message.openid());
        body.put("template_id", message.templateId());
        if (message.page() != null && !message.page().isBlank()) {
            body.put("page", message.page());
        }
        body.put("miniprogram_state", message.state());
        body.put("lang", "zh_CN");
        body.put("data", data);
        call("subscribe/send", () -> http.post()
                .uri(u -> u.path("/cgi-bin/message/subscribe/send")
                        .queryParam("access_token", token)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
    }

    private String requestPhone(String code, boolean forceRefresh) {
        String token = accessToken(forceRefresh);
        JsonNode body = call("getuserphonenumber", () -> http.post()
                .uri(u -> u.path("/wxa/business/getuserphonenumber")
                        .queryParam("access_token", token)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code))
                .retrieve()
                .body(String.class));
        JsonNode info = body.path("phone_info");
        String pure = text(info, "purePhoneNumber");
        if (pure == null) {
            throw new WeChatException(-1, "getuserphonenumber 未返回手机号");
        }
        String country = text(info, "countryCode");
        return country == null || "86".equals(country) ? pure : "+" + country + pure;
    }

    private synchronized String accessToken(boolean forceRefresh) {
        Instant now = clock.instant();
        if (!forceRefresh && accessToken != null && now.isBefore(accessToken.refreshAfter())) {
            return accessToken.value();
        }
        JsonNode body = call("stable_token", () -> http.post()
                .uri("/cgi-bin/stable_token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "grant_type",
                        "client_credential",
                        "appid",
                        properties.appId(),
                        "secret",
                        properties.appSecret(),
                        "force_refresh",
                        forceRefresh))
                .retrieve()
                .body(String.class));
        String value = text(body, "access_token");
        if (value == null) {
            throw new WeChatException(-1, "stable_token 未返回 access_token");
        }
        Duration ttl = Duration.ofSeconds(body.path("expires_in").asLong(7200));
        Duration usable = ttl.compareTo(TOKEN_MARGIN.multipliedBy(2)) > 0 ? ttl.minus(TOKEN_MARGIN) : ttl.dividedBy(2);
        accessToken = new AccessToken(value, now.plus(usable));
        return value;
    }

    private interface Request {
        String send();
    }

    /** 发请求并检查 errcode；异常信息只含接口名与微信错误码，不含密钥与凭证 */
    private JsonNode call(String api, Request request) {
        String raw;
        try {
            raw = request.send();
        } catch (RestClientException e) {
            // 不挂原始异常：其消息含完整 URL，jscode2session 的 URL 里有 AppSecret
            throw new WeChatException(-1, "调用微信 " + api + " 失败：" + e.getClass().getSimpleName());
        }
        JsonNode body;
        try {
            body = json.readTree(raw == null ? "" : raw);
        } catch (Exception e) {
            throw new WeChatException("微信 " + api + " 响应无法解析", e);
        }
        if (body == null || !body.isObject()) {
            throw new WeChatException(-1, "微信 " + api + " 响应为空");
        }
        int errcode = body.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new WeChatException(errcode, "微信 " + api + " 返回 errcode " + errcode + "：" + text(body, "errmsg"));
        }
        return body;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isEmpty() ? null : value.asText();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
