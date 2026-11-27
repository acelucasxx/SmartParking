package com.smartparking.device.imports;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** {@code import_task} 的读写。时间按库内东八区原样读出，由接口层加偏移 */
public class ImportTaskRepository {

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED
    }

    public record Task(
            long id,
            String fileName,
            Status status,
            int totalRows,
            int successRows,
            int failedRows,
            String errorMessage,
            LocalDateTime printExportedAt,
            String operatorName,
            LocalDateTime createdAt,
            LocalDateTime finishedAt) {}

    private static final String SELECT =
            """
            SELECT t.id, t.file_name, t.status, t.total_rows, t.success_rows, t.failed_rows, t.error_message,
                   t.print_exported_at, COALESCE(a.display_name, CONCAT('#', t.operator_id)) AS operator_name,
                   t.created_at, t.finished_at
              FROM import_task t
              LEFT JOIN admin_user a ON a.id = t.operator_id
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ImportTaskRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public long create(String fileName, int totalRows, long operatorId) {
        var keys = new GeneratedKeyHolder();
        template.update(
                con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO import_task (file_name, total_rows, operator_id) VALUES (?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, fileName);
                    ps.setInt(2, totalRows);
                    ps.setLong(3, operatorId);
                    return ps;
                },
                keys);
        return keys.getKey().longValue();
    }

    public Optional<Task> find(long id) {
        return jdbc.sql(SELECT + " WHERE t.id = ?")
                .param(id)
                .query(ImportTaskRepository::map)
                .optional();
    }

    public List<Task> page(int page, int size) {
        return jdbc.sql(SELECT + " ORDER BY t.id DESC LIMIT ? OFFSET ?")
                .params(size, (long) (page - 1) * size)
                .query(ImportTaskRepository::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM import_task").query(Long.class).single();
    }

    public String failedDetail(long id) {
        return jdbc.sql("SELECT failed_detail FROM import_task WHERE id = ?")
                .param(id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** PENDING → RUNNING；返回是否由本次调用推进 */
    public boolean markRunning(long id) {
        return jdbc.sql("UPDATE import_task SET status = 'RUNNING' WHERE id = ? AND status = 'PENDING'")
                        .param(id)
                        .update()
                == 1;
    }

    /** RUNNING → DONE；返回是否推进（任务已被记为失败时为 false） */
    public boolean finish(long id, int success, int failed, String failedDetailJson, byte[] printPayload) {
        return jdbc.sql(
                                """
                        UPDATE import_task
                           SET status = 'DONE', success_rows = ?, failed_rows = ?, failed_detail = ?,
                               print_payload = ?, finished_at = CURRENT_TIMESTAMP(3)
                         WHERE id = ? AND status = 'RUNNING'""")
                        .params(success, failed, failedDetailJson, printPayload, id)
                        .update()
                == 1;
    }

    public void fail(long id, String message) {
        jdbc.sql(
                        """
                        UPDATE import_task SET status = 'FAILED', error_message = ?, finished_at = CURRENT_TIMESTAMP(3)
                         WHERE id = ? AND status IN ('PENDING', 'RUNNING')""")
                .params(message, id)
                .update();
    }

    /** 创建超过 {@code staleMinutes} 分钟仍未完成的任务记为失败（处理它的实例已退出）；返回条数 */
    public int failStale(int staleMinutes, String message) {
        return jdbc.sql(
                        """
                        UPDATE import_task SET status = 'FAILED', error_message = ?, finished_at = CURRENT_TIMESTAMP(3)
                         WHERE status IN ('PENDING', 'RUNNING')
                           AND created_at < CURRENT_TIMESTAMP(3) - INTERVAL ? MINUTE""")
                .params(message, staleMinutes)
                .update();
    }

    /** 印刷导出用：加行锁读取，须在事务内 */
    public record PrintState(Status status, LocalDateTime exportedAt, byte[] payload, int successRows) {}

    public Optional<PrintState> lockForPrint(long id) {
        return jdbc.sql(
                        "SELECT status, print_exported_at, print_payload, success_rows FROM import_task WHERE id = ? FOR UPDATE")
                .param(id)
                .query((rs, n) -> new PrintState(
                        Status.valueOf(rs.getString(1)),
                        rs.getObject(2, LocalDateTime.class),
                        rs.getBytes(3),
                        rs.getInt(4)))
                .optional();
    }

    public void markPrintExported(long id) {
        jdbc.sql("UPDATE import_task SET print_exported_at = CURRENT_TIMESTAMP(3), print_payload = NULL WHERE id = ?")
                .param(id)
                .update();
    }

    private static Task map(ResultSet rs, int row) throws SQLException {
        return new Task(
                rs.getLong("id"),
                rs.getString("file_name"),
                Status.valueOf(rs.getString("status")),
                rs.getInt("total_rows"),
                rs.getInt("success_rows"),
                rs.getInt("failed_rows"),
                rs.getString("error_message"),
                rs.getObject("print_exported_at", LocalDateTime.class),
                rs.getString("operator_name"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }
}
