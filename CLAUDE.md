

## 必读规则

- 每次开始任务先阅读本文件，并检查当前 Git 状态、目录结构和相关源码。
- 根据任务从 `docs/index.md` 定位并读取必要文档；不得默认所有专题都要全文加载。
- `docs/product/roadmap.md` 记录当前阶段和下一项任务。
- `docs/` 中的规范定义预期行为；代码与规范不一致时，先识别差异并提出修正方案，不要默默忽略规范。

## 工作流程

1. 检查仓库、当前分支、未提交改动和相关实现；执行 `git fetch origin master`，用 `gh pr list --head <当前分支> --state all` 确认分支没有已合并 PR，并比较 `origin/master..HEAD`。若分支已合并或落后，从最新 master 新建分支，不复用旧分支。
2. 确认任务所属阶段、验收条件及需要加载的专题文档。
3. 先给出简短实施计划；小型明确任务可直接执行。
4. 完成最小的、可测试的改动。
5. 按“测试与评审节奏”运行目标测试；完成前运行一次全量并记录结果。如无法运行，明确说明原因。
6. 检查 diff、安全风险、兼容性和无关修改。
7. 更新受影响的文档与路线图状态。
8. 汇报改动文件、实际运行的命令、测试结果、风险和遗留事项。
9. 任务对应 GitHub issue 时，提交并推送当前分支，将完成报告作为评论写入 issue，然后关闭 issue。
10. PR 合并使用仓库实际策略（当前为 squash）：`gh pr merge <n> --squash --delete-branch`；随后 `git pull --ff-only origin master` 确认已回到并同步 master。squash 合并后原提交不是 master 祖先，`git log master..HEAD` 会误显示未合并，不得复用已合并分支继续开发。

## 测试与评审节奏

为减少等待时间，默认按以下节奏执行：

1. 开发期优先运行目标测试（`./mvnw -Dtest=... test`、按类过滤的集成测试），不要每轮改动都跑全量套件。
2. 完成实现后先做代码评审；评审与全量 `./mvnw verify` 并行执行，不要串行等待。
3. 评审修复后，仅在改动涉及生产代码或测试基础设施时再跑一次全量；最终每个任务只保留一次全量验证。
4. 每个 PR 只触发必要的 CI 轮次。不要为补充验收文档中的 CI 链接再提交并触发新 CI；最终运行链接记录在合并后的 issue 完成报告或 PR 讨论中，或直接引用 PR checks 页面。验收文档中的链接不作为合并门禁。

## 工程约束

- 遵守 `docs/architecture/overview.md` 中的技术栈和架构约束。
- 不得未经确认引入 Kubernetes、Kafka、Redis、微服务拆分、支付系统或 AI 根因分析。
- 数据库结构变更必须使用 Flyway 迁移。
- 业务行为变更必须增加或更新测试。
- 外部网络请求必须设置超时；用户可控 URL 必须遵守 SSRF 防护规范。
- 不得记录密钥、密码、完整心跳令牌或敏感请求头。
- 不得通过删除测试、跳过检查、放宽断言或吞掉异常来规避失败。
- 不得覆盖用户未要求修改的本地工作。
- 不得声称测试、构建或部署成功，除非已实际执行并确认。

## 需求冲突

如果需求与产品范围、安全约束或现有数据兼容性冲突，先解释冲突与影响，并提出最小替代方案。未经授权，不得擅自扩大产品范围。

## 完成报告

- 完成内容
- 主要修改文件
- 实际执行的测试/构建命令及结果
- 风险、未完成事项和环境限制
- 下一步建议

## Agent skills

### Issue tracker

Issues live in GitHub Issues; `origin` is configured as `kevinmxt/PulseOps`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` and `docs/adr/`. See `docs/agents/domain.md`.
