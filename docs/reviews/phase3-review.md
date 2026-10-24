# PulseOps Phase 3 代码审阅报告

## 结论

Phase 3 已完成，且与 Issue #19 的完成方案对齐：Issue 页显示 `5 / 5 issues completed`，并在活动记录里标记为 `completed`。该 issue 明确要求心跳监控项、令牌存储与轮换、心跳超时、Incident / Notification 的联动，以及只读查询能力。citeturn365806view0turn473228view0

从源码快照看，Phase 3 的实现是成熟的：
- 心跳监控复用 `Monitor` 单表，`HEARTBEAT` 与 `HTTP` 共存；
- 心跳令牌使用高熵随机值，数据库只保存 SHA-256 摘要；
- 有效心跳、超时扫描、恢复关闭 Incident、静默禁用等语义都已实现；
- 通知继续沿用 Phase 2 的 transactional outbox 与独立投递线程；
- CI / 验收文档已经把 phase3 的测试证据固定下来。

我没有发现会阻断 Phase 4A 的高危缺陷。下面是我认为值得保留的设计与一个可顺手修正的小问题。

## 已确认的优点

### 1. 心跳模型和 HTTP 模型的边界清晰
- `type=HEARTBEAT` 的 Monitor 复用主表。
- `last_heartbeat_at` 和 `next_check_at` 共同表达心跳超时调度。
- 心跳有效上报直接置 `UP`，超时直接置 `DOWN`，不复用 HTTP 的连续成功/失败阈值。
- 这些选择和 ADR-0006、heartbeat 规范一致。

### 2. 令牌安全性处理比较扎实
- 令牌只在创建/轮换时明文可见一次。
- 数据库存储的是 SHA-256 十六进制摘要。
- 轮换、撤销和无效 token 的行为都在测试中有覆盖。
- `HeartbeatController` 的 HTTPS 约束、1KB 请求体限制和 404 统一响应，都是合理的硬化措施。

### 3. 超时与恢复语义完整
- 超时创建唯一未解决 Incident。
- 重复扫描不会重复创建 Incident 或重复写 timeout CheckResult。
- 有效上报会关闭 open Incident 并触发恢复通知。
- 禁用监控会静默关闭 Incident，不额外制造恢复通知。
- 重启后 pending 通知可以继续发送，SENT 不会重发。

### 4. 单实例运行约束保持一致
当前设计仍然假设单 JVM / 单启用调度实例，这是和前面阶段一致的，并且文档里也已经明确限制了多实例前提。

## 值得顺手改进的点

### P2：通知重试期间缺少可直接诊断的错误摘要
**位置**
- `backend/src/main/java/dev/pulseops/notification/NotificationDispatcher.java`
- `backend/src/main/java/dev/pulseops/notification/NotificationDeliveryEntity.java`
- `backend/src/main/java/dev/pulseops/notification/api/NotificationDeliveryResponse.java`

**观察**
`lastErrorSummary` 只在终态 `FAILED` 时通过 `markFailed(...)` 写入；而当投递进入重试态 `PENDING` 时，只更新了 `nextAttemptAt`，没有同步保存最近一次失败摘要。这样一来，在 retry 窗口内查看 `/api/v1/incidents/{id}` 时，看到的投递状态虽然是 `PENDING`，但 error summary 可能仍为空，排查“为什么还没发出去”就不够直接。

**影响**
这不影响核心正确性，也不影响通知最终是否能重试成功，但会削弱运维可读性，尤其是 transient failure（429 / 5xx / 网络故障）反复重试时。

**建议**
- 在重试分支里也更新 `lastErrorSummary`，保留最新一次失败摘要；
- 或者新增单独的 `lastFailureSummary` / `lastFailureAt` 字段，把“最后一次失败”和“终态失败”区分开；
- 如果想保持最小改动，至少在 `PENDING` 重试态中显示最新失败摘要，不要等到最终失败才写入。

**验收标准**
1. 第 1 次或第 2 次失败后，`PENDING` 投递也能展示最近一次失败原因；
2. 重试成功后，`SENT` 记录仍清空错误摘要；
3. 终态 `FAILED` 的摘要仍保留且不泄漏 token。

## 进度判断
- Phase 0：完成
- Phase 1：完成
- Phase 1.1：完成
- Phase 2：完成
- **Phase 3：完成**
- Phase 4A：未开始

## 下一步建议
继续推进 Phase 4A 的最小可用 UI。优先顺序建议仍然是：
1. 监控项列表 / 创建 / 编辑 / 删除
2. 当前状态与最近检查时间
3. 历史结果分页
4. 活动 Incident 与通知状态
5. Heartbeat 配置与上报命令展示

