package com.smartparking.dtubench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 时延统计，百分位取最近秩（nearest-rank），样本少时也不插值。 */
public final class Latencies {

    private final List<Long> values = new ArrayList<>();

    public void add(long ms) {
        values.add(ms);
    }

    public int count() {
        return values.size();
    }

    /** @param p 0–100；无样本时为 -1 */
    public long percentile(double p) {
        if (values.isEmpty()) {
            return -1;
        }
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    public long max() {
        return values.isEmpty() ? -1 : Collections.max(values);
    }

    /** {@code p50 / p95 / max}，无样本为 “—” */
    public String summary() {
        return values.isEmpty() ? "—" : percentile(50) + " / " + percentile(95) + " / " + max();
    }
}
