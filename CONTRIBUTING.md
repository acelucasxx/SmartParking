# 协作约定

## 分支

- `main` 始终可部署，不直接提交。
- 每个任务一个分支：`feat/<任务号>-<简述>`，例如 `feat/IOT-07-command-state-machine`；修缺陷用 `fix/<Issue 号>-<简述>`。
- 通过 PR 合入，CI 通过且至少一人评审。PR 描述里写 `Closes #<Issue 号>`。

## 提交信息

格式：`<类型>(<范围>): <说明>`，说明用中文。

- 类型：`feat` `fix` `refactor` `test` `docs` `build` `ci` `chore`
- 范围：`server` `iot` `admin-web` `miniapp` `deploy` `docs` 等
- 例：`feat(iot): 指令状态机支持超时判定`

## 代码规范

| 工程 | 规范 | 命令 |
| --- | --- | --- |
| server | Palantir Java Format（Spotless） | `./gradlew spotlessApply`、`./gradlew check` |
| admin-web | ESLint + Prettier | `npm run lint`、`npm run format` |
| miniapp | TypeScript strict | `npm run typecheck` |

通用格式见根目录 `.editorconfig`。

## 模块边界（server）

- 业务模块只依赖 `common`，模块之间通过接口或领域事件协作，不跨模块访问对方的表。
- `iot` 不感知用户与权限；权限判定统一在 `control` 中完成。
- 数据库变更只通过 Flyway 迁移脚本，已合入 `main` 的脚本不得修改。
