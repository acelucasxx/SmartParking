package com.smartparking.admin.settings;

import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.common.config.SystemSettings.Keys;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AuditTrail;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 后台可配业务参数（{@code system_config}，PRD 6.2 系统设置）：按类型与取值范围校验后写入，写操作日志。各实例的缓存（默认 30 秒）
 * 过期后生效，即修改后最长 1 分钟内全部实例生效（契约 updateConfig）。
 */
public class ConfigService {

    /** INT 参数的取值范围（含两端） */
    record Range(int min, int max) {}

    static final Map<String, Range> RANGES = Map.of(
            Keys.MEMBER_MAX_PER_DEVICE, new Range(1, 20),
            Keys.CARD_TTL_HOURS, new Range(1, 168),
            Keys.COMMAND_TIMEOUT_SECONDS, new Range(5, 60),
            Keys.ONLINE_SLEEP_MINUTES, new Range(2, 120),
            Keys.ONLINE_OFFLINE_HOURS, new Range(1, 168),
            Keys.STATUS_POLL_INTERVAL_MINUTES, new Range(1, 60));

    public record Config(
            String key, String value, String valueType, String description, String updatedBy, Instant updatedAt) {}

    private final JdbcClient jdbc;
    private final Consumer<String> invalidate;

    /** @param invalidate 修改后清除本实例对该键的缓存 */
    public ConfigService(JdbcClient jdbc, Consumer<String> invalidate) {
        this.jdbc = jdbc;
        this.invalidate = invalidate;
    }

    public List<Config> list() {
        return jdbc.sql(SELECT + " ORDER BY c.config_key")
                .query(ConfigService::map)
                .list();
    }

    private Optional<Config> find(String key) {
        return jdbc.sql(SELECT + " WHERE c.config_key = ?")
                .param(key)
                .query(ConfigService::map)
                .optional();
    }

    /** 键不存在 10004；类型、范围或与相关参数的关系不合法 10001 */
    public Config update(AdminPrincipal admin, String key, String rawValue) {
        Config current = find(key).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "参数不存在"));
        String value = normalize(current, rawValue);
        checkRelations(key, value);
        jdbc.sql("UPDATE system_config SET config_value = ?, updated_by = ? WHERE config_key = ?")
                .params(value, admin.adminId(), key)
                .update();
        invalidate.accept(key);
        AuditTrail.detail("from", current.value());
        AuditTrail.detail("to", value);
        return find(key).orElseThrow();
    }

    private static String normalize(Config config, String raw) {
        String value = raw == null ? "" : raw.strip();
        switch (config.valueType()) {
            case "INT" -> {
                int n;
                try {
                    n = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.BAD_REQUEST, config.key() + " 须为整数");
                }
                Range range = RANGES.get(config.key());
                if (range != null && (n < range.min() || n > range.max())) {
                    throw new BizException(
                            ErrorCode.BAD_REQUEST, config.key() + " 取值范围为 " + range.min() + "–" + range.max());
                }
                return Integer.toString(n);
            }
            case "BOOL" -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new BizException(ErrorCode.BAD_REQUEST, config.key() + " 须为 true 或 false");
                }
                return value.toLowerCase();
            }
            default -> {
                if (value.isEmpty() || value.length() > 255) {
                    throw new BizException(ErrorCode.BAD_REQUEST, config.key() + " 为 1–255 个字符");
                }
                return value;
            }
        }
    }

    /**
     * 休眠窗口必须大于定时查询间隔（PRD 8.5：否则正常设备会被判休眠）；离线窗口必须大于休眠窗口。
     */
    private void checkRelations(String key, String value) {
        int sleep = key.equals(Keys.ONLINE_SLEEP_MINUTES)
                ? Integer.parseInt(value)
                : intValue(Keys.ONLINE_SLEEP_MINUTES, 10);
        int poll = key.equals(Keys.STATUS_POLL_INTERVAL_MINUTES)
                ? Integer.parseInt(value)
                : intValue(Keys.STATUS_POLL_INTERVAL_MINUTES, 5);
        int offlineHours = key.equals(Keys.ONLINE_OFFLINE_HOURS)
                ? Integer.parseInt(value)
                : intValue(Keys.ONLINE_OFFLINE_HOURS, 24);
        if (sleep <= poll) {
            throw new BizException(ErrorCode.BAD_REQUEST, "休眠判定窗口（" + sleep + " 分钟）须大于定时查询间隔（" + poll + " 分钟）");
        }
        if (offlineHours * 60 <= sleep) {
            throw new BizException(ErrorCode.BAD_REQUEST, "离线判定窗口须大于休眠判定窗口");
        }
    }

    private int intValue(String key, int fallback) {
        return jdbc.sql("SELECT config_value FROM system_config WHERE config_key = ?")
                .param(key)
                .query(String.class)
                .optional()
                .map(v -> {
                    try {
                        return Integer.parseInt(v.strip());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private static final String SELECT =
            """
            SELECT c.config_key, c.config_value, c.value_type, c.description, u.display_name AS updated_by, c.updated_at
            FROM system_config c LEFT JOIN admin_user u ON u.id = c.updated_by""";

    private static Config map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Config(
                rs.getString("config_key"),
                rs.getString("config_value"),
                rs.getString("value_type"),
                rs.getString("description"),
                rs.getString("updated_by"),
                AdminAccounts.fromDb(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
