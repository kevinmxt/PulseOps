# Phase 4B 验收证据

本文件汇总 Phase 4B（认证与数据维护）的验收证据。Phase 4B 是公网发布前的硬门槛，包含：

| Ticket | 内容 |
|---|---|
| #58 | 4B-1 用户模型与启动引导 |
| #59 | 4B-2 认证链与管理 API 边界 |
| #60 | 4B-3 前端认证与改密 |
| #61 | 4B-4 Phase 4A 遗留前端 P2/P3 修正 |
| #62 | 4B-5 历史数据保留与分批清理 |
| #63 | 4B-6 备份恢复脚本与演练 |
| #64 | 4B-7 阶段验收与文档收口（本文件） |

## CI 证据

工作流：[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)。每次 PR 在干净 checkout 上运行后端 `./mvnw verify`（Testcontainers PostgreSQL 17）与前端 `npm ci && npm test && npm run build`。

| Ticket | PR | PR checks | 结论 | 合并提交 |
|---|---|---|---|---|
| #58 4B-1 用户模型与启动引导 | [#67](https://github.com/kevinmxt/PulseOps/pull/67) | [checks](https://github.com/kevinmxt/PulseOps/pull/67/checks) | success | `873ef96` |
| #59 4B-2 认证链与管理 API 边界 | [#68](https://github.com/kevinmxt/PulseOps/pull/68) | [checks](https://github.com/kevinmxt/PulseOps/pull/68/checks) | success | `ec3ac01` |
| #60 4B-3 前端认证与改密 | [#69](https://github.com/kevinmxt/PulseOps/pull/69) | [checks](https://github.com/kevinmxt/PulseOps/pull/69/checks) | success | `4dc7f46` |
| #61 4B-4 Phase 4A 遗留 P2/P3 | [#66](https://github.com/kevinmxt/PulseOps/pull/66) | [checks](https://github.com/kevinmxt/PulseOps/pull/66/checks) | success | `159d15f` |
| #62 4B-5 保留策略与分批清理 | [#70](https://github.com/kevinmxt/PulseOps/pull/70) | [checks](https://github.com/kevinmxt/PulseOps/pull/70/checks) | success | `1df7625` |
| #63 4B-6 备份恢复脚本与演练 | [#71](https://github.com/kevinmxt/PulseOps/pull/71) | [checks](https://github.com/kevinmxt/PulseOps/pull/71/checks) | success | `daa5c27` |

本文件所属收口 PR 的 checks 页面与父 issue #57 的阶段完成报告记录 #64 的最终结果。按仓库测试与评审节奏，不为补充本文件中的链接再触发额外 CI。

## 本地全量验证

2026-10-26 在开发者本机执行（Windows 10 Pro 10.0.19045，JDK 21.0.11，Node 24.15.0，npm 11.12.1，Docker 29.6.1）：

```bash
cd backend
./mvnw --batch-mode --no-transfer-progress verify

cd ../frontend
npm test
npm run build
```

结果：

- 后端：单元测试 203 个、集成测试 217 个，合计 420 个，0 失败，`BUILD SUCCESS`，耗时 4 分 59 秒（集成测试使用 Testcontainers PostgreSQL 17.11）。
- 前端：20 个测试文件、158 个测试通过；`vue-tsc -b && vite build` 成功。
- 失败项记录：首次将前端测试与后端 `verify` 并行执行时，Vitest 出现 fork worker 启动超时（19 个 unhandled errors，无断言失败）；后端完成后单独重跑前端全部通过。判定为本机资源竞争，不是代码失败；CI 中两个 job 独立运行且均通过。

## 阶段完成条件对照

| 路线图完成条件 | 证据 |
|---|---|
| 单用户认证与授权覆盖所有管理 API | `SecurityConfiguration` 默认拒绝未匹配请求；`AuthFlowIT` 覆盖登录、会话、CSRF、改密、限流及受保护边界；全量集成测试保留真实安全链 |
| 未认证访问返回 401/403 的集成测试 | `AuthFlowIT#protectedEndpointRejectsAnonymousRequestsWithJsonEnvelope`、`#writeRequestWithoutCsrfReturnsCsrfInvalid` |
| 生产启动检查：公开监听且未启用认证时拒绝启动 | `AuthStartupValidatorTest`、`AuthStartupWiringTest`；`AuthStartupValidator` |
| 历史数据保留策略与分批清理任务 | `HistoryCleanupIT`；`CleanupPropertiesTest`；ADR-0009 |
| 备份可恢复 | 本文件“备份恢复演练（#63）” |
| 公网发布门槛与阶段状态 | [`deployment.md`](deployment.md)、[`../product/roadmap.md`](../product/roadmap.md) |

父 issue #57 的用户故事分组对照：

| 用户故事 | 证据 |
|---|---|
| 1–6 登录、过期提示、登出、路由守卫、改密、登录限流 | `AuthFlowIT`；`LoginView.test.ts`、`SettingsView.test.ts`、`router/index.test.ts`、`session.test.ts` |
| 7 认证日志不含密码、Cookie 或 CSRF 令牌 | `AuthFlowIT#authLogsDoNotContainCredentialsSessionIdsOrCsrfTokens` |
| 8–9 未认证 401、缺少 CSRF 403 | `AuthFlowIT` 的 401 与 `CSRF_INVALID` 用例 |
| 10 心跳上报独立于管理认证 | `AuthFlowIT#heartbeatReportsStayPublicAndCsrfExempt` |
| 11 健康检查公开 | `AuthFlowIT#healthStaysPublicWhileMetricsRequiresAuthentication` |
| 12 不安全的公开监听拒绝启动 | `AuthStartupValidatorTest` |
| 13–16 保留期限、Incident 级联、OPEN 保护、分批清理 | `HistoryCleanupIT` 的截止边界、批次上限、共享预算、级联、OPEN 保护与并发写入用例 |
| 17–18 备份保留与恢复验证 | 本文件“备份恢复演练（#63）” |
| 19 Phase 4A 遗留 P2/P3 | `client.test.ts`、`MonitorListView.test.ts`、`MonitorDetailView.test.ts` 的迟滞响应、abort 与分页竞态用例 |
| 20 规范与决策可追溯 | `docs/specs/auth.md`、`docs/specs/api.md`、ADR-0008/0009 与本文件 |

## Ticket 验收对照

### #58 4B-1：用户模型与启动引导

- `V12__create_app_user.sql` 建立 `app_user`、用户名/角色 CHECK 与唯一约束；`AppUserRepositoryIT#appliesV12MigrationAndPersistsUser`、`#rejectsDuplicateUsername`、`#rejectsInvalidUsernameFormatAtDatabaseLevel`、`#rejectsUnsupportedRoleAtDatabaseLevel`。
- 用户名裁剪与区分大小写查询：`AppUserRepositoryIT#trimsAndValidatesUsernameInputForLookup`、`#findsUsernameCaseSensitively`。
- 空表引导、缺失/非法配置拒绝启动、非回环监听校验与忽略变量 WARN：`AuthBootstrapIT#createsSingleAdminFromTestBootstrapCredentials`、`AuthStartupValidatorTest`（15 个用例）、`AuthStartupWiringTest`。
- BCrypt 成本因子 12 与前缀规则：`PasswordHashRulesTest`、`PasswordEncoderConfigurationTest`。

### #59 4B-2：认证链与管理 API 边界

- `AuthFlowIT` 覆盖：公开 session 与 CSRF 引导、匿名 401 错误信封、缺少 CSRF 的 403、`/actuator/health` 公开而 `/actuator/metrics` 需认证、Cookie 与空闲期限配置、认证日志不含敏感信息、改密校验与会话失效、绝对期限、心跳公开且豁免 CSRF、登录成功轮换 Session ID、错误凭据统一响应、登录 CSRF、登出幂等、限流清零与 429 `Retry-After`。
- 现有业务 MockMvc 测试均在真实安全链下补充认证与 CSRF 上下文；全量集成测试 217 个通过。

### #60 4B-3：前端认证与改密

- 登录表单、站内回跳、拒绝外部回跳、通用凭据错误、429 重试秒数与过期/改密提示：`LoginView.test.ts`。
- 路由守卫、登录页重定向、401 清理与 `expired=1`：`router/index.test.ts`。
- 内存会话状态、引导、登出失败仍清理本地状态、改密后清空状态：`session.test.ts`。
- CSRF 头、单次刷新重试、401 处理与 `Retry-After` 解析：`api/client.test.ts`。
- 改密表单校验、当前密码错误与 traceId 展示：`SettingsView.test.ts`。

### #61 4B-4：Phase 4A 遗留前端 P2/P3

- 迟到的监控响应、历史/Incident 错误不覆盖新状态：`MonitorDetailView.test.ts`。
- 轮询与分页竞争：`MonitorListView.test.ts` 的 `keeps the requested page when pagination happens during a refresh` 与 `keeps the user page when a poll fires during a pagination request`。
- abort 与响应体读取失败映射为 `TIMEOUT`：`api/client.test.ts`。

### #62 4B-5：历史数据保留与分批清理

- `V13__add_cleanup_indexes.sql` 增加 `check_result(started_at)` 与已解决 Incident 的部分索引。
- `HistoryCleanupIT` 覆盖：索引存在、严格早于截止时间才删除、单 tick 批次上限、CheckResult 与 Incident 共享批次预算、Incident 删除级联投递、一次 tick 清理两类数据、OPEN Incident 永不按时间删除、到期 PENDING 投递计数与日志、清理期间新探测结果不被阻塞。
- 配置范围与默认值：`CleanupPropertiesTest`；调度器非空且全局唯一：`SchedulingSupportTest`。

### #63 4B-6：备份恢复脚本与演练

见下一节。

### #64 4B-7：阶段验收与文档收口

逐项核对结果：

| 文档 | 核对结果 |
|---|---|
| `docs/specs/auth.md` | 端点、会话、CSRF、限流、启动校验与实现一致；更新密码重置引用 |
| `docs/specs/api.md` | auth 端点、受保护边界、分页与错误契约一致；无需修改 |
| `docs/specs/ui.md` | 路由与认证行为一致；更新阶段表述与 Phase 4B 验证方式 |
| `docs/architecture/security.md` | 认证要求一致；补充 auth 规范与 ADR-0008 引用 |
| `docs/architecture/data-model.md` | `app_user`、保留期限与 V12/V13 索引一致；无需修改 |
| `docs/quality/deployment.md` | 更新 Phase 4B 状态、管理员密码重置与公网发布提示 |
| ADR-0008 | 修正 CSRF 表述，明确心跳上报豁免 |
| ADR-0009 | 与清理实现一致；无需修改 |

- 修正文档缺口：README 的 Phase 4B 状态与认证说明、`specs/ui.md` 的阶段表述、`specs/auth.md` 的密码重置引用、部署文档的管理员密码重置流程与公网发布提示。
- 同步 `docs/index.md`、`docs/product/roadmap.md`、`README.md`。
- `.env.example` 已核对：认证引导变量与 Cookie 安全开关已存在；清理参数属于 Spring 配置，备份参数属于脚本环境变量，均不进入 Compose `.env`，因此无需修改。
- 本文件补齐 CI 证据、本地全量验证、恢复演练与已知限制。

## 备份恢复演练（#63）

### 环境

| 项目 | 值 |
|---|---|
| 日期 | 2026-10-26 |
| 宿主机 | Windows 10 Pro 10.0.19045，Git Bash |
| Docker | 29.6.1 |
| PostgreSQL | `postgres:17-alpine`，17.11 |
| JDK | 21（Maven Wrapper） |
| 源数据库 | `pulseops`（本地 Compose 服务 `postgres`） |
| 恢复目标库 | `pulseops_restore` |

### 演练步骤与结果

#### 1. 备份

```bash
./scripts/backup.sh
```

输出：

```text
Backing up database 'pulseops' from service 'postgres'...
Verifying dump with pg_restore --list...
Backup written: ./backups/pulseops-20261026T050841Z.dump (52K)
note: the dump contains password hashes and heartbeat token digests; keep it private.
Retained backups for 'pulseops': 1/7
```

- 备份文件：`backups/pulseops-20261026T050841Z.dump`，50,915 字节。
- `pg_restore --list` 自检通过，归档为 `CUSTOM` 格式，数据库版本 17.11。可用以下命令复核归档头：

  ```bash
  docker compose exec -T postgres pg_restore --list < backups/pulseops-20261026T050841Z.dump | head -12
  ```

- 文件保留在本地 `backups/`（已被 `.gitignore` 忽略），未提交到 Git。

保留份数验证：在临时目录放入 3 个旧备份，保留数设为 2 后执行备份，最旧的 2 个被删除：

```bash
TDIR=$(mktemp -d)
touch "$TDIR/pulseops-20200101T000000Z.dump" \
      "$TDIR/pulseops-20200201T000000Z.dump" \
      "$TDIR/pulseops-20200301T000000Z.dump"
BACKUP_DIR="$TDIR" BACKUP_RETENTION_COUNT=2 ./scripts/backup.sh
```

```text
Backup written: /tmp/.../pulseops-20261026T051018Z.dump (52K)
Removing old backup: /tmp/.../pulseops-20200101T000000Z.dump
Removing old backup: /tmp/.../pulseops-20200201T000000Z.dump
Retained backups for 'pulseops': 2/2
```

临时目录在验证后删除。

#### 2. 恢复到独立库

```bash
./scripts/restore.sh backups/pulseops-20261026T050841Z.dump pulseops_restore
```

输出：

```text
Verifying dump with pg_restore --list...
Creating database 'pulseops_restore'...
CREATE DATABASE
Restoring '/g/work/workspace/2026/PulseOps/backups/pulseops-20261026T050841Z.dump' into 'pulseops_restore'...
Restore completed: ... -> pulseops_restore
```

覆盖保护验证：

- 对已存在的 `pulseops_restore` 再次执行同一命令，脚本以退出码 1 拒绝，并提示使用 `--force`：

  ```text
  error: database 'pulseops_restore' already exists; pass --force to replace it
  ```

- 脚本无条件拒绝恢复进 Compose 源库与 PostgreSQL 维护库（与是否传 `--force` 无关）：

  ```text
  $ ./scripts/restore.sh backups/pulseops-20261026T050841Z.dump pulseops
  error: refusing to restore into the source database 'pulseops'; use a separate target database

  $ ./scripts/restore.sh backups/pulseops-20261026T050841Z.dump postgres
  error: refusing to restore into PostgreSQL maintenance database 'postgres'
  ```

#### 3. Flyway 历史与核心表校验

源库与恢复库的 Flyway 历史均为 12 条迁移（版本 1–9、11–13），全部 `success = true`，版本、描述与校验和聚合一致：

```text
flyway|12|e10902088b1e1941962fd2dfabc760cf
```

核心表行数与整表内容摘要（按主键排序的 `md5`）在两库之间完全一致：

| 表 | 行数 | 内容摘要（源库 = 恢复库） |
|---|---|---|
| `flyway_schema_history` | 12 | `e10902088b1e1941962fd2dfabc760cf` |
| `app_user` | 1 | `e8c7c4d934c2187b47c83701b61f886c` |
| `monitor` | 13 | `0a6abd298f64567a270d51daed87805b` |
| `check_result` | 1157 | `ae10c8fc83468171d383a31d1210b4d5` |
| `incident` | 16 | `4e2c8c4a454771ca014471aa69f2ea79` |
| `notification_delivery` | 12 | `0fd03e503c76c2e3d7d4bcb2d70effa1` |
| `heartbeat_credential` | 7 | `1d1656fff3c99345debe97eba7c74770` |

复核方式：对每个数据库分别执行同样的查询并 `diff`。普通表示例：

```bash
docker compose exec -T postgres psql -U pulseops -d pulseops -At -c \
  "select 'monitor|' || count(*) || '|' || md5(string_agg(t::text, '|' order by id)) from monitor t"
```

Flyway 历史使用 `version || ':' || description || ':' || checksum` 按 `installed_rank` 聚合；其余表使用 `t::text` 按 `id` 聚合。两库输出逐行 `diff` 为空。

抽样数据同样一致：

- `monitor`：13 条（HTTP 与 Heartbeat 混合，含禁用项 `id=13`）。
- `check_result`：最新 5 条（`id=1158`、`1157`、`1156`、`1155`、`1154`）的监控项、结果、HTTP 状态码与时间一致。
- `incident`：最新 5 条（`id=17`、`16`、`15`、`13`、`12`）的状态、开始与恢复时间一致。

#### 4. 用恢复库启动应用

```bash
cd backend
DB_URL=jdbc:postgresql://localhost:5432/pulseops_restore \
SERVER_PORT=8081 \
PULSEOPS_PROBE_SCHEDULING_ENABLED=false \
PULSEOPS_HEARTBEAT_SCHEDULING_ENABLED=false \
PULSEOPS_NOTIFICATION_SCHEDULING_ENABLED=false \
PULSEOPS_CLEANUP_SCHEDULING_ENABLED=false \
./mvnw spring-boot:run
```

关闭调度器是为了让演练只读，避免探测、心跳扫描、通知投递或清理修改恢复库。

结果：

| 检查 | 结果 |
|---|---|
| `GET /actuator/health` | 200 `{"status":"UP"}` |
| `GET /api/v1/auth/session`（未登录） | 200，`authenticated=false`，下发 CSRF Token |
| `POST /api/v1/auth/login` | 200 `{"username":"drill_admin","role":"ADMIN"}` |
| `GET /api/v1/monitors`（已登录） | 200，`totalElements=13`、`items=13` |
| `GET /api/v1/monitors`（未登录） | 401 |

#### 5. 清理

- 停止端口 8081 的演练后端进程。
- 删除 `pulseops_restore` 演练库，避免在本地保留带已知演练密码的数据副本。
- 删除演练过程中产生的 Cookie、登录响应与临时校验输出文件；仅保留备份文件于本地 `backups/`（已被 `.gitignore` 忽略）。

### 演练验收对照

| 验收条件 | 证据 |
|---|---|
| 脚本通过 `docker compose exec -T` 执行，宿主机无需客户端 | 备份与恢复输出；脚本实现 |
| `BACKUP_DIR` / `BACKUP_RETENTION_COUNT` / `COMPOSE_SERVICE` 默认值 | 脚本参数；保留份数验证 |
| 文件名含 UTC 时间戳 | `pulseops-20261026T050841Z.dump` |
| `pg_restore --list` 自检、`umask 077`、按份数删除最旧 | 备份输出；保留份数验证删除了 2 个最旧文件 |
| `restore.sh <file> <target-db>` 创建并恢复，不静默覆盖 | 恢复输出；重复执行退出码 1；源库与维护库保护输出 |
| `.gitignore` 忽略 `backups/` | `.gitignore` |
| 演练：Flyway、行数、抽样、健康检查与管理 API | 第 3、4 节 |
| 更新部署文档与验收记录 | `docs/quality/deployment.md`、本文件 |

### 演练限制

- **演练账号**：源库唯一管理员 `admin` 的密码未记录在本仓库中。为验证已认证的管理 API，仅在 `pulseops_restore` 中临时插入 `drill_admin`（已知密码的 BCrypt 哈希），随后随演练库一并删除；源库未改动。行数与校验和对比在插入该账号之前完成。
- **权限位**：演练宿主机为 Windows NTFS + Git Bash，不强制 POSIX 权限位，`ls -l` 仍显示 644。脚本中的 `umask 077` 与 `chmod 600` 在 Linux 上生效，需在 Phase 5 VPS 上复核。
- **失败清理未触发**：演练未人为制造 `pg_restore` 中途失败，恢复失败后删除半成品库的分支没有运行证据。

## 已知限制与残余风险

- **会话存储**：会话保存在应用内存，重启后需要重新登录；多实例前需要持久会话或粘性路由。[ADR-0008](../adr/0008-admin-auth-session.md)
- **登录限流**：只使用请求远端地址；Phase 5 引入反向代理后，若未正确配置可信代理头，多个客户端可能共享同一限流计数。
- **单实例调度**：探测、心跳扫描、通知投递与清理均为单实例语义；多实例前需要数据库租约或原子认领。[ADR-0009](../adr/0009-data-retention-policy.md)
- **数据删除不可逆**：超过保留期限的 CheckResult 与已解决 Incident 会被永久删除，默认 30/90 天；需要长期归档时应结合备份策略。
- **备份自动化**：定时备份、异地存放、加密与备份监控属于 Phase 5；当前只有手动脚本与一次本地恢复演练。备份包含密码哈希与心跳令牌摘要。
- **浏览器验证**：Phase 4B 未引入 Playwright 端到端测试，也未在本次收口补充真实浏览器人工走查；认证 UI 由组件、路由与 API 客户端测试覆盖。
- **代理与真实 IP**：反向代理头与客户端 IP 信任规则留待 Phase 5 与 Caddy 联调；当前限流与日志只使用请求远端地址。
- **前端认证状态**：只保存在内存，刷新页面依赖 `GET /api/v1/auth/session`；不可用时不写入浏览器存储。
- **公网发布**：Phase 4B 完成不代表可以公网发布；必须先完成 Phase 5 的 TLS、反向代理、防火墙、日志轮转与备份自动化。
