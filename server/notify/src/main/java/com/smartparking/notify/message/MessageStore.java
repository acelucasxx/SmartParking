package com.smartparking.notify.message;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.PageResult;
import com.smartparking.iot.DbTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code app_message} 读写：写入、列表、未读数、标记已读 */
public class MessageStore {

    static final int TITLE_MAX = 64;
    static final int CONTENT_MAX = 512;
    public static final int READ_IDS_MAX = 100;

    private final JdbcClient jdbc;
    private final Clock clock;

    public MessageStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void write(long userId, MessageType type, Long deviceId, String title, String content) {
        jdbc.sql(
                        "INSERT INTO app_message (user_id, type, device_id, title, content, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(
                        userId,
                        type.name(),
                        deviceId,
                        cut(title, TITLE_MAX),
                        cut(content, CONTENT_MAX),
                        DbTime.toDb(clock.instant()))
                .update();
    }

    /** 契约 Message */
    public record Message(
            long id, MessageType type, Long deviceId, String title, String content, boolean read, Instant createdAt) {}

    /** 最新的在前 */
    public PageResult<Message> list(long userId, int page, int size) {
        long total = jdbc.sql("SELECT COUNT(*) FROM app_message WHERE user_id = ?")
                .param(userId)
                .query(Long.class)
                .single();
        List<Message> items = jdbc.sql(
                        """
                        SELECT id, type, device_id, title, content, read_at, created_at FROM app_message
                        WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""")
                .params(userId, size, (long) (page - 1) * size)
                .query((rs, n) -> new Message(
                        rs.getLong("id"),
                        MessageType.valueOf(rs.getString("type")),
                        rs.getObject("device_id", Long.class),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getObject("read_at") != null,
                        DbTime.fromDb(rs.getObject("created_at", LocalDateTime.class))))
                .list();
        return new PageResult<>(total, page, size, items);
    }

    public int unread(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM app_message WHERE user_id = ? AND read_at IS NULL")
                .param(userId)
                .query(Integer.class)
                .single();
    }

    /**
     * 标记已读：{@code all} 为 true 时全部；否则 {@code ids} 指定的（1–100 条，只处理本人的，他人的静默忽略）。两者都没给 10001。
     *
     * @return 本次由未读变为已读的条数
     */
    public int markRead(long userId, List<Long> ids, boolean all) {
        LocalDateTime now = DbTime.toDb(clock.instant());
        if (all) {
            return jdbc.sql("UPDATE app_message SET read_at = ? WHERE user_id = ? AND read_at IS NULL")
                    .params(now, userId)
                    .update();
        }
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请传 ids 或 all = true");
        }
        if (ids.size() > READ_IDS_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ids 一次至多 " + READ_IDS_MAX + " 条");
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ids 不合法");
        }
        List<Object> params = new ArrayList<>(List.of(now, userId));
        params.addAll(ids);
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        return jdbc.sql("UPDATE app_message SET read_at = ? WHERE user_id = ? AND read_at IS NULL AND id IN (" + marks
                        + ")")
                .params(params)
                .update();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }
}
