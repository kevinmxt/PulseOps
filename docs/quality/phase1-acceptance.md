# Phase 1 验收证据

本文件区分两类证据：

- **CI 证据（当前）**：在干净 checkout 上由 GitHub Actions 执行，可作为阶段验收证据。
- **历史本地记录**：Phase 1 收尾时在开发者本机执行，仅保留作功能验证对照，不是 CI 证据。

## CI 证据

- 工作流：[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)
- PR 运行：[GitHub Actions 39782251352](https://github.com/kevinmxt/PulseOps/actions/runs/39782251352)，`pull_request`，结论 `success`
- 合并后运行：[GitHub Actions 39782515275](https://github.com/kevinmxt/PulseOps/actions/runs/39782515275)，`push` 到 master，结论 `success`
- 提交：PR 运行的 head 为 `3d44277bd39283ce5764223ed12215d0b0154be2`（PR [#17](https://github.com/kevinmxt/PulseOps/pull/17)）；rebase 合并后为 `ad2dfa3c4ba146947583012f0c484e276f23bb7c`，由上述 master push 运行覆盖
- 环境：干净 checkout，`ubuntu-latest`，JDK 21（Temurin），Node.js 24.15.0
- 后端：[`./mvnw --batch-mode --no-transfer-progress verify`](https://github.com/kevinmxt/PulseOps/actions/runs/39782251352/job/119836201321)，单元测试 116 个、集成测试 101 个，0 失败，`BUILD SUCCESS`；集成测试使用 Testcontainers PostgreSQL 17
- 前端：[`npm ci && npm run build`](https://github.com/kevinmxt/PulseOps/actions/runs/39782251352/job/119836201068)，生产构建通过

## 历史本地记录

以下为 Phase 1 收尾时（2026-10-22，Phase 1.1 修复与新增测试并入前）在本机执行的结果，**不是 CI 证据**。此后测试数量已变化，当前可追溯证据以上节 CI 运行为准。

```bash
cd backend
./mvnw verify
```

结果：单元测试 52 个、集成测试 80 个，全部通过，`BUILD SUCCESS`。（集成测试需要 Docker daemon 提供 Testcontainers PostgreSQL。）

## 完成条件对照

下表中的用例在 CI 中同样执行；CI 证据见上节。

| 路线图完成条件 | 证据 |
|---|---|
| 正常场景可重复测试 | `Phase1AcceptanceIT#probeSucceedsEndToEndAndExposesStatusAndHistory`（API 创建 → 调度 Probe → 状态与历史查询）；`ProbeServiceIT#recordsSuccessAndUpdatesMonitorStatus` |
| 失败场景可重复测试 | `Phase1AcceptanceIT#probeFailureEndToEndIsClassifiedWithoutLeakingResponseBody`；`ProbeServiceIT#recordsUnexpectedStatusAndDoesNotLeakResponseBody`、`#classifiesConnectionRefused` |
| 超时场景可重复测试 | `Phase1AcceptanceIT#probeTimeoutEndToEndIsClassifiedAsResponseTimeout`；`ProbeServiceIT#classifiesResponseTimeout`；`HttpProbeExecutorTest` 覆盖连接超时映射 |
| 禁用 Monitor 不再执行 | `Phase1AcceptanceIT#disabledMonitorProducesNoCheckResultsAcrossTicksAndResumesWhenReenabled`；`ProbeSchedulerIT#skipsDisabledMonitorAndResumesImmediatelyWhenReenabled`、`#allowsInFlightProbeToFinishAfterDisableButStopsFutureProbes` |
| 重启后配置与历史仍存在，到期 Monitor 恢复探测 | `Phase1RestartIT#keepsConfigurationAndHistoryAndResumesOverdueProbeAfterRestart`：关闭并重建 Spring 上下文（重新执行 Flyway、连接池与调度器），同一 PostgreSQL 中的配置和历史保留，重启后的定时 ticker 自动探测到期 Monitor |

## 安全要求对照

| 要求 | 证据 |
|---|---|
| 创建期拒绝 localhost、私有、链路本地、云元数据地址 | `MonitorApiIT#rejectsRestrictedTargetAddresses` |
| 执行期重新校验并拒绝受限地址 | `ProbeSsrfIT#blocksRestrictedTargetAtExecutionTime`（断言 `UnknownHostException`，证明在解析层拒绝）；`ProbeSsrfIT#blocksReachableLoopbackEndpointWithoutConnecting`（可达的 loopback 端点零命中）；`SsrfDnsResolverTest` |
| 不跟随重定向 | `ProbeServiceIT#doesNotFollowRedirects` |
| 日志与 CheckResult/错误响应不泄露 Target URL、响应体 | `Phase1AcceptanceIT#logsAndCheckResultResponsesDoNotLeakTargetOrResponseBody` |
| 错误响应不回显完整 Target URL | `MonitorApiIT#blockedTargetErrorDoesNotEchoRequestedUrl` |
| 探测不发送请求体、Cookie，不读取响应体 | `ProbeServiceIT#sendsNoBodyAndNoCookies`、`#doesNotReadResponseBody` |

## Phase 1.1 完成条件对照

| 路线图完成条件 | 证据 |
|---|---|
| 配置变更竞态测试通过 | `MonitorConfigRaceIT`（CI 中 6 个测试通过） |
| 执行期 SSRF 测试通过 | `ProbeSsrfIT`（CI 中 20 个测试通过） |
| CI 在干净 checkout 中通过 | 本文件“CI 证据”中的 PR 运行与 master push 运行 |

## 已知限制与说明

- 重启验证重建 Spring 上下文，但未重启 JVM 进程；配置、历史、迁移和调度恢复路径已被覆盖。
- Monitor 列表与详情按 issue #1 的 API 契约返回配置中的 `targetUrl`，因此“API 响应不包含 Target URL”仅对日志、错误响应和 CheckResult 响应生效。
- 探测本身不发送请求体、Cookie 或自定义请求头，因此不存在请求头泄露路径；`ProbeServiceIT#sendsNoBodyAndNoCookies` 覆盖该行为。
- 管理 API 认证按 [ADR-0003](../adr/0003-defer-admin-auth.md) 延后到 Phase 4B；Phase 1 默认仅监听 `127.0.0.1`。公网可达前的认证硬性门槛见 [`deployment.md`](deployment.md)。
