package com.smartparking.admin.query;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.PageResult;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.codec.Hex;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 指令审计（PRD 6.2、9：每条控制指令记录操作人、角色、通道、结果、原始帧）：筛选、详情与导出 */
public class CommandAudits {

    public static final Duration EXPORT_MAX_SPAN = Duration.ofDays(31);
    public static final int EXPORT_MAX_ROWS = 100_000;
    private static final DateTimeFormatter EXCEL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final JdbcClient jdbc;
    private final Phones phones;
    private final int exportMaxRows;

    CommandAudits(JdbcClient jdbc, Phones phones, int exportMaxRows) {
        this.jdbc = jdbc;
        this.phones = phones;
        this.exportMaxRows = exportMaxRows;
    }

    public record Operator(String type, long id, String name, String phoneMasked) {}

    /** 契约 CommandAudit；fwVersion 只在后台自检查询版本成功时有值 */
    public record Audit(
            long id,
            long deviceId,
            String sn,
            Operator operator,
            String role,
            String channel,
            String action,
            String status,
            String resultCode,
            String fwVersion,
            String downFrame,
            String rawFrame,
            Instant issuedAt,
            Instant finishedAt,
            Integer durationMs) {}

    /**
     * 筛选，均可为空。SN 精确匹配（不区分大小写）；手机号为操作人（小程序用户）手机号完整 11 位精确匹配；时间按下发时刻，from 含、to 不含。
     */
    public record Filter(
            Long deviceId,
            String sn,
            String phone,
            String channel,
            String status,
            OffsetDateTime from,
            OffsetDateTime to) {}

    private static final String FROM = " "
            + """
             FROM control_command c
             JOIN device d ON d.id = c.device_id
             LEFT JOIN app_user u ON u.id = c.user_id
             LEFT JOIN admin_user a ON a.id = c.admin_id""";

    private static final String SELECT =
            """
            SELECT c.id, c.device_id, d.sn, c.user_id, u.nickname, u.phone_cipher, c.admin_id, a.display_name,
                   c.role, c.channel, c.action, c.status, c.result_code, c.down_frame, c.raw_frame, c.fw_version,
                   c.issued_at, c.finished_at, c.duration_ms"""
                    + FROM;

    public PageResult<Audit> list(Filter f, int page, int size) {
        Where w = where(f);
        long total = count(w);
        List<Audit> items = jdbc.sql(SELECT + w.sql() + " ORDER BY c.issued_at DESC, c.id DESC LIMIT ? OFFSET ?")
                .params(w.paged(page, size))
                .query(this::map)
                .list();
        return new PageResult<>(total, page, size, items);
    }

    public Audit get(long commandId) {
        return jdbc.sql(SELECT + " WHERE c.id = ?")
                .param(commandId)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "指令不存在"));
    }

    /**
     * 导出为 xlsx（流式写出）。from、to 必填且跨度不超过 31 天，结果不超过 10 万行，否则 10001；校验在写出任何字节之前完成。
     *
     * @return 写出前调用 {@code beforeWrite}，此后的失败无法再返回 JSON 错误
     */
    public void export(Filter f, OutputStream out, Consumer<Long> beforeWrite) throws IOException {
        if (f.from() == null || f.to() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导出必须指定起止时间");
        }
        Times.checkRange(f.from(), f.to());
        if (Duration.between(f.from(), f.to()).compareTo(EXPORT_MAX_SPAN) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导出时间跨度不能超过 31 天");
        }
        Where w = where(f);
        long total = count(w);
        if (total > exportMaxRows) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导出结果 " + total + " 行，超过上限 " + exportMaxRows + " 行，请缩小范围");
        }
        beforeWrite.accept(total);
        Workbook wb = new Workbook(out, "SmartParking", "1.0");
        Worksheet ws = wb.newWorksheet("指令流水");
        String[] header = {
            "指令ID", "SN", "操作人类型", "操作人", "手机号", "角色", "通道", "动作", "状态", "结果码", "下发时间", "完成时间", "耗时(ms)", "下行帧", "上行帧"
        };
        for (int i = 0; i < header.length; i++) {
            ws.value(0, i, header[i]);
        }
        int[] r = {1};
        jdbc.sql(SELECT + w.sql() + " ORDER BY c.issued_at, c.id")
                .params(w.params())
                .query(rs -> {
                    Audit a = map(rs, r[0]);
                    int row = r[0]++;
                    ws.value(row, 0, a.id());
                    ws.value(row, 1, a.sn());
                    ws.value(row, 2, a.operator().type());
                    ws.value(row, 3, a.operator().name());
                    ws.value(row, 4, a.operator().phoneMasked());
                    ws.value(row, 5, a.role());
                    ws.value(row, 6, a.channel());
                    ws.value(row, 7, a.action());
                    ws.value(row, 8, a.status());
                    ws.value(row, 9, a.resultCode());
                    ws.value(row, 10, excelTime(a.issuedAt()));
                    ws.value(row, 11, excelTime(a.finishedAt()));
                    if (a.durationMs() != null) {
                        ws.value(row, 12, a.durationMs());
                    }
                    ws.value(row, 13, a.downFrame());
                    ws.value(row, 14, a.rawFrame());
                    if (row % 1000 == 0) {
                        try {
                            ws.flush();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
        wb.finish();
    }

    private Where where(Filter f) {
        Times.checkRange(f.from(), f.to());
        return new Where()
                .and("c.device_id = ?", f.deviceId())
                .and(
                        "d.sn = ?",
                        f.sn() == null || f.sn().isBlank()
                                ? null
                                : f.sn().strip().toUpperCase(Locale.ROOT))
                .and("u.phone_hash = ?", phones.hashOf(f.phone()))
                .and("c.channel = ?", f.channel())
                .and("c.status = ?", f.status())
                .and("c.issued_at >= ?", Times.db(f.from()))
                .and("c.issued_at < ?", Times.db(f.to()));
    }

    private long count(Where w) {
        return jdbc.sql("SELECT COUNT(*)" + FROM + w.sql())
                .params(w.params())
                .query(Long.class)
                .single();
    }

    private Audit map(ResultSet rs, int n) throws SQLException {
        Long userId = rs.getObject("user_id", Long.class);
        Operator operator = userId != null
                ? new Operator(
                        "USER",
                        userId,
                        Times.nickname(rs.getString("nickname")),
                        phones.masked(rs.getBytes("phone_cipher")))
                : new Operator(
                        "ADMIN",
                        rs.getLong("admin_id"),
                        rs.getString("display_name") == null
                                ? "后台账号 " + rs.getLong("admin_id")
                                : rs.getString("display_name"),
                        null);
        byte[] down = rs.getBytes("down_frame");
        byte[] raw = rs.getBytes("raw_frame");
        return new Audit(
                rs.getLong("id"),
                rs.getLong("device_id"),
                rs.getString("sn"),
                operator,
                rs.getString("role"),
                rs.getString("channel"),
                rs.getString("action"),
                rs.getString("status"),
                rs.getString("result_code"),
                rs.getString("fw_version"),
                down == null ? null : Hex.format(down),
                raw == null ? null : Hex.format(raw),
                Times.read(rs, "issued_at"),
                Times.read(rs, "finished_at"),
                AdminDeviceQueries.integer(rs, "duration_ms"));
    }

    private static String excelTime(Instant at) {
        return at == null ? null : EXCEL_TIME.format(at.atZone(DbTime.ZONE));
    }
}
