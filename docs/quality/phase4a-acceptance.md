# Phase 4A 验收证据

本文件汇总 Phase 4A（最小可用 UI）的验收证据。Phase 4A 包含以下 tickets：

- #45 前端骨架、API 边界与测试基础设施
- #46 监控列表、状态与探测延迟
- #47 HTTP 配置闭环：创建、编辑、启停与删除
- #48 心跳配置、一次性令牌与上报命令
- #49 监控详情、历史与 Incident 投递状态
- #50 阶段验收与文档（本文件）

## CI 证据

工作流：[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)。每次 PR 在干净 checkout 上运行后端 `./mvnw verify`（Testcontainers PostgreSQL 17）与前端 `npm ci && npm test && npm run build`。

| Ticket | PR | PR checks | 合并提交 |
|---|---|---|---|
| #45 前端骨架与测试基础设施 | [#51](https://github.com/kevinmxt/PulseOps/pull/51) | [checks](https://github.com/kevinmxt/PulseOps/pull/51/checks) | `a3c383a` |
| #46 监控列表与探测延迟 | [#52](https://github.com/kevinmxt/PulseOps/pull/52) | [checks](https://github.com/kevinmxt/PulseOps/pull/52/checks) | `fe19a69` |
| #47 HTTP 配置闭环 | [#53](https://github.com/kevinmxt/PulseOps/pull/53) | [checks](https://github.com/kevinmxt/PulseOps/pull/53/checks) | `52ade49` |
| #48 心跳配置与令牌 | [#54](https://github.com/kevinmxt/PulseOps/pull/54) | [checks](https://github.com/kevinmxt/PulseOps/pull/54/checks) | `0f1ca3d` |
| #49 详情、历史与投递状态 | [#55](https://github.com/kevinmxt/PulseOps/pull/55) | [checks](https://github.com/kevinmxt/PulseOps/pull/55/checks) | `3e2ba2d` |

本文件所属收口 PR 的 checks 页面记录在 #50 的完成报告中。按仓库测试与评审节奏，不为补充本文件中的链接再触发额外 CI。

## 本地执行记录

2026-10-24 在开发者本机执行（Windows 10，JDK 21，Node 24.15.0，Docker Desktop）：

```bash
cd backend
./mvnw --batch-mode --no-transfer-progress verify

cd ../frontend
npm test
npm run build
```

结果：

- 后端：183 个测试，0 失败，`BUILD SUCCESS`（集成测试使用 Testcontainers PostgreSQL 17）。
- 前端：104 个测试通过。
- 前端生产构建通过。

以上为本地对照记录；阶段门禁以 PR checks 为准。

## 手动 UI 走查

### 环境与数据

- 后端监听 `127.0.0.1:8080`，PostgreSQL 17 由 Docker Compose 提供。
- 前端 Vite 开发服务器监听 `127.0.0.1:5175`，通过 `/api` 代理访问后端。
- 浏览器：Microsoft Edge 156 headless，通过 Chrome DevTools Protocol 驱动；未引入 Playwright 或新的 E2E 依赖。
- 数据来源：真实 HTTP 探测（`CONNECT_TIMEOUT`）、真实心跳超时与恢复、真实通知 outbox 调度。
- 通知验证使用本地假 Telegram 端点，分别返回 500 / 200 / 400，以产生真实的 `PENDING` / `SENT` / `FAILED` 投递记录；未使用数据库直写数据作为证据。

### 走查结果

| 验收点 | 结果 | 说明 |
|---|---|---|
| 监控项列表展示名称、类型与状态 | 通过 | HTTP 与 Heartbeat 混合列表，显示「正常 / 故障 / 已禁用」 |
| 列表展示最近检查与 Probe Latency | 通过 | 相对时间 + `title` 绝对时间；延迟显示毫秒 |
| 通过 UI 创建 HTTP 监控 | 通过 | 创建后进入详情页，配置正确 |
| 通过 UI 编辑 HTTP 监控 | 通过 | 修改名称与间隔后回到详情并反映新值 |
| 列表行内禁用 | 通过 | 无需确认，状态列显示「已禁用」 |
| 删除二次确认 | 通过 | 确认后列表刷新且记录消失 |
| 通过 UI 创建 Heartbeat Monitor | 通过 | 类型选择、名称、间隔、宽限均可用 |
| 一次性令牌与上报命令 | 通过 | 创建后展示令牌与 `curl` 命令 |
| 命令使用当前 origin | 通过 | 命令包含 `http://127.0.0.1:5175/api/v1/heartbeats/<token>` |
| 令牌不进入 URL | 通过 | 展示后路径仍为 `/monitors/new` |
| 「我已保存」确认 | 通过 | 确认后令牌从页面移除 |
| 心跳详情配置与提示 | 通过 | 展示预期间隔/宽限，提示令牌需到编辑页轮换 |
| 轮换令牌 | 通过 | 轮换后展示新令牌 |
| 撤销令牌 | 通过 | 显示「令牌已撤销，上报将返回 404」 |
| 详情分开显示启用状态与 Monitor Status | 通过 | 禁用项同时显示「已禁用」与真实状态/原始枚举 |
| 检查历史 | 通过 | 展示失败类型、HTTP 状态码、耗时与错误摘要 |
| 详情只列 OPEN Incident | 通过 | 活动 Incident 表格 + 「查看全部」 |
| 详情跳转按监控过滤 | 通过 | `/incidents?monitor_id=5`，页面显示「仅显示监控项 #5」 |
| Incident 状态切换 | 通过 | OPEN / RESOLVED 均返回真实数据 |
| PENDING 投递展示失败摘要 | 通过 | 真实 500 投递显示「待发送」与 `Telegram returned HTTP 500` |
| FAILED 投递展示 | 通过 | 真实 400 投递显示「失败」与 `Telegram returned HTTP 400` |
| SENT 投递展示 | 通过 | 真实 200 投递显示「已发送」 |
| 恢复投递展示 | 通过 | 心跳恢复后 RESOLVED Incident 展示「故障恢复」且已发送 |

未提交截图；未在文档中记录任何令牌明文。

### 未手动覆盖的部分

- 复制令牌/命令的成功与剪贴板失败回退：由 `MonitorCreateView.test.ts` 覆盖。
- 历史分页控件渲染、翻页请求与页码映射：由 `MonitorDetailView.test.ts` 覆盖。
- 浏览器前进/后退与深层链接：路由使用 history 模式，`App.test.ts` 覆盖路由渲染；未单独手动点击后退。
- 列表删除最后一页最后一项后的页码回退：由 `MonitorListView.test.ts` 覆盖。
- 并发投递展开的过期响应保护：由 `IncidentListView.test.ts` 覆盖。

## 路线图完成条件对照

| 完成条件 | 证据 |
|---|---|
| 监控项列表、创建、编辑、禁用和删除 | 手动 UI 走查；`MonitorListView.test.ts`、`MonitorCreateView.test.ts`、`MonitorEditView.test.ts` |
| 当前状态、最近检查时间与延迟 | 手动 UI 走查；`MonitorApiIT#returnsLatestCheckDurationForHttpMonitor`、`MonitorListView.test.ts` |
| 历史结果分页、活动 Incident 与通知状态 | 手动 UI 走查；`MonitorDetailView.test.ts`、`IncidentListView.test.ts`；`IncidentApiIT#returnsRetryErrorSummaryForPendingDelivery` |
| 心跳配置与上报命令展示 | 手动 UI 走查；`MonitorCreateView.test.ts`、`MonitorEditView.test.ts`、`lib/heartbeatInput.test.ts`、`lib/heartbeatCommand.test.ts` |
| 核心功能可仅通过 UI 完成 | 上述手动走查全部在浏览器中完成，未直接调用 API 进行业务操作 |

## Ticket 验收对照

### #45 前端骨架、API 边界与测试基础设施

- 5 个路由、导航、未找到页面：`App.test.ts`。
- 统一错误信封与超时：`api/client.test.ts`。
- Vitest + `@vue/test-utils`，CI 执行 `npm test`：CI 证据。

### #46 监控列表、状态与探测延迟

- 跨页延迟正确：`MonitorApiIT#returnsDurationsForMonitorsAcrossPages`。
- 无历史为 `null`：`MonitorApiIT#returnsNullDurationWhenNoChecksExist`。
- Heartbeat 恒为 `null`：`MonitorApiIT#returnsNullDurationForHeartbeatMonitorEvenWithCheckResult`。
- 列表渲染、轮询、分页与空/错误状态：`MonitorListView.test.ts`。

### #47 HTTP 配置闭环

- 创建/编辑/启停/删除、类型不可变、完整 PUT：`MonitorCreateView.test.ts`、`MonitorEditView.test.ts`、`MonitorListView.test.ts`、`lib/monitorInput.test.ts`。
- 客户端校验与服务端错误展示：`lib/monitorInput.test.ts`、`lib/apiError.test.ts`。

### #48 心跳配置、一次性令牌与上报命令

- 心跳表单与范围校验：`lib/heartbeatInput.test.ts`。
- 一次性令牌、命令、复制回退、「我已保存」：`MonitorCreateView.test.ts`。
- 轮换/撤销、禁用状态可用、重进不显示令牌：`MonitorEditView.test.ts`。
- 令牌不落存储/URL：`MonitorEditView.test.ts`、`MonitorCreateView.test.ts`。

### #49 监控详情、历史与 Incident 投递状态

- 详情配置、状态分离、历史与 OPEN Incident：`MonitorDetailView.test.ts`。
- Incident 过滤、分页、投递展开：`IncidentListView.test.ts`。
- PENDING 摘要 / SENT 清空 / FAILED 保留：`NotificationFlowIT#retriesServerErrorsWithBackoffBeforeFailing`、`#deliversAfterTransientFailure`、`IncidentApiIT#returnsRetryErrorSummaryForPendingDelivery`。

### #50 阶段验收与文档

- 本文件、`docs/product/roadmap.md`、`README.md`、`docs/index.md`、`docs/specs/api.md`。
- 最终本地全量验证与 PR checks。

## 已知限制与残余风险

- **仅本地访问**：管理 API 默认绑定 `127.0.0.1`，Phase 4B 完成认证前不得公网发布。
- **无生产托管**：Phase 4A 只验证 Vite 开发代理；生产静态托管、Caddy/HTTPS 属于 Phase 5。
- **无浏览器 E2E 套件**：手动走查通过 headless Edge + CDP 执行，未引入 Playwright；交互关键路径由组件测试覆盖。
- **通知渠道**：本地走查使用假 Telegram 端点，未验证真实 Telegram 网络与限流行为。
- **单实例调度**：调度、心跳扫描与通知投递仍为单实例语义；多实例前需要数据库租约或原子认领。
- **历史清理**：Phase 4A 不实现数据保留策略；Phase 4B 的分批清理是公网前硬门槛。
- **相对时间**：超过 30 天回落为绝对时间；未来时间钳制为「刚刚」。
- **详情快速切换**：检查历史与 Incident 请求未做请求序号保护（投递展开已保护）；快速切换监控项时可能短暂显示旧数据。
- **令牌操作位置**：轮换/撤销在编辑页；详情页保持只读并提示到编辑页操作。

## 环境限制

- 后端集成测试需要 Docker daemon（Testcontainers PostgreSQL 17）。
- 手动 UI 走查依赖本地后端、PostgreSQL、Vite 与 headless Edge；未在 CI 中重复执行。
- 本文件不宣称已完成公网部署、认证、备份恢复或真实 Telegram 交付验证。
