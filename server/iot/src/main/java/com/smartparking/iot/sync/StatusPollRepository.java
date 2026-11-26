package com.smartparking.iot.sync;

import com.smartparking.iot.DbTime;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code device_runtime.last_poll_at} 的读写：挑出到期设备、按条件更新认领。 */
@Repository
class StatusPollRepository {

    private final JdbcClient jdbc;

    StatusPollRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 到期设备：MQTT 连接中，且 {@code dueBefore} 之后既没有上行、也没有下发过查询。按设备 ID 升序。
     *
     * @param afterDeviceId 上一页最后一台设备的 ID，首页传 0
     */
    List<Long> findDue(Instant dueBefore, long afterDeviceId, int limit) {
        return jdbc.sql(
                        """
                        SELECT device_id FROM device_runtime
                        WHERE device_id > :after
                          AND last_conn_event = 'CONNECTED'
                          AND (last_seen_at IS NULL OR last_seen_at < :due)
                          AND (last_poll_at IS NULL OR last_poll_at < :due)
                        ORDER BY device_id
                        LIMIT :limit""")
                .param("after", afterDeviceId)
                .param("due", DbTime.toDb(dueBefore))
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /**
     * 认领一次定时查询：仍满足到期条件才写入 {@code last_poll_at}。多实例并发时只有一方成功。
     *
     * @return 是否认领成功
     */
    boolean claim(long deviceId, Instant dueBefore, Instant now) {
        return jdbc.sql(
                                """
                        UPDATE device_runtime SET last_poll_at = :now
                        WHERE device_id = :id
                          AND last_conn_event = 'CONNECTED'
                          AND (last_seen_at IS NULL OR last_seen_at < :due)
                          AND (last_poll_at IS NULL OR last_poll_at < :due)""")
                        .param("now", DbTime.toDb(now))
                        .param("id", deviceId)
                        .param("due", DbTime.toDb(dueBefore))
                        .update()
                > 0;
    }

    /** 事件触发的查询（连接、绑定）也记下时刻，避免紧接着又被定时查询重复下发 */
    void markPolled(long deviceId, Instant now) {
        jdbc.sql("UPDATE device_runtime SET last_poll_at = :now WHERE device_id = :id")
                .param("now", DbTime.toDb(now))
                .param("id", deviceId)
                .update();
    }
}
