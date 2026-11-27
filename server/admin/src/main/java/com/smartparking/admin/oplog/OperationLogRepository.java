package com.smartparking.admin.oplog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.common.web.PageResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code operation_log} 读写 */
public class OperationLogRepository {

    public record Entry(
            long adminId,
            String adminName,
            String action,
            String targetType,
            String targetId,
            String reason,
            Map<String, Object> detail,
            boolean success,
            String ip) {}

    public record LogView(
            long id,
            long adminId,
            String adminName,
            String action,
            String targetType,
            String targetId,
            String reason,
            String result,
            String ip,
            JsonNode detail,
            Instant createdAt) {}

    public record Filter(Long adminId, String action, String targetId, Instant from, Instant to) {}

    private static final int REASON_MAX = 255;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OperationLogRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Entry e) {
        String detail;
        try {
            detail = e.detail() == null || e.detail().isEmpty() ? null : json.writeValueAsString(e.detail());
        } catch (Exception ex) {
            detail = null;
        }
        jdbc.sql(
                        """
                        INSERT INTO operation_log (admin_id, admin_name, action, target_type, target_id, reason, detail, result, ip)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)""")
                .params(
                        e.adminId(),
                        truncate(e.adminName(), 32),
                        e.action(),
                        e.targetType(),
                        truncate(e.targetId(), 64),
                        truncate(e.reason(), REASON_MAX),
                        detail,
                        e.success() ? "SUCCESS" : "FAIL",
                        truncate(e.ip(), 45))
                .update();
    }

    /** 按时间倒序；{@code from} 含、{@code to} 不含 */
    public PageResult<LogView> list(Filter f, int page, int size) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (f.adminId() != null) {
            where.add("admin_id = ?");
            params.add(f.adminId());
        }
        if (f.action() != null && !f.action().isBlank()) {
            where.add("action = ?");
            params.add(f.action().strip());
        }
        if (f.targetId() != null && !f.targetId().isBlank()) {
            where.add("target_id = ?");
            params.add(f.targetId().strip());
        }
        if (f.from() != null) {
            where.add("created_at >= ?");
            params.add(AdminAccounts.toDb(f.from()));
        }
        if (f.to() != null) {
            where.add("created_at < ?");
            params.add(AdminAccounts.toDb(f.to()));
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = jdbc.sql("SELECT COUNT(*) FROM operation_log" + clause)
                .params(params)
                .query(Long.class)
                .single();
        List<Object> paged = new ArrayList<>(params);
        paged.add(size);
        paged.add((long) (page - 1) * size);
        List<LogView> items = jdbc.sql(
                        "SELECT id, admin_id, admin_name, action, target_type, target_id, reason, result, ip, detail, created_at"
                                + " FROM operation_log" + clause
                                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(paged)
                .query((rs, n) -> new LogView(
                        rs.getLong("id"),
                        rs.getLong("admin_id"),
                        rs.getString("admin_name"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("reason"),
                        rs.getString("result"),
                        rs.getString("ip"),
                        parse(rs.getString("detail")),
                        AdminAccounts.fromDb(rs.getObject("created_at", LocalDateTime.class))))
                .list();
        return new PageResult<>(total, page, size, items);
    }

    private JsonNode parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
