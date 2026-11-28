# 我的车位与设备详情

对应 BIZ-06（PRD 6.1 页面 2、3、10）。代码在 `server/control` 的 `devices` 包，接口见契约 [openapi-app.yaml](../api/openapi-app.yaml) 的 device 分组。

| 接口 | 权限级别 | 说明 |
| --- | --- | --- |
| `GET /api/app/devices` | 登录即可 | 我的车位（`DeviceCard` 列表） |
| `GET /api/app/devices/{deviceId}` | `VIEW` | 控制页详情（`DeviceDetail`） |
| `GET /api/app/devices/{deviceId}/status` | `VIEW` | 实时状态，控制页每 5 秒轮询；绑定结果页据 `statusRefreshedAt` 判定“联网成功” |
| `PATCH /api/app/devices/{deviceId}` | `OWNER` | 修改名称、车位号，设备禁用时也可以 |

权限级别见 [permission.md](permission.md)。扫码绑定成功后返回的卡片与列表用同一实现（`DeviceViews`）。

## 车位列表

- 作为主人的设备在前，按成为主人的先后排列；有效期内的被授权设备在后，按授权开始的先后排列。
- 被授权设备以 `valid_from ≤ 现在 < valid_to` 为准：已到期、尚未开始、已撤销 / 已退出的都不出现，不依赖到期任务先改状态。
- 禁用的设备照常出现，`enabled = false`，前端显示“设备已停用，请联系客服”。
- 被授权人看到的 `validTo` 为授权止；主人与永久授权为 `null`。

## 状态从哪来

| 字段 | 来源 |
| --- | --- |
| `gateStatus`、`occupied`、`statusRefreshedAt`、`lastSeenAt` | 运行态（Redis 热数据，未命中回 MySQL）；从未上报为 `UNKNOWN` / `null` |
| `onlineState` | **按当前时刻判定**（PRD 8.5），不用库里存的值：后者最多要等 60 秒扫描才更新 |
| `batteryPct`、`signalLevel` | `device_runtime`；IOT-12 之前为 `null`，前端显示“—” |
| `pendingCommandId` | 该设备未终结的指令，前端据此置灰按钮并继续轮询 |

## 详情附加字段

- `ownerNickname`：主人昵称，未设置时为“微信用户”。被授权人只看到昵称，看不到手机号（PRD 10）。
- `bleAvailable`：`smartparking.ble.enabled` 打开且设备登记了蓝牙 MAC。开关默认 `false`：蓝牙依赖固件（EXT-03 / EXT-04），上线前离线时只显示原因。见 [ble.md](ble.md)。
- `lastOperation`：最近一条本人可见的 App 操作（`id` 为 `c-<指令 ID>`）。可见范围与操作记录一致：
  - 主人能看到自成为主人起的全部操作，以及本人发起的操作；被授权人只能看到本人发起的操作。
  - 操作人名称：主人看被授权人的操作时，备注优先；其余显示昵称。
  - 未终结的指令 `result` 为 `null`。
  - 主人还能看到遥控器、地感等非 App 动作（`e-` 记录），与操作记录同一口径（[records.md](records.md)）。

## 修改名称与车位号

- 只改请求里出现的字段：`{"spaceNo": "B2-109"}` 不会动名称。
- 名称去首尾空白后为 1–32 个字符，不能清空。车位号至多 32 个字符，空串或 `null` 表示清空。
- 空对象、类型不对、长度超限，都返回 10001。
