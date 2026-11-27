# 成员管理

对应 BIZ-12（PRD 5.5⑤⑥、6.1 页面 6）。代码在 `server/member` 的 `members` 包，接口见契约 [openapi-app.yaml](../api/openapi-app.yaml) 的 member 分组。邀请见 [invites.md](invites.md)。

| 接口 | 权限级别 | 说明 |
| --- | --- | --- |
| `GET /api/app/devices/{deviceId}/members` | `OWNER` | 成员列表与名额（设备禁用时也可以） |
| `PATCH /api/app/devices/{deviceId}/members/{memberId}` | `OWNER_ENABLED` | 修改有效期或备注 |
| `DELETE /api/app/devices/{deviceId}/members/{memberId}` | `OWNER` | 撤销授权 |
| `DELETE /api/app/devices/{deviceId}/membership` | `VIEW` | 被授权人主动退出 |

“生效中”指 `status = ACTIVE` 且未过 `valid_to`，与权限判定（[permission.md](permission.md)）、名额口径一致：授权到期那一刻起就不再列出、不再占名额，也不能修改或撤销，不等到期任务。

## 列表

- `members`：生效中的被授权人（含尚未开始的），按开始时间排列。昵称为空时显示“微信用户”。
- `invites`：未过期的待接受邀请，按发出时间排列。
- `used` = 两者之和；`limit` 取后台参数 `member.max_per_device`（默认 5）。

## 修改

只改请求里出现的字段，立即生效。空对象、未知字段返回 10001；授权不属于本设备、已结束或已到期返回 10004。

| `period.type` | 效果 |
| --- | --- |
| `PERMANENT` | 止改为永久，起不变 |
| `RELATIVE` | 止 = 修改时刻 + 1 / 7 / 30 天；尚未开始的授权同时改为立即开始 |
| `CUSTOM` | 起止都按新值，规则同邀请（按分钟截断，止晚于起与当前时间）；起在将来时，被授权人立即失去访问，到点恢复 |

- 有效期不合法返回 30011。
- `remark` 去首尾空白后至多 32 个字符；`null` 或空串表示清空。
- 修改后发布 `MemberUpdatedEvent`，`periodChanged` 表示有效期是否变化，供站内消息（BIZ-15）通知被授权人。

## 撤销与退出

- **撤销**：置为 `REVOKED` 并记 `ended_at`，立即释放名额，被授权人的下一个请求即被拒绝（30005）。已撤销的再撤销视为成功；已退出、已到期或不属于本设备返回 10004。
- **退出**：被授权人本人置为 `QUIT`，设备禁用时也可以。主人调用返回 10001（主人应解绑设备）。不在有效期内（含已退出、尚未开始）返回 30005（权限判定）。
- 两者都发布 `MemberEndedEvent`（`REVOKED` / `QUIT`），供站内消息分别通知被授权人和主人。

## 到期自动失效

定时任务（`smartparking.member.expire-interval`，默认 60 秒）：

- 把已过 `valid_to` 的生效授权置为 `EXPIRED`，`ended_at` 记**到期时刻**，而不是处理时刻。
- 逐条条件更新：多实例同时执行时，每条只有一方生效，只通知一次。
- 每条发布 `MemberEndedEvent`（`EXPIRED`）。
- 每轮最多 500 条，积压时分多轮处理。
