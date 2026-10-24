# Phase 3 后续可选修正任务

> 这些任务不阻断进入 Phase 4A，但适合在继续迭代时顺手处理。

## P2
- 在通知重试态也保留最近一次失败摘要，提升 Incident 详情页的排障效率。
- 如果后续要增强运维观测，可以补充 notification retry / failed / sent 的聚合指标视图。
- 在文档里明确 pending 通知可能在恢复后仍被发送，这是 at-least-once 的自然结果。

## P3
- 如果未来引入多实例，再为 notification dispatcher 设计任务认领或租约机制。
- 如需更强的通知顺序保证，再考虑引入投递队列，但这不是当前阶段必须项。
