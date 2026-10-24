# 故障事件、恢复与通知规范

## 核心概念
- `CheckResult`：单次探测结果。
- `Incident`：由连续失败或心跳超时形成的一次持续故障。
- `NotificationDelivery`：针对故障或恢复事件的一次通知发送记录。

三者不能混为一谈。

## HTTP 默认状态规则
- 连续失败达到 3 次后进入故障状态。
- 连续成功达到 2 次后恢复。
- `UNKNOWN` 状态下的首次成功直接判定为 `UP`；恢复阈值只用于 `DOWN` 回到 `UP`。
- 阈值应可配置，并有测试覆盖。
- 发生故障时创建 Incident。
- 故障持续期间只更新当前 Incident，不重复创建。
- 恢复时关闭 Incident 并记录恢复时间。

阈值是 MVP 默认值，不代表永远固定。配置更新时必须定义何时生效。

## Incident 生命周期
- 创建条件：失败计数达到 Failure Threshold，且该 Monitor 当前没有未解决 Incident；不依赖 `UP → DOWN` 的状态迁移，因此上线前已是 `DOWN` 的 Monitor 也能在后续失败达到阈值时创建。
- 禁用 Monitor 时关闭未解决 Incident，但不发送恢复通知；删除 Monitor 时级联删除其 Incident。
- 探测语义配置变更只清零计数，不关闭未解决 Incident。

## Heartbeat 规则
Heartbeat 使用最后一次有效心跳、预期间隔和宽限时间判断超时，不直接复用 HTTP 连续失败计数。

- 超时后置 `DOWN`、创建唯一未解决 Incident，失败类型为 `HEARTBEAT_TIMEOUT`（"心跳超时"），`started_at` 使用截止时间。
- 仅在创建 Incident 时写入一条 `HEARTBEAT_TIMEOUT` CheckResult；重复扫描不重复写入。
- 有效上报立即置 `UP` 并关闭未解决 Incident，生成恢复通知；不使用恢复阈值。
- 禁用监控项静默关闭 Incident，不生成恢复通知；重新启用时置 `UNKNOWN` 并获得完整间隔加宽限窗口。

## 一致性与并发
- 同一监控项最多有一个未解决 Incident，由部分唯一索引和探测写回的行锁保证。
- 使用事务、唯一约束或等效并发控制保证不变量。
- 状态变更和 Incident 更新应保持一致。
- 通知失败不能回滚已确认的故障状态。
- 服务重启后不能无条件重发所有历史通知。

## 通知
MVP 优先支持 Telegram Bot。

通知包含监控项名称、故障时间、故障类型/原因摘要；恢复通知包含恢复时间。

投递机制：
- Incident 创建/关闭与 NotificationDelivery 写入同一事务；发送由独立调度线程完成，使用固定延迟轮询，不在探测事务内发起网络请求。
- 渠道未配置（Bot Token 与 Chat ID 均缺失）时不生成投递行；只配置其中一个时应用启动失败。
- 禁用 Monitor 关闭 Incident 时不生成恢复通知。
- 心跳超时扫描使用独立单线程调度器，与 HTTP 探测、通知发送互不阻塞。
- 投递采用 at-least-once 语义，见 [ADR-0005](../adr/0005-transactional-outbox-notifications.md)。
- 投递状态通过 Incident 详情暴露。
- 网络错误、5xx 与 429 可重试：最多 3 次尝试，第 1、2 次失败后分别退避 1 分钟与 5 分钟；非 429 的 4xx 立即进入终态 `FAILED`。
- 终态 `FAILED` 不再尝试；重启后 `SENT` 不重发、`PENDING` 继续发送。
- Telegram 配置在启动时绑定；修改后需重启生效。重启后所有 `PENDING` 投递按新配置尝试，已 `SENT` / `FAILED` 的历史投递不会重放。
- 重试调度日志为 `debug`，终态失败日志为 `warn`，均不包含 Bot Token 或目标 URL。

必须：
- 记录发送状态。
- 设置超时。
- 使用有限重试。
- 防止重复通知。
- 不阻塞探测线程。
- 避免日志中泄漏 Bot Token 或其他凭证。

## 验收
- 未达到失败阈值时不产生故障 Incident。
- 达到阈值后创建一个 Incident。
- 故障期间不会重复创建 Incident。
- 达到恢复条件后正确关闭事件。
- 通知失败不会破坏状态一致性。
- 重试有限且可观察。
