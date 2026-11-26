# 小程序登录与会话

对应 BIZ-01：code2session、手机号授权、会话、手机号加密存储、封禁拦截。代码在 `server/user`，接口见契约 [openapi-app.yaml](../api/openapi-app.yaml) 的 auth 分组。

## 接口

| 接口 | 说明 | 可能的错误码 |
| --- | --- | --- |
| `POST /api/app/auth/login` | `wx.login` 的 code 换会话；首次登录自动注册 | 10001、10010、10020 |
| `POST /api/app/auth/phone` | 手机号快速验证组件的动态 code 换手机号，加密存储；重复授权以最新号码为准 | 10001、10021 |
| `POST /api/app/auth/logout` | 作废当前会话；已封禁用户也可调用 | — |
| `GET /api/app/me` | 昵称、是否已授权手机号、脱敏手机号 | — |
| `PATCH /api/app/me` | 修改昵称（去首尾空白后 1–32 个字符） | 10001 |

未设置昵称时返回“微信用户”：微信已不再向小程序提供用户真实昵称。

## 会话

- 登录成功签发 256 位随机 token（URL 安全 Base64，43 个字符），**不是 JWT**：退出登录与封禁要立即生效，自包含令牌做不到。
- Redis 只存 token 的 SHA-256：`sp:app-session:<哈希>` → 用户 ID，TTL 为 `smartparking.user.session-ttl`（默认 7 天，不续期）。到期后接口返回 401，前端重新 `wx.login` 静默登录即可。
- 同一用户可同时持有多个会话（多次登录不互踢），并记在 `sp:app-user-sessions:<用户 ID>` 集合里。`SessionStore.revokeAll` 据此一次作废全部会话，供后台封禁（BIZ-16）与注销调用。

## 登录与封禁拦截

`/api/app/**` 除登录接口外都经过拦截器：

| 情况 | 响应 |
| --- | --- |
| 没有 `Authorization: Bearer`、token 无效或过期 | 401 / 10002 |
| 会话对应的用户已不存在 | 401 / 10002，并作废该会话 |
| 用户已封禁（PRD 5.8②） | 403 / 10010；退出登录除外 |

封禁状态每个请求都查库（主键查询），后台封禁立即生效，不必等会话过期。已封禁用户重新登录同样返回 10010，不签发会话。

其他模块的小程序控制器声明 `AppPrincipal` 类型参数即可拿到当前用户 ID，只依赖 `common`，不依赖用户域。

## 手机号加密存储

| 列 | 存法 |
| --- | --- |
| `phone_cipher` | AES-256-GCM，`版本(1) + IV(12) + 密文 + 标签(16)`，11 位号码共 40 字节；版本号参与认证，便于日后轮换 |
| `phone_hash` | HMAC-SHA256 十六进制，后台按手机号精确查询用（BIZ-09） |

加密密钥与哈希密钥由同一主密钥 `smartparking.user.data-key`（环境变量 `USER_DATA_KEY`，至少 32 字节）按用途派生。缺少或过短时服务拒绝启动。**主密钥一旦换掉，已有手机号无法解密**，轮换须新增密钥版本并迁移，不能直接替换。

界面一律脱敏为前 3 位 + `****` + 后 4 位。中国大陆号码存 11 位纯号码，其他地区存 `+区号号码`。

## 微信接口

| 用途 | 接口 |
| --- | --- |
| 登录 | `GET /sns/jscode2session` |
| 手机号 | `POST /wxa/business/getuserphonenumber` |
| 接口调用凭证 | `POST /cgi-bin/stable_token`：稳定版凭证，多实例各自获取不会互相作废；本地缓存到过期前 5 分钟，微信报凭证无效（40001 / 40014 / 42001）时强制刷新并重试一次 |

- session_key 不落库也不返回：手机号走新版接口，不需要它解密。
- 日志与异常信息里只有接口名与微信错误码，不含 AppSecret、access_token、session_key。网络异常不挂原始异常，因为其消息里有带 AppSecret 的完整 URL。
- 超时：连接 3 秒、读取 5 秒（`smartparking.wechat.connect-timeout` / `read-timeout`）。

### 配置

| 配置 | 环境变量（本地） | 说明 |
| --- | --- | --- |
| `smartparking.wechat.mode` | `WECHAT_MODE` | `real`（默认）或 `mock`；本地 profile 默认 `mock` |
| `smartparking.wechat.app-id` | `WECHAT_APP_ID` | EXT-10 下来后提供 |
| `smartparking.wechat.app-secret` | `WECHAT_APP_SECRET` | 只放在密钥管理中 |
| `smartparking.user.data-key` | `USER_DATA_KEY` | 手机号加密主密钥 |

`real` 模式缺少 AppID 或 AppSecret 时服务拒绝启动。

### mock 模式（本地开发与测试）

小程序资质（EXT-10）下来前，用 mock 模式联调，不出网。**生产不得启用**，启动时会打 WARN。

| 输入 | 结果 |
| --- | --- |
| 登录 code 为 `test:<名字>` | openid `mock_<名字>`，用来造多个用户 |
| 其他登录 code（如开发者工具里 `wx.login` 的真实 code） | 都是同一个用户 `mock_devtools` |
| 手机号 code 为 1 开头的 11 位数字 | 返回该号码 |
| 其他手机号 code | `13800138000` |
| code 为 `fail` | 模拟微信失败（10020 / 10021） |

```bash
curl -s localhost:8080/api/app/auth/login -H 'Content-Type: application/json' -d '{"code":"test:alice"}'
```
