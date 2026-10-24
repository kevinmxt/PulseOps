# PulseOps

面向个人开发者的轻量级 HTTP/HTTPS API 与后台定时任务心跳监控平台。本文件只定义领域语言，不记录实现细节。

## Language

### 监控

**Monitor（监控项）**：
一份对一个目标进行持续监控的配置；HTTP 类型主动探测，Heartbeat 类型等待外部上报。
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

**Probe Latency（探测延迟）**：
最近一次 Probe 从发起到结束的耗时。Heartbeat Monitor 不使用 Probe，因此没有探测延迟。
_Avoid_: Response Time、Duration、耗时

**Monitor Status（监控状态）**：
Monitor 的当前状态，取值 `UNKNOWN`、`UP`、`DOWN`；由连续成功和连续失败达到阈值后的状态机决定，不由单次 Probe 结果决定。
_Avoid_: Health、State

**Failure Threshold（失败阈值）**：
判定 Monitor 进入故障前必须连续出现的失败 Probe 次数。
_Avoid_: Retry Count、Max Failures

**Recovery Threshold（恢复阈值）**：
判定 Monitor 恢复正常前必须连续出现的成功 Probe 次数。
_Avoid_: Success Count、Stable Count

### 心跳监控

**Heartbeat Monitor（心跳监控项）**：
一个等待外部任务主动上报的 Monitor；不使用 Probe，也不参与 HTTP 连续失败计数。
_Avoid_: Ping Monitor、Cron Monitor

**Heartbeat Report（心跳上报）**：
外部任务向服务端提交的一次成功信号。
_Avoid_: Ping、Beat

**Heartbeat Token（心跳令牌）**：
Heartbeat Monitor 的凭证明文，只在创建或轮换时展示一次。
_Avoid_: API Key、Secret

**Heartbeat Credential（心跳凭证）**：
持久化的心跳凭证记录，只保存令牌摘要，可撤销。
_Avoid_: Token Record

**Heartbeat Grace（宽限时间）**：
在预期间隔之外额外允许的延迟。
_Avoid_: Timeout、Tolerance

**Heartbeat Deadline（心跳截止时间）**：
最后一次有效上报（或创建时间）加预期间隔与宽限时间；超过即判定超时。
_Avoid_: Due Time、Expiry

**Heartbeat Timeout（心跳超时）**：
超过截止时间仍未收到有效上报的故障状态。
_Avoid_: Missed Beat、Silence

### 故障与通知

**Incident（故障事件）**：
由 Monitor 连续失败达到失败阈值所形成的一段持续故障，从确认故障持续到恢复为止。
_Avoid_: Alert、Error、Outage

**Incident Resolution（故障恢复）**：
使一个未解决的 Incident 结束的确认动作，前提是连续成功达到恢复阈值。
_Avoid_: Close、Clear

**Notification（通知）**：
把 Incident 的开始或恢复告知外部渠道的行为。
_Avoid_: Alert、Message

**Notification Delivery（通知投递记录）**：
针对一个 Incident 事件的一次通知投递及其状态记录。
_Avoid_: Notification Log、Send Result

**Notification Channel（通知渠道）**：
接收通知的外部目的地类型；MVP 仅支持 Telegram。
_Avoid_: Provider、Integration
