# 核心数据模型

本文件定义核心业务语义。字段和索引可以随实现调整，但不能丢失以下语义。

## Monitor
表示一个监控项。

核心字段：
- `id`
- `name`
- `type`：`HTTP` 或 `HEARTBEAT`
- `target_url`：HTTP 类型使用
- `method`
- `interval_seconds`
- `timeout_ms`
- `failure_threshold`
- `recovery_threshold`
- `heartbeat_grace_seconds`
- `enabled`
- `current_status`
- `last_checked_at`
- `last_success_at`
- `created_at`
- `updated_at`

对时间间隔、超时、URL 和状态字段实施服务端校验。

## CheckResult
表示一次探测或检查结果。

核心字段：
- `id`
- `monitor_id`
- `started_at`
- `finished_at`
- `duration_ms`
- `success`
- `failure_type`
- `http_status_code`
- `error_summary`

按 `monitor_id` 和时间字段建立适当索引。错误摘要应截断，不能包含凭证。

## Incident
表示一次持续故障事件。

核心字段：
- `id`
- `monitor_id`
- `started_at`
- `resolved_at`
- `status`
- `failure_type`
- `summary`

同一监控项原则上最多有一个未解决的当前 Incident。通过事务、唯一约束或等效并发控制保证一致性。

## HeartbeatCredential
- `id`
- `monitor_id`
- `token_hash`
- `created_at`
- `revoked_at`

数据库只保存安全摘要，不保存明文令牌。令牌必须足够随机并可撤销。

## NotificationDelivery
- `id`
- `incident_id`
- `channel`
- `event_type`
- `status`
- `attempt_count`
- `last_attempt_at`
- `sent_at`
- `last_error_summary`

用于追踪通知发送和重试，不得把通知失败与故障状态回滚绑定。

## 数据库规范
- 所有正式结构变更使用 Flyway。
- 明确外键、唯一约束、非空约束和索引。
- 时间统一使用 UTC。
- 历史清理采用分批处理。
- 删除或修改核心字段前检查数据兼容性。
- 并发相关不变量必须有测试。
