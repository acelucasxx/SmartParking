# DTU 模拟器

冒充“4G DTU + 道闸板”，在没有真机时联调设备接入、指令状态机与在线判定。协议编解码直接复用 `server/iot` 的 `Rs485Codec`，与云端只有一套实现。

它模拟的是**设备侧**：订阅 `parking/down/{imei}`，按协议回包并推进闸杆状态，向 `parking/up/{imei}` 发布上行帧（PRD 8.1、8.2）。

## 运行

先按 [deploy/local/README.md](../../deploy/local/README.md) 启动本地 EMQX，然后在 `server/` 目录执行：

```bash
./gradlew :dtu-sim:run --args="--count 3 --travel-ms 500"
```

启动后在同一终端输入控制台命令，`help` 查看全部命令。

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--broker <url>` | `tcp://127.0.0.1:1883` | 也可用环境变量 `BROKER` |
| `--count <n>` | 1 | 设备数量，1–1000 |
| `--imei-prefix <14 位>` | `86000000000000` | 从该值起顺序生成 IMEI，并补 Luhn 校验位，能通过后台导入校验 |
| `--imeis <a,b,…>` | — | 直接指定 IMEI，优先于 `--count` |
| `--travel-ms <ms>` | 1200 | 抬杆 / 落杆从开始到到位的耗时 |
| `--no-echo` | 回显 | 抬杆 / 落杆不回 6 字节回显帧 |
| `--active-report` | 关闭 | 状态变化时主动上报状态帧 |
| `--clean-session` | 持久会话 | 使用干净会话 |
| `--keep-alive <s>` | 60 | MQTT 心跳 |
| `--username` / `--password` | — | MQTT 认证，IOT-04 接入一机一密后使用 |

收发帧明细在 DEBUG 级别，用环境变量 `DTU_SIM_LOG=DEBUG` 打开。

## 设备行为

默认按“闸板不主动上报”建模（PRD 12.1 第 10 项尚未确认），结果只能靠云端查询发现：

| 收到 | 闸板行为 |
| --- | --- |
| 查询 `D0 C0` | 回状态帧：当前状态 + 最近一次动作原因 + 车位 |
| 抬杆 `03` / 落杆 `05` | 先回回显帧；状态变为开闸中 / 关闸中，经过动作耗时后到位，原因码 `16`（平台指令） |
| 已在目标状态时抬杆 / 落杆 | 只回回显，状态不变 |
| 查版本 `CF` | 回版本帧，默认版本 `16` |
| 非法帧、未知命令、其他地址 | 不回包 |

上电状态为关闸到位，原因码 `15`（上电找零）。

## 控制台命令

`<设备>` 可以是 IMEI、序号（从 1 开始）或 `all`。

| 命令 | 作用 |
| --- | --- |
| `list` | 列出设备、连接与闸杆状态 |
| `fault <设备> motor` | 下一次动作变为电机故障 `E3`，保持到 `repair` |
| `fault <设备> obstructed` / `infrared` | 下一次动作进行到一半停止：状态 `00`，原因 `11` / `12` |
| `fault <设备> stall` | 下一次动作一直停在开闸中 / 关闸中，用于验证 15 秒超时 |
| `repair <设备>` | 清除电机故障与未触发的异常，回到关闸到位 |
| `travel <设备> <ms>` | 调整动作耗时（到位延迟） |
| `echo <设备> on\|off` | 是否回显 |
| `silent <设备> on\|off` | 闸板完全不响应，模拟 RS485 故障 |
| `report <设备> on\|off` | 是否主动上报 |
| `car <设备> on\|off` | 车位有车 / 无车 |
| `trigger <设备> open\|close remote\|sensor\|switch\|auto` | 模拟遥控器、地感、外部开关、延时自动关触发的动作，原因码按 PRD 8.3 取 |
| `drop <设备> <秒>` | 不发 DISCONNECT 直接断开 TCP（同断电、掉网），到时自动重连 |
| `quit` | 退出 |

`fault` 只作用于下一次抬杆 / 落杆，触发后自动清除。

## 持久会话与离线指令

默认使用持久会话，与 DTU 的常见配置一致。此时设备离线期间下发的指令会由 EMQX 暂存，重连后照常执行。单测 `persistentSessionDeliversCommandSentWhileOffline` 复现了这一现象，这正是 PRD 5.3④“不排队下发”要避免的情况，见 [IOT-01 评估 3.7](../../docs/plan/workbuddy-reuse.md#37-mqtt-接入改造)。

## 尚未模拟

- DTU 心跳 / 注册包：格式待硬件确认（PRD 12.1 第 11 项）。
- 粘包、拆帧：取决于 DTU 串口打包参数（PRD 12.1 第 6 项），确认后再补。
- 电量与信号：协议中暂无对应字段。
- TLS 与一机一密：随 IOT-04 补充。

## 测试

```bash
./gradlew :dtu-sim:test
```

`GateBoardTest` 用手动推进的时钟验证闸板行为；`DtuSimulatorMqttTest` 用 Testcontainers 启动 EMQX 5.8.6，验证多设备并发、回显、故障注入、断线重连与持久会话，需要本机有 Docker。
