# Phase 2 验收证据

本文件汇总 Phase 2（Incident 与通知）的验收证据。Phase 2 包含以下 tickets：

- #20 阈值驱动的 Monitor Status
- #21 Incident 生命周期与只读 API
- #22 Telegram 通知端到端（outbox + 发送）
- #23 通知重试、退避与重启语义
- #24 验收证据与文档收口（本文件）

## CI 证据

- 工作流：[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)
- Phase 2 功能代码（master push，干净 checkout）：
  [GitHub Actions 39908467517](https://github.com/kevinmxt/PulseOps/actions/runs/39908467517)，提交 `34ed74c`，结论 `success`
- Phase 2 最后一个功能 PR：
  [GitHub Actions 39908220922](https://github.com/kevinmxt/PulseOps/actions/runs/39908220922)，PR [#28](https://github.com/kevinmxt/PulseOps/pull/28)，head `169325b`，结论 `success`
- 环境：干净 checkout，`ubuntu-latest`，JDK 21（Temurin）；后端使用 Testcontainers PostgreSQL 17，前端生产构建通过。

## 本地执行记录

2026-10-23 在开发者本机执行：

```bash
cd backend
./mvnw verify
```

结果：单元测试 159 个、集成测试 142 个，0 失败，`BUILD SUCCESS`。集成测试需要 Docker daemon。该记录仅作对照，阶段证据以上节 CI 运行为准。

## 路线图完成条件对照

| 完成条件 | 证据 |
|---|---|
| 不会为同一持续故障重复创建 Incident | `IncidentLifecycleIT#doesNotCreateDuplicateIncidentsWhileFailureContinues`、`#concurrentProbesCreateOnlyOneIncident`、`#databaseRejectsTwoOpenIncidentsForTheSameMonitor` |
| 通知故障不阻塞探测 | 通知发送位于独立事务与独立调度任务（`NotificationFlowIT#runsNotificationsOnADedicatedSchedulerPool`）；阻塞中的发送不阻止 Probe 执行（`#failingNotificationDoesNotBlockProbeExecution`）；发送失败不改变 Incident（`#clientErrorMarksDeliveryFailedWithoutChangingIncident`）；批次处理可验证（`#processesAtMostTheBatchSizePerDispatch`） |
| 恢复事件正确关闭 | `IncidentLifecycleIT#resolvesIncidentOnlyWhenSuccessesReachRecoveryThreshold`、`#createsNewIncidentAfterThePreviousOneResolves` |

## Ticket 验收对照

### #20 阈值驱动的 Monitor Status

| 验收标准 | 证据 |
|---|---|
| 阈值字段、默认值与 1–10 校验 | `MonitorConfigValidatorTest#appliesDefaultsForOmittedFields`、`#rejectsOutOfRangeThresholds`、`#acceptsThresholdsAtTheBoundaries`；`V6__add_monitor_level_thresholds.sql` |
| 成功/失败计数互斥清零 | `ThresholdStatusIT#successResetsConsecutiveFailures`、`#failureResetsConsecutiveSuccesses` |
| UNKNOWN 首次成功变 UP、未达阈值不切换 | `ThresholdStatusIT#staysUnknownWhileFailuresAreBelowTheDefaultThreshold`、`#upMonitorStaysUpBelowFailureThresholdAndGoesDownAtThreshold` |
| 达到失败阈值变 DOWN | `ThresholdStatusIT#becomesDownWhenFailuresReachTheDefaultThreshold` |
| 达到恢复阈值变 UP | `ThresholdStatusIT#staysDownUntilSuccessesReachTheDefaultRecoveryThreshold` |
| 配置语义变更清零计数且保留状态 | `ThresholdStatusIT#probeSemanticChangeResetsCountersAndKeepsStatus` |
| 修改阈值不重探、不追溯 | `ThresholdStatusIT#thresholdChangeDoesNotResetCountersOrScheduleImmediateProbe` |
| 禁用保留状态、启用置 UNKNOWN | `ThresholdStatusIT#disablingKeepsStatusAndEnablingStartsFromUnknown` |
| 自定义阈值生效 | `ThresholdStatusIT#appliesConfiguredThresholds` |
| 现有探测、竞态与重启测试保持通过 | 完整 `./mvnw verify` 仍包含 `ProbeServiceIT`、`MonitorConfigRaceIT`、`ProbeSchedulerIT`、`Phase1RestartIT` 等既有测试，CI 全部通过 |

### #21 Incident 生命周期与只读 API

| 验收标准 | 证据 |
|---|---|
| 达到阈值创建唯一 OPEN Incident，记录时间、失败类型与安全摘要 | `IncidentLifecycleIT#createsOneOpenIncidentWhenFailuresReachTheThreshold` |
| 持续失败不重复创建；并发与唯一索引兜底 | `IncidentLifecycleIT#doesNotCreateDuplicateIncidentsWhileFailureContinues`、`#concurrentProbesCreateOnlyOneIncident`、`#databaseRejectsTwoOpenIncidentsForTheSameMonitor` |
| 达到恢复阈值关闭并写 `resolved_at` | `IncidentLifecycleIT#resolvesIncidentOnlyWhenSuccessesReachRecoveryThreshold` |
| 禁用静默关闭；删除级联 | `IncidentLifecycleIT#disablingMonitorResolvesOpenIncidentSilently`、`#deletingMonitorRemovesItsIncidents`；`NotificationFlowIT#disablingMonitorClosesIncidentWithoutNotification`（通知上下文） |
| 预置 DOWN 的 Monitor 按阈值创建 | `IncidentLifecycleIT#createsIncidentForMonitorAlreadyDownWithoutOpenIncident`、`#doesNotCreateIncidentForAlreadyDownMonitorBelowFailureThreshold`、`#successfulProbeBelowRecoveryDoesNotCreateIncidentForAlreadyDownMonitor` |
| 列表过滤、分页、倒序 | `IncidentApiIT#listsIncidentsNewestFirstWithPaginationAndMonitorNames`、`#filtersByStatusAndMonitor` |
| 详情含 monitorName；404 错误码 | `IncidentApiIT#returnsIncidentDetailWithMonitorName`、`#returnsNotFoundForUnknownIncident` |
| 指标与 info 日志 | `IncidentLifecycleIT#recordsIncidentMetricsAndInfoLogs` |
| 不新增写接口 | `IncidentController` 仅提供两个 `GET` 映射 |

### #22 Telegram 通知端到端

| 验收标准 | 证据 |
|---|---|
| Incident 打开/恢复同事务生成 PENDING 投递 | `NotificationFlowIT#createsPendingDeliveryInTheSameProbeAsTheIncident`、`#dispatchesRecoveryNotification` |
| 独立调度任务（共享 2 线程调度池）、5s/50 条/串行 | `NotificationFlowIT#runsNotificationsOnADedicatedSchedulerPool`、`#processesAtMostTheBatchSizePerDispatch` |
| 2xx 标记 SENT 并记录尝试信息 | `NotificationFlowIT#dispatchesOpenedNotificationAndMarksDeliverySent` |
| 中文消息内容、4096 截断 | `NotificationFlowIT#dispatchesOpenedNotificationAndMarksDeliverySent`、`#dispatchesRecoveryNotification`；`NotificationMessageFormatterTest` |
| 发送失败不改变 Incident、不阻塞探测 | `NotificationFlowIT#clientErrorMarksDeliveryFailedWithoutChangingIncident` |
| 未配置不建投递行；半配置启动失败 | `IncidentLifecycleIT#createsOneOpenIncidentWhenFailuresReachTheThreshold`（无投递行）；`TelegramPropertiesTest#rejectsHalfConfiguration` |
| 详情内嵌最近 5 条投递 | `IncidentApiIT#returnsDeliveryHistoryInIncidentDetail` |
| Token 脱敏 | `TelegramNotificationSenderTest#removesBotTokenFromErrorMessages`；`NotificationFlowIT#retriesServerErrorsWithBackoffBeforeFailing` |
| 指标可观察 | `NotificationFlowIT#dispatchesOpenedNotificationAndMarksDeliverySent`、`#clientErrorMarksDeliveryFailedWithoutChangingIncident` |
| ADR | [ADR-0005](../adr/0005-transactional-outbox-notifications.md) |
| 本地假 Telegram 集成测试 | `NotificationFlowIT`（内嵌 `HttpServer`） |

### #23 通知重试、退避与重启

| 验收标准 | 证据 |
|---|---|
| 网络错误、5xx、429 重试；3 次上限；1/5 分钟退避 | `NotificationFlowIT#retriesServerErrorsWithBackoffBeforeFailing`、`#retriesRateLimiting`、`#deliversAfterTransientFailure`；`TelegramNotificationSenderTest#marksNetworkErrorsAsRetryable` |
| 非 429 的 4xx 立即 FAILED | `NotificationFlowIT#clientErrorMarksDeliveryFailedWithoutChangingIncident` |
| 终态不再尝试；摘要不含 Token | `NotificationFlowIT#retriesServerErrorsWithBackoffBeforeFailing`（终态后再推进时钟不增加尝试）、`#clientErrorMarksDeliveryFailedWithoutChangingIncident`；500 字符截断无直接测试，见已知限制 |
| 配置变更语义（与 issue 原文的偏差） | issue 原文要求“运行期修改只影响之后创建的投递行”；实现为启动时绑定、无热更新入口，重启后 `PENDING` 使用新配置，历史投递不重放。语义见 `docs/specs/incidents.md` 与已知限制 |
| 重启后 SENT/FAILED 不重发、PENDING 续发 | `Phase2RestartIT#resendsOnlyPendingDeliveriesAfterRestart`（双 Spring 上下文共享数据库，含重试状态与 FAILED 行） |
| Token 不出现在日志与摘要 | `NotificationFlowIT#retriesServerErrorsWithBackoffBeforeFailing`、`TelegramNotificationSenderTest` |
| 重试日志 debug、终态 warn | `NotificationFlowIT#retriesServerErrorsWithBackoffBeforeFailing` 断言 `DEBUG` / `WARN` 日志行 |

## 端到端覆盖对照

[`acceptance.md`](acceptance.md) 要求的最小端到端场景：

| 场景 | 状态 | 证据 |
|---|---|---|
| 创建 HTTP Monitor | 已覆盖 | `MonitorApiIT`、`ThresholdStatusIT` |
| 成功探测 | 已覆盖 | `ProbeServiceIT`、`Phase1AcceptanceIT` |
| 连续失败并创建 Incident | 已覆盖 | `IncidentLifecycleIT` |
| 触发通知 | 已覆盖 | `NotificationFlowIT` |
| 恢复并关闭 Incident | 已覆盖 | `IncidentLifecycleIT` |
| 发送恢复通知 | 已覆盖 | `NotificationFlowIT#dispatchesRecoveryNotification` |
| Heartbeat 创建与超时恢复 | Phase 3 | 不在本阶段范围 |

## 安全与敏感信息

- Bot Token 不出现在日志、投递错误摘要或测试输出中；见上文脱敏证据。
- Incident 摘要不拼接目标 URL，且不超过 200 字符。
- 探测目标 SSRF 防护测试继续在 CI 中执行（`ProbeSsrfIT`、`SsrfDnsResolverTest`）。
- 管理 API 默认仅监听回环地址；认证与启动期强制校验按 [ADR-0003](../adr/0003-defer-admin-auth.md) 延后到 Phase 4B。

## 已知限制与未完成事项

- 管理 API 认证属于 Phase 4B；本阶段不宣称可安全暴露到公网。
- 单实例调度假设不变；多实例部署仍需数据库租约或原子认领。
- Telegram 配置为启动时绑定，不支持热更新；重启后未发送的 `PENDING` 投递使用新配置。
- 通知发送与探测共享大小为 2 的调度池，依赖任务数量不超过池大小。
- Incident 指标与日志在探测事务提交前触发；极端回滚下可能留下未落库事件的观测数据。
- `last_error_summary` 的 500 字符截断没有直接测试（实际错误消息远短于上限）。
- `notification.pending` gauge 每次抓取执行一次 `count` 查询。
- 未接入真实 Telegram 网络；集成测试使用本地假服务。
- 未执行 100 个 Monitor 的性能验证；本阶段不宣称性能目标。
- UI 属于 Phase 4A；当前 Incident 与投递状态仅通过 API 查看。

## 环境限制

- 集成测试需要 Docker daemon（Testcontainers PostgreSQL 17）。
- 测试中禁用自动调度，通过手动 tick / dispatch 与可注入 Clock 保证确定性。
