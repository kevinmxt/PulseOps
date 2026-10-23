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
- `connect_timeout_ms`
- `response_timeout_ms`
- `expected_status_min`
- `expected_status_max`
- `enabled`
- `current_status`：`UNKNOWN`、`UP`、`DOWN`；由连续失败/成功达到阈值的状态机决定，不由单次 Probe 结果决定。`UNKNOWN` 下首次成功直接变为 `UP`。
- `last_checked_at`
- `last_success_at`
- `next_check_at`：内部调度字段，不对外暴露
- `config_version`：内部并发控制字段。`target_url`、`method`、`interval_seconds`、`connect_timeout_ms`、`response_timeout_ms`、`expected_status_min`、`expected_status_max` 变化时递增；`name`、`enabled`、阈值不计入版本。
- `consecutive_failures`：连续失败 Probe 计数，成功时清零。
- `consecutive_successes`：连续成功 Probe 计数，失败时清零。
- `failure_threshold`：进入故障前所需的连续失败次数。
- `recovery_threshold`：恢复前所需的连续成功次数。
- `created_at`
- `updated_at`

`config_version` 用于探测结果写回时的条件校验；行为规范见 `specs/monitoring.md`。

Phase 2 的计数与阈值在探测写回事务内更新；探测语义配置变更、禁用和重新启用时计数清零。修改阈值不追溯已有计数。Phase 3 加入 `heartbeat_grace_seconds`。各阶段迁移只创建该阶段需要的列。

对时间间隔、超时、URL 和状态字段实施服务端校验。连接超时与响应超时分别对应建连阶段和已建连后的响应阶段，见 ADR-0004。

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

同一监控项最多有一个未解决的当前 Incident，由部分唯一索引与探测写回的行锁保证。`status` 取 `OPEN` 或 `RESOLVED`；恢复时写入 `resolved_at`。禁用 Monitor 时静默关闭未解决 Incident，删除时级联删除。

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
