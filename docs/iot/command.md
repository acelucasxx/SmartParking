# 指令状态机

对应 PRD 5.3、8.4，实现于 IOT-07。代码在 `server/iot` 的 `command` 包。iot 只负责“对设备 X 执行动作 Y”，发起人与权限、设备禁用由调用方（BIZ-05 控制接口）校验后传入。

## 提交

`CommandService.submit(deviceId, action, idempotencyKey, initiator)` 立即返回指令，前端按 1 秒轮询 `get`。依次判断：

| 步骤 | 条件 | 结果 |
| --- | --- | --- |
| 1 | 同一设备、同一幂等键已有指令 | 原样返回，不重复下发 |
| 2 | 设备不存在 | `DEVICE_NOT_FOUND` |
| 3 | 设备已有未终结指令 | `COMMAND_IN_PROGRESS` |
| 4 | 在线判定为离线，或云端与 broker 未连接 | `DEVICE_OFFLINE`，不排队 |
| 5 | 在线判定为休眠 | `DEVICE_SLEEPING`，不排队 |
| 6 | 缓存状态超过 30 秒未由状态帧刷新 | 先同步查询一次（最多等 2 秒），以查询结果判定；无回包则照常下发 |
| 7 | 当前为电机故障 | `DEVICE_FAULT` |
| 8 | 已在目标状态 | 不下发，记一条 `SUCCESS` / `ALREADY_IN_TARGET` |
| 9 | 其余 | 落库 `PENDING`（含下行帧）→ 发布 → `SENT`，交给执行器 |

在线判定用 `OnlinePolicy`（PRD 8.5 的优先级），阈值读系统设置 `online.sleep_minutes`、`online.offline_hours`。

**设备级串行**由 `control_command` 的唯一键 `uk_command_inflight` 保证：同一设备同时最多一条 `PENDING` / `SENT` / `EXECUTING` 指令，跨实例同样有效。并发提交时，同一幂等键的第二个请求返回第一条指令，不同幂等键的返回 `COMMAND_IN_PROGRESS`。

## 执行与判定

下发后每 300 ms 查询一次状态，按收到的状态帧推进（`CommandJudge`）：

| 状态帧 | 推进 |
| --- | --- |
| 到位（开闸到位 / 关闸到位） | `SUCCESS` |
| 与下发前完全相同（闸板还没动） | 忽略 |
| `E3` 电机故障 | `FAILED` / `MOTOR_FAULT` |
| 原因码 `11` 遇阻、`12` 红外 | `FAILED` / `OBSTRUCTED`、`INFRARED` |
| 朝目标动作中（开闸中 / 关闸中） | `EXECUTING` |
| `00` 中间停止 | `FAILED` / `INTERRUPTED` |
| 反方向动作、未知码 | 忽略，等待超时 |

- **回显帧不参与判定**，只说明命令到了串口。
- 超过 `command.timeout_seconds`（默认 15 秒）未到位：`TIMEOUT`。
- 落库后发布失败（链路中断）：`FAILED` / `OFFLINE`，本次提交返回 `DEVICE_OFFLINE`。
- 进入终态时发布 `CommandFinishedEvent`，供告警（BIZ-07 连续超时）、操作记录、消息使用。
- 状态推进都带“仍未终结”的条件，与对账（IOT-08）并发时只有一方生效。

## 已知限制

- 执行器只跟踪本实例发起的指令，跟踪信息在内存中。服务重启后，未终结指令会一直占着设备的串行位，直到对账（IOT-08）按超时补齐；IOT-08 上线前重启服务可能导致设备在一段时间内无法控制。
- 多实例部署时，每个实例都订阅全部上行，只有发起指令的实例推进它。
