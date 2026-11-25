-- 运行态修订号（IOT-06）：device_runtime 每次更新 +1，Redis 热数据只接受修订号更大的写入，
-- 避免并发回写时旧快照覆盖新状态。

ALTER TABLE device_runtime
    ADD COLUMN rev BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '修订号，每次更新 +1；Redis 缓存按它判断新旧' AFTER last_conn_at;
