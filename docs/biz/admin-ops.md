# 后台运维操作

对应 BIZ-10（PRD 5.7、5.8、6.2 设备运维操作）。代码在 `server/admin` 的 `ops` 包；解绑级联在 `server/ownership` 的 `unbind` 包，自检在 `server/iot` 的 `selfcheck` 包。接口见契约 [openapi-admin.yaml](../api/openapi-admin.yaml) 的 device 分组。

| 接口 | 角色 | 操作日志 |
| --- | --- | --- |
| `POST /api/admin/devices/{deviceId}/force-unbind` | 超管、运维、客服 | `DEVICE_FORCE_UNBIND` |
| `POST /api/admin/devices/{deviceId}/reset-activate-code` | 超管、运维、客服 | `DEVICE_RESET_CODE` |
| `POST /api/admin/devices/{deviceId}/disable`、`/enable` | 超管、运维 | `DEVICE_DISABLE`、`DEVICE_ENABLE` |
| `POST /api/admin/devices/{deviceId}/replace-part` | 超管、运维 | `DEVICE_REPLACE_PART` |
| `POST /api/admin/devices/{deviceId}/self-check` | 超管、运维 | `DEVICE_SELF_CHECK` |

- 除自检外都是高危操作，必须填原因：去首尾空白后 2–255 个字符。缺少或过短返回 40020，超长返回 10001。原因写入操作日志的 `reason`。
- 设备不存在 10004。成功与失败都写操作日志（[admin-auth.md](admin-auth.md)）。激活码明文只在响应中出现，不进日志。

## 强制解绑

用于抢绑申诉、二手转卖未转让等场景。未绑定的设备返回 40021。

同一事务内（`OwnershipRelease`，与主人解绑、转让共用，见 [transfer-unbind.md](transfer-unbind.md)）完成以下操作：
1. 锁定设备行。
2. 作废全部待接受邀请与有效转让卡片（`VOIDED`）。
3. 全部生效中的被授权人置为“已撤销”，并记 `ended_at`；已失效的记录不动。
4. 设备回到待激活：清空主人、成为主人的时刻、名称与车位号。
5. 写 `ownership_log`（`FORCE_UNBIND`，操作人为后台账号，带原因）。

设备记录与历史指令保留，用于审计。原主人与被授权人的下一个请求即失去权限（每个请求都重新判定，见 [permission.md](permission.md)）。

- `resetActivateCode = true` 时同一事务内换新激活码，并返回明文（只出现这一次），同时清除该 SN 的绑定错误计数，申诉人可立即用新码绑定。不重置时 `activateCode` 为 `null`。
- 事务提交后发布 `DeviceUnboundEvent`（原主人、被撤销的被授权人），供站内消息（BIZ-15）给他们各发一条。

## 重置激活码

旧码立即失效，新码只在响应中出现。绑定关系不变，同时清除该 SN 的绑定错误计数与锁定。

## 禁用 / 启用

只改 `enabled`，绑定与授权关系保留。禁用后，控制、邀请、转让接口拒绝（20002，见 [permission.md](permission.md)）。重复禁用、重复启用视为成功，操作日志的明细 `changed` 记录这次是否真的改变了状态。

## 换件

只改 IMEI（`DTU`，换 4G 模块）或蓝牙 MAC（`BOARD`，换主板），SN 与绑定、授权关系不变。写 `device_part_log`（旧值、新值、操作人、原因），返回换件后的设备。

| 情况 | 结果 |
| --- | --- |
| IMEI 不是 15 位数字或 Luhn 校验不过 | 40011 |
| IMEI 已被其他设备使用 | 40012 |
| MAC 格式不对或为空 | 10001（接受 `AA:BB:…`、`aa-bb-…` 或不分隔，统一存大写冒号分隔） |
| MAC 已被使用 | 40013 |
| 与当前值相同、`part` 缺失或不合法 | 10001 |

换 4G 模块后，旧 IMEI 立即不再登记，旧模块的上行被丢弃（[device-access.md](../iot/device-access.md)）。新模块用新 IMEI 派生的密码连接 EMQX。旧模块已建立的 MQTT 连接不会被主动踢下线，只是它的消息不再被处理。

## 设备自检

只下发查询状态（`D0 C0`）或查询版本（`CF`），不动闸杆。接口立即返回新建的指令（`CommandAudit`），结果用 `GET /api/admin/commands/{commandId}` 轮询。

- 与 App 抬杆 / 落杆共用“同一设备同时最多一条未终结指令”：有未终结指令时返回 20005；自检进行中，App 指令同样返回 20005。
- 离线 20003、休眠 20004，判定与远程控制相同。
- 收到状态帧（查询状态）或版本帧（查询版本）即成功，`rawFrame` 为该帧。回显帧不算。超时取后台参数 `command.timeout_seconds`（默认 15 秒），超时记 `TIMEOUT`。
- 查询版本成功时，指令记 `fwVersion`，并更新设备的 `fw_version`。版本号暂记为版本字节的两位十六进制，显示格式待真机联调（IOT-11）确认。
- 进程重启等原因没有收尾的自检，由定时任务在超过“超时 + 5 秒”后记为超时。
- 自检指令与 App 指令同表，但相互隔离：
  - 自检不发布 `CommandFinishedEvent`，不计入“连续 3 次超时”告警（[alarms.md](alarms.md)）和今日成功率。
  - 指令状态机、对账与小程序接口只处理 App 抬杆 / 落杆。
