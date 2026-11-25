package com.smartparking.common.config;

/**
 * 后台可配的业务参数（{@code system_config} 表，PRD 7 系统设置）。由后台域实现并缓存，其他模块只通过本接口读取。
 *
 * <p>读取失败或值不合法时返回默认值，不抛异常：参数缺失不应阻断设备控制等主链路。
 */
public interface SystemSettings {

    int getInt(String key, int defaultValue);

    /** 键名，与 V6 种子数据一致 */
    final class Keys {
        public static final String COMMAND_TIMEOUT_SECONDS = "command.timeout_seconds";
        public static final String ONLINE_SLEEP_MINUTES = "online.sleep_minutes";
        public static final String ONLINE_OFFLINE_HOURS = "online.offline_hours";
        public static final String STATUS_POLL_INTERVAL_MINUTES = "status_poll.interval_minutes";
        public static final String MEMBER_MAX_PER_DEVICE = "member.max_per_device";
        public static final String CARD_TTL_HOURS = "card.ttl_hours";

        private Keys() {}
    }
}
