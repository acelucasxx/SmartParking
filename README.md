# SmartParking · 智能车位道闸 网络版

业主通过微信小程序远程控制自己车位上的道闸，并可按有效期授权给家人。

## 文档

- 产品需求：[docs/prd/PRD.md](docs/prd/PRD.md)
- 开发计划：[docs/plan/dev-plan.md](docs/plan/dev-plan.md)
- 协作约定：[CONTRIBUTING.md](CONTRIBUTING.md)

## 工程

| 目录 | 内容 | 技术栈 |
| --- | --- | --- |
| `server/` | 业务服务（模块化单体） | Java 17、Spring Boot 3.5、Gradle |
| `admin-web/` | 管理后台 | Vue 3、Element Plus、Vite |
| `miniapp/` | 微信小程序 | 原生小程序、TypeScript |
| `deploy/local/` | 本地开发依赖 | Docker Compose：MySQL 8.4、Redis 7.4、EMQX 5.8 |

后续补充：`tools/dtu-sim/`（IOT-03）、`docs/api/`（INF-05）。

## 本地运行

**依赖服务**（需要 Docker），详见 [deploy/local/README.md](deploy/local/README.md)

```bash
cd deploy/local && docker compose up -d
```

**server**（需要 JDK 17+）

```bash
cd server && ./gradlew :app:bootRun
```

健康检查：`http://localhost:8080/actuator/health`

**admin-web**（需要 Node 20+）

```bash
cd admin-web && npm install && npm run dev
```

**miniapp**：用微信开发者工具导入 `miniapp/` 目录；类型检查：

```bash
cd miniapp && npm install && npm run typecheck
```
