# SmartParking · 智能车位道闸 网络版

[![CI](https://github.com/acelucasxx/SmartParking/actions/workflows/ci.yml/badge.svg)](https://github.com/acelucasxx/SmartParking/actions/workflows/ci.yml)

业主通过微信小程序远程控制自己车位上的道闸，并可按有效期授权给家人。

## 文档

- 产品需求：[docs/prd/PRD.md](docs/prd/PRD.md)
- 开发计划：[docs/plan/dev-plan.md](docs/plan/dev-plan.md)
- 数据库约定：[docs/db/conventions.md](docs/db/conventions.md)
- 接口契约与错误码：[docs/api/README.md](docs/api/README.md)
- 设备接入与一机一密：[docs/iot/device-access.md](docs/iot/device-access.md)
- 设备运行态与状态流水：[docs/iot/runtime.md](docs/iot/runtime.md)
- 指令状态机：[docs/iot/command.md](docs/iot/command.md)
- 状态同步：[docs/iot/status-sync.md](docs/iot/status-sync.md)
- 真机联调手册：[docs/iot/bench.md](docs/iot/bench.md)（工具 [tools/dtu-bench](tools/dtu-bench/README.md)）
- 小程序登录与会话：[docs/biz/app-auth.md](docs/biz/app-auth.md)
- 统一权限判定：[docs/biz/permission.md](docs/biz/permission.md)
- 远程控制接口：[docs/biz/remote-control.md](docs/biz/remote-control.md)
- 设备入库与后台守卫：[docs/biz/device-import.md](docs/biz/device-import.md)
- 扫码绑定：[docs/biz/bind.md](docs/biz/bind.md)
- 我的车位与设备详情：[docs/biz/devices.md](docs/biz/devices.md)
- 告警：[docs/biz/alarms.md](docs/biz/alarms.md)
- DTU 模拟器：[tools/dtu-sim/README.md](tools/dtu-sim/README.md)
- WorkBuddy 代码复用评估：[docs/plan/workbuddy-reuse.md](docs/plan/workbuddy-reuse.md)
- 协作约定：[CONTRIBUTING.md](CONTRIBUTING.md)

## 工程

| 目录 | 内容 | 技术栈 |
| --- | --- | --- |
| `server/` | 业务服务（模块化单体） | Java 17、Spring Boot 3.5、Gradle |
| `admin-web/` | 管理后台 | Vue 3、Element Plus、Vite |
| `miniapp/` | 微信小程序 | 原生小程序、TypeScript |
| `deploy/local/` | 本地开发依赖 | Docker Compose：MySQL 8.4、Redis 7.4、EMQX 5.8 |
| `tools/dtu-sim/` | DTU 模拟器（设备侧） | Java 17，作为 `server` 的子工程构建 |
| `tools/dtu-bench/` | 真机联调工具（云端侧） | Java 17，作为 `server` 的子工程构建 |

## 本地运行

**依赖服务**（需要 Docker），详见 [deploy/local/README.md](deploy/local/README.md)

```bash
cd deploy/local && docker compose up -d
```

**server**（需要 JDK 17+）

```bash
cd server && ./gradlew :app:bootRun
```

健康检查：`http://localhost:8080/actuator/health`。启动时会自动执行数据库迁移，所以要先起依赖服务。`./gradlew build` 的集成测试用 Testcontainers 自带 MySQL，只需要 Docker 在运行。

**admin-web**（需要 Node 20+）

```bash
cd admin-web && npm install && npm run dev
```

**miniapp**：用微信开发者工具导入 `miniapp/` 目录；类型检查：

```bash
cd miniapp && npm install && npm run typecheck
```
