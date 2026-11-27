package com.smartparking.member.invite;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * 授权有效期（契约 Period，PRD 5.5②）。
 *
 * <ul>
 *   <li>{@code PERMANENT} 永久。
 *   <li>{@code RELATIVE} 从接受（或修改）时起算 1 / 7 / 30 天。
 *   <li>{@code CUSTOM} 自定义起止，按分钟截断；止须晚于起，且晚于当前时间。
 * </ul>
 *
 * 不合法一律 30011。
 */
public record Period(Type type, Integer durationDays, Instant validFrom, Instant validTo) {

    public enum Type {
        PERMANENT,
        RELATIVE,
        CUSTOM
    }

    static final Set<Integer> DAYS = Set.of(1, 7, 30);

    /** 校验并规整请求里的有效期 */
    public static Period of(String type, Integer durationDays, OffsetDateTime from, OffsetDateTime to, Instant now) {
        Type t;
        try {
            t = type == null ? null : Type.valueOf(type);
        } catch (IllegalArgumentException e) {
            t = null;
        }
        if (t == null) {
            throw invalid("有效期类型须为 PERMANENT、RELATIVE 或 CUSTOM");
        }
        return switch (t) {
            case PERMANENT -> new Period(t, null, null, null);
            case RELATIVE -> {
                if (durationDays == null || !DAYS.contains(durationDays)) {
                    throw invalid("有效天数须为 1、7 或 30");
                }
                yield new Period(t, durationDays, null, null);
            }
            case CUSTOM -> {
                if (from == null || to == null) {
                    throw invalid("自定义有效期须填写起止时间");
                }
                Instant f = from.toInstant().truncatedTo(ChronoUnit.MINUTES);
                Instant e = to.toInstant().truncatedTo(ChronoUnit.MINUTES);
                if (!e.isAfter(f)) {
                    throw invalid("结束时间须晚于开始时间");
                }
                if (!e.isAfter(now)) {
                    throw invalid("结束时间须晚于当前时间");
                }
                yield new Period(t, null, f, e);
            }
        };
    }

    /** 存库：RELATIVE 存分钟数 */
    Integer durationMinutes() {
        return durationDays == null ? null : (int) Duration.ofDays(durationDays).toMinutes();
    }

    /** 从库里还原 */
    public static Period stored(String type, Integer durationMinutes, Instant from, Instant to) {
        Type t = Type.valueOf(type);
        Integer days = durationMinutes == null
                ? null
                : (int) Duration.ofMinutes(durationMinutes).toDays();
        return new Period(t, days, from, to);
    }

    /** 接受时落到 {@code device_member} 的起止；CUSTOM 的起早于当前时仍按原值（立即生效） */
    public Instant[] resolve(Instant acceptedAt) {
        return switch (type) {
            case PERMANENT -> new Instant[] {acceptedAt, null};
            case RELATIVE -> new Instant[] {acceptedAt, acceptedAt.plus(Duration.ofDays(durationDays))};
            case CUSTOM -> new Instant[] {validFrom, validTo};
        };
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_PERIOD, message);
    }
}
