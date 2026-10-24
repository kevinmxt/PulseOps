# REST API 约定

## 通用约定
- 业务接口前缀：`/api/v1`
- JSON 请求和响应。
- 对输入进行服务端校验。
- 使用一致的错误结构：稳定错误代码、用户可理解的消息、可选请求追踪 ID。
- 不向客户端返回异常堆栈、数据库连接串或内部配置。
- 列表接口必须支持分页或有明确的结果上限。

## 初始接口清单

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/monitors` | 查询监控项 |
| POST | `/api/v1/monitors` | 创建监控项 |
| GET | `/api/v1/monitors/{id}` | 查询监控详情 |
| PUT | `/api/v1/monitors/{id}` | 修改监控配置 |
| DELETE | `/api/v1/monitors/{id}` | 删除监控项 |
| GET | `/api/v1/monitors/{id}/checks` | 查询探测历史 |
| POST | `/api/v1/monitors/{id}/heartbeat-token` | 轮换心跳令牌 |
| DELETE | `/api/v1/monitors/{id}/heartbeat-token` | 撤销心跳令牌 |
| GET | `/api/v1/incidents` | 查询故障事件 |
| GET | `/api/v1/incidents/{id}` | 查询故障详情 |
| POST | `/api/v1/heartbeats/{token}` | 上报任务心跳 |
| GET | `/actuator/health` | 健康检查（DNS 解析器持续耗尽时返回 503） |
| GET | `/actuator/metrics/{name}` | 按名查询运行指标 |

路径可随实现调整，但必须维护一致的 API 文档和测试。

Monitor 的请求与响应包含 `failureThreshold` 与 `recoveryThreshold`（取值 1–10，默认 3/2）；请求中缺失时使用默认值。

### 监控类型

`type` 取 `HTTP`（默认）或 `HEARTBEAT`，创建后不可变。更新使用整体替换语义：除省略的 `type` 表示保持原类型外，未提供的可配置字段按默认值处理；传入不同的 `type` 返回 400 `VALIDATION_ERROR`。

心跳监控项的请求支持 `intervalSeconds`（30–2592000，默认 300）与 `heartbeatGraceSeconds`（0–86400，默认 60）。HTTP 专用字段（`targetUrl`、`method`、超时、期望状态码）及 `failureThreshold`/`recoveryThreshold` 对心跳类型被忽略；响应中这些字段为 `null`，并返回 `heartbeatGraceSeconds` 与 `lastHeartbeatAt`。创建时 `currentStatus` 为 `UNKNOWN`。

创建心跳监控项返回 201，响应额外包含仅此一次可见的 `heartbeatToken`；列表、详情和更新响应中该字段恒为 `null`。数据库只保存令牌摘要，删除监控项时级联删除凭证。更新 `intervalSeconds` 或 `heartbeatGraceSeconds` 时重算心跳截止时间。

### 心跳上报与令牌管理

- `POST /api/v1/heartbeats/{token}`：有效令牌返回 204 且不解析请求体；更新 `lastHeartbeatAt`、`lastCheckedAt`、`lastSuccessAt`，状态置 `UP`，顺延 `nextCheckAt`，并写入一条成功 CheckResult。无效、已撤销、不存在或所属监控项已禁用的令牌统一返回 404 `HEARTBEAT_NOT_FOUND`，不区分原因。
- `POST /api/v1/monitors/{id}/heartbeat-token`：轮换令牌，返回 200 与仅此一次可见的 `heartbeatToken`；旧令牌立即失效；禁用状态仍允许。非心跳类型返回 400 `NOT_HEARTBEAT_MONITOR`；监控项不存在返回 404 `MONITOR_NOT_FOUND`。
- `DELETE /api/v1/monitors/{id}/heartbeat-token`：撤销令牌，幂等返回 204；禁用状态仍允许；之后上报返回 404。
- 防护：按令牌 SHA-256 摘要做进程内固定窗口限流，每分钟 120 次（无效令牌同样计数），超出返回 429 `RATE_LIMITED` 并带 `Retry-After`；请求体超过 1KB 返回 413 `PAYLOAD_TOO_LARGE`；`pulseops.heartbeat.require-https` 开启时要求原生 TLS 或 `X-Forwarded-Proto: https`，否则返回 400 `HTTPS_REQUIRED`。

## 分页约定

列表接口使用统一信封：`{items, page, size, totalElements}`。`page` 从 0 开始；`size` 默认 20，最大 100。

`GET /api/v1/monitors/{id}/checks` 返回指定 Monitor 的 CheckResult，按 `started_at` 倒序；没有历史时返回空 `items` 和 `totalElements = 0`；Monitor 不存在时返回 404 和 `MONITOR_NOT_FOUND`。

`GET /api/v1/incidents` 支持 `status`（`OPEN` / `RESOLVED`）与 `monitor_id` 过滤，按 `started_at` 倒序；非法 `status` 返回 400 和 `VALIDATION_ERROR`。`GET /api/v1/incidents/{id}` 返回 `monitorId`、`monitorName`、`startedAt`、`resolvedAt`、`status`、`failureType`、`summary`，并内嵌最近 5 条投递记录（渠道、事件类型、状态、尝试次数、最后尝试时间、发送时间、错误摘要）；不存在时返回 404 和 `INCIDENT_NOT_FOUND`。

## 安全约束
- 管理接口必须受认证和授权保护；Phase 4B 前仅允许回环地址访问，具体门槛见 [`../quality/deployment.md`](../quality/deployment.md) 与 [ADR-0003](../adr/0003-defer-admin-auth.md)。
- 心跳令牌独立于管理凭证。
- 不在响应或日志中暴露秘密。
- `/actuator/health` 只返回必要的健康信息。
- Actuator 端点只随应用监听地址暴露（默认 `127.0.0.1`），不得直接对公网开放。
- 目标 URL 必须遵守 SSRF 防护规范。

## 兼容性
修改已存在的接口时，先检查前端、测试和文档依赖。破坏性变更应记录原因，并同步更新相关文档。
