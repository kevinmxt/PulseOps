# Architecture Decision Records (ADR)

当决策影响以下任一方面时，创建一份 ADR：
- 核心技术栈或运行时
- 模块边界或部署架构
- 核心数据模型和兼容性
- 安全边界
- 新增长期维护的基础设施
- 会显著增加成本或运维复杂度的设计

## 命名
`NNNN-short-title.md`，例如 `0001-use-modular-monolith.md`。

## 模板
```markdown
# ADR-NNNN: 标题

- 状态：Proposed / Accepted / Superseded
- 日期：YYYY-MM-DD

## 背景
描述需要解决的问题。

## 决策
说明采用的方案。

## 备选方案
列出考虑过的替代方案。

## 后果
描述收益、代价、风险和迁移影响。
```

小型局部实现选择不需要 ADR。不要为了形式创建大量无意义记录。
