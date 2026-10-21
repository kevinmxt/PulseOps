# PulseOps

面向个人开发者的轻量级 HTTP/HTTPS API 与后台定时任务心跳监控平台。本文件只定义领域语言，不记录实现细节。

## Language

**Monitor（监控项）**：
一份持续检查某个目标的配置。
_Avoid_: Job、Task、Target

**Target（目标）**：
Monitor 指向的 HTTP/HTTPS 端点。
_Avoid_: 用 URL 指代被监控的对象

**Probe（探测）**：
对一个 Monitor 的目标执行的一次检查。
_Avoid_: Check、Request

**CheckResult（探测结果）**：
一次 Probe 的持久化结果。
_Avoid_: Check、Result

**Monitor Status（监控状态）**：
Monitor 的当前状态，取值 `UNKNOWN`、`UP`、`DOWN`；Phase 1 由最近一次 Probe 决定。
_Avoid_: Health、State
