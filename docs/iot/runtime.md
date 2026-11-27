# 设备运行态与状态流水

对应 PRD 8.5、8.7、9，实现于 IOT-06（运行态、流水）与 IOT-09（在线判定）。代码在 `server/iot` 的 `runtime` 包。

## 数据流

```text
DeviceUplinkEvent（IOT-04）──┐
                            ├─▶ DeviceRuntimeService ─▶ device_runtime（事实来源）─▶ Redis sp:runtime:{deviceId}
DeviceConnectionEvent（IOT-05）┘                      └─▶ device_event（仅状态变化）  └─▶ DeviceStateChangedEvent
```

## 写入规则

| 上行 | `device_runtime` | `device_event` |
| --- | --- | --- |
| 状态帧 | 刷新 `gate_status`、`status_code`、`last_reason`、`occupied`、`status_refreshed_at`、`last_seen_at` | 状态码、原因码或车位与上一帧不同时追加一条；首帧也记 |
| 回显帧、版本帧、未知帧 | 只刷新 `last_seen_at`（PRD 8.5：任意上行） | 不写 |
| 连接事件（IOT-05） | `last_conn_event`、`last_conn_at`，连接时刷新 `last_seen_at` | 不写 |

- 同一设备的状态帧在行锁（`SELECT … FOR UPDATE`）内比较并写入；早于已记录 `status_refreshed_at` 的帧丢弃。
- 执行中每 300 ms 的查询回包大多与上一帧相同，只刷新时间，不会刷满事件表。
- `approx`：触发方式不是平台指令（原因码 `16`）的变化记 1。闸板不主动上报时，这类动作只能在下一次查询时发现，时间是“发现时间”，界面显示“约”（PRD 8.7）。硬件确认主动上报后在 IOT-13 调整。
- 每次写入后紧接着做一次在线判定（见下一节），再写缓存。

## 在线判定

规则见 PRD 8.5，实现为纯函数 `OnlinePolicy`，自上而下命中即止：

| 优先级 | 结论 | 判据 |
| --- | --- | --- |
| 1 | `OFFLINE` | 从未上行，或 `last_seen_at` 距今超过 `online.offline_hours`（默认 24 小时） |
| 2 | `SLEEP` | `last_conn_event = DISCONNECTED`，或 `last_seen_at` 距今超过 `online.sleep_minutes`（默认 10 分钟） |
| 3 | `ONLINE` | 以上都不满足 |

窗口取后台系统设置，修改后在设置缓存时间（30 秒）内生效。`OnlineStateService` 在三个时机判定并写入 `online_state`：

| 时机 | 入口 | 说明 |
| --- | --- | --- |
| 收到上行或连接事件 | `DeviceRuntimeService` 写完运行态后调用 `reevaluate` | 上线、断开即时生效 |
| 每 60 秒扫描 | `sweep`，间隔 `smartparking.online.sweep-interval` | 沉默导致的休眠 / 离线没有事件，靠扫描发现；SQL 按同一规则挑出“已存状态与当前判定不一致”的行，按设备 ID 分页（每页 500，单轮最多 40 页） |
| 控制下发前 | `CommandService` 调用 `judge` | 以当前时刻判定缓存快照；与已存状态不同时读库重判并落库，不必等扫描 |

- **只写有变化的**：结论与已存状态相同时不写库。
- **并发安全**：写入条件是“修订号仍为判定时读到的值”，期间行被改过就重读重判（最多 3 次）。多实例同时扫描、事件与扫描同时到达时，不会写回过期结论，每次变化只发布一次 `OnlineStateChangedEvent`。
- 扫描是兜底：EMQX 事件丢失（业务服务长时间不可用）时，按 `last_seen_at` 仍能在 60 秒内把设备转入休眠 / 离线。
- 断开后收到的上行（EMQX 事件延迟到达）不会让设备回到在线：最近一次连接事件仍为断开，直到下一次连接事件。

## Redis 热数据

- 键 `sp:runtime:{deviceId}`，Hash，字段与 `DeviceRuntime` 一致，时间存毫秒时间戳；TTL 1 天，每次写入续期。
- 每次写库后从库里重读整行再写缓存。`device_runtime.rev`（V7）每次更新 +1，缓存只接受修订号更大的快照（Lua 脚本比较），并发回写时旧快照不会覆盖新状态。
- 读：`DeviceRuntimeService.find` 先读 Redis，未命中读 MySQL 并回填。
- Redis 不可用：读回落 MySQL、写跳过并记日志，设备接入与状态写库不受影响。

## device_event 分区维护

`EventPartitionMaintainer` 在应用启动时与每天 03:10（东八区）执行：

- 保证分区覆盖到“当月 + 3 个月”，缺的月份一次性从 `p_future` 拆出。
- 整月都早于“今天 − 186 天”的分区直接 `DROP PARTITION`（PRD 9：保留 ≥ 180 天）。
- 用 MySQL 命名锁 `smartparking.device_event.partitions`，多实例时同一时刻只有一个执行；失败只记错误日志，次日重试。

配置前缀 `smartparking.device-event.partitions`：`enabled`、`retention-days`（不得小于 180）、`months-ahead`。

## 事件

| 事件 | 时机 | 订阅方 |
| --- | --- | --- |
| `DeviceStateChangedEvent` | 写入一条 `device_event` 后 | 电机故障、遇阻、红外告警（BIZ-07，[alarms.md](../biz/alarms.md)）、非 App 动作入记录（IOT-13） |
| `OnlineStateChangedEvent` | `online_state` 变化写库后 | 离线超 24 小时告警（BIZ-07）、在线率监控（INF-08） |

指令状态机（IOT-07）需要每一帧状态，直接订阅 `DeviceUplinkEvent`，判断新鲜度用 `DeviceRuntime.statusRefreshedAt`。

`DeviceRuntimeService` 对 `DeviceUplinkEvent`、`DeviceConnectionEvent` 的监听排在最前（`@Order(HIGHEST_PRECEDENCE)`）：其他订阅方据某一帧做出判定时，运行态已写入这一帧。否则可能出现指令已判“电机故障”失败、紧接着的提交仍读到故障前的状态而照常下发（IOT-10 CI 中出现过，已用延迟注入复现）。
