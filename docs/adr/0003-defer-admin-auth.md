# ADR-0003: Phase 1–3 管理接口暂不认证，默认仅本机监听

- 状态：Accepted
- 日期：2026-10-21

## 背景

`docs/architecture/security.md` 要求公网管理界面必须启用认证，但 `docs/product/roadmap.md` 把认证与授权安排在 Phase 4。Phase 1 需要先交付 HTTP 监控核心。

## 决策

Phase 1–3 的管理 API 不做身份认证，应用默认只监听 `127.0.0.1`。在 Phase 4 完成认证前，不得通过反向代理或防火墙向公网暴露管理接口。

## 备选方案

- 在 Phase 1 引入最小 API Key / Basic 认证：提前实现 Phase 4 范围，增加当前阶段复杂度。
- 不做任何网络限制：与安全规范冲突。

## 后果

- 部署阶段（Phase 5）必须在认证（Phase 4）之后进行。
- 任何临时对外暴露都必须显式修改监听地址，并承担未认证风险。
