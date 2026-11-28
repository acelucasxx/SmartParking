# 转让与主人解绑

对应 BIZ-13（PRD 5.6、5.7①④）。代码在 `server/ownership` 的 `transfer` 与 `unbind` 包，接口见契约 [openapi-app.yaml](../api/openapi-app.yaml) 的 transfer 分组。强制解绑见 [admin-ops.md](admin-ops.md)。

| 接口 | 权限级别 | 说明 |
| --- | --- | --- |
| `GET /api/app/devices/{deviceId}/transfer` | `OWNER` | 进行中的转让；没有时 `data` 为 `null` |
| `POST /api/app/devices/{deviceId}/transfer` | `OWNER_ENABLED` | 发起转让，返回分享卡片 |
| `DELETE /api/app/devices/{deviceId}/transfer` | `OWNER` | 取消进行中的转让 |
| `GET /api/app/transfers/{token}` | 登录即可 | 打开转让卡片 |
| `POST /api/app/transfers/{token}/accept` | 登录即可 | 接收设备，返回新主人视角的车位卡片 |
| `POST /api/app/devices/{deviceId}/unbind` | `OWNER` | 主人解绑（设备禁用时也可以），前端二次确认 |

## 级联

接收转让、主人解绑、强制解绑都在同一事务内调用 `OwnershipRelease`（PRD 5.6⑦、5.7④），先锁设备行：

| 步骤 | 转让 | 解绑 / 强制解绑 |
| --- | --- | --- |
| 待接受邀请、有效转让卡片 | 作废 | 作废 |
| 生效中的被授权人 | 全部置为“已撤销” | 全部置为“已撤销” |
| 设备 | 换主人，`owner_since` 为此刻；名称、车位号保留，新主人可再修改 | 回到待激活，清空主人、名称、车位号 |
| 激活码 | 不变（PRD 5.6⑤） | 主人解绑不变；强制解绑可选重置 |
| `ownership_log` | `TRANSFER`，`from_user` 原主人，`to_user` 与操作人为接收人 | `UNBIND`（操作人为主人）/ `FORCE_UNBIND`（后台，带原因） |

原主人与原被授权人的下一个请求即失去权限（每个请求都重新判定，见 [permission.md](permission.md)）。新主人只能看到 `owner_since` 之后的操作记录，历史记录保留用于审计。

事务提交后发布事件，供站内消息（BIZ-15）通知原主人与被撤销的被授权人：转让发布 `DeviceTransferredEvent`，解绑发布 `DeviceUnboundEvent`。

## 发起与取消

- token 规则同邀请（[invites.md](invites.md)）：256 位随机、库里只存 SHA-256、一次性。卡片有效期取后台参数 `card.ttl_hours`（默认 24 小时）。
- 卡片标题：`{主人昵称}向你转让「{设备名称}」`，分享路径 `/pages/transfer/accept?token=…`。
- 同一设备同时只有一张有效卡片，已有时返回 30023。表上的唯一约束兜住并发发起。已过期而还没被标记的卡片会在发起前置为过期，不挡住新的转让。
- 发起不影响现有被授权人，接收时才清空。
- 取消只针对进行中、未过期的转让，没有则返回 10004。

## 预览

| `state` | 条件（按顺序） |
| --- | --- |
| `OWN_TRANSFER` | 看的人是发起人，或已是当前主人 |
| `ACCEPTED` | 已被接收 |
| `CANCELLED` | 已取消或已作废（解绑、强制解绑），或发起人已不是当前主人、其账号已被封禁（解封后恢复） |
| `EXPIRED` | 已过期 |
| `VALID` | 可以接收 |

## 接收

同一事务内先锁设备、再锁卡片，按以下顺序校验：

| 顺序 | 检查 | 结果 |
| --- | --- | --- |
| 1 | token 不存在 | 10004 |
| 2 | 自己是发起人或已是主人 | 30016 |
| 3 | 已取消 / 已作废；已被接收；已过期 | 30022；30021；30020 |
| 4 | 发起人已不是当前主人，或其账号已被封禁（[disable-ban.md](disable-ban.md)） | 30022 |
| 5 | 设备已禁用 | 20002 |

- 接收人原来是该设备的被授权人时，其授权一并结束，改以主人身份使用。
- 并发接收时只有一人成功。

## 主人解绑

事务内锁定设备行后，再确认调用人仍是主人：与并发的转让、强制解绑只有一方成功，后到的返回 10004。之后的处理见上方“级联”。重新绑定仍需 SN 与原激活码。

定时任务（`smartparking.transfer.expire-interval`，默认 5 分钟）把过期的进行中转让标为过期，只是让数据与状态一致；所有判定都按时间。
