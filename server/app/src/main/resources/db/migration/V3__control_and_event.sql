-- 控制域：指令、设备事件、蓝牙票据（PRD 5.3、5.4、8.4、8.7、9）

CREATE TABLE control_command (
    id              BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    device_id       BIGINT UNSIGNED   NOT NULL,
    user_id         BIGINT UNSIGNED   NULL     COMMENT '小程序发起人；后台自检时为空',
    admin_id        BIGINT UNSIGNED   NULL     COMMENT '后台自检发起人',
    role            VARCHAR(16)       NOT NULL COMMENT 'OWNER / MEMBER / ADMIN'
                                      CHECK (role IN ('OWNER', 'MEMBER', 'ADMIN')),
    channel         VARCHAR(16)       NOT NULL COMMENT 'REMOTE 远程 / BLE 蓝牙'
                                      CHECK (channel IN ('REMOTE', 'BLE')),
    action          VARCHAR(16)       NOT NULL COMMENT 'OPEN 抬杆 / CLOSE 落杆 / QUERY_STATUS / QUERY_VERSION（后台自检）'
                                      CHECK (action IN ('OPEN', 'CLOSE', 'QUERY_STATUS', 'QUERY_VERSION')),
    status          VARCHAR(16)       NOT NULL DEFAULT 'PENDING'
                                      COMMENT 'PENDING 待下发 / SENT 已下发 / EXECUTING 执行中 / SUCCESS / FAILED / TIMEOUT'
                                      CHECK (status IN ('PENDING', 'SENT', 'EXECUTING', 'SUCCESS', 'FAILED', 'TIMEOUT')),
    result_code     VARCHAR(32)       NULL     COMMENT '如 ALREADY_IN_TARGET、MOTOR_FAULT、OBSTRUCTED、INFRARED、OFFLINE',
    down_frame      VARBINARY(16)     NULL     COMMENT '下行 RS485 原始帧',
    raw_frame       VARBINARY(16)     NULL     COMMENT '判定结果的上行 RS485 原始帧',
    ble_ticket_id   CHAR(32)          NULL     COMMENT '蓝牙通道对应的票据',
    idempotency_key VARCHAR(64)       NULL     COMMENT '客户端幂等键，连点只产生一条指令',
    issued_at       DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at     DATETIME(3)       NULL,
    duration_ms     INT UNSIGNED      NULL,
    inflight_flag   TINYINT AS (IF(status IN ('PENDING', 'SENT', 'EXECUTING'), 1, NULL)) STORED,
    updated_at      DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_command_idempotency (device_id, idempotency_key),
    UNIQUE KEY uk_command_inflight (device_id, inflight_flag) COMMENT '设备级串行：同一设备同时最多一条未终结指令',
    KEY idx_command_device (device_id, issued_at),
    KEY idx_command_user (user_id, issued_at),
    KEY idx_command_reconcile (status, issued_at),
    CHECK (user_id IS NOT NULL OR admin_id IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '控制指令全量审计；平台定时查询不入此表';

-- 按月 RANGE 分区。分区表要求主键与唯一键包含分区列，且不支持外键。
-- 初始分区覆盖到 2027-12，p_future 兜底；后续分区由定时任务从 p_future 拆出，超过保留期（≥180 天）的分区整体删除。
CREATE TABLE device_event (
    id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    device_id   BIGINT UNSIGNED  NOT NULL,
    gate_status VARCHAR(16)      NOT NULL
                                 CHECK (gate_status IN ('OPENED', 'CLOSED', 'OPENING', 'CLOSING', 'STOPPED', 'FAULT', 'UNKNOWN')),
    status_code TINYINT UNSIGNED NULL,
    reason      TINYINT UNSIGNED NULL     COMMENT '动作原因码：01/02 遥控器、03/04 地感雷达、10 延时自动关、11 遇阻、12 红外防砸、16 平台指令等',
    occupied    TINYINT(1)       NULL,
    raw_frame   VARBINARY(16)    NOT NULL,
    approx      TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '1 = 由定时查询发现，at 为发现时间（显示“约”）',
    at          DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id, at),
    KEY idx_event_device (device_id, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '设备状态变化流水，按月分区，保留 ≥ 180 天'
PARTITION BY RANGE (TO_DAYS(at)) (
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
    PARTITION p202702 VALUES LESS THAN (TO_DAYS('2027-03-01')),
    PARTITION p202703 VALUES LESS THAN (TO_DAYS('2027-04-01')),
    PARTITION p202704 VALUES LESS THAN (TO_DAYS('2027-05-01')),
    PARTITION p202705 VALUES LESS THAN (TO_DAYS('2027-06-01')),
    PARTITION p202706 VALUES LESS THAN (TO_DAYS('2027-07-01')),
    PARTITION p202707 VALUES LESS THAN (TO_DAYS('2027-08-01')),
    PARTITION p202708 VALUES LESS THAN (TO_DAYS('2027-09-01')),
    PARTITION p202709 VALUES LESS THAN (TO_DAYS('2027-10-01')),
    PARTITION p202710 VALUES LESS THAN (TO_DAYS('2027-11-01')),
    PARTITION p202711 VALUES LESS THAN (TO_DAYS('2027-12-01')),
    PARTITION p202712 VALUES LESS THAN (TO_DAYS('2028-01-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

CREATE TABLE ble_ticket (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id  CHAR(32)        NOT NULL COMMENT '128 位随机，十六进制',
    device_id  BIGINT UNSIGNED NOT NULL,
    user_id    BIGINT UNSIGNED NOT NULL,
    expire_at  DATETIME(3)     NOT NULL COMMENT '签发后 60 秒',
    used       TINYINT(1)      NOT NULL DEFAULT 0,
    used_at    DATETIME(3)     NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ble_ticket (ticket_id),
    KEY idx_ble_ticket_device (device_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '蓝牙票据，60 秒一次性';
