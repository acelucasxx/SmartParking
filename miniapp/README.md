# 小程序

原生微信小程序 + TypeScript（MP-01 骨架）。用微信开发者工具打开 `miniapp/` 目录。

## 目录

| 路径 | 内容 |
| --- | --- |
| `miniprogram/api/client.ts` | 请求核心：会话、静默登录（并发只登录一次）、登录失效自动重登并重试一次、统一信封解析、PATCH 改写 |
| `miniprogram/api/errors.ts` | 错误码表与处理方式 `errorAction`：`relogin` / `banned` / `leave` / `support` / `retry` / `show` |
| `miniprogram/api/types.ts` | 契约中多个页面共用的类型 |
| `miniprogram/api/index.ts` | 接入 `wx.request` / `wx.login` / 本地存储，导出全局 `api`；`onBanned` 监听账号停用 |
| `miniprogram/mock/` | 不连后端时的模拟数据与接口 |
| `miniprogram/components/` | 基础组件：`status-badge` 状态徽标、`battery-icon` 电量图标、`confirm-dialog` 确认弹窗（已在 `app.json` 全局注册） |
| `miniprogram/utils/format.ts` | 徽标文案、电量分格、卡片标题、时间与有效期（按东八区）、幂等键 |
| `test/` | 纯逻辑的单元测试（Node 内置测试框架，不依赖微信环境） |

## 用法

```ts
import { api, isApiError } from '../../api/index'

try {
  const cards = await api.get<DeviceCard[]>('/devices')
} catch (e) {
  if (isApiError(e) && e.action === 'leave') wx.switchTab({ url: '/pages/index/index' })
}
```

- 路径不含 `/api/app` 前缀。除 `auth: false` 的请求外都会先确保已登录。
- `wx.request` 不支持 PATCH：`api.patch()` 自动改成 `POST` + `X-HTTP-Method-Override: PATCH`，服务端按 PATCH 处理。
- 错误统一抛 `ApiError`，`message` 优先用服务端的提示，可直接展示；是否弹 toast 由页面决定（控制页把错误显示在按钮附近）。

## 登录与手机号授权

- 启动即静默登录（`wx.login`），不弹任何授权。
- 手机号在需要时才授权：扫码绑定等操作前调用 `requirePhone(当前页面路径)`（`auth/index.ts`），未授权时打开登录授权页 `pages/login`，授权后回到原页面。平台规范不允许启动时强制获取手机号。
- 授权前必须勾选同意《用户协议》《隐私政策》（`pages/agreement`）。按钮为 `getPhoneNumber|agreePrivacyAuthorization`，一次点击同时完成微信隐私授权；`app.json` 开启了 `__usePrivacyCheck__`，小程序后台需填写隐私保护指引（手机号、蓝牙、位置）。
- 协议文本在 `content/agreements.ts`，**目前是占位文本**，待 EXT-16 提供正式条款后替换，并修改 `AGREEMENT_VERSION`（已同意旧版的用户会被要求重新同意）。
- 账号停用（10010）时跳到 `pages/banned`，只保留联系客服与退出登录。

## 连后端还是用 mock

`miniprogram/config.ts`：

- `mock: false`（默认）：连 `apiBase`。本地后端用 `smartparking.wechat.mode=mock` 启动时，开发者工具里的任何 `wx.login` code 都能登录；开发者工具需勾选“不校验合法域名”。
- `mock: true`：不连后端。用户初始未授权手机号（手机号 code 规则同服务端 mock：1 开头的 11 位数字即该号码，`fail` 模拟失败）。内置 4 台示例设备（主人在线 / 被授权人休眠低电量 / 主人离线 / 已停用），指令按时间推进：下发后 0.5 秒“已下发”、2 秒内“执行中”、2 秒后到位。还没实现的接口返回“mock 未实现：方法 路径”，在对应页面任务里补。

## 检查

```bash
npm run typecheck
```

```bash
npm test
```

CI 的 `miniapp · typecheck` 任务两项都会跑。
