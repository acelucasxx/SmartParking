package com.smartparking.admin.query;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.iot.DbTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** 查询用的时间工具：库内东八区 DATETIME(3) 与 {@link Instant} 互转、时间范围校验 */
final class Times {

    private Times() {}

    static Instant read(ResultSet rs, String column) throws SQLException {
        return DbTime.fromDb(rs.getObject(column, LocalDateTime.class));
    }

    static LocalDateTime db(OffsetDateTime at) {
        return at == null ? null : DbTime.toDb(at.toInstant());
    }

    /** {@code from} 含、{@code to} 不含；两者都给时 from 必须早于 to，否则 10001 */
    static void checkRange(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "起始时间须早于结束时间");
        }
    }

    static String nickname(String raw) {
        return raw == null || raw.isBlank() ? "微信用户" : raw;
    }
}
