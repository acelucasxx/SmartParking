-- 后台：账号、角色、操作日志、系统参数、导入任务（PRD 3.1、7、9、10）

CREATE TABLE role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)     NOT NULL COMMENT 'SUPER_ADMIN / OPS / SUPPORT',
    name        VARCHAR(32)     NOT NULL,
    permissions JSON            NOT NULL COMMENT '权限点编码数组，BIZ-08 定义',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '后台角色';

CREATE TABLE admin_user (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    username            VARCHAR(32)      NOT NULL,
    password_hash       VARCHAR(255)     NOT NULL,
    display_name        VARCHAR(32)      NOT NULL,
    role_id             BIGINT UNSIGNED  NOT NULL,
    status              VARCHAR(16)      NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED'
                                         CHECK (status IN ('ACTIVE', 'DISABLED')),
    failed_attempts     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    locked_until        DATETIME(3)      NULL,
    last_login_at       DATETIME(3)      NULL,
    password_changed_at DATETIME(3)      NULL,
    created_at          DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username),
    KEY idx_admin_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '后台账号';

CREATE TABLE operation_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    admin_id    BIGINT UNSIGNED NOT NULL,
    admin_name  VARCHAR(32)     NOT NULL COMMENT '操作时的用户名快照',
    action      VARCHAR(64)     NOT NULL COMMENT '如 DEVICE_FORCE_UNBIND、DEVICE_RESET_CODE、DEVICE_REPLACE_PART、DEVICE_DISABLE、USER_BAN',
    target_type VARCHAR(32)     NULL     COMMENT 'DEVICE / APP_USER / ADMIN_USER / SYSTEM_CONFIG …',
    target_id   VARCHAR(64)     NULL,
    reason      VARCHAR(255)    NULL     COMMENT '高危操作必填',
    detail      JSON            NULL,
    result      VARCHAR(16)     NOT NULL COMMENT 'SUCCESS / FAIL'
                                CHECK (result IN ('SUCCESS', 'FAIL')),
    ip          VARCHAR(45)     NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_oplog_time (created_at),
    KEY idx_oplog_admin (admin_id, created_at),
    KEY idx_oplog_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '后台操作日志，保留 ≥ 180 天';

CREATE TABLE system_config (
    config_key   VARCHAR(64)     NOT NULL,
    config_value VARCHAR(255)    NOT NULL,
    value_type   VARCHAR(16)     NOT NULL COMMENT 'INT / STRING / BOOL'
                                 CHECK (value_type IN ('INT', 'STRING', 'BOOL')),
    description  VARCHAR(255)    NOT NULL,
    updated_by   BIGINT UNSIGNED NULL,
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '后台可配业务参数';

CREATE TABLE import_task (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    file_name         VARCHAR(255)    NOT NULL,
    status            VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / DONE / FAILED'
                                      CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    total_rows        INT UNSIGNED    NOT NULL DEFAULT 0,
    success_rows      INT UNSIGNED    NOT NULL DEFAULT 0,
    failed_rows       INT UNSIGNED    NOT NULL DEFAULT 0,
    failed_detail     JSON            NULL     COMMENT '失败行：[{row, sn, imei, mac, reason}]，用于导出修正',
    error_message     VARCHAR(512)    NULL     COMMENT '任务级失败原因',
    print_exported_at DATETIME(3)     NULL     COMMENT '印刷文件（含激活码明文）已导出的时刻；仅可导出一次',
    operator_id       BIGINT UNSIGNED NOT NULL,
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at       DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY idx_import_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '设备导入任务与失败行';
