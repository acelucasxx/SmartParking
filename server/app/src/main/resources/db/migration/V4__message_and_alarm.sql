-- 通知与告警：站内消息、告警（PRD 5.5–5.8、6.2、7、9）

CREATE TABLE app_message (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    BIGINT UNSIGNED NOT NULL,
    type       VARCHAR(16)     NOT NULL COMMENT 'GRANT 授权 / REVOKE 撤销 / EXPIRE 到期 / TRANSFER 转让 / UNBIND 解绑 / FAULT 故障 / SYSTEM'
                               CHECK (type IN ('GRANT', 'REVOKE', 'EXPIRE', 'TRANSFER', 'UNBIND', 'FAULT', 'SYSTEM')),
    device_id  BIGINT UNSIGNED NULL,
    title      VARCHAR(64)     NOT NULL,
    content    VARCHAR(512)    NOT NULL,
    read_at    DATETIME(3)     NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_message_user (user_id, created_at),
    KEY idx_message_unread (user_id, read_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '站内消息';

CREATE TABLE alarm (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_id  BIGINT UNSIGNED NOT NULL,
    type       VARCHAR(32)     NOT NULL COMMENT 'MOTOR_FAULT 电机故障 / OBSTRUCTED 遇阻 / INFRARED 红外防砸 / CMD_TIMEOUT 连续 3 次超时 / OFFLINE_24H'
                               CHECK (type IN ('MOTOR_FAULT', 'OBSTRUCTED', 'INFRARED', 'CMD_TIMEOUT', 'OFFLINE_24H')),
    level      VARCHAR(16)     NOT NULL COMMENT 'CRITICAL / WARNING'
                               CHECK (level IN ('CRITICAL', 'WARNING')),
    first_at   DATETIME(3)     NOT NULL,
    last_at    DATETIME(3)     NOT NULL COMMENT '同设备同类 30 分钟内合并，刷新 last_at 并累加 count',
    count      INT UNSIGNED    NOT NULL DEFAULT 1,
    status     VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 未处理 / HANDLED 已处理'
                               CHECK (status IN ('OPEN', 'HANDLED')),
    handler_id BIGINT UNSIGNED NULL     COMMENT 'admin_user.id',
    handled_at DATETIME(3)     NULL,
    note       VARCHAR(512)    NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_alarm_merge (device_id, type, status, last_at),
    KEY idx_alarm_status (status, last_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '告警';
