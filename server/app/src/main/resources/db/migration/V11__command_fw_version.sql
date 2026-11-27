-- 后台自检（BIZ-10）：查询版本成功时记录读到的闸板版本，指令审计据此显示 fwVersion。
ALTER TABLE control_command
    ADD COLUMN fw_version VARCHAR(32) NULL COMMENT 'QUERY_VERSION 成功时读到的闸板版本' AFTER raw_frame;
