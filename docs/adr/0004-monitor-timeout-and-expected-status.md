# ADR-0004: Monitor 拆分连接/响应超时并显式保存预期状态范围

- 状态：Accepted
- 日期：2026-10-21

## 背景

`docs/specs/monitoring.md` 要求配置连接超时、响应超时以及预期状态码或状态码范围；原 `docs/architecture/data-model.md` 中的 Monitor 只有单一 `timeout_ms`，且没有预期状态字段，无法区分建连失败与响应超时，也无法判断成功。

## 决策

Monitor 使用 `connect_timeout_ms`、`response_timeout_ms`、`expected_status_min`、`expected_status_max` 四个字段，预期状态默认为 200–299。两个超时分别约束建连阶段与已建连后的响应阶段。

## 备选方案

- 单一 `timeout_ms`：无法区分连接失败与响应超时，错误分类会失真。
- 字符串表达式（如 `"200-299,301"`）：更灵活，但需要自定义解析，查询和校验更复杂。

## 后果

- 需要 Flyway V2 迁移和对应的服务端校验。
- 当前没有存量业务数据，无兼容负担。
- Phase 1 迁移只包含本阶段需要的列；`failure_threshold`、`recovery_threshold` 与 heartbeat 相关字段在对应阶段迁移中加入。
