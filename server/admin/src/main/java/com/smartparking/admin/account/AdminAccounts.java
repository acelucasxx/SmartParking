package com.smartparking.admin.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.common.web.AdminRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code admin_user} 与 {@code role} 的读写 */
public class AdminAccounts {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public enum Status {
        ACTIVE,
        DISABLED
    }

    /**
     * @param passwordChangedAt 为空表示仍在用临时密码（新建或被重置），必须先修改
     */
    public record Account(
            long id,
            String username,
            String passwordHash,
            String displayName,
            AdminRole role,
            Status status,
            int failedAttempts,
            Instant lockedUntil,
            Instant lastLoginAt,
            Instant passwordChangedAt,
            Instant createdAt) {

        public boolean mustChangePassword() {
            return passwordChangedAt == null;
        }

        public boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    public record Role(AdminRole code, String name, List<String> permissions) {}

    private static final String SELECT =
            """
            SELECT a.id, a.username, a.password_hash, a.display_name, r.code AS role, a.status, a.failed_attempts,
                   a.locked_until, a.last_login_at, a.password_changed_at, a.created_at
            FROM admin_user a JOIN role r ON r.id = a.role_id""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AdminAccounts(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Account> find(long id) {
        return jdbc.sql(SELECT + " WHERE a.id = ?")
                .param(id)
                .query(AdminAccounts::map)
                .optional();
    }

    public Optional<Account> findByUsername(String username) {
        return jdbc.sql(SELECT + " WHERE a.username = ?")
                .param(username)
                .query(AdminAccounts::map)
                .optional();
    }

    public List<Account> page(int page, int size) {
        return jdbc.sql(SELECT + " ORDER BY a.id LIMIT ? OFFSET ?")
                .params(size, (long) (page - 1) * size)
                .query(AdminAccounts::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM admin_user").query(Long.class).single();
    }

    /** 新建账号，密码为临时密码（{@code password_changed_at} 为空）；用户名重复时抛 {@code DuplicateKeyException} */
    public long create(String username, String passwordHash, String displayName, AdminRole role) {
        jdbc.sql(
                        """
                        INSERT INTO admin_user (username, password_hash, display_name, role_id)
                        SELECT :username, :hash, :name, id FROM role WHERE code = :role""")
                .param("username", username)
                .param("hash", passwordHash)
                .param("name", displayName)
                .param("role", role.name())
                .update();
        return jdbc.sql("SELECT id FROM admin_user WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
    }

    /**
     * 记一次密码错误：连续错误达到 {@code maxFailures} 时锁定到 {@code lockUntil}。上一次锁定已过期则从 1 重新计数。锁定期间不计（条件不满足，
     * 返回空），锁定不会被延长。
     *
     * @return 更新后的连续错误次数与锁定截止；账号正处于锁定时为空
     */
    public Optional<Account> recordFailure(long id, Instant now, int maxFailures, Instant lockUntil) {
        // MySQL 单表 UPDATE 的赋值自左向右执行，第二项读到的是已加 1 的 failed_attempts
        int updated = jdbc.sql(
                        """
                        UPDATE admin_user
                        SET failed_attempts = IF(locked_until IS NOT NULL AND locked_until <= :now, 1, failed_attempts + 1),
                            locked_until = IF(failed_attempts >= :max, :until, NULL)
                        WHERE id = :id AND (locked_until IS NULL OR locked_until <= :now)""")
                .param("now", toDb(now))
                .param("max", maxFailures)
                .param("until", toDb(lockUntil))
                .param("id", id)
                .update();
        return updated == 0 ? Optional.empty() : find(id);
    }

    public void loginSucceeded(long id, Instant now) {
        jdbc.sql("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL, last_login_at = ? WHERE id = ?")
                .params(toDb(now), id)
                .update();
    }

    public void changePassword(long id, String hash, Instant now) {
        jdbc.sql("UPDATE admin_user SET password_hash = ?, password_changed_at = ? WHERE id = ?")
                .params(hash, toDb(now), id)
                .update();
    }

    /** 重置为临时密码，同时解除锁定 */
    public boolean resetPassword(long id, String hash) {
        return jdbc.sql(
                                """
                        UPDATE admin_user SET password_hash = ?, password_changed_at = NULL, failed_attempts = 0,
                                              locked_until = NULL
                        WHERE id = ?""")
                        .params(hash, id)
                        .update()
                > 0;
    }

    public void update(long id, String displayName, AdminRole role, Status status) {
        jdbc.sql(
                        """
                        UPDATE admin_user a JOIN role r ON r.code = :role
                        SET a.display_name = :name, a.role_id = r.id, a.status = :status
                        WHERE a.id = :id""")
                .param("role", role.name())
                .param("name", displayName)
                .param("status", status.name())
                .param("id", id)
                .update();
    }

    public List<Role> roles() {
        return jdbc.sql("SELECT code, name, permissions FROM role ORDER BY id")
                .query((rs, n) -> new Role(
                        AdminRole.valueOf(rs.getString("code")),
                        rs.getString("name"),
                        permissions(rs.getString("permissions"))))
                .list();
    }

    public List<String> permissions(AdminRole role) {
        return jdbc.sql("SELECT permissions FROM role WHERE code = ?")
                .param(role.name())
                .query(String.class)
                .optional()
                .map(this::permissions)
                .orElse(List.of());
    }

    private List<String> permissions(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("role.permissions 不是字符串数组：" + raw, e);
        }
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                AdminRole.valueOf(rs.getString("role")),
                Status.valueOf(rs.getString("status")),
                rs.getInt("failed_attempts"),
                fromDb(rs.getObject("locked_until", LocalDateTime.class)),
                fromDb(rs.getObject("last_login_at", LocalDateTime.class)),
                fromDb(rs.getObject("password_changed_at", LocalDateTime.class)),
                fromDb(rs.getObject("created_at", LocalDateTime.class)));
    }

    public static LocalDateTime toDb(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, ZONE);
    }

    public static Instant fromDb(LocalDateTime at) {
        return at == null ? null : at.atZone(ZONE).toInstant();
    }
}
