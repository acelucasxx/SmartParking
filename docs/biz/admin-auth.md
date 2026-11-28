# 后台账号、会话与操作日志

对应 BIZ-08（PRD 6.2 登录与账号安全、系统设置）。代码在 `server/admin`，接口见契约 [openapi-admin.yaml](../api/openapi-admin.yaml) 的 auth 与 system 分组。

## 请求经过的拦截器

`/api/admin/**`（登录接口除外）依次经过：

| 顺序 | 拦截器 | 作用 |
| --- | --- | --- |
| 1 | `AdminSessionInterceptor` | 按 `Authorization: Bearer` 找会话、顺延空闲超时，按库里最新状态写入 `AdminPrincipal` |
| 2 | `OperationLogInterceptor` | 标了 `@Audited` 的接口在请求结束后写操作日志 |
| 3 | `AdminAccessInterceptor` | 没有 `AdminPrincipal` → 401 / 10002；角色不在 `@AdminRoles` 内 → 403 / 10003 |

每个请求都重新读账号：停用、改角色下一次请求即生效。账号已停用或已删除时作废该会话，按未登录处理。

## 登录

`POST /api/admin/auth/login`，用户名不区分大小写、忽略首尾空白。

| 顺序 | 检查 | 结果 |
| --- | --- | --- |
| 1 | 用户名或密码为空 | 10001 |
| 2 | 账号不存在 | 40001“用户名或密码错误”（同样做一次哈希比对，不从提示或耗时暴露账号是否存在） |
| 3 | 锁定中 | 40002“账号已锁定，请 N 分钟后再试”，不校验密码、不计次 |
| 4 | 密码错误 | 40001“还可尝试 N 次”；第 5 次直接 40002 |
| 5 | 已停用 | 40003（密码正确才告知） |
| 6 | 成功 | 清零错误计数，记 `last_login_at`，签发会话 |

### 锁定

- 连续 5 次密码错误锁定 15 分钟（`smartparking.admin.max-failures`、`lock-duration`）。按账号计数，存 `admin_user.failed_attempts` / `locked_until`。
- 计数用一条条件更新完成：并发输错也不会多计；锁定期间的尝试不计数，也不延长锁定。
- 锁定过期后再输错，从 1 重新计数；登录成功清零。
- 超级管理员“重置密码”会同时解除锁定。

## 会话

- token 是 256 位随机数，不是 JWT：退出、停用、重置密码要立即生效。Redis 只存 token 的 SHA-256：`sp:admin-session:<sha256>` → `<adminId>:<登录时刻>`；`sp:admin-user-sessions:<adminId>` 记该账号全部会话。
- **空闲超时 30 分钟**（`session-idle`）：每次请求顺延。**绝对超时 12 小时**（`session-max`）：自登录起，到期必须重新登录，顺延也不超过它。登录结果里的 `expiresAt` 是按空闲超时算的时刻。
- 退出只作废当前会话；停用、重置密码作废该账号全部会话；修改密码作废其他会话、保留当前会话。

## 临时密码

- 新建账号、重置密码时服务端生成 12 位临时密码（字母与数字，去掉易混淆字符），只在本次响应中出现，库里只存 BCrypt 哈希。
- `password_changed_at` 为空即仍在用临时密码，`mustChangePassword = true`。此时除查看当前账号、修改密码、退出外，所有接口返回 403 / 10003“请先修改密码”。
- 修改密码：原密码错误 40001（不计入登录锁定）；新密码 10–64 位、同时含字母与数字、只含可见 ASCII 字符，且不能与原密码相同，否则 10001。

### 初始超级管理员

库里没有任何后台账号、且配置了 `smartparking.admin.bootstrap-password`（`ADMIN_BOOTSTRAP_PASSWORD`）时，启动时创建超级管理员 `admin`，该密码按临时密码处理，首次登录必须修改。已有账号时什么也不做。本机 `local` profile 默认 `admin_dev_only1`；生产首次部署时设置，上线后可删除。

## 账号与角色

仅超级管理员。

- 新建：用户名为小写字母开头的 3–32 位小写字母、数字或下划线；名称 1–32 个字符；重复用户名 40030。
- 修改（`PATCH`）：只改出现的字段；空对象、未知字段、取值不合法 10001；不能停用自己或修改自己的角色（10003）。
- 角色权限点（`role.permissions`，V10 迁移写入）只给前端控制按钮，服务端仍按接口的 `@AdminRoles`（契约 `x-roles`）判定：

| 角色 | 权限点 |
| --- | --- |
| `SUPER_ADMIN` | `*` |
| `OPS` | `dashboard:view`、`device:view`、`device:import`、`device:disable`、`device:replace-part`、`device:self-check`、`device:force-unbind`、`device:reset-code`、`command:view`、`command:export`、`alarm:view`、`alarm:handle` |
| `SUPPORT` | `dashboard:view`、`device:view`、`device:force-unbind`、`device:reset-code`、`user:view`、`user:ban`、`command:view`、`command:export` |

## 操作日志

- 后台写接口标 `@Audited(action, targetType, targetIdVariable)`（`common`），请求结束后写一条 `operation_log`：成功与失败都记，越权调用（403）也记为 `FAIL`；未登录的请求不记，只读接口不记。
- 结果按响应状态判定：2xx / 3xx 为 `SUCCESS`。写入失败只记错误日志，不影响响应。
- 原因、明细、运行时才知道的对象 ID 在接口内用 `AuditTrail` 补充。明细不放密码、激活码等敏感值。
- `admin_name` 记操作时的用户名快照，`ip` 取请求的远端地址。
- 目前记录的动作：

| 动作 | 接口 |
| --- | --- |
| `ADMIN_USER_CREATE` / `ADMIN_USER_UPDATE` / `ADMIN_USER_RESET_PASSWORD` | 账号管理 |
| `ADMIN_PASSWORD_CHANGE` | 修改自己的密码 |
| `CONFIG_UPDATE` | 修改业务参数（明细含修改前后的值） |
| `DEVICE_CREATE` / `DEVICE_IMPORT` / `DEVICE_PRINT_EXPORT` | 设备入库（印刷文件含激活码明文） |
| `ALARM_HANDLE` | 告警标记处理 |
| `COMMAND_EXPORT` | 导出指令流水（明细含起止时间与行数） |
| `DEVICE_FORCE_UNBIND` / `DEVICE_RESET_CODE` / `DEVICE_DISABLE` / `DEVICE_ENABLE` / `DEVICE_REPLACE_PART` / `DEVICE_SELF_CHECK` | 设备运维操作（[admin-ops.md](admin-ops.md)），高危操作带必填原因 |
| `USER_BAN` / `USER_UNBAN` | 封禁 / 解封 C 端用户（[disable-ban.md](disable-ban.md)），带必填原因 |

`GET /api/admin/operation-logs` 按 `adminId`、`action`、`targetId`、时间（`from` 含、`to` 不含）筛选，按时间倒序分页。

## 业务参数

`GET /api/admin/configs`、`PUT /api/admin/configs/{key}`，仅超级管理员。键不存在 10004；以下不合法都是 10001。

| 键 | 范围 |
| --- | --- |
| `member.max_per_device` | 1–20 |
| `card.ttl_hours` | 1–168 |
| `command.timeout_seconds` | 5–60 |
| `online.sleep_minutes` | 2–120，且大于 `status_poll.interval_minutes` |
| `online.offline_hours` | 1–168，且换算成分钟后大于 `online.sleep_minutes` |
| `status_poll.interval_minutes` | 1–60，且小于 `online.sleep_minutes` |

- 休眠窗口必须大于定时查询间隔（PRD 8.5），否则正常设备也会被判休眠。
- 其他模块通过 `SystemSettings` 读取，各实例缓存 30 秒（`smartparking.settings.cache-ttl`）：处理修改请求的实例立即生效，其他实例 30 秒内生效，满足契约“最长 1 分钟内全部实例生效”。
