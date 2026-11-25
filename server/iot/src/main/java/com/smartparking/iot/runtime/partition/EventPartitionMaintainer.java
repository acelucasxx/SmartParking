package com.smartparking.iot.runtime.partition;

import com.smartparking.iot.DbTime;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天 03:10 与应用启动时维护 {@code device_event} 分区。多实例部署时用 MySQL 命名锁保证同一时刻只有一个实例执行 DDL。
 */
@Component
public class EventPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(EventPartitionMaintainer.class);
    private static final String LOCK = "smartparking.device_event.partitions";

    private final JdbcClient jdbc;
    private final EventPartitionProperties props;
    private final Clock clock;

    @Autowired
    public EventPartitionMaintainer(JdbcClient jdbc, EventPartitionProperties props) {
        this(jdbc, props, Clock.system(DbTime.ZONE));
    }

    EventPartitionMaintainer(JdbcClient jdbc, EventPartitionProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        maintain();
    }

    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Shanghai")
    public void daily() {
        maintain();
    }

    /** @return 执行的 DDL；未启用、未拿到锁或出错时为空 */
    public List<String> maintain() {
        if (!props.enabled()) {
            return List.of();
        }
        try {
            Integer locked = jdbc.sql("SELECT GET_LOCK(?, 0)")
                    .param(LOCK)
                    .query(Integer.class)
                    .single();
            if (locked == null || locked != 1) {
                log.info("{} 分区维护正由其他实例执行，跳过", EventPartitionPlan.TABLE);
                return List.of();
            }
            try {
                List<String> existing = jdbc.sql(
                                """
                                SELECT partition_name FROM information_schema.partitions
                                WHERE table_schema = DATABASE() AND table_name = ? ORDER BY partition_ordinal_position""")
                        .param(EventPartitionPlan.TABLE)
                        .query(String.class)
                        .list();
                List<String> ddl = EventPartitionPlan.plan(
                        existing, LocalDate.now(clock), props.retentionDays(), props.monthsAhead());
                for (String statement : ddl) {
                    log.info("维护 {} 分区：{}", EventPartitionPlan.TABLE, statement);
                    jdbc.sql(statement).update();
                }
                return ddl;
            } finally {
                jdbc.sql("SELECT RELEASE_LOCK(?)")
                        .param(LOCK)
                        .query(Integer.class)
                        .optional();
            }
        } catch (RuntimeException e) {
            // 定时任务抛异常不会影响下一次调度，但这里记清楚原因，避免分区耗尽后数据全挤进 p_future
            log.error("维护 {} 分区失败", EventPartitionPlan.TABLE, e);
            return List.of();
        }
    }
}
