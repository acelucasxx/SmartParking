package com.smartparking.notify.alarm;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.PageResult;
import com.smartparking.iot.DbTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 告警写入与合并、后台查询与处理（PRD 6.2 告警中心）。
 *
 * <p>同设备同类、尚未处理、最近一次在 30 分钟内的告警合并：刷新 {@code last_at}、累加 {@code count}；已处理的不再合并。
 */
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    public static final Duration MERGE_WINDOW = Duration.ofMinutes(30);
    public static final int NOTE_MAX = 512;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AlarmService(
            JdbcClient jdbc, PlatformTransactionManager txManager, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        // 订阅方在设备上行链路里同步调用：独立事务，失败不影响、也不连带回滚发布方
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.events = events;
        this.clock = clock;
    }

    /** 写入一次告警。设备不存在时忽略并返回空。 */
    public Optional<AlarmRaisedEvent> raise(long deviceId, AlarmType type, Instant at) {
        AlarmRaisedEvent raised = tx.execute(status -> {
            // 锁设备行，把同一设备的并发写入（多实例、多事件）串行化，保证合并窗口内只有一条未处理告警
            boolean exists = jdbc.sql("SELECT id FROM device WHERE id = ? FOR UPDATE")
                    .param(deviceId)
                    .query(Long.class)
                    .optional()
                    .isPresent();
            if (!exists) {
                return null;
            }
            LocalDateTime when = DbTime.toDb(at);
            Optional<Long> open = jdbc.sql(
                            """
                            SELECT id FROM alarm
                            WHERE device_id = :device AND type = :type AND status = 'OPEN' AND last_at >= :since
                            ORDER BY last_at DESC, id DESC LIMIT 1""")
                    .param("device", deviceId)
                    .param("type", type.name())
                    .param("since", DbTime.toDb(at.minus(MERGE_WINDOW)))
                    .query(Long.class)
                    .optional();
            if (open.isPresent()) {
                jdbc.sql("UPDATE alarm SET count = count + 1, last_at = GREATEST(last_at, :at) WHERE id = :id")
                        .param("at", when)
                        .param("id", open.get())
                        .update();
                return new AlarmRaisedEvent(open.get(), deviceId, type, type.level(), true, at);
            }
            jdbc.sql(
                            """
                            INSERT INTO alarm (device_id, type, level, first_at, last_at)
                            VALUES (:device, :type, :level, :at, :at)""")
                    .param("device", deviceId)
                    .param("type", type.name())
                    .param("level", type.level().name())
                    .param("at", when)
                    .update();
            long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
            return new AlarmRaisedEvent(id, deviceId, type, type.level(), false, at);
        });
        if (raised == null) {
            log.warn("设备 {} 不存在，忽略 {} 告警", deviceId, type);
            return Optional.empty();
        }
        if (raised.merged()) {
            log.info("设备 {} {} 告警合并进 #{}", deviceId, type, raised.alarmId());
        } else {
            log.warn("设备 {} 新告警 #{} {}（{}）", deviceId, raised.alarmId(), type, type.level());
        }
        events.publishEvent(raised);
        return Optional.of(raised);
    }

    // ------------------------------------------------------------ 后台

    /** 契约 Alarm */
    public record AlarmView(
            long id,
            long deviceId,
            String sn,
            AlarmType type,
            AlarmLevel level,
            Instant firstAt,
            Instant lastAt,
            int count,
            String status,
            String handlerName,
            Instant handledAt,
            String note) {}

    /** 列表筛选，均可为空 */
    public record Filter(Long deviceId, String sn, AlarmType type, String status) {}

    private static final String SELECT =
            """
            SELECT a.id, a.device_id, d.sn, a.type, a.level, a.first_at, a.last_at, a.count, a.status,
                   u.display_name AS handler_name, a.handled_at, a.note
            FROM alarm a
            JOIN device d ON d.id = a.device_id
            LEFT JOIN admin_user u ON u.id = a.handler_id""";

    /** 按最近发生时间倒序 */
    public PageResult<AlarmView> list(Filter filter, int page, int size) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (filter.deviceId() != null) {
            where.add("a.device_id = ?");
            params.add(filter.deviceId());
        }
        if (filter.sn() != null && !filter.sn().isBlank()) {
            where.add("d.sn = ?");
            params.add(filter.sn().strip().toUpperCase(Locale.ROOT));
        }
        if (filter.type() != null) {
            where.add("a.type = ?");
            params.add(filter.type().name());
        }
        if (filter.status() != null) {
            where.add("a.status = ?");
            params.add(filter.status());
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = jdbc.sql("SELECT COUNT(*) FROM alarm a JOIN device d ON d.id = a.device_id" + clause)
                .params(params)
                .query(Long.class)
                .single();
        List<Object> paged = new ArrayList<>(params);
        paged.add(size);
        paged.add((long) (page - 1) * size);
        List<AlarmView> items = jdbc.sql(SELECT + clause + " ORDER BY a.last_at DESC, a.id DESC LIMIT ? OFFSET ?")
                .params(paged)
                .query(AlarmService::map)
                .list();
        return new PageResult<>(total, page, size, items);
    }

    public Optional<AlarmView> find(long alarmId) {
        return jdbc.sql(SELECT + " WHERE a.id = ?")
                .param(alarmId)
                .query(AlarmService::map)
                .optional();
    }

    private static AlarmView map(ResultSet rs, int rowNum) throws SQLException {
        return new AlarmView(
                rs.getLong("id"),
                rs.getLong("device_id"),
                rs.getString("sn"),
                AlarmType.valueOf(rs.getString("type")),
                AlarmLevel.valueOf(rs.getString("level")),
                DbTime.fromDb(rs.getObject("first_at", LocalDateTime.class)),
                DbTime.fromDb(rs.getObject("last_at", LocalDateTime.class)),
                rs.getInt("count"),
                rs.getString("status"),
                rs.getString("handler_name"),
                DbTime.fromDb(rs.getObject("handled_at", LocalDateTime.class)),
                rs.getString("note"));
    }

    /**
     * 标记已处理。备注去首尾空白后 1–512 个字符，否则 10001；告警不存在 10004；已被处理过 10009（并发处理时只有一人成功）。
     */
    public AlarmView handle(long alarmId, AdminPrincipal admin, String note) {
        String text = note == null ? "" : note.strip();
        if (text.isEmpty() || text.length() > NOTE_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "处理备注为 1–" + NOTE_MAX + " 个字符");
        }
        int updated = jdbc.sql(
                        """
                        UPDATE alarm SET status = 'HANDLED', handler_id = :admin, handled_at = :at, note = :note
                        WHERE id = :id AND status = 'OPEN'""")
                .param("admin", admin.adminId())
                .param("at", DbTime.toDb(clock.instant()))
                .param("note", text)
                .param("id", alarmId)
                .update();
        AlarmView view = find(alarmId).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "告警不存在"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该告警已被处理");
        }
        log.info("告警 #{} 由 {}（{}）标记处理", alarmId, admin.displayName(), admin.adminId());
        return view;
    }
}
