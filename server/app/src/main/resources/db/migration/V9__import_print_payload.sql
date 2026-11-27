-- 设备入库（BIZ-03）：印刷文件导出前暂存激活码明文的密文，导出即清空（PRD 5.1④“激活码仅在该次导出中可见”）。
-- 内容为 AES-256-GCM 加密的 JSON [{sn, code, model, batchNo}]，密钥由 smartparking.device.activate-code-key 派生。
ALTER TABLE import_task
    ADD COLUMN print_payload MEDIUMBLOB NULL COMMENT '待导出的印刷数据（加密）；导出后置空' AFTER print_exported_at;
