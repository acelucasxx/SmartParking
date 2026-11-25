# 设备接入：MQTT 与一机一密

对应 PRD 8.1，实现于 IOT-04。代码在 `server/iot` 的 `mqtt`、`auth` 包，EMQX 配置在 `deploy/local/emqx`。

## 链路

```text
DTU（dtu-{imei}）──MQTT 3.1.1──▶ EMQX ──MQTT 5──▶ 业务服务（smartparking-server-xxxx）
                                 │
                                 └─ 连接时 HTTP 回调 /internal/emqx/authn 认证
```

| 项 | 约定 |
| --- | --- |
| 上行 | 设备发布 `parking/up/{imei}`；云端订阅 `parking/up/+`，QoS 1 |
| 下行 | 云端发布 `parking/down/{imei}`，QoS 1，带消息过期时间（默认 15 秒） |
| 设备身份 | 用户名 = IMEI，客户端 ID = `dtu-{imei}`，二者不一致拒绝连接 |
| 云端身份 | 用户名 `smartparking-server`，认证为超级用户；客户端 ID 每个实例随机生成，多实例不会互踢 |
| 会话 | 云端用干净会话，重启期间的上行不补收，由对账与定时查询补齐（PRD 8.4、8.7） |

## 一机一密

设备 MQTT 密码 = HMAC-SHA256(平台密钥, IMEI) 的前 16 字节，写成 32 位小写十六进制，见 `DeviceCredentials`。

- **为什么用派生**：产线在设备入库前就要预置 DTU 参数（PRD 5.1 第 1 步）。派生只需要 IMEI 和平台密钥，不依赖设备先入库，平台也不存任何设备密码。
- **平台密钥**：`smartparking.mqtt.auth.device-secret-key`，至少 32 字节，缺失或过短时服务拒绝启动。只放在密钥管理里，不进仓库；日志只打印 8 位密钥指纹（`keyId`），用于核对。
- **轮换**：密钥变更等于全部设备换密码，需要产线 / 运维重新下发 DTU 参数。一期不做多版本并存，轮换方案在上线前评审。
- **产线取密码**：后台导入、印刷文件导出时一并生成（DEV 线任务）；联调时 DTU 模拟器用同一算法自动计算。

## 认证与权限

EMQX 每次连接调用 `POST /internal/emqx/authn`，请求头带 `X-Emqx-Token`（`smartparking.mqtt.auth.hook-token`），令牌不对返回 403。判定规则：

| 情况 | 结果 |
| --- | --- |
| 云端账号且密码正确 | 允许，超级用户 |
| 用户名是 15 位数字、客户端 ID 为 `dtu-{imei}`、IMEI 已在 `device` 表、密码等于派生值 | 允许 |
| 其余（匿名、密码错、未登记 IMEI、客户端 ID 不符） | 拒绝，服务端日志写明原因 |

主题权限由 `acl.conf` 控制：设备只能发布 `parking/up/{自己的 IMEI}`、订阅 `parking/down/{自己的 IMEI}`，冒用他人主题的消息会被丢弃。

服务端还有第二道检查：`UplinkDispatcher` 对每条上行再查一次 IMEI，未登记的丢弃并按 IMEI 每 10 分钟告警一次；主题或帧结构不合法也只记日志、不抛异常。合法帧解码后发布 `DeviceUplinkEvent`，回显帧、版本帧同样发布，供在线判定刷新 `last_seen_at`。

被禁用的设备（`enabled = 0`）仍可连接和上报：禁用只阻止控制（PRD 5.8）。

## 离线不排队

DTU 若使用持久会话，EMQX 会替离线设备暂存 QoS 1 消息，设备重连后照常执行，这正是 PRD 5.3④ 要避免的（DTU 模拟器测试已复现）。云端下发一律带 MQTT 5 消息过期时间 `smartparking.mqtt.downlink-expiry`，过期即被 EMQX 丢弃。集成测试 `MqttAccessIntegrationTest.downlinkExpiresWhileDeviceIsOffline` 验证了这一点：过期时间 2 秒、设备 4 秒后重连，指令不再执行。

这只是兜底，控制接口仍须在下发前判定在线（IOT-07、IOT-09）。

## 生产部署要求

- 设备只走 8883（TLS），1883 不对公网开放（INF-06）。
- Nginx 不得转发 `/internal/`；认证回调只在内网由 EMQX 访问。
- 通过环境变量提供 `MQTT_SERVER_PASSWORD`、`EMQX_HOOK_TOKEN`、`DEVICE_SECRET_KEY` 对应的配置，不得沿用本地开发值。
- 业务服务不可用时设备无法连接；业务服务恢复后，设备按 DTU 自身的重连策略重新上线。

## 待确认

- DTU 是否支持 MQTT 用户名密码、长度上限（B100Ym-C1 参数表，PRD 12.1 第 5 项）。32 位十六进制若超长需缩短。
- DTU 心跳 / 注册包是否发到上行主题（PRD 12.1 第 11 项）；若会，需在 `UplinkDispatcher` 里与 RS485 帧区分。
