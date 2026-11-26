# 状态同步

对应 PRD 8.7，实现于 IOT-10。代码在 `server/iot` 的 `sync` 包（`StatusSyncService`）。

协议一问一答，闸板在遥控器、地感等触发后是否主动上报尚未确认（PRD 12.1 第 10 项）。为不依赖该能力，平台主动下发查询 `D0 C0`。回包走正常上行链路（[runtime.md](runtime.md)），刷新闸杆 / 车位状态、`last_seen_at` 与在线状态；发查询的一方不等回包。

## 时机

| 时机 | 实现 | 说明 |
| --- | --- | --- |
| 设备 MQTT 连接事件 | `onConnection` | 延迟 `smartparking.status-sync.connect-delay`（默认 2 秒）后下发，见下文 |
| 绑定成功 | `requestQuery(deviceId)` | 由绑定流程（BIZ）调用；返回 `false` 表示设备不在线或链路不可用，等设备上线由连接事件触发 |
| 下发控制前，缓存超过 30 秒 | `CommandService`（IOT-07） | 同步查询、等回包，见 [command.md](command.md) |
| 控制执行中每 300 ms | `CommandExecutor`（IOT-07） | |
| 定时，默认每 5 分钟 | `pollDue` | 周期取后台参数 `status_poll.interval_minutes`，`0` 为关闭；每 `smartparking.status-sync.tick`（30 秒）检查一次到期设备 |

## 定时查询

- **只查需要查的**：MQTT 连接中（`last_conn_event = CONNECTED`），且一个周期内既没有上行、也没有下发过查询的设备。有上行的设备本来就是活的，不必再查；断开的设备查了也收不到。
- **自然错开**：每台设备的到期时刻随各自最近一次上行，不会在同一时刻集中下发。
- **不回包的设备**每个周期只查一次：下发时记 `device_runtime.last_poll_at`（V8），不靠是否有回包判断。
- **多实例**：下发前按到期条件条件更新 `last_poll_at` 认领，只有一个实例成功。`last_poll_at` 不计入运行态修订号、也不进 Redis。
- 连接、绑定触发的查询同样记 `last_poll_at`，紧接着的定时查询不会重复下发。
- 与 broker 断开时本轮直接停止，下一轮再试。
- 按设备 ID 分页，每页 200，单轮最多 25 页（5,000 台）。

## 上线查询为什么延迟

EMQX 在连接建立时即发出 `client.connected` 事件，设备随后才订阅 `parking/down/{imei}`；清洁会话下，订阅前发布的下行没有接收方，会直接丢弃。延迟 2 秒是给真实 DTU 留出订阅时间的保险，同时让下发不占用 EMQX 回调线程。

`MqttAccessIntegrationTest.connectingDeviceIsQueriedWithoutAnyRequest` 经真实 EMQX 验证上线即查询。本地实测延迟设为 0 时该测试也能通过（HTTP 回调本身的耗时已超过模拟器订阅耗时），因此 2 秒的必要性未被测试证明，需在试点期用真实 DTU 确认。

## 待硬件确认后调整

若硬件确认闸板会主动上报且 DTU 心跳会上行，可在后台把 `status_poll.interval_minutes` 调到 30 或设为 0 关闭（PRD 8.7）。休眠窗口（默认 10 分钟）必须大于实际上行间隔，调大查询周期时同步检查（PRD 8.5）。
