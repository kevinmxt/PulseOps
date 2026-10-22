# PulseOps 修复与落地计划

此计划按依赖关系排序，适合拆成小 issue 交给 AI Coding Agent。每个任务完成后要求先跑相关测试，再运行完整验证。

## P0 — 先处理正确性风险

### R1：防止旧探测结果覆盖新配置

**涉及文件**
- `backend/src/main/java/dev/pulseops/monitor/MonitorEntity.java`
- `backend/src/main/java/dev/pulseops/monitor/MonitorService.java`
- `backend/src/main/java/dev/pulseops/monitor/MonitorRepository.java`
- `backend/src/main/java/dev/pulseops/monitor/probe/ProbeService.java`
- 新增 Flyway migration 与并发集成测试

**推荐实现**
1. 新增 `config_version BIGINT NOT NULL DEFAULT 1`。
2. 更新任何影响探测语义的字段时递增版本。
3. Probe 开始时读取配置版本。
4. 持久化时仅当当前版本仍等于开始版本时更新 Monitor 当前状态；不匹配时按明确策略丢弃旧结果。
5. 保证“旧任务完成”不会覆盖由配置更新设定的 `nextCheckAt`。
6. 将该行为封装成清晰的 repository 更新方法，避免先读后写导致竞态。

**测试**
- 阻塞目标 A 的 HTTP 响应。
- 在阻塞期间把 URL 更新为 B。
- 释放 A，断言 A 的结果不能修改 B 对应的当前状态/下一次调度时间。
- 断言 B 能及时执行。
- 另外覆盖删除、禁用、重新启用和间隔更新。

### R2：定义探测相关配置变更的立即调度规则

**涉及文件**
- `MonitorService.java`
- `MonitorConfig.java` / `MonitorConfigValidator.java`
- 相关集成测试

**建议**
- 将展示字段（例如 name）与探测语义字段分开比较。
- URL、method、interval、connect timeout、response timeout、expected status 范围发生变化时，安排立即探测。
- enabled 从 false 变 true 时立即探测；变 false 时阻止后续任务，但需定义已经开始的任务是否记录历史。
- 与 R1 的配置版本机制共享同一套变化检测逻辑。

## P1 — 公网前必须完成

### R3：认证发布门槛

- 保持默认 `SERVER_ADDRESS=127.0.0.1`。
- 实现管理 API 认证与授权。
- 对所有 CRUD 和历史查询接口增加未认证测试。
- 生产 profile 中若未启用认证却配置公开监听，启动失败或明确阻断。
- 检查 Caddy/反向代理和后端监听，防止直接绕过代理访问管理 API。
- 更新 ADR-0003，说明认证实现完成后的决策变化。

### R4：DNS 超时资源验证

- 增加可注入的 DNS resolver 测试替身。
- 模拟慢解析、持续超时、恢复和中断。
- 记录超时数量与未完成解析任务数量（若可安全获得）。
- 验证取消不能保证中断底层查询时，应用仍有硬资源边界。
- 若不满足边界，替换为有界/可控解析方案或依赖可靠的系统 DNS 超时。

### R5：SSRF 地址策略测试与文档

- 明确“阻止内网/本机”还是“仅允许全球可路由公共 IP”。
- 覆盖 IPv4/IPv6、特殊用途网段、混合 DNS 答案和 IPv4-mapped IPv6。
- 保留当前执行期 DNS resolver 防护。
- 明确重定向仍保持禁用；未来若允许重定向，每一跳都必须重新验证。
- 建议在部署层限制应用的出站访问范围，作为纵深防御。

## P2 — 进入试用前完成

### R6：历史数据保留策略

- 先测算单个 Monitor 的日/年结果数量。
- 选择默认保留期并允许以后配置。
- 使用分批删除，避免长事务。
- 增加清理作业成功/失败日志。
- 覆盖保留边界、并发查询和任务失败重试。

### R7：CI 与交付验证

在 CI 中运行：
```bash
cd backend
./mvnw verify
cd ../frontend
npm ci
npm run build
```

- CI 必须在干净 checkout 下运行，不能依赖本地缓存以外的隐式状态。
- 集成测试需具备 Docker/Testcontainers 环境，或提供等价的可复现 CI 服务。
- 保证 `backend/mvnw` 在 Git 中有 executable bit。
- 把 CI 结果作为阶段验收证据。

### R8：单实例运行边界

- 文档明确当前只支持一个启用调度的应用实例连接同一数据库。
- 监控部署配置不要默认启动第二个调度实例。
- 未来多实例前再设计数据库租约/原子认领，配合并发集成测试。

## 每个任务的 Agent 完成报告模板

```markdown
## 改动摘要
- ...

## 修改文件
- ...

## 风险与兼容性
- ...

## 验证
- 命令：
- 结果：
- 未运行的测试及原因：

## 验收标准
- [ ] ...
```

## 推荐合并顺序

1. R1 配置版本与旧结果竞态
2. R2 配置更新的立即调度语义
3. R4 DNS 超时资源验证（若测试发现不满足边界则先修）
4. R5 SSRF 地址策略补测/加固
5. R3 认证（可与 Phase 2 后半段并行，但公网前必须完成）
6. R6 历史清理
7. R7 CI
8. R8 单实例约束文档
