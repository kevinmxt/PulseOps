# Phase 4B：认证与数据维护 — 设计共识

## 状态与用途

- 本文件是 grilling 设计访谈的产物，作为 `to-spec` 与 `to-tickets` 的输入。
- 长期行为规范以 [`docs/specs/auth.md`](../specs/auth.md) 及现有 `docs/specs/api.md`、`docs/architecture/security.md`、`docs/architecture/data-model.md`、`docs/quality/deployment.md` 为准；本文件保留决策背景与被否决方案。
- 领域术语不在此重复，见 [`GLOSSARY.md`](../../GLOSSARY.md)，特别是 **Admin Account（管理员账号）**、**Admin Session（管理会话）**、**Role（角色）**、**Retention Period（保留期限）**。
- 阶段、范围与完成条件以 [`../product/roadmap.md`](../product/roadmap.md) 为准。

## 目标与完成条件

Phase 4B 是公网发布前的硬门槛：

- 单用户认证覆盖所有管理 API 与管理 UI 操作；账号存入数据库，为后续多用户预留角色字段。
- 未认证访问返回 401/403 的集成测试。
- 启动校验阻止不安全的公开监听配置。
- 历史数据保留策略与分批清理任务。
- 备份脚本与一次可复现的恢复演练。

完成条件：未授权访问被拒绝；清理任务遵守保留期限；备份可恢复。

## 现状事实

- 后端未引入 Spring Security；`server.address` 默认 `127.0.0.1`。
- 现有 328 个后端测试中的 HTTP 测试全部使用 MockMvc；重启类验收测试通过 Repository 与 Spring 上下文操作，不直接走 HTTP。
- 没有用户表、保留期配置、清理任务或备份脚本。
- `notification_delivery.incident_id` 为 `ON DELETE CASCADE`，删除已解决 Incident 会自动删除其投递记录。
- `check_result` 只有 `(monitor_id, started_at DESC)` 复合索引，没有独立的 `started_at` 索引；`incident` 只有 `started_at` 相关索引，没有 `resolved_at` 索引。全局期限清理需要新增索引。
- 前端为 Vue 3 + TypeScript + Vite，使用原生 `fetch` 封装；Phase 4A 的认证状态完全不存在，UI 通过 Vite 代理访问回环后端。

## 已确认决策

| 主题 | 决策 |
|---|---|
| 认证机制 | Spring Security 服务端 Session + JSON 登录/登出接口；不使用 HTTP Basic 或前端长期 Bearer Token |
| 授权模型 | 单用户；唯一 `ADMIN` 角色，所有管理接口要求该角色；暂不做资源归属 |
| 账号存储 | 数据库表 `app_user`；不采用仅内存用户 |
| 账号字段 | `id`、`username`（唯一、1–64、`[A-Za-z0-9._-]`、区分大小写）、`password_hash`、`role`（默认 `ADMIN`，CHECK 暂只允许 `ADMIN`）、`created_at`、`updated_at`；暂不加 `enabled` |
| 密码哈希 | Spring Security `DelegatingPasswordEncoder`，存 `{bcrypt}$2a$12$...`（BCrypt cost 12）；不存明文 |
| 首次引导 | `app_user` 为空时从 `PULSEOPS_AUTH_BOOTSTRAP_USERNAME` 与 `PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH` 创建；表为空且变量缺失则启动失败；表非空时忽略变量 |
| 改密 | `PUT /api/v1/auth/password` + `/settings` UI；校验当前密码；成功后使该用户全部会话失效 |
| 认证开关 | 认证始终启用，无旁路；缺少凭据直接拒绝启动 |
| 会话存储 | 应用内存（`SessionRegistryImpl`）；重启后需要重新登录 |
| 会话期限 | 空闲 30 分钟 + 绝对 12 小时 |
| 会话 Cookie | 名称 `PULSEOPS_SESSION`；`HttpOnly`、`SameSite=Lax`、`Path=/`；`Secure` 由 `pulseops.auth.cookie-secure` 控制 |
| CSRF | 双提交 Cookie：`XSRF-TOKEN` + `X-XSRF-TOKEN`；登录、登出、改密与所有非 GET 管理请求校验；心跳上报豁免 |
| 受保护边界 | 除登录、会话引导、登出、心跳上报与 `/actuator/health` 外，`/api/v1/**` 与 `/actuator/metrics` 全部要求认证 |
| 错误契约 | 未认证 401 `UNAUTHORIZED`；登录失败 401 `INVALID_CREDENTIALS`；CSRF 失败 403 `CSRF_INVALID`；角色不足 403 `FORBIDDEN`；限流 429 `RATE_LIMITED`；当前密码错误 400 `INVALID_CURRENT_PASSWORD`；沿用 `{code, message, traceId}` |
| 登录限流 | 用户名 + 客户端 IP，15 分钟内连续失败 5 次返回 429 并带 `Retry-After`；成功清零；计数在内存 |
| 启动校验 | 空用户表且缺引导变量、非回环监听且未启用 Secure Cookie、引导哈希格式非法，均拒绝启动 |
| 测试策略 | 所有测试保留真实安全链；MockMvc 测试增加 `user()` 与 `csrf()`；新建 `AuthFlowIT` 覆盖真实登录、CSRF、限流、401/403、改密与会话失效 |
| 前端认证 | 公开 `/login`、受保护 `/settings`；内存认证状态；401 跳转 `/login?redirect=...&expired=1`；站内回跳校验；CSRF 失败刷新引导并重试一次 |
| UI 托管 | 4B 不改变托管方式，继续 Vite 开发代理；生产静态托管与 Caddy 属于 Phase 5 |
| 保留期限 | `CheckResult` 30 天；`RESOLVED` Incident 连同投递记录 90 天；`OPEN` Incident 及其投递永不清理；期限可配置；不做“每个监控保留最近 N 条” |
| 清理调度 | 独立 `cleanup-tick-` 单线程调度器；默认每小时；单批 1000 行、每批短事务、批间暂停 50ms、单 tick 最多 50 批 |
| 清理可观测性 | INFO 日志 + Micrometer 计数器（按数据类型标签）；调度器纳入“非空且全局唯一”测试 |
| 备份格式 | `pg_dump -Fc` 自定义格式；`BACKUP_DIR` 默认 `./backups`，保留 7 份；生成后 `pg_restore --list` 自检 |
| 备份执行方式 | 通过 `docker compose exec -T postgres pg_dump/pg_restore`，不要求宿主机安装 PostgreSQL 客户端 |
| 恢复演练 | 恢复到 `pulseops_restore`，校验 Flyway 历史、核心表行数、数据抽样，并用恢复库启动应用访问健康检查与管理 API |
| 哈希生成 | 文档给出 Docker `htpasswd -bnBC 12` 命令；启动时校验 `{bcrypt}` 前缀与 BCrypt 格式 |
| 前端遗留修正 | 纳入 Phase 4A 审查的三个 P2/P3：过期响应保护、响应体 abort 映射、轮询与分页竞争 |
| 文档与 ADR | 新增 `specs/auth.md`、ADR-0008、ADR-0009、`quality/phase4b-acceptance.md`；更新安全、API、数据模型、部署、索引、README、`.env.example` 与路线图 |

## 认证 API 契约

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| GET | `/api/v1/auth/session` | 公开 | 返回 `{authenticated, username, role, csrfToken}`；同时下发/刷新 `XSRF-TOKEN` Cookie；未登录返回 200 且 `authenticated=false` |
| POST | `/api/v1/auth/login` | 公开 + CSRF | `{username, password}` → 200 `{username, role}`；失败 401 `INVALID_CREDENTIALS`；限流 429 |
| POST | `/api/v1/auth/logout` | 公开 + CSRF | 始终 204；会话不存在时也不报错 |
| PUT | `/api/v1/auth/password` | 需认证 + CSRF | `{currentPassword, newPassword}` → 204；当前密码错误 400；成功后使该用户全部会话失效 |

- 独立的 `/api/v1/auth/csrf` 端点不创建；CSRF 引导合并进 `/auth/session`。
- 密码规则：12–128 字符，无强制字符组合，前后不裁剪空格；新密码不得与当前相同；确认框仅前端校验。
- 登录成功时轮换 Session ID（Spring Security 默认 `changeSessionId`）。
- 认证日志只记录结果、客户端地址与 `traceId`，不记录用户名、密码或 Cookie。

## 会话、CSRF 与访问边界

- Session 保存在内存；绝对期限通过会话创建时间属性在过滤器中校验，空闲期限使用容器会话超时。
- CSRF Cookie 可被前端脚本读取（`HttpOnly=false`）；`Secure` 与 Session Cookie 使用同一配置。
- 心跳上报 `POST /api/v1/heartbeats/{token}` 不参与 Session/CSRF，继续只靠 Heartbeat Token 识别监控项。
- `/actuator/health` 公开且只返回必要信息；`/actuator/metrics` 要求认证。
- 401/403 响应由 `AuthenticationEntryPoint` 与 `AccessDeniedHandler` 输出统一错误信封，并保留 `traceId`。
- 反向代理头与客户端 IP 信任规则在 Phase 5 与 Caddy 联调时统一处理；4B 只使用请求的远端地址，文档注明限流在代理后可能共享同一计数。

## 启动校验

启动时按顺序校验，任一失败拒绝启动并输出可操作的错误信息：

1. `app_user` 为空且缺少引导用户名或哈希 → 失败。
2. 引导哈希不以 `{bcrypt}$2` 开头或格式非法 → 失败。
3. `server.address` 非回环且 `pulseops.auth.cookie-secure=false` → 失败。
4. `app_user` 非空且仍提供引导变量 → 忽略并记录 WARN，不覆盖已有账号。

Phase 5 在容器内监听 `0.0.0.0` 时同样会触发第 3 条，必须显式启用 Secure Cookie 并在前置代理终止 TLS。

## 数据保留与清理

- `CheckResult`：删除 `started_at < now - checkResultRetentionDays` 的行。
- `RESOLVED` Incident：删除 `resolved_at < now - resolvedIncidentRetentionDays` 的行；其 `NotificationDelivery` 由外键级联删除。
- `OPEN` Incident：永不按时间清理。
- 清理不关心 Monitor 是否启用；Monitor 删除仍由既有级联处理。
- 若已解决 Incident 仍滞留 `PENDING` 投递，到期后同样删除并记录数量；通知投递器遇到已不存在的投递行必须按无操作处理，不得抛错终止 tick。
- 配置项（`pulseops.cleanup` 前缀）：
  - `enabled`（默认 `true`）
  - `scheduling-enabled`（默认 `true`，集成测试关闭并手动调用）
  - `tick-interval`（默认 `1h`，范围 1m–24h）
  - `check-result-retention-days`（默认 30，范围 1–3650）
  - `resolved-incident-retention-days`（默认 90，范围 1–3650）
  - `batch-size`（默认 1000，范围 1–10000）
  - `max-batches-per-tick`（默认 50，范围 1–1000）
- 迁移 `V12__create_app_user.sql`、`V13__add_cleanup_indexes.sql`：
  - `V13` 增加 `idx_check_result_started_at ON check_result (started_at)` 与 `idx_incident_resolved_at ON incident (resolved_at) WHERE status = 'RESOLVED'`。
- 清理批次每次使用独立短事务；单批返回行数小于 `batch-size` 或达到 `max-batches-per-tick` 时停止。

## 备份与恢复

- 脚本：`scripts/backup.sh`、`scripts/restore.sh`，通过 `docker compose exec -T postgres` 执行 `pg_dump`/`pg_restore`。
- 参数：`BACKUP_DIR`（默认 `./backups`）、`BACKUP_RETENTION_COUNT`（默认 7）、`COMPOSE_SERVICE`（默认 `postgres`）；从 `.env` 或 `POSTGRES_*` 变量读取连接信息。
- `backup.sh`：生成 `<db>-<UTC 时间戳>.dump`，执行 `pg_restore --list` 自检，按份数删除最旧文件；备份文件权限使用 `umask 077`。
- `restore.sh <file> <target-db>`：创建目标库（默认建议 `pulseops_restore`）并恢复；不覆盖现有库，除非显式确认。
- `.gitignore` 增加 `backups/`。
- 演练证据写入 `docs/quality/phase4b-acceptance.md`：时间、命令、备份大小、Flyway 历史、核心表行数、抽样数据、恢复库启动后的健康检查与管理 API 结果。

## 后端改动

- 新增 `dev.pulseops.auth` 模块：实体/仓库、`UserDetailsService`、安全配置、认证控制器、CSRF 引导、登录限流、绝对会话期限过滤器、启动校验器、密码改密服务。
- `pom.xml` 增加 `spring-boot-starter-security` 与测试用 `spring-security-test`。
- `application.yml` 增加 `pulseops.auth` 配置；默认保持回环监听。
- 新增清理模块（可放在 `history` 或独立 `cleanup` 包）：配置属性、调度器、分批删除服务、指标。
- 通知投递器增加“投递行已不存在”容错。
- 测试：迁移 `src/test/resources/application.properties` 增加测试引导账号；现有 MockMvc 测试补 `user()`/`csrf()`；新增认证、启动校验、清理边界与并发测试。

## 前端改动

- `src/api/client.ts`：统一附加 `X-XSRF-TOKEN`；401 时区分“引导/登录请求”和普通请求；403 `CSRF_INVALID` 刷新引导并重试一次。
- `src/auth/`：模块级响应式认证状态、`session()` 引导、登录、登出、改密 API。
- 路由：新增公开 `/login` 与受保护 `/settings`；全局守卫在首次导航调用 session。
- `LoginView.vue`、`SettingsView.vue`；`App.vue` 顶栏显示用户名、设置与登出。
- 401 跳转保留站内回跳路径与 `expired=1`；登录成功只接受单个 `/` 开头的站内路径。
- 登录页展示通用的“用户名或密码错误”；429 展示可重试秒数。
- 改密成功后提示“请重新登录”并跳转登录页。
- 测试：认证状态与守卫、CSRF 头、401 跳转、登录表单与错误、改密表单、登出。

## 已考虑但未采用

- **HTTP Basic 每请求携带凭据**：浏览器缓存与前端存储风险更高，且缺少服务端登出。
- **Bearer Token 存 `localStorage`**：XSS 暴露面大。
- **仅环境变量内存用户**：无法为多用户与改密提供持久事实来源。
- **认证开关**：引入只在开发/测试存在的旁路，容易与生产行为漂移。
- **Spring Session JDBC**：单实例下增加表与清理负担，重启保持登录不是本阶段目标。
- **关闭 CSRF，仅依赖 `SameSite=Strict`**：在代理与浏览器差异下不可靠。
- **独立 `/auth/csrf` 端点**：与 session 引导合并可减少一次请求。
- **按 `started_at` 清理已解决 Incident**：保留期更自然的语义是从恢复时间起算。
- **单独按期限清理 `NotificationDelivery`**：与 Incident 级联语义冲突，且会留下孤儿审计信息。
- **每个监控保留最近 N 条结果**：规则复杂化，30 天期限已满足调试需求。
- **本阶段引入自动备份容器/定时任务**：属于 Phase 5 部署范围。
- **本阶段让 Spring Boot 托管静态前端**：会把构建产物、缓存头与探活路径拉进认证阶段。
- **多角色与资源归属**：当前只有一个用户，角色字段只做前向兼容。
- **密码复杂度组合规则**：长度约束 + 通用错误已足够，避免无收益的可用性损失。
- **Playwright 端到端测试**：延续 Phase 4A 的人工走查证据模式。

## 任务拆分与依赖

每个任务一个 issue 与一个 PR，标签 `ready-for-agent`。

| 编号 | 内容 | 关键验收 | 依赖 |
|---|---|---|---|
| 4B-1 | 用户模型与启动引导：`app_user` 迁移、实体/仓库、BCrypt、引导变量、启动校验、配置与单元测试 | 空表引导成功；缺失/非法配置拒绝启动；非回环 + 非 Secure Cookie 拒绝启动 | — |
| 4B-2 | 认证链与管理 API 边界：Security 配置、四个 auth 端点、401/403 处理器、登录限流、路径边界、现有测试认证改造、`AuthFlowIT` | 未认证 401；CSRF 缺失 403；登录/登出/改密闭环；限流 429；心跳与健康检查不受影响 | 4B-1 |
| 4B-3 | 前端认证与改密：登录页、路由守卫、内存认证状态、CSRF 与单次重试、401 跳转、登出、`/settings`、前端测试 | 未登录访问受保护路由跳登录；登录后回跳；会话过期提示；改密后重新登录；CSRF 头正确 | 4B-2 |
| 4B-4 | Phase 4A 遗留 P2/P3：过期响应保护、响应体 abort 映射、轮询与分页竞争 | 迟到的旧响应不能覆盖新状态；abort 映射为 `TIMEOUT`；轮询期间分页意图不丢失 | —（建议先于 4B-3） |
| 4B-5 | 保留策略与分批清理：索引迁移、独立调度器、配置、指标、边界/并发测试 | 截止边界正确；`OPEN` 不删；级联删除投递；批次上限生效；探测写入不受阻 | — |
| 4B-6 | 备份恢复脚本与演练：`backup.sh`、`restore.sh`、`.gitignore`、部署文档、恢复证据 | 转储自检通过；恢复到新库；行数与抽样一致；恢复库可启动并访问管理 API | — |
| 4B-7 | 阶段验收与文档收口：`specs/auth.md`、ADR-0008/0009、规范与索引更新、`phase4b-acceptance.md`、全量验证 | 文档齐全；全量验证只保留一次；验收证据与已知限制记录完整 | 4B-1 ~ 4B-6 |

执行顺序：4B-1 → 4B-2 → 4B-3；4B-4 可在 4B-1/4B-2 期间完成并在 4B-3 前合入；4B-5 与 4B-6 可与认证线并行；4B-7 最后。每个 PR 只触发必要 CI 轮次，最终全量验证只做一次。

## 文档更新清单

- 新建 `docs/specs/auth.md`：认证端点、会话、CSRF、限流、启动校验、错误契约、日志边界。
- 新建 `docs/adr/0008-admin-auth-session.md`、`docs/adr/0009-data-retention-policy.md`。
- 新建 `docs/quality/phase4b-acceptance.md`（阶段收口时）。
- 更新 `docs/architecture/security.md`：认证实现、会话安全、登录限流、启动校验。
- 更新 `docs/specs/api.md`：auth 端点、错误码、受保护边界。
- 更新 `docs/specs/ui.md`：登录页、`/settings`、路由守卫、401 跳转与认证状态内存约束。
- 更新 `docs/architecture/data-model.md`：`app_user`、保留期限语义、清理批次约束。
- 更新 `docs/quality/deployment.md`：备份/恢复流程、恢复演练要求、Secure Cookie 与公开监听前置条件。
- 更新 `docs/index.md`：登记本计划、`specs/auth.md`、新 ADR 与阶段验收文档。
- 更新 `README.md` 与 `.env.example`：引导账号、哈希生成命令、认证配置与本地启动步骤。
- 更新 `docs/product/roadmap.md`：完成 4B 时勾选并推进阶段。
- 更新 `GLOSSARY.md`：Admin Account、Role、Admin Session、Retention Period。

## ADR 草稿要点

### ADR-0008：管理认证采用服务端会话与 CSRF

- **决策**：管理 API 使用 Spring Security 服务端 Session（内存）+ JSON 登录/登出 + 双提交 CSRF；账号存 `app_user` 表，密码用 BCrypt；唯一 `ADMIN` 角色；认证不可关闭。
- **备选**：HTTP Basic、前端 Bearer Token、仅环境变量内存用户、可关闭认证开关。
- **后果**：重启后需重新登录；所有非 GET 管理请求需要 CSRF 头；公开监听必须启用 Secure Cookie；引导变量只用于首次建号；改密使全部会话失效。

### ADR-0009：历史数据保留期限与删除语义

- **决策**：`CheckResult` 保留 30 天；`RESOLVED` Incident 从恢复时间起保留 90 天并级联删除投递；`OPEN` Incident 永不按时间清理；独立调度器分批删除。
- **备选**：永久保留、按开始时间清理、单独清理投递、每监控保留最近 N 条。
- **后果**：超过期限的数据会永久丢失；已解决事件的投递审计最多保留 90 天；到期仍滞留的 `PENDING` 投递会被删除，投递器必须容忍投递行消失；全局期限删除依赖新增索引。

## 风险与缓解

- **测试改造面大**：现有 HTTP 测试都需要认证上下文。缓解：统一测试工具方法，机械替换，最终只跑一次全量并检查未认证路径由 `AuthFlowIT` 覆盖。
- **SPA 的 401/CSRF 交互复杂**：缓解：引导与登录请求不触发全局跳转；CSRF 失败只刷新并重试一次；专门的前端测试覆盖。
- **内存会话重启失效**：单实例可接受；文档明确重启后需重新登录。
- **代理后 IP 共享**：Phase 5 前限流可能对所有客户端共用一个桶；单用户下可接受，Phase 5 统一代理头信任规则。
- **Secure Cookie 校验阻止 LAN 明文调试**：这是有意的安全默认值；需要 HTTPS 前置代理或回环监听。
- **清理与投递竞态**：删除与发送可能竞争；投递器按“记录不存在即跳过”处理，清理记录删除数量。
- **大表清理性能**：新增索引、小批次、批间暂停、单 tick 上限；测试覆盖边界与并发写入。
- **备份文件含敏感数据**：密码哈希与心跳令牌摘要都会进入转储；`umask 077`、`backups/` 不入库、文档提示保护。
- **引导变量丢失**：表非空时忽略变量，忘记密码需按文档用 SQL 更新哈希；文档提供恢复步骤。
- **无浏览器 E2E**：登录、过期、改密流程需要人工验收并如实记录，不声称自动化覆盖。

## 验收映射

| 路线图要求 | 覆盖任务 | 证据 |
|---|---|---|
| 单用户认证与授权覆盖所有管理 API | 4B-1、4B-2 | 安全配置、`AuthFlowIT`、现有业务测试的认证上下文 |
| 未认证访问返回 401/403 的集成测试 | 4B-2 | `AuthFlowIT` 的 401、`CSRF_INVALID`、`RATE_LIMITED` 用例 |
| 生产 profile 启动检查 | 4B-1 | 启动校验器单元测试：空表缺变量、非法哈希、非回环 + 非 Secure Cookie |
| 历史数据保留策略与分批清理 | 4B-5 | 边界、批次上限、级联、`OPEN` 保护与并发写入测试 |
| 备份与恢复演练 | 4B-6 | `phase4b-acceptance.md` 中的恢复演练记录与抽样校验 |
| 所有管理操作检查权限 | 4B-2 | 过滤器链默认拒绝 + `/actuator/metrics` 认证测试 |

## 下一步

1. `docs/specs/auth.md` 与 ADR-0008/0009 已起草；其余规范更新在对应任务完成时同步。
2. 创建 Phase 4B 父 issue 与 7 个子 issue；父 issue 链接本文件，不复制全文。
3. 按 `4B-1 → 4B-2 → 4B-3` 认证主线推进，`4B-4` 前置合入，`4B-5` 与 `4B-6` 并行，最后执行 `4B-7`。
