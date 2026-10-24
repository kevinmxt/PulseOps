# ADR-0006: 心跳监控复用 Monitor 单表与截止时间语义

- 状态：Accepted
- 日期：2026-10-24

## 背景

Phase 3 需要支持后台任务主动上报的心跳监控，并复用现有的 Monitor 生命周期、Incident 与通知机制。现有 `monitor` 表只服务 HTTP 探测，`next_check_at` 表示下一次探测时间。需要决定心跳是否作为独立聚合，以及如何表示"多久没上报算超时"。

## 决策

- 心跳是 `Monitor` 的一种类型：`type=HEARTBEAT`，与 HTTP 共用同一张表和同一套管理 API。HTTP 专用列（目标 URL、方法、超时、期望状态码）对心跳类型允许为空，并由按类型的 CHECK 约束保证 HTTP 类型仍有完整配置。
- 新增 `last_heartbeat_at` 记录最后一次有效上报。心跳截止时间按 `(last_heartbeat_at ?? created_at) + interval_seconds + grace_seconds` 计算，并**复用 `next_check_at`** 作为调度器处理该 Monitor 的时刻；HTTP 探测查询和心跳超时扫描都按 `type` 过滤。收到有效上报后把 `next_check_at` 顺延为接收时间加间隔与宽限。
- 心跳不参与 HTTP 的连续失败/成功计数。超时直接置 `DOWN` 并创建 Incident；收到有效上报立即置 `UP` 并关闭未解决 Incident，不设恢复阈值。
- 有效上报写一条成功的 CheckResult；超时只在"从无未解决 Incident 到创建 Incident"时写一条 `HEARTBEAT_TIMEOUT` 的 CheckResult，重复扫描不重复写。
- 每个 ticker 使用独立的单线程调度器，不再依赖 `spring.task.scheduling.pool.size`；避免新增 ticker 时忘记调整共享池，并隔离通知 ticker 的网络 I/O。

## 备选方案

- 独立的 `heartbeat_monitor` 表与 `/heartbeat-monitors` 资源：生命周期、Incident、通知和 Phase 4A 列表都要维护两套，收益不足。
- 新增独立的 `heartbeat_deadline_at` 列：语义更直观，但与 `next_check_at` 重复，且需要两套到期查询与索引。
- 用 `last_success_at` 代替 `last_heartbeat_at`：复用现有列，但 `last_success_at` 对 HTTP 表示探测成功，对心跳表示上报，语义混叠，且未来无法区分。
- 心跳恢复也使用连续成功阈值：心跳是明确的外部信号，一次有效上报即可证明任务恢复；额外阈值只会延迟恢复通知。

## 后果

- 单表意味着 HTTP 专用列必须可空，`MonitorResponse` 中这些字段对心跳类型返回 `null`；`type` 创建后不可变。
- `next_check_at` 成为"该 Monitor 下一次需要被调度器处理的时刻"，两种类型含义不同，需在数据模型和调度文档中明确。
- CheckResult 与 Monitor 共享历史清理策略（Phase 4B）；心跳上报频率直接决定历史增长速度。
- 单实例调度约束不变，多实例前仍需数据库租约或原子认领机制。
