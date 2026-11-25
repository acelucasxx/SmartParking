package com.smartparking.iot.runtime.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventPartitionPlanTest {

    /** V3 建出的初始分区：2026-11 到 2027-12 + p_future */
    private static List<String> initial() {
        var names = new ArrayList<String>();
        for (YearMonth m = YearMonth.of(2026, 11); !m.isAfter(YearMonth.of(2027, 12)); m = m.plusMonths(1)) {
            names.add(EventPartitionPlan.nameOf(m));
        }
        names.add("p_future");
        return names;
    }

    @Test
    void noChangeWhileCoveredAndNothingExpired() {
        assertThat(EventPartitionPlan.plan(initial(), LocalDate.of(2026, 11, 26), 186, 3))
                .isEmpty();
    }

    @Test
    void addsMissingMonthsFromFutureInOneStatement() {
        var ddl = EventPartitionPlan.plan(initial(), LocalDate.of(2027, 10, 15), 400, 3);
        assertThat(ddl)
                .containsExactly("ALTER TABLE device_event REORGANIZE PARTITION p_future INTO ("
                        + "PARTITION p202801 VALUES LESS THAN (TO_DAYS('2028-02-01')), "
                        + "PARTITION p_future VALUES LESS THAN MAXVALUE)");
    }

    @Test
    void crossesYearBoundary() {
        var ddl = EventPartitionPlan.plan(List.of("p202711", "p_future"), LocalDate.of(2027, 11, 3), 186, 2);
        assertThat(ddl.get(0))
                .contains("PARTITION p202712 VALUES LESS THAN (TO_DAYS('2028-01-01'))")
                .contains("PARTITION p202801 VALUES LESS THAN (TO_DAYS('2028-02-01'))");
    }

    @Test
    void dropsMonthsEntirelyOlderThanRetention() {
        // 2027-06-04 往前 186 天是 2026-11-30：p202611 的上界 2026-12-01 晚于它，仍保留
        assertThat(EventPartitionPlan.plan(initial(), LocalDate.of(2027, 6, 4), 186, 3))
                .isEmpty();
        // 2027-06-05 往前 186 天是 2026-12-01：p202611 整月都已超过保留期
        assertThat(EventPartitionPlan.plan(initial(), LocalDate.of(2027, 6, 5), 186, 3))
                .containsExactly("ALTER TABLE device_event DROP PARTITION p202611");
    }

    @Test
    void neverDropsFuturePartition() {
        var ddl = EventPartitionPlan.plan(List.of("p202611", "p_future"), LocalDate.of(2030, 1, 1), 186, 1);
        assertThat(ddl).hasSize(2);
        assertThat(ddl.get(1)).isEqualTo("ALTER TABLE device_event DROP PARTITION p202611");
    }

    @Test
    void requiresFuturePartition() {
        assertThatThrownBy(() -> EventPartitionPlan.plan(List.of("p202611"), LocalDate.of(2026, 11, 1), 186, 3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesMonthlyNamesOnly() {
        assertThat(EventPartitionPlan.monthOf("p202611")).contains(YearMonth.of(2026, 11));
        assertThat(EventPartitionPlan.monthOf("p_future")).isEmpty();
        assertThat(EventPartitionPlan.monthOf("p202613")).isEmpty();
        assertThat(EventPartitionPlan.monthOf(null)).isEmpty();
    }

    @Test
    void propertiesEnforceMinimumRetention() {
        assertThat(new EventPartitionProperties(null, null, null).retentionDays())
                .isEqualTo(186);
        assertThatThrownBy(() -> new EventPartitionProperties(true, 90, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventPartitionProperties(true, 186, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
