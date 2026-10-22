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
- 报告不替代部署环境中的渗透测试、压力测试和备份恢复演练。

## 总体结论

Phase 1 的分层、持久化、测试组织和执行期 DNS 解析校验整体方向良好。建议先修复“配置更新与正在执行的探测之间的竞态”，并将公网部署前的认证列为硬性门槛，然后再继续 Phase 2。
