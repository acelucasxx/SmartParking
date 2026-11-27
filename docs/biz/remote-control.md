# 远程控制接口

对应 BIZ-05（PRD 5.3）。代码在 `server/control` 的 `command` 包，接口见契约 [openapi-app.yaml](../api/openapi-app.yaml) 的 control 分组。

| 接口 | 权限级别 | 说明 |
| --- | --- | --- |
| `POST /api/app/devices/{deviceId}/commands` | `CONTROL` | 抬杆 / 落杆，立即返回指令 |
| `GET /api/app/devices/{deviceId}/commands/{commandId}` | `VIEW` | 查询结果；设备禁用后仍可查 |

前端提交后每 1 秒轮询，直到 `status` 为 `SUCCESS` / `FAILED` / `TIMEOUT`；执行中的 `gateStatus` 为 `OPENING` / `CLOSING` 时播放动画。

## 分工

| 判定 | 谁做 | 错误码 |
| --- | --- | --- |
| 登录、封禁 | 登录拦截器（[app-auth.md](app-auth.md)） | 10002、10010 |
| 主人或有效期内被授权人、设备启用 | 统一权限判定（[permission.md](permission.md)） | 10004、30005、20002 |
| 参数 | 本接口 | 10001 |
| 幂等、串行、在线 / 休眠、先查询、故障、已在目标状态 | 指令状态机（[command.md](../iot/command.md)） | 20003、20004、20005、20006 |

权限判定在指令状态机之前：没有权限的请求不落库、不下发。

## 写入 `control_command`

- `user_id` 为当前用户，`role` 为权限判定得出的 `OWNER` / `MEMBER`，`channel` 为 `REMOTE`。
- 已在目标状态时同样写一条 `SUCCESS` / `ALREADY_IN_TARGET`（PRD 5.3②“直接返回成功并记录”）。
- 离线、休眠、执行中、故障在落库前就拒绝，不写记录；落库后下发失败记 `FAILED` / `OFFLINE`，本次返回 20003。

## 可见范围

指令的可见范围与操作记录一致（PRD 4 权限表）：

| 角色 | 能查看的指令 |
| --- | --- |
| 主人 | 自成为主人（`device.owner_since`）起的全部指令，以及本人发起的指令 |
| 被授权人 | 本人发起的指令 |

看不到的指令按不存在处理（10004）。同一设备上，若别人重放了已被使用的 `idempotencyKey`，返回 10001“idempotencyKey 已被使用”，而不是把那条指令返回给他。前端每次点击生成新 UUID，正常使用不会碰到。

## `resultMessage`

失败或超时时给出可直接展示的说明，成功与未终结时为 `null`：

| `resultCode` | 说明 |
| --- | --- |
| `MOTOR_FAULT` | 设备电机故障，请联系客服 |
| `OBSTRUCTED` | 闸杆遇阻，请检查现场 |
| `INFRARED` | 检测到障碍物，闸杆已停止 |
| `INTERRUPTED` | 闸杆中途停止，请重试 |
| `TIMEOUT` | 设备长时间未到位，请查看现场 |
| `OFFLINE` | 设备连接中断，指令未送达 |
