# PulseOps Phase 2 代码审阅报告

## 结论

Phase 2 已完成，且和 issue 计划基本对齐：Issue #19 显示 `5 / 5 issues completed`，子 issue #20–#24 已全部完成；方案明确要求阈值驱动状态机、Incident 生命周期、Telegram 通知、重试/退避与重启语义。citeturn924625view0turn670154view0

从源码快照看，Phase 2 的实现方向是正确的：
- Incident 与 Notification Delivery 已拆分为独立实体；
- 通过 outbox + 独立调度器实现通知发送；
- 使用数据库唯一约束与探测写回时的行锁来避免同一 Monitor 的重复开放 Incident；
- Telegram 配置缺失/半配置在构造期就会失败；
- 通知消息进行了长度控制和 token 脱敏。

我没有发现需要立刻阻断 Phase 3 的高危问题，但有两项值得保留为“后续修正/强化”的重点。

## 代码审阅要点

### 1. 事件流设计合理
- Probe 负责结果写回；
- IncidentService 负责状态机与事件打开/关闭；
- NotificationOutbox 负责写入待发送投递；
- NotificationDispatcher 独立轮询发送。

这个职责划分符合 Issue #19 中“发送失败不回滚 Incident、发送不阻塞探测、只读查询 Incident+Delivery”的目标。citeturn924625view0turn670154view0

### 2. 主要优点
- **恢复语义清晰**：恢复只在成功达到阈值后关闭 Incident。
- **重复告警抑制**：持续故障期间不会重复创建 open Incident。
- **通知重试可观测**：PENDING / SENT / FAILED 状态完整记录。
- **安全意识到位**：Telegram bot token 不进入正常日志与错误摘要。
- **数据库层兜底**：唯一约束 + 行锁对并发 probe 形成第二道保护。

### 3. 需要继续关注的点
1. **IncidentService 的事务边界是“隐式依赖调用方”的。**  
   当前 `IncidentService` 本身没有把核心方法显式标为事务边界，而是依赖 `ProbeService` 的 `TransactionTemplate`、`MonitorService.update()` 的事务上下文来保证一致性。当前调用链是安全的，但未来如果有人从别的线程/异步入口直接调用 `IncidentService`，很容易把这个隐式前提打破。建议后续把事务边界明确化，或者把这种“必须在事务内调用”的约束写进注释/文档。

2. **通知是 at-least-once 语义。**  
   这是合理的产品决策，不是 bug；但要把它视作长期契约，避免后续为了“看起来更干净”把它改成可能丢消息的实现。

3. **当前仍然是单实例调度假设。**  
   Phase 2 的实现与 issue 预期一致，但如果将来想做多实例，必须重新设计 dispatch 认领机制与 Probe 认领机制。

## 进度判断
- Phase 0：完成
- Phase 1：完成
- Phase 1.1：完成
- **Phase 2：完成**
- Phase 3：未开始

## 建议的下一步
继续推进 Phase 3 Heartbeat。实现顺序建议保持：
1. 心跳监控项与令牌模型
2. 心跳上报接口
3. 超时扫描与 Incident 复用
4. 恢复与通知
5. 验收文档与 CI 证据

