# 微信订阅消息

对应 BIZ-18（PRD 6.1 消息通知）。代码在 `server/notify` 的 `subscribe` 包，微信接口在 `server/user` 的 `wechat` 包。

站内消息（[messages.md](messages.md)）是准，订阅消息只是提醒：每写入一条站内消息，若该类型配置了模板，就给接收人再推一条订阅消息。

## 上线前要做的

模板审核（EXT-15）通过后：

1. 在配置里打开开关，为每种要推送的消息类型填模板 ID 和字段（下节）。PRD 列的四种是授权成功 `GRANT`、被撤销 `REVOKE`、转让完成 `TRANSFER`、设备故障 `FAULT`；其余类型（`EXPIRE`、`UNBIND`、`SYSTEM`）配置了也会推送。
2. 小程序在合适的页面（授权、接受邀请、转让、设备详情等）用 `wx.requestSubscribeMessage` 请用户订阅。一次性订阅每次授权只能推一条，用户没订阅时微信返回 43101，服务端只记 debug 日志。

## 配置

前缀 `smartparking.wechat.subscribe`，默认 `enabled: false`、没有模板，什么都不推。

```yaml
smartparking:
  wechat:
    subscribe:
      enabled: true
      state: formal            # 点击跳转的小程序版本：formal / trial / developer
      templates:
        GRANT:
          template-id: <审核通过的模板 ID>
          page: pages/messages/index   # 可省略
          data:
            thing1: "{device}"
            thing2: "{title}"
            time3: "{time}"
```

字段名（`thing1`、`time3` 等）以审核通过的模板为准。取值可以是固定文字，也可以含占位符：

| 占位符 | 内容 |
| --- | --- |
| `{title}` | 站内消息标题 |
| `{content}` | 站内消息正文 |
| `{device}` | 设备名称，未命名时为 SN；消息不关联设备时为空 |
| `{time}` | 消息时间，`yyyy-MM-dd HH:mm`（北京时间） |

字段值按字段类型截断，超出时最后一个字换成“…”，避免微信因超长拒收（47003）：

| 字段类型 | 上限（字） |
| --- | --- |
| `thing` | 20 |
| `name` | 10 |
| `phrase`、`symbol` | 5 |
| `character_string`、`letter`、`number` | 32 |
| 其他（`time`、`date`、`amount` 等） | 不截断 |

## 发送

- 在独立线程池里异步发送（2 个线程，最多排队 1000 条，满了丢弃并记日志），不拖慢业务请求。
- 接收人已封禁或不存在不发。
- 失败只记日志，不重试，不影响业务与站内消息。微信报接口调用凭证失效（40001 / 40014 / 42001）时强制刷新凭证并重试一次，与手机号接口相同。
- 本地开发与测试用 `smartparking.wechat.mode=mock`：不出网，openid 为 `mock_unsubscribed` 时模拟未订阅。
