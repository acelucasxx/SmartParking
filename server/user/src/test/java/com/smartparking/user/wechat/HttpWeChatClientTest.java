package com.smartparking.user.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 对着本地 HTTP 桩验证请求格式、errcode 处理与接口调用凭证的缓存 / 刷新 */
class HttpWeChatClientTest {

    private static final String SECRET = "app-secret-must-not-leak";

    private record Call(String method, String path, String query, String body) {}

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, String> responder = c -> "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Call call = new Call(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        calls.add(call);
        byte[] body = responder.apply(call).getBytes(StandardCharsets.UTF_8);
        // 微信部分接口以 text/plain 返回 JSON
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private HttpWeChatClient client(String baseUrl) {
        return new HttpWeChatClient(
                new WeChatProperties(
                        WeChatProperties.Mode.REAL,
                        "wx-app-id",
                        SECRET,
                        baseUrl,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2)),
                RestClient.builder(),
                new ObjectMapper(),
                Clock.systemUTC());
    }

    private HttpWeChatClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private List<Call> callsTo(String path) {
        return calls.stream().filter(c -> c.path().equals(path)).toList();
    }

    @Test
    void code2SessionSendsCredentialsAndReturnsIds() {
        responder = c -> "{\"openid\":\"o-1\",\"session_key\":\"sk\",\"unionid\":\"u-1\"}";

        assertThat(client().code2Session("the-code")).isEqualTo(new WeChatClient.WeChatSession("o-1", "u-1"));

        Call call = calls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/sns/jscode2session");
        assertThat(call.query())
                .contains("appid=wx-app-id", "secret=" + SECRET, "js_code=the-code", "grant_type=authorization_code");
    }

    @Test
    void code2SessionWithoutUnionidAndErrors() {
        responder = c -> "{\"openid\":\"o-1\",\"session_key\":\"sk\"}";
        assertThat(client().code2Session("c").unionid()).isNull();

        responder = c -> "{\"errcode\":40029,\"errmsg\":\"invalid code\"}";
        assertThatThrownBy(() -> client().code2Session("c"))
                .isInstanceOfSatisfying(
                        WeChatException.class, e -> assertThat(e.errcode()).isEqualTo(40029));

        responder = c -> "not json";
        assertThatThrownBy(() -> client().code2Session("c")).isInstanceOf(WeChatException.class);
    }

    @Test
    void phoneNumberFetchesTokenOnceAndReusesIt() {
        responder = c -> c.path().equals("/cgi-bin/stable_token")
                ? "{\"access_token\":\"AT-1\",\"expires_in\":7200}"
                : "{\"errcode\":0,\"errmsg\":\"ok\",\"phone_info\":{\"phoneNumber\":\"13912341234\","
                        + "\"purePhoneNumber\":\"13912341234\",\"countryCode\":\"86\"}}";
        HttpWeChatClient client = client();

        assertThat(client.phoneNumber("p1")).isEqualTo("13912341234");
        assertThat(client.phoneNumber("p2")).isEqualTo("13912341234");

        assertThat(callsTo("/cgi-bin/stable_token")).hasSize(1);
        Call token = callsTo("/cgi-bin/stable_token").get(0);
        assertThat(token.method()).isEqualTo("POST");
        assertThat(token.body()).contains("\"grant_type\":\"client_credential\"", "\"force_refresh\":false");

        List<Call> phones = callsTo("/wxa/business/getuserphonenumber");
        assertThat(phones).hasSize(2);
        assertThat(phones.get(0).query()).isEqualTo("access_token=AT-1");
        assertThat(phones.get(0).body()).isEqualTo("{\"code\":\"p1\"}");
    }

    @Test
    void invalidTokenIsForceRefreshedAndRetriedOnce() {
        responder = c -> {
            if (c.path().equals("/cgi-bin/stable_token")) {
                return c.body().contains("\"force_refresh\":true")
                        ? "{\"access_token\":\"AT-2\",\"expires_in\":7200}"
                        : "{\"access_token\":\"AT-1\",\"expires_in\":7200}";
            }
            return c.query().equals("access_token=AT-1")
                    ? "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"
                    : "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13912341234\",\"countryCode\":\"86\"}}";
        };

        assertThat(client().phoneNumber("p")).isEqualTo("13912341234");
        assertThat(callsTo("/wxa/business/getuserphonenumber")).hasSize(2);
        assertThat(callsTo("/cgi-bin/stable_token")).hasSize(2);
    }

    @Test
    void otherPhoneErrorsAreNotRetried() {
        responder = c -> c.path().equals("/cgi-bin/stable_token")
                ? "{\"access_token\":\"AT-1\",\"expires_in\":7200}"
                : "{\"errcode\":40029,\"errmsg\":\"invalid code\"}";

        assertThatThrownBy(() -> client().phoneNumber("p"))
                .isInstanceOfSatisfying(
                        WeChatException.class, e -> assertThat(e.errcode()).isEqualTo(40029));
        assertThat(callsTo("/wxa/business/getuserphonenumber")).hasSize(1);
    }

    @Test
    void foreignNumbersKeepCountryCode() {
        responder = c -> c.path().equals("/cgi-bin/stable_token")
                ? "{\"access_token\":\"AT-1\",\"expires_in\":7200}"
                : "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"91234567\",\"countryCode\":\"852\"}}";
        assertThat(client().phoneNumber("p")).isEqualTo("+85291234567");
    }

    private static WeChatClient.SubscribeMessage subscribe(String page) {
        var data = new java.util.LinkedHashMap<String, String>();
        data.put("thing1", "家里车位");
        data.put("time2", "2026-11-28 10:00");
        return new WeChatClient.SubscribeMessage("o-1", "TPL-1", page, "trial", data);
    }

    @Test
    void subscribeMessageIsSentWithTokenAndNestedValues() throws Exception {
        responder = c -> c.path().equals("/cgi-bin/stable_token")
                ? "{\"access_token\":\"AT-1\",\"expires_in\":7200}"
                : "{\"errcode\":0,\"errmsg\":\"ok\"}";
        HttpWeChatClient client = client();

        client.sendSubscribeMessage(subscribe("pages/messages/index"));
        client.sendSubscribeMessage(subscribe(null));

        assertThat(callsTo("/cgi-bin/stable_token")).hasSize(1);
        List<Call> sends = callsTo("/cgi-bin/message/subscribe/send");
        assertThat(sends).hasSize(2);
        assertThat(sends.get(0).method()).isEqualTo("POST");
        assertThat(sends.get(0).query()).isEqualTo("access_token=AT-1");
        var body = new ObjectMapper().readTree(sends.get(0).body());
        assertThat(body.get("touser").asText()).isEqualTo("o-1");
        assertThat(body.get("template_id").asText()).isEqualTo("TPL-1");
        assertThat(body.get("page").asText()).isEqualTo("pages/messages/index");
        assertThat(body.get("miniprogram_state").asText()).isEqualTo("trial");
        assertThat(body.get("lang").asText()).isEqualTo("zh_CN");
        assertThat(body.get("data").get("thing1").get("value").asText()).isEqualTo("家里车位");
        assertThat(body.get("data").get("time2").get("value").asText()).isEqualTo("2026-11-28 10:00");
        assertThat(new ObjectMapper().readTree(sends.get(1).body()).has("page")).isFalse();
    }

    @Test
    void subscribeRefreshesInvalidTokenOnceAndReportsOtherErrors() {
        responder = c -> {
            if (c.path().equals("/cgi-bin/stable_token")) {
                return c.body().contains("\"force_refresh\":true")
                        ? "{\"access_token\":\"AT-2\",\"expires_in\":7200}"
                        : "{\"access_token\":\"AT-1\",\"expires_in\":7200}";
            }
            return c.query().equals("access_token=AT-1")
                    ? "{\"errcode\":42001,\"errmsg\":\"access_token expired\"}"
                    : "{\"errcode\":0}";
        };
        HttpWeChatClient client = client();
        client.sendSubscribeMessage(subscribe(null));
        assertThat(callsTo("/cgi-bin/message/subscribe/send")).hasSize(2);
        assertThat(callsTo("/cgi-bin/stable_token")).hasSize(2);

        calls.clear();
        responder = c -> c.path().equals("/cgi-bin/stable_token")
                ? "{\"access_token\":\"AT-3\",\"expires_in\":7200}"
                : "{\"errcode\":43101,\"errmsg\":\"user refuse to accept the msg\"}";
        assertThatThrownBy(() -> client.sendSubscribeMessage(subscribe(null)))
                .isInstanceOfSatisfying(
                        WeChatException.class, e -> assertThat(e.errcode()).isEqualTo(43101));
        assertThat(callsTo("/cgi-bin/message/subscribe/send")).hasSize(1);
    }

    @Test
    void networkFailureDoesNotLeakSecret() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort).code2Session("c"))
                .isInstanceOfSatisfying(WeChatException.class, e -> {
                    assertThat(e.errcode()).isEqualTo(-1);
                    assertThat(e.getMessage()).doesNotContain(SECRET);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void realModeRequiresCredentials() {
        assertThatThrownBy(() -> new HttpWeChatClient(
                        new WeChatProperties(null, "", null, null, null, null),
                        RestClient.builder(),
                        new ObjectMapper(),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
