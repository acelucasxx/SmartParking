# 统一权限判定

对应 BIZ-02。代码在 `server/control` 的 `access` 包。规则依据 PRD 第 9 章：设备为启用状态、用户未被封禁，且用户是设备主人，或是状态为生效、当前时间在有效期内的被授权人。

## 用法

小程序控制器方法的路径含 `{deviceId}` 时，必须标 `@DeviceAccess`，并按下表选级别。方法可以声明 `DeviceGrant` 参数，拿到判定结果：角色、被授权人记录 ID、有效期止、设备是否启用。

```java
@PostMapping("/api/app/devices/{deviceId}/commands")
@DeviceAccess(AccessLevel.CONTROL)
public ApiResponse<...> send(@PathVariable long deviceId, DeviceGrant grant, ...) { ... }
```

没有 `{deviceId}` 路径的流程（如接受邀请、接受转让）直接调用 `DeviceAccessService.check(userId, deviceId, level)`。

**强制**：服务启动时会检查全部接口映射，遇到以下任一情况直接拒绝启动：

- `/api/app/**` 下路径含 `{deviceId}`，却没有标 `@DeviceAccess`。
- 标了注解，路径里却没有 `{deviceId}`。
- 声明了 `DeviceGrant` 参数，却没有标注解。

后台接口 `/api/admin/**` 走 RBAC（BIZ-08），不经过这里。

## 级别

| 级别 | 谁可以 | 设备禁用时 |
| --- | --- | --- |
| `VIEW` | 主人、有效期内的被授权人 | 可以 |
| `CONTROL` | 主人、有效期内的被授权人 | 拒绝（20002） |
| `OWNER` | 主人 | 可以 |
| `OWNER_ENABLED` | 主人 | 拒绝（20002） |

“设备禁用时”一列取自接口契约：`x-error-codes` 里列了 20002 的接口要求设备启用。禁用后保留查看记录、成员管理与解绑（PRD 5.8①）。

### 契约中各接口的级别

| 接口 | 级别 |
| --- | --- |
| `GET /devices/{deviceId}`、`GET …/status`、`GET …/commands/{commandId}`、`GET …/records` | `VIEW` |
| `DELETE …/membership`（被授权人退出） | `VIEW` |
| `POST …/commands`、`POST …/ble-tickets` | `CONTROL` |
| `PATCH /devices/{deviceId}`、`GET …/members`、`DELETE …/members/{memberId}`、`DELETE …/invites/{inviteId}`、`GET` / `DELETE …/transfer`、`POST …/unbind` | `OWNER` |
| `PATCH …/members/{memberId}`、`POST …/invites`、`POST …/transfer` | `OWNER_ENABLED` |

## 判定顺序与错误码

| 顺序 | 情况 | 错误码 |
| --- | --- | --- |
| 1 | 用户已封禁 / 用户不存在 | 10010 / 10002 |
| 2 | 设备不存在，或用户与设备从无关系 | 10004 |
| 3 | 有被授权人记录，但当前不在有效期内（已过期、已撤销、已退出、尚未开始） | 主人级 30004，其余 30005 |
| 4 | 主人级接口，调用人是被授权人 | 30004 |
| 5 | 要求启用而设备已禁用 | 20002 |

- 设备不存在与“与我无关”返回同一个 10004，不向无关的人透露设备是否存在。禁用判定放在关系判定之后，无关的人也看不出设备是否被禁用。
- 有效期以 `valid_from ≤ 现在 < valid_to` 为准（`valid_to` 为空表示永久），**不依赖**到期任务（BIZ-12）先把状态改成 `EXPIRED`，到期那一刻起就拒绝。尚未开始的授权返回 30005，提示“授权尚未生效”。
- 主人以 `bind_status = BOUND` 且 `owner_user_id` 为本人为准。待激活设备即使残留 `owner_user_id`，也不认主人。
- 主人被封禁不影响被授权人（PRD 5.8②）；被封禁的用户自己的所有请求先被登录拦截器拒绝，判定服务里再查一次兜底。
- 一次判定只查一条 SQL，命中主键与 `uk_member_active` 索引。

## 与登录拦截器的关系

拦截器的执行顺序为：登录拦截器（用户域，order 0）确定当前用户 → 权限拦截器（order 100）判定设备权限。权限拦截器拿不到当前用户时按未登录拒绝，不放行。登录与会话见 [app-auth.md](app-auth.md)。
