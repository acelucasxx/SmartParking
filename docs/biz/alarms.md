# 告警

对应 BIZ-07（PRD 6.2 告警中心）。代码在 `server/notify` 的 `alarm` 包，后台接口见契约 [openapi-admin.yaml](../api/openapi-admin.yaml) 的 alarm 分组。

## 何时产生

| 类型 | 级别 | 来源事件 | 判定 |
| --- | --- | --- | --- |
| `MOTOR_FAULT` 电机故障 | CRITICAL | `DeviceStateChangedEvent` | 状态码 `E3` |
| `OBSTRUCTED` 遇阻 | WARNING | 同上 | 原因码 `11` |
| `INFRARED` 红外防砸 | WARNING | 同上 | 原因码 `12` |
| `CMD_TIMEOUT` 连续 3 次超时 | WARNING | `CommandFinishedEvent` | 该设备最近 3 条已终结的指令都是 `TIMEOUT` |
| `OFFLINE_24H` 离线超 24 小时 | CRITICAL | `OnlineStateChangedEvent` | 在线状态变为 `OFFLINE`（PRD 8.5 判据即 `last_seen_at` 超过 24 小时） |

事件定义见 [runtime.md](../iot/runtime.md) 与 [command.md](../iot/command.md)。

- **E3 / 遇阻 / 红外**：只有本帧的异常与变化前不同，才算一次新的发生。同一次遇阻，闸板反转、到位的后续帧仍带原因码 `11`，不重复计数；故障持续期间车位变化引起的状态帧也不计。
  - App 指令和遥控器、地感等非 App 动作都在这里判定。指令因此失败时不再单独计一次。
- **连续超时**：第 3 次连续超时产生告警，之后每多一次连续超时合并计数 +1。中间有一次成功或失败（含“已在目标状态”）即重新计数。
- **离线**：每次离线只发生一次（在线状态变化只发布一次）。只针对已绑定的设备：库存中未激活的设备长期不上电是正常的。设备恢复上线不会自动关闭告警，由运维处理。

## 合并

同设备、同类型、**未处理**、最近一次（`last_at`）在 30 分钟内的告警合并：`count + 1`，`last_at` 取较晚者，`first_at` 不变。

- 窗口按最近一次计，持续发生的问题始终是一条告警。
- 已处理的告警不再合并，处理后再发生即新告警。
- 写入时锁设备行，多实例、多事件并发时，窗口内也只有一条未处理告警。

写入在独立事务里完成，事务提交后发布 `AlarmRaisedEvent`（`merged` 表示是否只是累加了次数），供站内故障消息（BIZ-15）、监控（INF-08）订阅。

告警订阅方在设备上行、在线扫描、指令收尾的链路里同步执行：任何失败只记错误日志，不影响运行态、指令状态与在线判定。设备不存在时忽略。

## 告警中心接口

仅超级管理员与运维（`SUPER_ADMIN`、`OPS`），后台守卫见 [device-import.md](device-import.md)。

| 接口 | 说明 |
| --- | --- |
| `GET /api/admin/alarms` | 按 `deviceId`、`sn`（不区分大小写、忽略首尾空白）、`type`、`status` 筛选，按最近发生时间倒序，分页 |
| `POST /api/admin/alarms/{alarmId}/handle` | 标记已处理，`note` 去首尾空白后 1–512 个字符 |

- 处理时记录处理人与时间；`handlerName` 为后台账号的显示名。
- 告警不存在 10004；已被处理过 10009，处理人与备注不被覆盖（并发处理时只有一人成功）。
- `status` 或 `type` 取值不在枚举内、备注为空或超长、分页参数不合法，都返回 10001。
