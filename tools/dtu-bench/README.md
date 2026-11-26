# 真机联调工具

站在**云端一侧**，对真实 DTU（B100Ym-C1 + 闸板）跑查询 / 查版本 / 抬杆 / 落杆，记录时延与每条上行的原始字节，用来发现粘包、拆包与混在上行主题里的心跳包（IOT-11）。与之相对，[dtu-sim](../dtu-sim/README.md) 模拟的是设备侧。

完整的联调步骤、要回答的问题与记录表见 [docs/iot/bench.md](../../docs/iot/bench.md)。

## 用法

在 `server/` 目录执行：

```bash
./gradlew :dtu-bench:run --args="password --imei 860000000000009"
./gradlew :dtu-bench:run --args="run --imei 860000000000009 --rounds 20"
./gradlew :dtu-bench:run --args="listen --imei 860000000000009 --minutes 10"
```

| 子命令 | 作用 |
| --- | --- |
| `password` | 打印该 IMEI 的 MQTT 密码（一机一密），给 DTU 配参数用 |
| `run` | 每轮：复位到关闸 → 查询 → 查版本 → 抬杆 → 落杆，输出成功率与时延 |
| `listen` | 只收不发，记录全部上行 |

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--broker <url>` | `tcp://127.0.0.1:1883` | 也可用环境变量 `BROKER` |
| `--username <name>` | `smartparking-server` | 云端账号 |
| `--password <pwd>` | 环境变量 `MQTT_SERVER_PASSWORD`，再默认本地开发值 | |
| `--anonymous` | — | 不带用户名密码 |
| `--secret-key <key>` | 环境变量 `DEVICE_SECRET_KEY`，再默认本地开发值 | `password` 子命令用 |
| `--rounds <n>` | 10 | `run` 轮数 |
| `--timeout-s <s>` | 15 | 抬杆 / 落杆超时 |
| `--poll-ms <ms>` | 300 | 动作中查询间隔，也是到位时延的分辨率 |
| `--pause-ms <ms>` | 1000 | 轮间隔 |
| `--minutes <n>` | 10 | `listen` 时长 |
| `--out <dir>` | `bench-<imei>-<时间>` | 结果目录：`report.md`、`samples.csv`、`uplinks.csv` |

## 实现要点

- 与业务服务的 `MqttGateway` 一样：MQTT 5、QoS 1、下行带 15 秒消息过期时间。客户端 ID 为 `bench-随机`，可与业务服务同时在线。
- 协议没有请求编号，回包按“下发后到达的第一帧同类帧”对应。联调时须关闭业务服务的定时查询，见手册。
- `PayloadAnalyzer` 把每条载荷归为：恰好一帧、粘包、拆包前半、拆包后半（与上一条拼接）、非 RS485（疑似心跳）、无法识别。只做观察，不改变云端行为。
- 测试用 dtu-sim 冒充真机，经真实 EMQX（Testcontainers）跑完整剧本。
