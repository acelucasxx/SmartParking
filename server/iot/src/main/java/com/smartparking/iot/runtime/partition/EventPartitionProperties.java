package com.smartparking.iot.runtime.partition;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code device_event} 分区维护，前缀 {@code smartparking.device-event.partitions}。
 *
 * @param enabled 关闭后不自动建 / 删分区
 * @param retentionDays 保留天数，PRD 要求 ≥ 180
 * @param monthsAhead 至少提前建好的月份数
 */
@ConfigurationProperties("smartparking.device-event.partitions")
public record EventPartitionProperties(Boolean enabled, Integer retentionDays, Integer monthsAhead) {

    public EventPartitionProperties {
        enabled = enabled == null || enabled;
        retentionDays = retentionDays == null ? 186 : retentionDays;
        monthsAhead = monthsAhead == null ? 3 : monthsAhead;
        if (retentionDays < 180) {
            throw new IllegalArgumentException("device_event 保留期不得少于 180 天（PRD 9），当前 " + retentionDays);
        }
        if (monthsAhead < 1) {
            throw new IllegalArgumentException("months-ahead 至少为 1");
        }
    }
}
