# 接口契约 v1

小程序与管理后台的全部接口，前后端以此为准并行开发。依据 PRD V1.1。

| 文件 | 内容 |
| --- | --- |
| [openapi-app.yaml](openapi-app.yaml) | 小程序接口，前缀 `/api/app`，33 个操作 |
| [openapi-admin.yaml](openapi-admin.yaml) | 管理后台接口，前缀 `/api/admin`，38 个操作 |
| [common.yaml](common.yaml) | 共用组件：响应信封、错误码、分页、枚举 |
| [redocly.yaml](redocly.yaml) | 契约校验配置 |

## 变更规则

- v1 在第 2 周末冻结。之后的改动走 PR，修改 `info.version`，并在 PR 描述里说明对前端的影响，通知两条前端线。
- 只做向后兼容的改动：可以加接口、加可选字段、加错误码；删除或改名需要先在评审中确认。
- 新增错误码按顺序改三处：`common.yaml` → `ErrorCode.java` → 本文错误码表。`ErrorCodeContractTest` 校验三处一致，并校验每个接口 `x-error-codes` 里的错误码都已定义。

## 通用约定

| 项 | 约定 |
| --- | --- |
| 响应信封 | `{"code": 0, "message": "成功", "data": ...}`。`code = 0` 为成功；失败时 `data` 缺省，`message` 可直接展示 |
| HTTP 状态 | 与错误码对应（见下表）。前端以 `code` 判断业务结果，以 401 统一处理登录失效 |
| 认证 | `Authorization: Bearer <token>`；小程序与后台的 token 互不通用 |
| 时间 | ISO 8601 带偏移，统一东八区，如 `2026-12-31T18:00:00+08:00` |
| ID | 64 位整数；当前规模不会超出 JS 安全整数范围 |
| 分页 | 请求 `page`（从 1 开始）、`size`（默认 20，最大 100）；响应 `{total, page, size, items}` |
| 空值 | 可空字段返回 `null`，不省略 |
| 幂等 | 控制指令必须带 `idempotencyKey`（每次点击一个 UUID，重试复用） |
| 一次性明文 | 激活码、邀请 / 转让 token、后台临时密码只在生成它的那次响应中出现 |
| 扩展字段 | `x-error-codes`：该接口可能返回的业务错误码；`x-roles`（仅后台）：可调用的角色 |

## 本地 mock

前端在后端接口完成前用 [Prism](https://github.com/stoplightio/prism) 按契约起 mock，返回值取自 schema 中的 `example`：

```bash
cd docs/api && npx @stoplight/prism-cli@5.14.2 mock -p 4010 openapi-app.yaml
```

```bash
cd docs/api && npx @stoplight/prism-cli@5.14.2 mock -p 4011 openapi-admin.yaml
```

- 没带 `Authorization` 头时返回 401，和真实服务一致。
- 模拟错误：请求头加 `Prefer: code=409`；随机数据：加 `Prefer: dynamic=true`。
- Prism 会校验请求参数，不符合契约时返回 422 并在终端打印原因，可用来自查。

## 校验

```bash
cd docs/api && npx @redocly/cli@1.34.5 lint
```

CI 的 `api · lint` 任务在每个 PR 上执行同样的检查。

## 错误码表

区段：1xxxx 通用，2xxxx 设备与控制，3xxxx 归属与授权，4xxxx 后台。提示文字是默认值，具体场景可以带补充信息（如“激活码错误，还可尝试 3 次”）。

| 编码 | 名称 | HTTP | 默认提示 |
| --- | --- | --- | --- |
| 0 | `OK` | 200 | 成功 |
| 10001 | `BAD_REQUEST` | 400 | 请求参数不合法 |
| 10002 | `UNAUTHORIZED` | 401 | 未登录或登录已过期 |
| 10003 | `FORBIDDEN` | 403 | 无权执行该操作 |
| 10004 | `NOT_FOUND` | 404 | 资源不存在 |
| 10009 | `CONFLICT` | 409 | 状态冲突，请刷新后重试 |
| 10010 | `USER_BANNED` | 403 | 账号已停用 |
| 10011 | `TOO_MANY_REQUESTS` | 429 | 操作过于频繁，请稍后再试 |
| 10020 | `WX_LOGIN_FAILED` | 502 | 微信登录失败，请重试 |
| 10021 | `WX_PHONE_FAILED` | 502 | 获取手机号失败，请重试 |
| 19999 | `INTERNAL_ERROR` | 500 | 系统繁忙，请稍后重试 |
| 20001 | `DEVICE_NOT_FOUND` | 404 | 设备不存在 |
| 20002 | `DEVICE_DISABLED` | 403 | 设备已停用，请联系客服 |
| 20003 | `DEVICE_OFFLINE` | 409 | 设备离线，可在现场使用蓝牙 |
| 20004 | `DEVICE_SLEEPING` | 409 | 设备暂时无法远程控制，可用蓝牙 |
| 20005 | `COMMAND_IN_PROGRESS` | 409 | 设备正在执行上一条指令 |
| 20006 | `DEVICE_FAULT` | 409 | 设备故障，请联系客服 |
| 20010 | `BLE_NOT_AVAILABLE` | 409 | 蓝牙控制暂未开放 |
| 20011 | `BLE_TICKET_INVALID` | 400 | 蓝牙票据无效或已过期 |
| 30001 | `ACTIVATE_CODE_WRONG` | 400 | 激活码错误 |
| 30002 | `ACTIVATE_LOCKED` | 429 | 错误次数过多，请 30 分钟后再试 |
| 30003 | `DEVICE_ALREADY_BOUND` | 409 | 该设备已有主人 |
| 30004 | `NOT_OWNER` | 403 | 仅主人可执行该操作 |
| 30005 | `ACCESS_EXPIRED` | 403 | 授权已到期或已被撤销 |
| 30010 | `MEMBER_LIMIT_REACHED` | 409 | 成员名额已满 |
| 30011 | `INVALID_PERIOD` | 400 | 有效期不合法 |
| 30012 | `INVITE_EXPIRED` | 409 | 邀请已过期 |
| 30013 | `INVITE_USED` | 409 | 邀请已被他人接受 |
| 30014 | `INVITE_CANCELLED` | 409 | 邀请已撤销 |
| 30015 | `ALREADY_MEMBER` | 409 | 你已是该设备的成员 |
| 30016 | `CANNOT_ACCEPT_OWN` | 400 | 不能接受自己发出的邀请或转让 |
| 30020 | `TRANSFER_EXPIRED` | 409 | 转让卡片已过期 |
| 30021 | `TRANSFER_USED` | 409 | 转让卡片已被他人接受 |
| 30022 | `TRANSFER_CANCELLED` | 409 | 转让已取消 |
| 30023 | `TRANSFER_PENDING_EXISTS` | 409 | 已有进行中的转让 |
| 40001 | `LOGIN_FAILED` | 401 | 用户名或密码错误 |
| 40002 | `ACCOUNT_LOCKED` | 403 | 账号已锁定，请稍后再试 |
| 40003 | `ACCOUNT_DISABLED` | 403 | 账号已停用 |
| 40010 | `SN_DUPLICATE` | 409 | SN 已存在 |
| 40011 | `IMEI_INVALID` | 400 | IMEI 不合法 |
| 40012 | `IMEI_DUPLICATE` | 409 | IMEI 已存在 |
| 40013 | `MAC_DUPLICATE` | 409 | 蓝牙 MAC 已存在 |
| 40014 | `IMPORT_FILE_INVALID` | 400 | 导入文件格式不正确 |
| 40015 | `PRINT_ALREADY_EXPORTED` | 409 | 印刷文件已导出过 |
| 40020 | `REASON_REQUIRED` | 400 | 必须填写原因 |
| 40021 | `DEVICE_NOT_BOUND` | 409 | 设备未绑定 |
| 40030 | `USERNAME_EXISTS` | 409 | 用户名已存在 |

## 需要产品确认的取舍

- **邀请有效期**：“1 / 7 / 30 天”从接受邀请时起算；修改有效期时从修改时起算。
- **扫码预检** `GET /bind/{sn}`：会告诉扫码人该 SN 是否已被绑定（不透露主人信息），便于直接显示“该设备已有主人”。
- **操作记录**：主人看到的操作人显示被授权人的备注（如“爸爸”），没有备注时显示昵称。
- **后台账号**：由超级管理员创建，服务端生成临时密码，首次登录必须修改。
