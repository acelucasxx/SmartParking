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

EMQX 控制台：<http://localhost:18083>。可以在“WebSocket 客户端”里订阅 `parking/up/#` 观察设备上行。

## 说明

- **EMQX 本地不做设备认证**，任何客户端都能连 1883。一机一密认证与 TLS 在 IOT-04 / INF-06 中接入，测试与生产环境只开放 8883。
- **表结构不在这里建**：服务启动时由 Flyway 自动迁移，脚本在 `server/app/src/main/resources/db/migration`，约定见 [docs/db/conventions.md](../../docs/db/conventions.md)。
- 服务端连接配置在 `server/app/src/main/resources/application-local.yml`，与这里的默认值一致。
