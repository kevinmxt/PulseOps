# PulseOps 代码审查资料

本目录包含对上传源码快照进行的静态代码审查、路线图评估和修复计划。

## 阅读顺序

1. [`phase1-code-review.md`](phase1-code-review.md)：审查范围、结论、按风险分级的问题与建议。
2. [`roadmap-review.md`](roadmap-review.md)：对原 Phase 1–6 路线图的调整建议。
3. [`remediation-plan.md`](remediation-plan.md)：建议按顺序落地的修复任务和验收条件。

## 审查边界

- 审查对象是本次上传的 `PulseOps-master.zip` 源码快照，不代表 GitHub 后续提交。
- 以源码静态阅读、配置/迁移/测试文档核对为主。
- 快照审查环境无法完成 `./mvnw verify`：上传 ZIP 未保留 Unix 可执行权限；通过 `bash mvnw verify` 尝试后，Maven Wrapper 下载因网络不可用而失败；Docker daemon 也不可用。因此，**本报告不声称已在该环境重新跑通测试**。这些限制描述的是历史快照，不代表当前仓库：执行位已修复并建立 CI，当前测试与 CI 证据见 [`../quality/phase1-acceptance.md`](../quality/phase1-acceptance.md)。
- 报告中的“已确认”指可从源码控制流直接确认；“风险/加固建议”不等于已经发生的漏洞。
- 审查文档保留当时的阶段命名；文中涉及认证的“Phase 4”对应现行 Phase 4B。
- 报告不替代部署环境中的渗透测试、压力测试和备份恢复演练。

## 总体结论

Phase 1 的分层、持久化、测试组织和执行期 DNS 解析校验整体方向良好。建议先修复“配置更新与正在执行的探测之间的竞态”，并将公网部署前的认证列为硬性门槛，然后再继续 Phase 2。

# PulseOps Phase 2 审阅资料

## 阅读顺序

1. `phase2-review.md`：当前进度完成情况、代码审阅结论、风险与建议。
2. `phase2-roadmap-notes.md`：对后续路线的修正建议。
3. `phase2-remediation.md`：如需继续迭代，可按此拆任务。

## 审阅范围

- 本次上传的源码快照
- Issue #19 及其子 issue 的完成说明
- Phase 2 对 Incident、Notification 与恢复语义的实现

## 结论摘要

Phase 2 已完成，当前可进入 Phase 3（Heartbeat）。代码整体质量较好，未见需要阻断推进的高危缺陷；但仍建议把“单实例调度”“at-least-once 通知”“服务端认证门槛”作为后续阶段的硬约束继续保持。

# PulseOps Phase 3 审阅资料

## 阅读顺序

1. `phase3-review.md`：当前进度、代码审阅结论、风险与建议。
2. `phase3-roadmap-notes.md`：对 Phase 4 及之后路线的建议。
3. `phase3-remediation.md`：可选的后续修正任务。

## 审阅范围

- 本次上传的源码快照
- Phase 3 心跳监控、令牌、超时、Incident 与通知实现
- Issue #19 及其完成方案
- Phase 3 验收证据

## 结论摘要

Phase 3 已完成，可以进入 Phase 4A（最小可用 UI）。代码整体质量继续保持较好，已满足当前单实例、事务性 outbox、心跳超时与恢复语义的设计目标。审阅中唯一比较值得顺手改进的是：**通知重试期间的 `lastErrorSummary` 目前只在终态失败时写入，PENDING 重试态下对排障不够直观**。

# PulseOps Phase 4A 审阅资料

## 阅读顺序

1. [`phase4a-review.md`](phase4a-review.md)：Phase 4A UI、API 客户端、异步状态与验证边界的代码审阅。
2. [`phase4a-roadmap-notes.md`](phase4a-roadmap-notes.md)：Phase 4A 完成后的路线建议，重点为 Phase 4B。
3. [`phase4a-remediation.md`](phase4a-remediation.md)：后续可处理的前端可靠性修正清单。

## 审阅范围与结论

- 审阅对象：本次上传的 `PulseOps-master (3).zip` 源码快照。
- Phase 4A 按仓库已有 `docs/quality/phase4a-acceptance.md` 验收记录完成；本次静态审阅未发现阻断进入 Phase 4B 的高危问题。
- 建议跟进：详情/Incident 列表过期响应保护、响应体读取阶段的超时错误映射、列表轮询与分页竞争。
- 本次审阅环境的 Node.js 版本不符合 `frontend/.nvmrc`，依赖安装未在时限内完成；本报告不声称本机重新跑通前端测试或构建。
- Phase 1、Phase 2、Phase 3 的历史审阅结论及原有索引均保留。


# PulseOps Phase 4B 审阅资料

## 阅读顺序

1. [`phase4b-review.md`](phase4b-review.md)：认证、限流、数据清理、备份恢复的代码审阅结论与验证边界。
2. [`phase4b-roadmap-notes.md`](phase4b-roadmap-notes.md)：进入 Phase 5（VPS 部署）的建议顺序。
3. [`phase4b-remediation.md`](phase4b-remediation.md)：登录限流加固与 Phase 5 运维验收清单。

## 审阅范围与结论

- 审阅对象：本次上传的 `PulseOps-master (4).zip` 源码快照。
- Phase 4B 按 `docs/quality/phase4b-acceptance.md` 中记录的 CI 与开发者本机验收证据判定完成；本次静态审查未发现需要否定阶段完成的明显高危问题。
- 建议在公网开放前加固登录限流的并发配额与容量溢出行为；在目标 Linux VPS 上复核脚本执行权限、TLS/代理边界、备份权限和恢复失败清理路径。
- 本次环境没有 Docker，Node.js 版本也不符合项目要求；Maven Wrapper 下载因网络不可用失败。因此本次没有独立重跑后端集成测试、前端测试/构建或恢复演练。详情见 `phase4b-review.md`。
- **历史报告保留：** Phase 1、2、3、4A 的原有报告与以上内容均保留；本次只新增 Phase 4B 三份报告，并在此 README 末尾追加本节。
