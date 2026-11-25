-- 用户与归属域：C 端用户、被授权人、邀请、转让、归属变更（PRD 3、5.2、5.5–5.8、9）
-- 部分唯一约束用“生成列 + 唯一索引”实现：生成列在条件不满足时为 NULL，NULL 不参与唯一性比较。

CREATE TABLE app_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    openid        VARCHAR(64)     NOT NULL,
    unionid       VARCHAR(64)     NULL,
    phone_cipher  VARBINARY(255)  NULL     COMMENT '手机号密文（应用层加密）',
    phone_hash    CHAR(64)        NULL     COMMENT '手机号 HMAC-SHA256，用于按手机号精确查询',
    nickname      VARCHAR(64)     NULL,
    status        VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / BANNED 已封禁'
                                  CHECK (status IN ('ACTIVE', 'BANNED')),
    last_login_at DATETIME(3)     NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_openid (openid),
    KEY idx_app_user_unionid (unionid),
    KEY idx_app_user_phone (phone_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'C 端用户';

CREATE TABLE device_member (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_id   BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    remark      VARCHAR(32)     NULL     COMMENT '主人填的备注，如“爸爸”',
    valid_from  DATETIME(3)     NOT NULL,
    valid_to    DATETIME(3)     NULL     COMMENT '空 = 永久',
    status      VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 生效 / REVOKED 已撤销 / EXPIRED 已过期 / QUIT 已退出'
                                CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED', 'QUIT')),
    granted_by  BIGINT UNSIGNED NOT NULL COMMENT '授权时的主人',
    invite_id   BIGINT UNSIGNED NULL     COMMENT '来源邀请 share_invite.id',
    ended_at    DATETIME(3)     NULL     COMMENT '撤销 / 过期 / 退出的时刻',
    active_flag TINYINT AS (IF(status = 'ACTIVE', 1, NULL)) STORED,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_active (device_id, user_id, active_flag) COMMENT '同一人在同一设备只能有一条生效授权',
    KEY idx_member_user (user_id, status),
    KEY idx_member_expire (status, valid_to),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '被授权人';

CREATE TABLE share_invite (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    token_hash       CHAR(64)        NOT NULL COMMENT '邀请 token（≥128 位随机）的 SHA-256，明文只在分享卡片中',
    device_id        BIGINT UNSIGNED NOT NULL,
    inviter_id       BIGINT UNSIGNED NOT NULL COMMENT '发出邀请时的主人',
    period_type      VARCHAR(16)     NOT NULL COMMENT 'PERMANENT 永久 / RELATIVE 1/7/30 天，自接受时起算 / CUSTOM 自定义起止'
                                     CHECK (period_type IN ('PERMANENT', 'RELATIVE', 'CUSTOM')),
    duration_minutes INT UNSIGNED    NULL     COMMENT 'RELATIVE 时的时长',
    valid_from       DATETIME(3)     NULL     COMMENT 'CUSTOM 时的起',
    valid_to         DATETIME(3)     NULL     COMMENT 'CUSTOM 时的止',
    remark           VARCHAR(32)     NULL,
    expire_at        DATETIME(3)     NOT NULL COMMENT '卡片过期时刻，默认创建后 24 小时',
    status           VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待接受（占名额）/ ACCEPTED / EXPIRED / CANCELLED 主人撤销 / VOIDED 主人变更作废'
                                     CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'CANCELLED', 'VOIDED')),
    accepted_by      BIGINT UNSIGNED NULL,
    accepted_at      DATETIME(3)     NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_token (token_hash),
    KEY idx_invite_device (device_id, status),
    KEY idx_invite_expire (status, expire_at),
    CHECK (period_type <> 'RELATIVE' OR duration_minutes IS NOT NULL),
    CHECK (period_type <> 'CUSTOM' OR (valid_from IS NOT NULL AND valid_to IS NOT NULL AND valid_to > valid_from))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '邀请卡片';

CREATE TABLE device_transfer (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    token_hash   CHAR(64)        NOT NULL COMMENT '转让 token 的 SHA-256',
    device_id    BIGINT UNSIGNED NOT NULL,
    from_user    BIGINT UNSIGNED NOT NULL,
    to_user      BIGINT UNSIGNED NULL     COMMENT '接受后填写',
    expire_at    DATETIME(3)     NOT NULL,
    status       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / CANCELLED 发起人取消 / EXPIRED / VOIDED 解绑作废'
                                 CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'EXPIRED', 'VOIDED')),
    accepted_at  DATETIME(3)     NULL,
    pending_flag TINYINT AS (IF(status = 'PENDING', 1, NULL)) STORED,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_transfer_token (token_hash),
    UNIQUE KEY uk_transfer_pending (device_id, pending_flag) COMMENT '同一设备同时只能有一张有效转让卡片',
    KEY idx_transfer_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '转让记录，永久保留';

CREATE TABLE ownership_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    device_id     BIGINT UNSIGNED NOT NULL,
    type          VARCHAR(16)     NOT NULL COMMENT 'BIND 绑定 / TRANSFER 转让 / UNBIND 解绑 / FORCE_UNBIND 强制解绑'
                                  CHECK (type IN ('BIND', 'TRANSFER', 'UNBIND', 'FORCE_UNBIND')),
    from_user     BIGINT UNSIGNED NULL,
    to_user       BIGINT UNSIGNED NULL,
    operator_type VARCHAR(16)     NOT NULL COMMENT 'USER / ADMIN'
                                  CHECK (operator_type IN ('USER', 'ADMIN')),
    operator_id   BIGINT UNSIGNED NOT NULL,
    reason        VARCHAR(255)    NULL     COMMENT '强制解绑必填',
    at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ownership_device (device_id, at),
    CHECK (type <> 'FORCE_UNBIND' OR reason IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '归属变更，决定新主人可见记录的起点';
