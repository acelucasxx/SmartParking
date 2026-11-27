# 数据库约定

适用于 `server/app/src/main/resources/db/migration` 下的全部 Flyway 脚本。表结构以 PRD 第 9 章为准，本文只记录落到 DDL 时的约定与取舍。

## 迁移脚本

- 命名 `V<序号>__<说明>.sql`，序号递增，不复用、不跳号。
- **已合入 `main` 的脚本不得修改**，改结构一律新增脚本。Flyway 启动时校验校验和，不一致会拒绝启动。
- 服务启动时自动执行迁移；`clean` 已禁用。
- 本地重建数据库：`docker compose -f deploy/local/docker-compose.yml down -v` 后重新 `up`，再启动服务。

| 脚本 | 内容 |
| --- | --- |
| V1__device | `device`、`device_runtime`、`device_part_log` |
| V2__user_and_ownership | `app_user`、`device_member`、`share_invite`、`device_transfer`、`ownership_log` |
| V3__control_and_event | `control_command`、`device_event`（按月分区）、`ble_ticket` |
| V4__message_and_alarm | `app_message`、`alarm` |
| V5__admin | `role`、`admin_user`、`operation_log`、`system_config`、`import_task` |
| V6__seed | 三个后台角色、系统设置默认参数；**不预置后台账号** |
| V7__runtime_revision | `device_runtime.rev` 修订号，Redis 热数据据此判断新旧（IOT-06） |
| V8__status_poll | `device_runtime.last_poll_at`，定时状态查询的节流（IOT-10） |
| V9__import_print_payload | `import_task.print_payload`，印刷文件导出前暂存加密的激活码，导出即清空（BIZ-03） |

## 字段

| 项 | 约定 |
| --- | --- |
| 主键 | `BIGINT UNSIGNED AUTO_INCREMENT`；`device_runtime` 以 `device_id` 为主键，`system_config` 以 `config_key` 为主键 |
| 时间 | `DATETIME(3)`，东八区。JDBC 连接设置 `connectionTimeZone=Asia/Shanghai` 并强制到会话，`NOW()` 与应用写入一致 |
| 审计列 | `created_at` / `updated_at` 由数据库默认值维护 |
| 枚举 | 大写英文编码存 `VARCHAR`，并加 `CHECK` 约束；新增取值需要新迁移脚本 |
| 布尔 | `TINYINT(1)` |
| 字符集 | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| 外键 | **不建物理外键**，关联列一律建索引，一致性由服务层在事务内保证。`device_event` 是分区表，MySQL 本身也不支持外键 |
| 原始帧 | `VARBINARY(16)` 存 RS485 字节，审计展示时转十六进制 |

## 敏感数据

| 数据 | 存法 |
| --- | --- |
| 激活码 | 只存哈希 `activate_code_hash`，明文仅出现在一次性印刷文件中 |
| 手机号 | `phone_cipher` 存应用层加密密文；`phone_hash` 存 HMAC，用于按手机号精确查询 |
| 邀请 / 转让 token | 只存 SHA-256 `token_hash`，明文只在分享卡片中 |
| 印刷用激活码 | `import_task.print_payload` 加密暂存，导出印刷文件即清空 |
| 后台密码 | `password_hash`，算法由 BIZ-08 确定 |

## 用数据库兜底的业务规则

MySQL 不支持部分索引，以下规则用“生成列 + 唯一索引”实现：条件不成立时生成列为 `NULL`，不参与唯一性比较。

| 规则（PRD） | 实现 |
| --- | --- |
| 同一人在同一设备只能有一条生效授权（5.5 #7、第 9 章） | `device_member.active_flag` + `uk_member_active` |
| 同一设备同时只能有一张有效转让卡片（5.6 #6） | `device_transfer.pending_flag` + `uk_transfer_pending` |
| 同一设备同时最多一条未终结指令（5.3 #3、8.4） | `control_command.inflight_flag` + `uk_command_inflight` |
| 连点只产生一条指令（10 可靠性） | `uk_command_idempotency (device_id, idempotency_key)` |
| 已绑定设备必须有主人 | `device` 上的 `CHECK` |
| 强制解绑必须填原因（5.7 #2） | `ownership_log` 上的 `CHECK` |

服务层仍需先查再写并给出友好提示，唯一索引只防并发穿透。违反唯一约束时 Spring 抛 `DuplicateKeyException`；违反 `CHECK` 时 MySQL 返回错误码 3819，Spring 归为 `UncategorizedSQLException`。

## `device_event` 分区

- `PARTITION BY RANGE (TO_DAYS(at))`，一月一个分区，主键为 `(id, at)`。
- 初始分区 `p202611` 至 `p202712`，`p_future` 兜底。
- 分区由 `EventPartitionMaintainer` 自动维护（IOT-06）：启动时与每天 03:10 从 `p_future` 拆出未来 3 个月的分区，并删除整月超过 186 天的分区，见 [docs/iot/runtime.md](../iot/runtime.md#device_event-分区维护)。

## 业务上的取舍（需产品确认）

- **邀请有效期起算**：选“1 / 7 / 30 天”时，`share_invite` 只记录时长（`period_type = RELATIVE`），**从接受时起算**；“自定义”记录绝对起止时间；“永久”不设止。
- **指令表范围**：`control_command` 只记录用户控制与后台自检指令。平台定时查询（每 5 分钟，5,000 台约 144 万次/天）不入表，状态变化写 `device_event`。
