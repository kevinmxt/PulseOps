# ADR-0003: Phase 1–3 管理接口暂不认证，默认仅本机监听

- 状态：Accepted
- 日期：2026-10-21

> 注：路线图已将 Phase 4 拆分为 Phase 4A（UI）与 Phase 4B（认证与数据维护）。本 ADR 中的“Phase 4 认证”对应 Phase 4B；公网认证硬性门槛以 [`../quality/deployment.md`](../quality/deployment.md) 为唯一事实来源。

## 背景

依据安全规范，公网管理界面必须启用认证，但 `docs/product/roadmap.md` 当时把认证与授权安排在 Phase 4。Phase 1 需要先交付 HTTP 监控核心。

## 决策

Phase 1–3 的管理 API 不做身份认证，应用默认只监听 `127.0.0.1`。在 Phase 4 完成认证前，不得通过反向代理或防火墙向公网暴露管理接口。

## 备选方案

- 在 Phase 1 引入最小 API Key / Basic 认证：提前实现 Phase 4 范围，增加当前阶段复杂度。
- 不做任何网络限制：与安全规范冲突。

## 后果

- 部署阶段（Phase 5）必须在认证（Phase 4）之后进行。
- 任何临时对外暴露都必须显式修改监听地址，并承担未认证风险。
