# Phase 2 完成后的路线建议

## 不需要修正的部分
- Phase 3 继续做 Heartbeat 是合理的。
- Phase 4 仍应拆成 UI 与认证/数据维护两个子段。
- 公网部署前的认证仍应是硬门槛。
- 暂时不需要引入 Redis、Kafka、Kubernetes 或多实例调度。

## 建议强化的部分
1. 把“单实例调度”写成明确的运行约束，避免未来误部署。
2. 把“通知 at-least-once”写成长期契约，不要隐式改成 best-effort。
3. 在 Phase 3 之前，补一条统一的观测规范：incident opened/resolved、notification pending/sent/failed、probe error 分类。
4. 如果后面要做公开部署，认证应提前到“任何公网可访问管理接口”之前，而不是等到最后一刻。
5. Phase 4 的历史数据清理与备份恢复建议尽早设计，因为 Phase 2/3 已经开始产生更真实的运维数据。

## 推荐继续执行的顺序
1. Heartbeat
2. 最小 UI
3. 认证与授权
4. 数据保留与清理
5. VPS 部署与恢复演练
6. 小规模试用

