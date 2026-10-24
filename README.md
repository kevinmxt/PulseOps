# PulseOps

面向个人开发者的轻量级 HTTP/HTTPS API 与后台定时任务心跳监控平台。

项目当前处于 **Phase 2 已完成、下一阶段 Phase 3：Heartbeat**。产品范围、架构约束和验收标准见 [`docs/index.md`](docs/index.md)。

## 目录结构

```
backend/        Spring Boot 3.5（Java 21）后端
frontend/       Vue 3 + TypeScript + Vite 前端
docs/           产品、架构、规范与运维文档
compose.yaml    本地 PostgreSQL
.env.example    本地环境变量占位示例
```

## 环境要求

- JDK 21
- Docker 与 Docker Compose（本地数据库、集成测试）
- Node.js 24.15.0 与 npm（版本固定于 `frontend/.nvmrc`，可使用 `nvm use`）

Maven 由 Maven Wrapper 提供，无需单独安装。

## 本地启动

1. 启动 PostgreSQL（仅绑定 `127.0.0.1`）：

   ```bash
   docker compose up -d
   ```

   如需覆盖默认值，复制 `.env.example` 为 `.env` 并修改。后端不会自动读取 `.env`，修改数据库账号密码后需同时导出对应的 `DB_*` 变量。

2. 启动后端：

   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```

   健康检查：`curl http://localhost:8080/actuator/health`

3. 启动前端：

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

   Vite 开发服务器已将 `/api` 和 `/actuator` 代理到 `http://localhost:8080`。

## 配置

后端通过环境变量配置，默认值仅适用于本地开发：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/pulseops` | 数据库连接串 |
| `DB_USERNAME` | `pulseops` | 数据库账号 |
| `DB_PASSWORD` | `pulseops` | 数据库密码，非本地环境必须覆盖 |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `SERVER_ADDRESS` | `127.0.0.1` | 监听地址；Phase 4B 认证门槛完成前保持回环地址（见 [`docs/quality/deployment.md`](docs/quality/deployment.md)） |
| `PULSEOPS_TELEGRAM_BOT_TOKEN` | 空（通知关闭） | Telegram Bot Token；必须与 Chat ID 同时配置 |
| `PULSEOPS_TELEGRAM_CHAT_ID` | 空（通知关闭） | Telegram Chat ID |
| `PULSEOPS_TELEGRAM_API_BASE_URL` | `https://api.telegram.org` | Telegram API 基地址，仅用于测试或代理 |
| `HEARTBEAT_REQUIRE_HTTPS` | `false` | 心跳上报是否要求 HTTPS；仅信任回环来源的 `X-Forwarded-Proto: https`，生产由本地代理设置 |

数据库结构变更一律通过 `backend/src/main/resources/db/migration/` 下的 Flyway 迁移完成，不使用 Hibernate 自动建表。

### 探测调度

以下 Spring 配置项位于 `pulseops.probe` 前缀下，可通过环境变量或启动参数覆盖：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `pulseops.probe.tick-interval` | `1s` | 调度 tick 的 fixedDelay；最小 100ms |
| `pulseops.probe.batch-size` | `200` | 每次 tick 最多提交的到期 Monitor 数 |
| `pulseops.probe.pool-size` | `8` | 探测线程数 |
| `pulseops.probe.queue-capacity` | `100` | 探测任务队列容量；被拒绝的任务在下一 tick 重试 |
| `pulseops.probe.scheduling-enabled` | `true` | 是否启用自动调度；集成测试中关闭并手动调用 tick |

### 心跳扫描

以下配置项位于 `pulseops.heartbeat` 前缀下：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `pulseops.heartbeat.tick-interval` | `5s` | 心跳超时扫描的 fixedDelay；最小 100ms |
| `pulseops.heartbeat.batch-size` | `100` | 每次 tick 最多处理的到期心跳监控项数 |
| `pulseops.heartbeat.scheduling-enabled` | `true` | 是否启用自动扫描；集成测试中关闭并手动调用 `HeartbeatScheduler.tick()` |
| `pulseops.heartbeat.require-https` | `false` | 心跳上报是否强制 HTTPS |

心跳扫描使用独立的单线程调度器 `heartbeatTickScheduler`，与探测、通知互不阻塞。

### 通知投递

以下配置项位于 `pulseops.notification` 前缀下：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `pulseops.notification.dispatch-interval` | `5s` | 投递轮询的 fixedDelay |
| `pulseops.notification.batch-size` | `50` | 每轮最多处理的投递数 |
| `pulseops.notification.scheduling-enabled` | `true` | 是否启用自动投递；集成测试中关闭并手动调用 |

投递与探测各自使用独立的单线程调度器（`probeTickScheduler` / `notificationTickScheduler`），一个 tick 阻塞不会影响另一个；新增 ticker 必须声明自己的调度器，测试会校验非空且全局唯一。

失败重试：网络错误、5xx 与 429 最多尝试 3 次，退避 1 分钟与 5 分钟；非 429 的 4xx 直接进入 `FAILED`。重启后只续发 `PENDING` 投递，已发送的不会重发。

## 测试与构建

```bash
cd backend
./mvnw test      # 单元测试
./mvnw verify    # 构建并运行集成测试（Testcontainers，需要 Docker）
```

```bash
cd frontend
npm run build    # 类型检查与生产构建
```

CI 在 PR 与 master push 时执行后端 `verify` 与前端 `npm ci && npm run build`；当前阶段证据见 [`docs/quality/phase2-acceptance.md`](docs/quality/phase2-acceptance.md)，历史证据见 [`docs/quality/phase1-acceptance.md`](docs/quality/phase1-acceptance.md)。

## 安全说明

- 不要提交 `.env`、真实凭证或心跳令牌。
- 本地 Compose 仅将 PostgreSQL 绑定到回环地址，不对外暴露。
- 外部请求必须设置超时，用户可控 URL 必须遵守 SSRF 防护规范。
