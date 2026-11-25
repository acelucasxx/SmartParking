-- 设备域：设备主档、运行态、换件记录（PRD 4.1、5.1、8.5、9）
-- 约定见 docs/db/conventions.md：时间为 DATETIME(3) 东八区；枚举存大写编码并加 CHECK；不建物理外键。

CREATE TABLE device (
    id                 BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    sn                 VARCHAR(32)      NOT NULL COMMENT '业务身份，印在机身二维码，终身不变',
    imei               CHAR(15)         NOT NULL COMMENT '通信身份，MQTT 主题与客户端 ID；换 4G 模块时更新',
    ble_mac            CHAR(17)         NULL     COMMENT '蓝牙 MAC，AA:BB:CC:DD:EE:FF；换主板时更新',
    iccid              VARCHAR(20)      NULL,
    rs485_addr         TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'RS485 帧地址位',
    model              VARCHAR(32)      NOT NULL,
    batch_no           VARCHAR(32)      NOT NULL,
    activate_code_hash VARCHAR(255)     NOT NULL COMMENT '8 位激活码的哈希，明文不落库',
    bind_status        VARCHAR(16)      NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待激活 / BOUND 已绑定'
                                        CHECK (bind_status IN ('PENDING', 'BOUND')),
    enabled            TINYINT(1)       NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 已禁用；与绑定状态拆开，禁用不丢绑定关系',
    owner_user_id      BIGINT UNSIGNED  NULL     COMMENT '主人，app_user.id；待激活时为空',
    owner_since        DATETIME(3)      NULL     COMMENT '成为当前主人的时刻，冗余自 ownership_log，用于记录可见起点',
    name               VARCHAR(32)      NULL     COMMENT '主人设置的设备名称',
    space_no           VARCHAR(32)      NULL     COMMENT '车位号',
    fw_version         VARCHAR(32)      NULL,
    work_mode          VARCHAR(16)      NOT NULL DEFAULT 'KEEP_ALIVE' COMMENT 'KEEP_ALIVE 保持连接 / DEEP_SLEEP 深睡'
                                        CHECK (work_mode IN ('KEEP_ALIVE', 'DEEP_SLEEP')),
    version            INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at         DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_sn (sn),
    UNIQUE KEY uk_device_imei (imei),
    UNIQUE KEY uk_device_ble_mac (ble_mac),
    KEY idx_device_owner (owner_user_id),
    KEY idx_device_batch (batch_no),
    CHECK (bind_status = 'PENDING' OR owner_user_id IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '设备主档';

CREATE TABLE device_runtime (
    device_id           BIGINT UNSIGNED  NOT NULL,
    gate_status         VARCHAR(16)      NOT NULL DEFAULT 'UNKNOWN'
                                         COMMENT 'OPENED 开闸到位 / CLOSED 关闸到位 / OPENING / CLOSING / STOPPED 中间停止 / FAULT 电机故障 / UNKNOWN'
                                         CHECK (gate_status IN ('OPENED', 'CLOSED', 'OPENING', 'CLOSING', 'STOPPED', 'FAULT', 'UNKNOWN')),
    status_code         TINYINT UNSIGNED NULL     COMMENT '最近状态帧原始状态码，如 0x09',
    last_reason         TINYINT UNSIGNED NULL     COMMENT '最近状态帧动作原因码，如 0x16 平台指令',
    occupied            TINYINT(1)       NULL     COMMENT '车位有车；未知为空',
    battery_pct         TINYINT UNSIGNED NULL     COMMENT '剩余电量百分比；设备未上报为空（PRD 8.6）'
                                         CHECK (battery_pct <= 100),
    signal_level        TINYINT          NULL     COMMENT '4G 信号强度（signal 为 MySQL 保留字）',
    online_state        VARCHAR(16)      NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE / SLEEP / OFFLINE，判定见 PRD 8.5'
                                         CHECK (online_state IN ('ONLINE', 'SLEEP', 'OFFLINE')),
    last_seen_at        DATETIME(3)      NULL     COMMENT '任意上行或连接事件刷新；断开事件不刷新',
    status_refreshed_at DATETIME(3)      NULL     COMMENT '最近一次由状态帧刷新闸杆状态的时刻（30 秒新鲜度判定）',
    last_conn_event     VARCHAR(16)      NULL     COMMENT 'CONNECTED / DISCONNECTED'
                                         CHECK (last_conn_event IN ('CONNECTED', 'DISCONNECTED')),
    last_conn_at        DATETIME(3)      NULL,
    updated_at          DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id),
    KEY idx_runtime_online (online_state, last_seen_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '设备运行态，热数据同时放 Redis';

CREATE TABLE device_part_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_id   BIGINT UNSIGNED NOT NULL,
    part        VARCHAR(16)     NOT NULL COMMENT 'DTU 4G 模块（改 IMEI）/ BOARD 主板（改 MAC）'
                                CHECK (part IN ('DTU', 'BOARD')),
    old_value   VARCHAR(64)     NOT NULL,
    new_value   VARCHAR(64)     NOT NULL,
    operator_id BIGINT UNSIGNED NOT NULL COMMENT 'admin_user.id',
    reason      VARCHAR(255)    NOT NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_part_log_device (device_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '换件记录';
