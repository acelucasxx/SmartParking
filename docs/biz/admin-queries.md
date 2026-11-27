# 后台查询

对应 BIZ-09（PRD 6.2 仪表盘、设备管理、用户管理、指令审计）。代码在 `server/admin` 的 `query` 包，接口见契约 [openapi-admin.yaml](../api/openapi-admin.yaml)。登录、角色判定与操作日志见 [admin-auth.md](admin-auth.md)。

| 接口 | 角色 | 说明 |
| --- | --- | --- |
| `GET /api/admin/dashboard` | 全部 | 仪表盘 |
| `GET /api/admin/devices` | 全部 | 设备列表 |
| `GET /api/admin/devices/{deviceId}` | 全部 | 设备详情 |
| `GET /api/admin/devices/{deviceId}/events` | 全部 | 设备事件流 |
| `GET /api/admin/users`、`/users/{userId}` | 超管、客服 | C 端用户 |
| `GET /api/admin/commands`、`/commands/{commandId}` | 全部 | 指令审计 |
| `GET /api/admin/commands/export` | 全部 | 导出指令流水（写操作日志 `COMMAND_EXPORT`） |

## 通用规则

- 手机号只显示脱敏值（`138****5678`，PRD 10）。按手机号查询须给完整 11 位，用 HMAC 哈希精确匹配，不解密全表；格式不对 10001。
- 时间按东八区输出；时间范围 `from` 含、`to` 不含，`from` 须早于 `to`。
- 分页 `page` 从 1 开始，`size` 1–100；枚举参数取值不合法 10001。
- 昵称为空时显示“微信用户”。

## 设备

- **列表**：
  - SN、IMEI 为**前缀匹配**（SN 不区分大小写），只接受字母数字。
  - `phone` 为主人手机号，`bindStatus`、`onlineState`、`enabled` 精确匹配。
  - 按 ID 倒序。
  - 在线状态用库里存的值筛选与显示：定时扫描每 60 秒更新，最多滞后 60 秒；从未上报的设备为 `OFFLINE` / `UNKNOWN`。
- **详情**：
  - 在线状态**按当前时刻判定**（与小程序一致）。
  - 主人含昵称、脱敏手机号与成为主人的时刻；待激活设备 `owner` 为 `null`。
  - `members` 只列生效中的授权（含尚未开始的），按开始时间排列。已过 `validTo` 而到期任务还没处理的显示 `EXPIRED`。
  - 指令、告警、事件分别用各自的列表接口按 `deviceId` 查询。
- **事件流**：`device_event` 按时间倒序。`statusCode` / `reasonCode` 为两位十六进制；`source` 由原因码映射；`approx = true` 表示由定时查询发现，时刻为发现时间。设备不存在 10004。

## C 端用户

- 列表按注册时间倒序，可按手机号、状态筛选。
- `ownedCount` 为名下已绑定设备数；`memberCount` 为生效中的被授权记录数。
- 详情：名下设备按成为主人的先后；授权记录**含历史**（已撤销、已过期、已退出），最近开始的在前，最多 100 条。
- 运维看不到用户信息（契约 `x-roles` 只有超管与客服）。封禁 / 解封在 BIZ-11。

## 指令审计

- 按 `deviceId`、SN（精确，不区分大小写）、操作人手机号、通道、状态、下发时间筛选，按下发时间倒序。
- `operator`：小程序用户为 `USER`（昵称与脱敏手机号）；后台自检为 `ADMIN`（显示名）。
- `downFrame` / `rawFrame` 为大写十六进制、空格分隔；`fwVersion` 只在后台自检查询版本成功时有值（见 [admin-ops.md](admin-ops.md)）。

### 导出

- `from`、`to` 必填，跨度不超过 31 天，结果不超过 10 万行，否则 10001。这些检查都在写出文件之前完成，因此失败时仍返回 JSON 错误。
- xlsx 流式写出，按下发时间正序；手机号同样脱敏。
- 每次导出写操作日志 `COMMAND_EXPORT`，明细含起止时间与行数。

## 仪表盘

| 字段 | 口径 |
| --- | --- |
| `devices.total` / `bound` | 全部设备 / 已绑定设备 |
| `online` / `sleep` / `offline` | 只统计**已绑定**设备（库存设备不上电，算进离线没有意义），按库里存的在线状态；没有运行态的计为离线 |
| `fault` | 已绑定且闸杆状态为电机故障（E3），与在线状态重叠计数 |
| `commandsToday` | 东八区今天 0 点起下发、已终结的 App 抬杆 / 落杆：不含执行中的指令与后台自检；成功含“已在目标状态”；没有指令时成功率为 0，前端据 `total` 显示“—” |
| `openAlarms` | 未处理告警数 |
