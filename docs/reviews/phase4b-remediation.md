# Phase 4B 后续修正清单

> 本清单记录本次审阅建议；不表示业务源码已经修改。Phase 4B 可按现有验收证据完成，以下项目建议在公网开放前或 Phase 5 部署过程中处理。

## P2：使登录限流的并发配额原子化

**涉及文件**
- `backend/src/main/java/dev/pulseops/auth/LoginRateLimiter.java`
- `backend/src/main/java/dev/pulseops/auth/api/AuthController.java`
- `backend/src/test/java/dev/pulseops/auth/LoginRateLimiterTest.java`

**任务**
- 明确限流计算的是失败次数还是认证尝试次数。
- 将同一 key 的配额检查/占用改为原子操作，避免并发请求同时通过 `checkAllowed()`。
- 添加多线程并发测试，验证窗口内的实际认证尝试数。
- 评估是否需要同时提供 per-IP 与 per-account 两种限流维度。

**验收**
- 并发请求的限流行为确定且可复现。
- 达到阈值后返回 429 和合理的 `Retry-After`。
- 正常登录清理正确的失败状态，不误伤其他独立 key。

## P3：明确限流 key 容量溢出策略

**涉及文件**
- `LoginRateLimiter.java`
- `LoginRateLimiterTest.java`

**任务**
- 评估 `MAX_TRACKED_KEYS` 达到上限时任意移除 key 的行为。
- 采用可解释的溢出策略，例如拒绝新 key、按来源 IP 先行限流，或引入明确的淘汰算法。
- 增加容量上限与过期窗口测试，并为异常增长提供不含敏感信息的可观测性。

**验收**
- 容量满时行为可预测；有效窗口不会被无意静默驱逐。
- 内存占用仍有上限，且不会记录凭据、Cookie 或 CSRF Token。

## Phase 5 运维验收补充

- 在目标 Linux VPS 上验证 `test -x scripts/backup.sh scripts/restore.sh backend/mvnw`。
- 检查备份目录和文件的属主/权限；确认备份文件不在 Git 中。
- 人为模拟恢复中途失败，确认半成品数据库被清理。
- 配置异地备份/加密/失败告警，并实际执行一次恢复演练。
- 配置 Caddy/TLS、防火墙、可信代理头、日志轮转、外部健康检查与回滚流程。
- 连续运行至少 7 天，记录资源使用、探测延迟、通知延迟和未解决故障。

## 验证边界

本次审阅环境未重新运行 Maven/Testcontainers 或前端构建。请在项目规定的 JDK 21、Node.js 24.15.0 与可用 Docker 环境中运行完整验证，并以干净 checkout 的 CI checks 作为合并证据。
