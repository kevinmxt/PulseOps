# PulseOps 文档索引

## 项目一句话

PulseOps 是面向个人开发者的轻量级 HTTP/HTTPS API 与后台定时任务心跳监控平台，支持故障事件、恢复判断和通知。

## 渐进式披露规则

1. 每次任务先读根目录 `CLAUDE.md`。
2. 根据任务只读相关专题文档，不要默认加载全部文档。
3. 开始新迭代或选择下一项任务时，读取 `product/roadmap.md`。
4. 修改架构、技术栈或跨模块边界时，读取 `architecture/overview.md`；必要时创建 ADR。
5. 涉及数据库时读取 `architecture/data-model.md`。
6. 涉及用户输入 URL、认证、令牌、网络边界或敏感数据时读取 `architecture/security.md`；公网发布与认证硬性门槛另读 `quality/deployment.md`。
7. 完成任务前，按变更范围读取 `quality/acceptance.md`；涉及部署时另读 `quality/deployment.md`。
8. 如果专题文档缺失，不要自行假设它已存在。根据已有规范实现最小方案，并在必要时提出新增文档。

## 文档地图

| 文档                           | 内容                   | 何时读取           |
| ---------------------------- | -------------------- | -------------- |
| `product/vision.md`          | 定位、目标用户、差异化、非目标      | 需求和产品范围        |
| `product/roadmap.md`         | 阶段、优先级、当前任务          | 迭代计划与进度更新      |
| `plans/phase4a-ui.md`        | Phase 4A 设计共识与任务拆分     | 阶段设计回顾、spec 与 ticket 输入 |
| `architecture/overview.md`   | 技术栈、模块边界、架构约束        | 新模块或架构调整       |
| `architecture/data-model.md` | 核心实体和数据语义            | 数据库、事务、状态一致性   |
| `architecture/security.md`   | SSRF、认证、令牌、网络安全      | 所有安全敏感改动       |
| `specs/monitoring.md`        | HTTP 探测行为            | HTTP 监控功能      |
| `specs/heartbeat.md`         | 定时任务心跳               | Heartbeat 功能   |
| `specs/incidents.md`         | 故障状态机和通知             | Incident、告警、恢复 |
| `specs/api.md`               | REST API 约定          | 接口设计或修改        |
| `specs/ui.md`                | 最小可用 UI 行为规范         | UI 开发、评审与验收    |
| `quality/acceptance.md`      | 全局测试和完成定义            | 实现完成、提交前       |
| `quality/phase1-acceptance.md` | Phase 1/1.1 验收证据（CI 与历史记录）与限制 | 阶段验收与回顾        |
| `quality/phase2-acceptance.md` | Phase 2 验收证据（状态机、Incident、通知、重试）与限制 | 阶段验收与回顾        |
| `quality/phase3-acceptance.md` | Phase 3 验收证据（心跳、令牌、超时、重启）与容量/限制 | 阶段验收与回顾        |
| `quality/phase4a-acceptance.md` | Phase 4A 验收证据（UI、令牌、Incident、投递）与限制 | 阶段验收与回顾        |
| `quality/deployment.md`      | VPS、Compose、HTTPS、备份、公网发布硬门槛 | 部署和运维          |
| `adr/README.md`              | 架构决策记录规则             | 重大技术决策         |

## 规范冲突处理

- 安全要求优先于便利性。
- 已批准的架构决策优先于临时实现偏好。
- 当前代码不自动代表正确规范；若代码与文档冲突，应说明差异并提出修正。
- 变更文档时，尽量只维护一个事实来源，避免复制粘贴同一套规则到多个文件。

## 代码审查资料

本次 Phase 1 源码审查报告与修复计划见 [`reviews/README.md`](reviews/README.md)。
