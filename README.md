# PulseOps

面向个人开发者的轻量级 HTTP/HTTPS API 与后台定时任务心跳监控平台。

项目当前处于 **Phase 0：工程初始化**。产品范围、架构约束和验收标准见 [`docs/index.md`](docs/index.md)。

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
- Node.js 20.19+ 或 22.12+ 与 npm

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

数据库结构变更一律通过 `backend/src/main/resources/db/migration/` 下的 Flyway 迁移完成，不使用 Hibernate 自动建表。

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

## 安全说明

- 不要提交 `.env`、真实凭证或心跳令牌。
- 本地 Compose 仅将 PostgreSQL 绑定到回环地址，不对外暴露。
- 外部请求必须设置超时，用户可控 URL 必须遵守 SSRF 防护规范。
