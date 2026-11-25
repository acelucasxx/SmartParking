# 本地开发环境

| 服务 | 镜像 | 端口 | 默认账号 |
| --- | --- | --- | --- |
| MySQL | `mysql:8.4` | 3306 | `smartparking` / `smartparking_dev_only`；库 `smartparking`、`smartparking_test` |
| Redis | `redis:7.4-alpine` | 6379 | 密码 `redis_dev_only` |
| EMQX | `emqx/emqx:5.8.6` | 1883（MQTT）、8083（WebSocket）、18083（控制台） | 控制台 `admin` / `emqx_dev_only` |

默认值只用于本机。要改端口或密码，复制 `.env.example` 为 `.env` 再修改。

## 常用命令

在本目录（`deploy/local`）下执行：

```bash
docker compose up -d
```

```bash
docker compose ps
```

```bash
docker compose logs -f emqx
```

停止但保留数据：

```bash
docker compose down
```

清空全部数据（MySQL 初始化脚本会在下次启动时重新执行）：

```bash
docker compose down -v
```

## 自检

```bash
docker compose exec mysql mysql -usmartparking -psmartparking_dev_only -e "SHOW DATABASES;"
```

```bash
docker compose exec redis redis-cli ping
```

```bash
docker compose exec emqx emqx ctl status
```

EMQX 控制台：<http://localhost:18083>。可以在“WebSocket 客户端”里用云端账号 `smartparking-server` / `mqtt_server_dev_only` 登录（业务服务需在运行），订阅 `parking/up/#` 观察设备上行。

## 说明

- **EMQX 连接认证回调业务服务**：每次连接都调用本机 8080 的 `/internal/emqx/authn`，所以**先启动业务服务，再启动设备或模拟器**；业务服务刚启动时 EMQX 可能拒绝第一次连接，客户端会自动重试。设备必须先在库里登记才能连上，规则见 [docs/iot/device-access.md](../../docs/iot/device-access.md)。配置在 `emqx/emqx.conf`（认证）与 `emqx/acl.conf`（主题权限）。TLS 在 INF-06 中接入，测试与生产环境只开放 8883。
- 用模拟器联调前，先把模拟器的 IMEI 写进 `device` 表，例如：

  ```bash
  docker compose exec mysql mysql -usmartparking -psmartparking_dev_only smartparking -e "INSERT IGNORE INTO device (sn, imei, model, batch_no, activate_code_hash) VALUES ('SIM860000000000009', '860000000000009', 'SP-G1', 'SIM', 'x');"
  ```

  `860000000000009` 是模拟器 `--count 1` 生成的第一个 IMEI。
- **表结构不在这里建**：服务启动时由 Flyway 自动迁移，脚本在 `server/app/src/main/resources/db/migration`，约定见 [docs/db/conventions.md](../../docs/db/conventions.md)。
- 服务端连接配置在 `server/app/src/main/resources/application-local.yml`，与这里的默认值一致。
