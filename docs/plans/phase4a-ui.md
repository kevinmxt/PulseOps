# Phase 4A：最小可用 UI — 设计共识

## 状态与用途

- 本文件是 grilling 设计访谈的产物，作为 `to-spec` 与 `to-tickets` 的输入。
- 长期行为规范以 `docs/specs/ui.md`（待创建）和 `docs/specs/api.md` 为准；本文件保留决策背景与被否决方案。
- 领域术语不在此重复，见 [`GLOSSARY.md`](../../GLOSSARY.md)，特别是 **Probe Latency（探测延迟）**。
- 阶段、范围与完成条件以 [`../product/roadmap.md`](../product/roadmap.md) 为准。

## 目标与完成条件

只用 UI 完成核心闭环：

- 监控项列表、创建、编辑、启用/禁用、删除（HTTP 与 Heartbeat）。
- 当前状态、最近检查时间、探测延迟。
- 历史结果分页、活动 Incident 与通知投递状态。
- 心跳配置、一次性令牌与上报命令展示。

完成条件：核心功能可仅通过 UI 完成。管理 API 仍只绑定 `127.0.0.1`，UI 通过 Vite 代理本地运行。

## 现状事实

- 后端 API 已覆盖 Monitor CRUD、`/checks` 分页、`/incidents`（支持 `status`、`monitor_id` 过滤）与 Incident 详情（内嵌最近 5 条投递）。
- `MonitorResponse` 已有 `lastCheckedAt`、`lastSuccessAt`、`lastHeartbeatAt`，但没有延迟；延迟仅存在于 `CheckResult` 的 `durationMs`。
- `PUT /api/v1/monitors/{id}` 是整体替换语义，未提供的可配置字段按默认值处理；列表响应已包含全部可配置字段。
- 一次性心跳令牌只在创建或轮换时返回。
- 前端仍是 Vite 脚手架，无路由、无 HTTP 客户端、无组件库、无测试框架；CI 只运行 `npm run build`。
- 后端默认 `SERVER_ADDRESS=127.0.0.1`，Vite 开发服务器默认仅本地监听。

## 已确认决策

| 主题 | 决策 |
|---|---|
| 延迟来源 | 后端 `MonitorResponse` 增加 `lastCheckDurationMs`，按当前页批量查询每个 Monitor 的最新 CheckResult；复用现有 `idx_check_result_monitor_started_at`，无需迁移 |
| 延迟语义 | 取最近一次 Probe 的耗时；Heartbeat Monitor 恒为 `null`（心跳上报产生的 CheckResult 不是 Probe） |
| 启用/禁用 | 前端用列表行数据构造全量 `PUT`，不新增后端接口；禁用不弹确认 |
| 路由 | `/monitors`、`/monitors/new`、`/monitors/:id`、`/monitors/:id/edit`、`/incidents` |
| 前端依赖 | `vue-router` + 原生 `fetch` 封装 + 手写 CSS；无 UI 组件库、无 Pinia、无 axios |
| 界面语言 | 中文文案；状态标签显示「正常 / 故障 / 未知」，详情页保留 `UP / DOWN / UNKNOWN` 原始值 |
| 视觉方向 | 紧凑的开发者运维工具风：高信息密度、明确状态色、URL 与令牌使用等宽字体、单一亮色主题 |
| 时间显示 | 列表与最近时间用相对时间（如「3 分钟前」），`title` 显示绝对本地时间；历史表格用绝对时间 |
| 刷新 | 列表页在标签页可见时每 30 秒自动刷新，并提供手动刷新；详情页仅进入、手动刷新和变更后重新加载 |
| 监控详情 Incident | 只显示该监控的 OPEN Incident，附「查看全部」链接到 `/incidents?monitor_id=<id>` |
| Incident 页 | `/incidents` 支持 OPEN/RESOLVED 切换、按 `monitor_id` 过滤，内联展开最近 5 条投递记录 |
| 禁用展示 | 状态列优先显示「已禁用」，小字保留最后已知状态；详情页分开显示启用状态与 Monitor Status |
| 表单字段 | 常用字段（名称、URL、方法、间隔、启用）直接展示；高级字段（连接/响应超时、期望状态码、失败/恢复阈值）折叠但可编辑；心跳类型只显示名称、间隔、宽限、启用 |
| 表单错误 | 前端基础校验（必填、范围、URL 格式）；服务端错误统一显示在表单顶部，含 `code`、`message`、`traceId`；不扩展错误契约 |
| 删除 | 二次确认；删除后级联删除心跳凭证 |
| 心跳令牌 | 只在创建/轮换后一次性展示；保存在组件内存，不写 `localStorage`/`sessionStorage`、不入 URL、不打印控制台；离开页面即清除；提供复制按钮与「我已保存」确认 |
| 上报命令 | `curl -fsS -X POST <window.location.origin>/api/v1/heartbeats/<token>`；提示生产环境需替换主机名 |
| 前端测试 | 引入 Vitest + `@vue/test-utils`，覆盖 API 错误映射、表单负载构造、状态与时间格式化；CI 增加 `npm test` |
| 端到端验证 | 手动浏览器验收并记录清单；不引入 Playwright |
| 顺手修复 | 纳入 Phase 3 P2：`PENDING` 重试态也保存最近失败摘要 |
| 验收证据 | `docs/quality/phase4a-acceptance.md` 记录手动验收清单、CI 链接与已知限制；不提交截图 |

不新增 ADR：以上决策均可低成本回退，且不需要未来读者追问「为什么这样做」。

## 已考虑但未采用

- **前端 N+1 拉延迟**：分页 20 时产生 21 个请求。
- **只在详情页显示延迟**：不满足路线图对列表的要求。
- **新增 `PATCH /monitors/{id}` 只改 `enabled`**：扩大 API 面，当前全量 PUT 已足够。
- **UI 组件库、Pinia、axios**：违背「依赖越少越好」。
- **无路由单页 + 模态框**：详情深层链接、浏览器后退和分页体验差。
- **Playwright**：超出 Phase 4A 范围。
- **扩展字段级错误数组**：当前服务端消息已包含字段名，暂不改变契约。
- **令牌写入 `sessionStorage`**：刷新可见的便利不抵泄露面扩大。
- **提交验收截图**：仓库体积与本地数据隐私。
- **搜索/状态过滤、图表大屏、主题切换、i18n、生产托管与认证**：非目标或属于后续阶段。

## 后端改动

1. `MonitorResponse` 增加 `lastCheckDurationMs`（`Long`，可空）：
   - 按当前页的 Monitor ID 批量查询最新 CheckResult，避免 N+1。
   - HTTP Monitor 返回最新 CheckResult 的 `durationMs`；无历史时为 `null`。
   - Heartbeat Monitor 恒为 `null`，即使上报写入了 CheckResult。
2. 通知重试态修复：投递进入 `PENDING` 重试时保存最近一次失败摘要；`SENT` 清空；终态 `FAILED` 保留。
3. 测试：覆盖列表延迟的批量查询、无历史为 `null`、Heartbeat 为 `null`；覆盖 PENDING 摘要写入与 SENT 清空。

## 前端改动

- `src/api/`：带类型的 `fetch` 封装，统一解析 `{code, message, traceId}` 错误。
- `src/views/`：监控列表、创建、编辑、详情、Incident 页。
- `src/components/`：状态徽章、类型徽章、分页器、确认对话框、表单字段组、令牌展示。
- `src/composables/`：轮询、时间格式化、监控表单负载构造。
- 样式基线：颜色变量、状态色、等宽字体类、紧凑表格。
- 路由：5 个路由，导航栏含「监控项」「Incidents」。
- CI：增加 `npm test`。

## 任务拆分与依赖

每个任务一个 issue 与一个 PR，标签 `ready-for-agent`。

| 编号 | 内容 | 关键验收 | 依赖 |
|---|---|---|---|
| 4A-1 | 前端基础设施：路由、API 客户端、布局与 CSS 基线、Vitest、CI `npm test` | 5 个路由可渲染；错误对象统一；`npm run build` 与 `npm test` 通过 | — |
| 4A-2 | 监控列表：类型/状态徽章、禁用展示、相对时间、延迟、分页、轮询与手动刷新；含后端 `lastCheckDurationMs` | 列表数据正确；跨页延迟正确；禁用项显示「已禁用」；轮询生效 | 4A-1 |
| 4A-3 | 配置闭环（HTTP）：创建/编辑表单、行内启用/禁用、删除确认 | HTTP 监控项可仅通过 UI 完成创建、编辑、禁用、删除；类型不可修改 | 4A-2 |
| 4A-4 | 心跳闭环：心跳项创建/编辑、一次性令牌、轮换/撤销、上报命令 | 可创建心跳项并复制命令；旧令牌轮换后立即失效；撤销后上报返回 404；令牌不落存储 | 4A-3 |
| 4A-5 | 只读详情与 Incident：历史分页、OPEN Incident 区、`/incidents` 页、投递状态；含 P2 修复 | 历史分页正确；Incident 过滤与投递状态可读；PENDING 显示最近失败摘要 | 4A-2 |
| 4A-6 | 验收与文档：`phase4a-acceptance.md`、roadmap、`api.md`、`index.md`、README、最终全量验证 | 验收清单与 CI 链接齐全；全量验证只保留一次 | 4A-1 ~ 4A-5 |

说明：4A-3 只交付 HTTP 配置闭环；心跳创建与令牌流程统一放在 4A-4，避免中途出现「创建成功但看不到令牌」的半成品。

## 文档更新清单

- 新建 `docs/specs/ui.md`：路由与信息架构、展示语义、刷新与错误处理、令牌安全约束、非目标。
- 更新 `docs/specs/api.md`：`lastCheckDurationMs` 语义；PENDING 态 `lastErrorSummary`。
- 更新 `docs/index.md`：登记 `plans/` 与 `specs/ui.md`。
- 更新 `docs/product/roadmap.md`：完成 4A 时勾选并更新当前阶段。
- 更新 `README.md`：前端命令与 UI 使用入口。
- 完成时新建 `docs/quality/phase4a-acceptance.md`。

## 风险与缓解

- **全量 PUT 覆盖风险**：列表行数据可能过期。缓解：单用户、列表自动刷新、编辑页始终重新 GET。
- **轮询与分页交互**：删除后页码可能越界，需要回退到最后一页；刷新时保留当前页。
- **剪贴板 API**：依赖安全上下文，`localhost` 满足；提供手动选择回退。
- **无认证**：仍依赖回环绑定，4A 不改变该边界。
- **无端到端自动化**：验收依赖人工执行，必须如实记录，不能声称自动化覆盖。

## 验收映射

| 路线图要求 | UI 路径 | 证据 |
|---|---|---|
| 监控项列表、创建、编辑、禁用、删除 | `/monitors`、`/monitors/new`、`/monitors/:id/edit` | 手动验收清单 |
| 当前状态、最近检查时间、延迟 | `/monitors` 列表列 | 手动验收清单 + 4A-2 测试 |
| 历史结果分页、活动 Incident、通知状态 | `/monitors/:id`、`/incidents` | 手动验收清单 |
| 心跳配置与上报命令 | `/monitors/new`、`/monitors/:id` | 手动验收清单 + curl 实测 |
| 核心功能可仅通过 UI 完成 | 全流程不调用 API 客户端 | 端到端手动走查 |

## 下一步

1. 用 `to-spec` 生成本文件「文档更新清单」中的规范变更。
2. 用 `to-tickets` 创建 Phase 4A 父 issue 与 6 个子 issue；父 issue 链接本文件，不复制全文。
3. 从 4A-1 开始按「测试与评审节奏」推进。
