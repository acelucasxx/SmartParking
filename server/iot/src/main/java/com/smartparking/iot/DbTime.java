package com.smartparking.iot;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 库内时间统一按东八区存 DATETIME(3)（docs/db/conventions.md），这里负责与 {@link Instant} 互转。 */
public final class DbTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private DbTime() {}

    public static LocalDateTime toDb(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }

    public static Instant fromDb(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toInstant();
    }
}
