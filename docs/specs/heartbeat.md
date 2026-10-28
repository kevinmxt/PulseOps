# Heartbeat 心跳监控规范

## 目标
让后台任务、脚本和定时作业主动报告执行成功，以发现任务停止、延迟或未执行的问题。

## 创建心跳监控项
每个 Heartbeat Monitor 至少配置：
- 名称
- 预期执行间隔
- 宽限时间
- 启用状态

创建后生成独立的高强度随机令牌。令牌只在创建或重新生成时显示一次；数据库保存安全摘要。

## 上报接口

路径：`POST /api/v1/heartbeats/{token}`

- 有效令牌返回 `204`，更新最后心跳、最后检查和最后成功时间，状态置 `UP`，顺延截止时间，并写入一条成功 CheckResult。
- 无效、已撤销、不存在或所属监控项已禁用的令牌统一返回 `404 HEARTBEAT_NOT_FOUND`，不区分原因。
- 不要求后台任务使用管理员登录凭证。
- 不得将完整令牌写入日志。
- 按令牌 SHA-256 摘要限流 120 次/分钟（无效令牌同样计数），超出返回 `429` 并带 `Retry-After`；内存中的跟踪键数量设上限，防止无效令牌无限增长。
- 请求体上限 1KB，超出返回 `413`。
- `pulseops.heartbeat.require-https` 开启时，仅接受被判定为 HTTPS 的请求，否则返回 `400`；判定规则、可信代理与客户端地址统一见 [`auth.md`](auth.md#可信代理与客户端地址)。

## 令牌轮换与撤销

- `POST /api/v1/monitors/{id}/heartbeat-token`：在同一事务内撤销旧凭证并生成新凭证，返回仅此一次可见的新令牌；旧令牌立即失效。禁用状态的监控项仍允许轮换。
- `DELETE /api/v1/monitors/{id}/heartbeat-token`：撤销当前凭证，幂等返回 `204`；禁用状态的监控项仍允许撤销。
- 撤销或轮换不删除历史凭证记录，保留审计信息。

## 超时扫描与恢复

- 截止时间 = `(last_heartbeat_at ?? created_at) + interval_seconds + grace_seconds`。
- 扫描器按 `pulseops.heartbeat.tick-interval`（默认 5 秒，最小 100ms）运行，每批最多 `batch-size`（默认 100，范围 1–500）个；查询启用的 `HEARTBEAT` 监控项中 `next_check_at <= now` 的记录。
- `next_check_at` 是调度与锁定后重新校验的截止时间：创建、有效上报和配置变更时等于上述公式；重新启用时设为启用时间加间隔与宽限。
- 每个监控项在独立事务内锁定 Monitor 后重新校验 `next_check_at`，与有效上报串行化。
- 超时后状态置 `DOWN`，创建唯一未解决 Incident，并写入一条 `HEARTBEAT_TIMEOUT` CheckResult；仅在从"无未解决 Incident"转为创建时写入，重复扫描不重复创建 Incident 或 CheckResult。
- Incident 的 `started_at` 使用截止时间；summary 包含最后心跳时间（或"从未"）、预期间隔与宽限时间，长度不超过 200 字符。
- 已有未解决 Incident 时只顺延 `next_check_at`，不修改状态、最后检查时间，也不重复创建 Incident 或 CheckResult。
- 收到有效上报后立即置 `UP`，关闭未解决 Incident 并生成恢复通知；不使用连续成功阈值。
- 禁用监控项时静默关闭未解决 Incident，不发送恢复通知；重新启用时置 `UNKNOWN` 并获得完整的间隔加宽限窗口。
- 修改间隔或宽限时以 `(last_heartbeat_at ?? created_at)` 为基准重算截止时间；若新截止时间已过，由下一轮扫描处理。
- 心跳规则独立于 HTTP 连续失败阈值；HTTP 探测器拒绝心跳监控项。

## 验收
- 有效令牌可上报。
- 无效或撤销令牌被拒绝。
- 令牌不会明文持久化或进入日志。
- 按时心跳不产生超时告警。
- 超时会产生故障事件。
- 恢复心跳会正确关闭事件。
- 重复扫描不会创建重复事件。
