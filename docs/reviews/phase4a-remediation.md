# Phase 4A 后续修正清单

> 本清单记录审阅建议，不表示这些修改已在业务源码中完成。均可作为小型后续任务，不阻断按当前计划进入 Phase 4B。

## P2：异步请求过期响应保护

**涉及文件**
- `frontend/src/views/MonitorDetailView.vue`
- `frontend/src/views/IncidentListView.vue`
- 对应视图测试文件

**任务**
- 对每一类查询使用 request sequence 或 `AbortController`。
- 只有最新请求可以更新数据、loading 和 error 状态。
- 覆盖快速切换监控 ID、Incident 状态和 `monitor_id` 的响应乱序场景。

**验收**
- 旧请求成功或失败都不能覆盖最新请求结果。
- 页面 URL、筛选条件与最终展示数据一致。

## P2：响应体读取超时映射

**涉及文件**
- `frontend/src/api/client.ts`
- `frontend/src/api/client.test.ts`

**任务**
- 将响应体读取过程中的 `AbortError` 统一映射为 `ApiError` / `TIMEOUT`。
- 保持非 JSON 成功响应、非 JSON 错误响应和普通网络失败的既有语义。

**验收**
- 模拟 `response.text()` abort 的测试通过。
- 视图显示稳定的超时错误代码与文案。

## P3：列表轮询与分页竞争

**涉及文件**
- `frontend/src/views/MonitorListView.vue`
- `frontend/src/views/MonitorListView.test.ts`

**任务**
- 记录刷新期间最后一次用户分页目标，或让用户分页请求替代低优先级轮询请求。
- 不要因后台轮询而吞掉分页点击。

**验收**
- 模拟轮询未完成时点击下一页，最终页面为目标页。
- 保留删除当前页最后一项时回退上一页的既有行为。

## 验证环境

- 使用 `frontend/.nvmrc` 指定的 Node.js `24.15.0`。
- 执行 `npm ci`、`npm test`、`npm run build`。
- 合并后确认 CI checks 通过。
- 本清单不包括认证、数据清理与备份恢复；这些是 Phase 4B 的独立硬门槛。
