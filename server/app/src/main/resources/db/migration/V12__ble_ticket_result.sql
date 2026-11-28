-- 蓝牙票据（BIZ-17，PRD 5.4）：签发时记下动作与角色，上报时记下结果。一张票据对应一次尝试，只上报一次。
-- 表在一期上线前为空，NOT NULL 列不需要默认值。

ALTER TABLE ble_ticket
    ADD COLUMN action      VARCHAR(16) NOT NULL COMMENT 'OPEN 抬杆 / CLOSE 落杆' CHECK (action IN ('OPEN', 'CLOSE')) AFTER user_id,
    ADD COLUMN role        VARCHAR(16) NOT NULL COMMENT '签发时的角色 OWNER / MEMBER' CHECK (role IN ('OWNER', 'MEMBER')) AFTER action,
    ADD COLUMN result      VARCHAR(16) NULL     COMMENT '上报结果 SUCCESS / FAILED' CHECK (result IN ('SUCCESS', 'FAILED')),
    ADD COLUMN fail_reason VARCHAR(16) NULL     COMMENT 'BLE_OFF / NOT_FOUND / CONNECT_FAILED / TICKET_REJECTED / DEVICE_ERROR'
        CHECK (fail_reason IN ('BLE_OFF', 'NOT_FOUND', 'CONNECT_FAILED', 'TICKET_REJECTED', 'DEVICE_ERROR')),
    ADD COLUMN reported_at DATETIME(3) NULL     COMMENT '服务端收到上报的时刻（补报时晚于 used_at）',
    ADD COLUMN command_id  BIGINT UNSIGNED NULL COMMENT '写入操作记录的指令；未到达设备的失败为空',
    ADD KEY idx_ble_ticket_user (user_id, created_at);
