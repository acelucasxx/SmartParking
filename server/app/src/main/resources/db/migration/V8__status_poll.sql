-- 定时状态查询（IOT-10，PRD 8.7）：记录最近一次向设备下发查询的时刻。
-- 多实例按它条件更新“认领”到期设备，同一设备同一周期只查一次；不计入运行态修订号，也不进 Redis。

ALTER TABLE device_runtime
    ADD COLUMN last_poll_at DATETIME(3) NULL COMMENT '最近一次平台主动下发查询的时刻（PRD 8.7）' AFTER last_conn_at;
