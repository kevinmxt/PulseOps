# Phase 3 验收证据

本文件汇总 Phase 3（Heartbeat 心跳监控）的验收证据。Phase 3 包含以下 tickets：

- #33 创建心跳监控项并获得令牌
- #34 心跳上报与令牌轮换/撤销
- #35 心跳超时、Incident 与通知
- #36 重启验收、文档与 Phase 3 证据（本文件）

## CI 证据

- 工作流：[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)
- #33 创建心跳监控项：[GitHub Actions 40059897877](https://github.com/kevinmxt/PulseOps/actions/runs/40059897877)，PR [#38](https://github.com/kevinmxt/PulseOps/pull/38)，head `156fc5d`，结论 `success`
- #34 心跳上报与令牌管理：[GitHub Actions 40061655278](https://github.com/kevinmxt/PulseOps/actions/runs/40061655278)，PR [#39](https://github.com/kevinmxt/PulseOps/pull/39)，head `3e933bc`，结论 `success`
- #35 超时、Incident 与通知：[GitHub Actions 40063379820](https://github.com/kevinmxt/PulseOps/actions/runs/40063379820)，PR [#40](https://github.com/kevinmxt/PulseOps/pull/40)，head `1240f54`，结论 `success`
- #36 文档收口 PR：[GitHub Actions 40064666873](https://github.com/kevinmxt/PulseOps/actions/runs/40064666873)，PR [#41](https://github.com/kevinmxt/PulseOps/pull/41)，head `e79c08b`，结论 `success`（此后仅更新本行证据链接）
- 环境：干净 checkout，`ubuntu-latest`，JDK 21（Temurin）；后端使用 Testcontainers PostgreSQL 17，前端生产构建通过。

## 本地执行记录

2026-10-24 在开发者本机执行：

```bash
cd backend
./mvnw verify
```

结果：单元测试 145 个、集成测试 178 个，0 失败，`BUILD SUCCESS`。集成测试需要 Docker daemon。该记录仅作对照，阶段证据以上节 CI 运行为准。

## 路线图完成条件对照

| 完成条件 | 证据 |
|---|---|
| 有效令牌可上报 | `HeartbeatReportApiIT#reportsHeartbeatAndUpdatesMonitorState` |
| 无效/撤销令牌被拒绝 | `HeartbeatReportApiIT#rejectsInvalidRevokedDisabledAndUnknownTokensWithSameNotFound`、`#rotatesTokenAndInvalidatesOldOneImmediately` |
| 超时可触发故障 | `HeartbeatTimeoutIT#timeoutCreatesDownIncidentCheckResultAndNotification` |
| 重复扫描不会创建重复事件 | `HeartbeatTimeoutIT#repeatedScansDoNotDuplicateIncidentOrCheckResult`、`#scanAndReportConcurrentlyNeverDuplicateIncidentOrTimeoutResult`、`Phase3RestartIT#restartDetectsTimeoutsWithoutDuplicatesAndSendsPendingNotifications` |
| 重启后仍能发现超时、不重复 Incident、待发通知继续 | `Phase3RestartIT#restartDetectsTimeoutsWithoutDuplicatesAndSendsPendingNotifications` |

## Ticket 验收对照

### #33 创建心跳监控项并获得令牌

| 验收标准 | 证据 |
|---|---|
| 创建返回 201 与仅一次可见令牌；列表/详情永不含令牌 | `HeartbeatMonitorApiIT#createsHeartbeatMonitorAndReturnsTokenOnce`、`#neverReturnsTokenOnListOrDetail` |
| 数据库只保存摘要；删除级联删除凭证 | `HeartbeatMonitorApiIT#createsHeartbeatMonitorAndReturnsTokenOnce`（摘要断言）、`#cascadesCredentialDeletionWithMonitor`；`HeartbeatTokenGeneratorTest#digestsTokenAsLowercaseSha256Hex` |
| HTTP 专用字段为空、阈值忽略、`type` 不可变 | `HeartbeatMonitorApiIT#ignoresHttpOnlyFieldsAndThresholdsOnCreate`、`#rejectsTypeChanges` |
| 默认值与范围、初始状态 UNKNOWN、HTTP 回归 | `HeartbeatMonitorApiIT#validatesHeartbeatIntervalAndGraceBoundaries`、`#createsHeartbeatMonitorAndReturnsTokenOnce`；`MonitorApiIT` |

### #34 心跳上报与令牌轮换/撤销

| 验收标准 | 证据 |
|---|---|
| 有效上报 204、更新时间/状态并写成功 CheckResult | `HeartbeatReportApiIT#reportsHeartbeatAndUpdatesMonitorState` |
| 无效/撤销/禁用统一 404 | `HeartbeatReportApiIT#rejectsInvalidRevokedDisabledAndUnknownTokensWithSameNotFound` |
| 轮换后旧令牌失效；撤销幂等；禁用状态可用 | `HeartbeatReportApiIT#rotatesTokenAndInvalidatesOldOneImmediately`、`#rotatesTokenForDisabledMonitorAndRejectsHttpMonitor`、`#revokesTokenIdempotentlyAndRejectsHttpMonitor`、`#revokesTokenForDisabledMonitor` |
| 限流 120 次/分钟（含无效令牌）、429 + Retry-After | `HeartbeatReportApiIT#limitsReportsPerTokenIncludingInvalidTokens`；`HeartbeatRateLimiterTest` |
| 超过 1KB 返回 413；HTTPS 开关 | `HeartbeatReportApiIT#rejectsRequestBodiesLargerThanOneKilobyte`、`HeartbeatControllerTest`；`HeartbeatHttpsRequiredIT` |
| 日志不含完整令牌；HTTP 不受影响 | `HeartbeatReportApiIT#neverLogsFullTokens`、`#doesNotAffectHttpMonitors` |

### #35 心跳超时、Incident 与通知

| 验收标准 | 证据 |
|---|---|
| 超时 DOWN、唯一 OPEN Incident、一条超时 CheckResult、一条待发通知 | `HeartbeatTimeoutIT#timeoutCreatesDownIncidentCheckResultAndNotification` |
| 重复扫描/上报与扫描并发不重复 | `HeartbeatTimeoutIT#repeatedScansDoNotDuplicateIncidentOrCheckResult`、`#scanAndReportConcurrentlyNeverDuplicateIncidentOrTimeoutResult` |
| 有效上报恢复并生成恢复通知；禁用静默关闭；重启用完整窗口 | `HeartbeatTimeoutIT#validReportResolvesIncidentAndEnqueuesRecovery`、`#disablingSilentlyClosesIncidentWithoutRecoveryNotification`、`#reenablingResetsToUnknownWithFullWindow`、`#reenabledMonitorTimesOutOnlyAfterTheFullWindow` |
| 配置变更重算截止时间；已有 Incident 时只顺延 | `HeartbeatTimeoutIT#intervalChangeRecomputesDeadlineAndScanUsesIt`、`#overdueChangeWithOpenIncidentOnlyReschedules` |
| `HEARTBEAT_TIMEOUT` 约束与通知文案 | `V11__add_heartbeat_timeout_failure_type.sql`；`NotificationMessageFormatterTest#formatsHeartbeatTimeoutLabel` |
| HTTP/心跳调度互不拾取；通知失败不影响状态 | `HeartbeatTimeoutIT#heartbeatAndHttpSchedulesDoNotPickEachOther`、`#notificationFailureDoesNotChangeHeartbeatState`；`ProbeService` 防御性拒绝 |

### #36 重启验收、文档与阶段证据

| 验收标准 | 证据 |
|---|---|
| 重启后发现超时、不重复 Incident、待发通知继续 | `Phase3RestartIT#restartDetectsTimeoutsWithoutDuplicatesAndSendsPendingNotifications` |
| 规范文档与实现一致 | `docs/specs/heartbeat.md`、`api.md`、`incidents.md`、`docs/architecture/{data-model,security,overview}.md` |
| 路线图标记完成与验收文档 | `docs/product/roadmap.md`、本文件 |
| 干净 checkout CI 通过 | 上节 CI 证据 |

## CheckResult 容量估算

心跳每次有效上报写一条 CheckResult，超时只在创建 Incident 时写一条。按约 200 B/行（含索引）估算：

| 上报频率 | 行/天/监控项 | 估算/年/监控项 |
|---|---|---|
| 每分钟 | 1440 | 约 105 MB |
| 每 10 秒 | 8640 | 约 0.63 GB |
| 每 5 分钟 | 288 | 约 21 MB |
| 每小时 | 24 | 约 1.8 MB |
| 每日 | 1 | 约 73 KB |

实际磁盘占用还需考虑 WAL、autovacuum 与备份，约为表数据的 2–3 倍。Phase 3 不实现历史清理；Phase 4B 的保留策略与分批清理是公网发布前的硬门槛。

## 安全与敏感信息

- 心跳令牌为 32 字节 CSPRNG，数据库只保存 SHA-256 摘要；明文只在创建与轮换响应中出现一次。
- 应用日志与错误响应不包含完整令牌；上报接口不记录完整 URI。
- 上报接口按令牌摘要限流 120 次/分钟（含无效令牌），请求体上限 1KB；`require-https` 开启时仅接受原生 TLS 或回环来源的 `X-Forwarded-Proto: https`。
- 管理 API 仍默认只监听回环地址；认证按 [ADR-0003](../adr/0003-defer-admin-auth.md) 延后到 Phase 4B。
- 探测目标继续执行 SSRF 防护（`ProbeSsrfIT`、`SsrfDnsResolverTest`）。

## 残余风险与已知限制

- 调度与限流均为单实例语义；多实例部署前需要数据库租约或原子认领机制。
- 限流为进程内固定窗口，不按 IP 限制；每 IP 粗粒度限流留给 Phase 5 反向代理。
- 令牌位于 URL 路径；Phase 5 必须在 Caddy 访问日志中脱敏或排除 `/api/v1/heartbeats/`。
- 历史保留与清理属于 Phase 4B；高频心跳会产生显著存储增长（见容量表）。
- 管理 API 认证与数据维护属于 Phase 4B；本阶段不宣称可安全暴露到公网。
- UI 属于 Phase 4A；心跳配置与上报命令目前仅通过 API 使用。
- 未执行 100 个 Monitor 的性能验证；不宣称性能目标。
- Telegram 配置为启动时绑定；重启后未发送的 `PENDING` 投递按新配置继续。
- 心跳扫描精度受 `tick-interval`（默认 5 秒）限制；超时判定存在同等量级的延迟。
- 通知未接入真实 Telegram 网络；集成测试使用本地假服务。

## 环境限制

- 集成测试需要 Docker daemon（Testcontainers PostgreSQL 17）。
- 测试中禁用自动调度，通过手动 tick / dispatch 与可注入 Clock 保证确定性；重启验收使用真实时钟并保留时间余量。
