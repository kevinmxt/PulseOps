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
| GET | `/api/v1/incidents` | 查询故障事件 |
| GET | `/api/v1/incidents/{id}` | 查询故障详情 |
| POST | `/api/v1/heartbeats/{token}` | 上报任务心跳 |
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/metrics/{name}` | 按名查询运行指标 |

路径可随实现调整，但必须维护一致的 API 文档和测试。

## 分页约定

列表接口使用统一信封：`{items, page, size, totalElements}`。`page` 从 0 开始；`size` 默认 20，最大 100。

`GET /api/v1/monitors/{id}/checks` 返回指定 Monitor 的 CheckResult，按 `started_at` 倒序；没有历史时返回空 `items` 和 `totalElements = 0`；Monitor 不存在时返回 404 和 `MONITOR_NOT_FOUND`。

## 安全约束
- 管理接口必须执行认证和授权。
- 心跳令牌独立于管理凭证。
- 不在响应或日志中暴露秘密。
- `/actuator/health` 只返回必要的健康信息。
- Actuator 端点只随应用监听地址暴露（默认 `127.0.0.1`），不得直接对公网开放。
- 目标 URL 必须遵守 SSRF 防护规范。

## 兼容性
修改已存在的接口时，先检查前端、测试和文档依赖。破坏性变更应记录原因，并同步更新相关文档。
