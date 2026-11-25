package com.smartparking.iot.auth;

import com.smartparking.iot.DeviceDirectory;
import com.smartparking.iot.Imei;
import com.smartparking.iot.mqtt.MqttProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * EMQX HTTP 认证回调（EMQX 5 {@code authentication.backend = http}）。只在内网供 EMQX 调用，Nginx 不得转发 {@code /internal/}。
 *
 * <p>规则：
 *
 * <ul>
 *   <li>云端账号（{@code smartparking.mqtt.username}）密码正确 → 允许，超级用户，不受 ACL 限制。
 *   <li>设备：用户名为 15 位 IMEI、客户端 ID 为 {@code dtu-{imei}}、IMEI 已登记、密码等于派生密码 → 允许；主题权限由 EMQX
 *       的 ACL 文件限定为只能发布自己的上行、订阅自己的下行。
 *   <li>其余一律拒绝。未登记的 IMEI 在这里就连不上（PRD 8.1）。
 * </ul>
 *
 * <p>响应体是 EMQX 约定的格式，不走统一信封。
 */
@RestController
public class EmqxAuthController {

    private static final Logger log = LoggerFactory.getLogger(EmqxAuthController.class);

    static final Map<String, Object> ALLOW = Map.of("result", "allow", "is_superuser", false);
    static final Map<String, Object> ALLOW_SUPERUSER = Map.of("result", "allow", "is_superuser", true);
    static final Map<String, Object> DENY = Map.of("result", "deny");

    private final DeviceDirectory directory;
    private final DeviceCredentials credentials;
    private final MqttProperties mqtt;
    private final EmqxHookToken hookToken;

    public EmqxAuthController(
            DeviceDirectory directory, DeviceCredentials credentials, MqttProperties mqtt, EmqxHookToken hookToken) {
        this.directory = directory;
        this.credentials = credentials;
        this.mqtt = mqtt;
        this.hookToken = hookToken;
    }

    /** EMQX 发送的认证请求体 */
    public record AuthRequest(String clientid, String username, String password) {}

    @PostMapping("/internal/emqx/authn")
    public ResponseEntity<Map<String, Object>> authenticate(
            @RequestHeader(value = EmqxHookToken.HEADER, required = false) String token,
            @RequestBody AuthRequest request) {
        if (!hookToken.matches(token)) {
            log.warn("拒绝 EMQX 认证回调：X-Emqx-Token 不正确");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(decide(request));
    }

    Map<String, Object> decide(AuthRequest request) {
        String username = request.username();
        if (username != null && username.equals(mqtt.username())) {
            if (constantTimeEquals(mqtt.password(), request.password())) {
                return ALLOW_SUPERUSER;
            }
            log.warn("拒绝 MQTT 连接：云端账号 {} 密码错误（客户端 {}）", username, request.clientid());
            return DENY;
        }
        if (!Imei.isWellFormed(username)) {
            log.warn("拒绝 MQTT 连接：用户名不是 IMEI（客户端 {}）", request.clientid());
            return DENY;
        }
        if (!Imei.clientId(username).equals(request.clientid())) {
            log.warn("拒绝 MQTT 连接：{} 的客户端 ID 应为 {}，实际 {}", username, Imei.clientId(username), request.clientid());
            return DENY;
        }
        if (directory.findByImei(username).isEmpty()) {
            log.warn("拒绝 MQTT 连接：IMEI {} 未登记", username);
            return DENY;
        }
        if (!credentials.matches(username, request.password())) {
            log.warn("拒绝 MQTT 连接：IMEI {} 密码错误（密钥 {}）", username, credentials.keyId());
            return DENY;
        }
        return ALLOW;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || expected.isEmpty() || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
