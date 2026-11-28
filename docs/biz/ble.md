# 蓝牙票据

对应 BIZ-17（PRD 5.4）。代码在 `server/control` 的 `ble` 包，接口见契约 [openapi-app.yaml](../api/openapi-app.yaml) 的 ble 分组。

## 上线开关

固件票据校验（EXT-04）与执行结果回传（EXT-05）确认前，`smartparking.ble.enabled` 保持 `false`：

- 申请票据返回 409 / 20010“蓝牙控制暂未开放”；
- 设备详情 `bleAvailable = false`，小程序不显示蓝牙入口，离线时只显示原因。

打开开关前还要补上票据载荷 `payload`（SN + 有效期 + HMAC，用产线写入的 deviceKey 签名），格式等固件确定，现在不返回（契约里非必填）。

## 申请票据

`POST /api/app/devices/{deviceId}/ble-tickets`，`{"action": "OPEN" | "CLOSE"}`。

| 顺序 | 检查 | 结果 |
| --- | --- | --- |
| 1 | 统一权限判定，`CONTROL` 级（[permission.md](permission.md)） | 10004 / 30005 / 20002 等 |
| 2 | `action` 缺失或不是 OPEN / CLOSE | 10001 |
| 3 | 开关关闭，或设备没有登记蓝牙 MAC | 20010 |
| 4 | 成功 | 返回 `ticketId`（128 位随机，十六进制）、`expireAt`（60 秒后）、`bleMac`（连接时比对）、`sn`、`action` |

- 不检查设备在线、休眠或是否有远程指令在执行：蓝牙就是离线时的兜底。
- 票据记下签发时的动作和角色（`ble_ticket.action / role`），上报时以此为准。
- 一次性由固件保证（同一票据只执行一次）；服务端保证一张票据只上报一次。

## 上报结果

`POST /api/app/ble-reports`，`{"reports": [...]}`，1–50 条。手机无网时缓存，恢复后补报。

整批校验，任一条不合法整批 10001、什么都不写：

- 缺 `ticketId` / `action` / `result` / `at`，或取值不在枚举内；
- `ticketId` 不是 32 位小写十六进制；
- `result = FAILED` 而没有 `failReason`，或 `SUCCESS` 却带了 `failReason`。

逐条处理，每条一个事务：

| 情况 | 结果 |
| --- | --- |
| 票据不存在、不是当前用户的、动作与签发时不一致 | 进 `rejected`，票据不受影响 |
| 已上报过（含同一批里重复的） | 忽略，计入 `accepted`：手机可以清掉缓存 |
| 其余 | 计入 `accepted`，票据记下结果 |

- 不检查上报时票据是否过期、用户是否仍有权限、设备是否已禁用：补报的是当时发生的事。
- 手机时钟可能不准：执行时刻 `at` 限定在票据的签发到过期之间（设备只在有效期内接受票据）。`durationMs` 不在 0–60000 内时不记录。

### 哪些进入操作记录

| 结果 | 写入操作记录 |
| --- | --- |
| `SUCCESS` | 是 |
| `FAILED` + `TICKET_REJECTED`（设备拒绝票据）、`DEVICE_ERROR`（设备执行出错） | 是，状态 `FAILED` |
| `FAILED` + `BLE_OFF`、`NOT_FOUND`、`CONNECT_FAILED`（没到达设备） | 否，只记在票据上 |

写入的是一条 `control_command`：通道 `BLE`、终态、`ble_ticket_id` 为票据、幂等键 `ble:<ticketId>`、`issued_at` 为执行时刻、`finished_at = issued_at + durationMs`。蓝牙失败原因不在契约的结果码枚举内，`result_code` 留空，原因记在 `ble_ticket.fail_reason`。

因此蓝牙操作与远程操作在同一张表里：操作记录（[records.md](records.md)）、最近一次操作、后台指令流水都能看到，可见范围规则相同。连续 3 次超时告警只看远程指令，蓝牙结果不打断、也不计入（[alarms.md](alarms.md)）。
