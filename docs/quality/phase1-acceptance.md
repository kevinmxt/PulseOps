# Phase 1 验收证据

验收日期：2026-10-22

执行命令：

```bash
cd backend
./mvnw verify
```

结果：单元测试 52 个、集成测试 80 个，全部通过，`BUILD SUCCESS`。（集成测试需要 Docker daemon 提供 Testcontainers PostgreSQL。）

## 完成条件对照

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

## 已知限制与说明

- 重启验证重建 Spring 上下文，但未重启 JVM 进程；配置、历史、迁移和调度恢复路径已被覆盖。
- Monitor 列表与详情按 issue #1 的 API 契约返回配置中的 `targetUrl`，因此“API 响应不包含 Target URL”仅对日志、错误响应和 CheckResult 响应生效。
- 探测本身不发送请求体、Cookie 或自定义请求头，因此不存在请求头泄露路径；`ProbeServiceIT#sendsNoBodyAndNoCookies` 覆盖该行为。
- 管理 API 认证按 ADR-0003 延后到 Phase 4；Phase 1 默认仅监听 `127.0.0.1`。
