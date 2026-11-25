package com.smartparking.iot.runtime.partition;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * {@code device_event} 分区维护的纯计算（不碰数据库，便于单测）。分区方案见 V3：按 {@code TO_DAYS(at)} 月度 RANGE 分区，
 * {@code pYYYYMM} 装该月数据，上界为次月 1 日，{@code p_future} 兜底且必须最后。
 *
 * <ul>
 *   <li>补分区：保证覆盖到“当月 + monthsAhead”，从 {@code p_future} 一次性拆出缺的月份。
 *   <li>删分区：整月都早于“今天 − retention”的分区直接 DROP（元数据操作，秒级）。
 * </ul>
 *
 * <p>参考 WorkBuddy 的 EventPartitions（IOT-01 评估 3.8），兜底分区名改为 {@code p_future}。
 */
public final class EventPartitionPlan {

    public static final String TABLE = "device_event";
    public static final String FUTURE = "p_future";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private EventPartitionPlan() {}

    public static String nameOf(YearMonth month) {
        return "p" + month.format(MONTH);
    }

    /** {@code p202611} → 2026-11；不是月度分区（如 p_future）返回空 */
    public static Optional<YearMonth> monthOf(String partition) {
        if (partition == null || !partition.matches("p\\d{6}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(partition.substring(1), MONTH));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * @param existing 现有分区名
     * @param today 今天（东八区）
     * @param retentionDays 保留天数，PRD 要求 ≥ 180
     * @param monthsAhead 至少提前建好的月份数
     * @return 依次执行的 DDL；无需变更时为空
     */
    public static List<String> plan(List<String> existing, LocalDate today, int retentionDays, int monthsAhead) {
        if (!existing.contains(FUTURE)) {
            throw new IllegalStateException(TABLE + " 缺少兜底分区 " + FUTURE + "，请人工检查分区结构");
        }
        var months = new TreeSet<YearMonth>();
        existing.forEach(p -> monthOf(p).ifPresent(months::add));
        var statements = new ArrayList<String>();

        YearMonth target = YearMonth.from(today).plusMonths(monthsAhead);
        YearMonth next =
                months.isEmpty() ? YearMonth.from(today) : months.last().plusMonths(1);
        if (!next.isAfter(target)) {
            var parts = new StringBuilder();
            for (YearMonth m = next; !m.isAfter(target); m = m.plusMonths(1)) {
                parts.append("PARTITION ")
                        .append(nameOf(m))
                        .append(" VALUES LESS THAN (TO_DAYS('")
                        .append(m.plusMonths(1).atDay(1))
                        .append("')), ");
            }
            statements.add("ALTER TABLE " + TABLE + " REORGANIZE PARTITION " + FUTURE + " INTO (" + parts + "PARTITION "
                    + FUTURE + " VALUES LESS THAN MAXVALUE)");
        }

        LocalDate keepFrom = today.minusDays(retentionDays);
        var expired = months.stream()
                .filter(m -> !m.plusMonths(1).atDay(1).isAfter(keepFrom))
                .map(EventPartitionPlan::nameOf)
                .toList();
        if (!expired.isEmpty()) {
            statements.add("ALTER TABLE " + TABLE + " DROP PARTITION " + String.join(", ", expired));
        }
        return statements;
    }
}
