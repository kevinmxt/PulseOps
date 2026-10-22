# PulseOps Phase 1 源码审查报告

- 审查日期：2026-10-22
- 审查对象：本次上传的 `PulseOps-master.zip`
- 关注范围：HTTP 监控核心、调度、目标 URL / SSRF、数据一致性、API 边界、测试和工程可运行性
- 审查方式：静态阅读 Java 源码、Flyway migration、配置、Compose、README、路线图和 Phase 1 验收文档
- 动态验证状态：未完成（原因见“验证限制”）

## 1. 执行摘要

**总体评价：Phase 1 的工程结构与安全意识不错，但当前还不建议直接把它作为公网生产服务使用。**

值得保留的设计：
- 按 `monitor`、`probe`、`target`、`api` 等职责拆分代码，模块边界清楚。
- 使用 Flyway 管理 schema，并通过数据库约束补充应用层校验。
- 探测结果在独立事务中持久化，避免网络请求期间长时间持有数据库事务。
- Apache HttpClient 禁止自动重定向、自动重试、Cookie 管理，并通过自定义 DNS resolver 在建连解析时再次校验地址。
- 探测线程池和队列有界，错误摘要有长度限制，探测不读取响应正文。
- 验收文档列出了正常、失败、超时、禁用、重启和 SSRF 场景。

**首要建议：先修复配置更新与 in-flight probe 的竞态，再进入 Phase 2 的 Incident 状态机实现。** 该问题可能让旧目标的探测结果覆盖新配置的状态，并推迟新目标的首次探测。

## 2. 风险汇总

| ID | 等级 | 主题 | 状态 |
|---|---|---|---|
| P1-01 | P1 高优先级 | 修改监控配置时，旧的 in-flight probe 仍可写回状态 | 源码控制流可确认 |
| P1-02 | P1 发布门槛 | 管理 API 当前无认证；安全性依赖默认只监听回环地址 | 当前设计已知限制，公网部署前必须解决 |
| P2-01 | P2 中优先级 | DNS 超时取消不保证底层阻塞解析被终止 | 资源风险，需要针对运行时验证 |
| P2-02 | P2 中优先级 | SSRF 策略对特殊用途/非全球可路由地址的覆盖还可更完整 | 加固建议，不等同于已确认的内网访问绕过 |
| P2-03 | P2 中优先级 | 修改部分探测相关配置不会立即触发重新探测 | 源码控制流可确认，产品语义需确定 |
| P2-04 | P2 中优先级 | 调度器的 in-flight 去重仅在单 JVM 内有效 | 当前单实例可接受，扩容前需处理 |
| P2-05 | P2 中优先级 | CheckResult 长期累积，需要明确保留和清理策略 | 已在路线图规划，建议提前定义容量预算 |
| P3-01 | P3 低优先级 | 上传 ZIP 未保留脚本可执行权限，当前快照无法直接执行 `./mvnw` | 归档/交付问题，未证明 Git 仓库本身有问题 |

## 3. 详细发现

### P1-01：配置更新与正在执行的探测之间存在竞态

**位置**
- `backend/src/main/java/dev/pulseops/monitor/MonitorService.java:64-79`
- `backend/src/main/java/dev/pulseops/monitor/probe/ProbeScheduler.java:44-77`
- `backend/src/main/java/dev/pulseops/monitor/probe/ProbeService.java:33-68`

**观察**
- `ProbeScheduler` 用进程内 `inFlight` 集合阻止同一个 Monitor 在同一 JVM 内重复排队/执行。
- `ProbeService.probe()` 先读取 Monitor 配置，然后执行网络请求。
- 网络请求完成后，`persist()` 再次按 ID 读取当前 Monitor，但它只拿当前 interval 来计算 `nextCheckAt`；它没有验证探测期间 `targetUrl`、method、期望状态码范围或其他探测配置是否已改变。
- `MonitorService.update()` 在目标/间隔变化时将 `nextCheckAt` 设置为当前时间，但已在执行中的任务完成后仍会写入状态，并把 `nextCheckAt` 改为 `finishedAt + 当前 interval`。

**影响**
例如：探测目标 A 时网络请求挂起；用户把该 Monitor 的目标改为 B；旧请求随后成功/失败。旧结果可能被记入同一个 Monitor 的历史并更新当前状态，而 B 的首次探测还可能被推迟到下一 interval。API 中的 Monitor 已经是 B，但状态却可能反映 A。

**建议修复**
- 给探测使用的配置增加版本号（例如 `config_version`），更新任何会影响探测语义的字段时递增。
- Probe 开始时捕获 `monitorId + configVersion + target/config snapshot`。
- 写回状态前，在同一事务内确认当前 `configVersion` 仍等于探测开始时的版本；不一致时丢弃旧结果的状态更新，并明确决定是否将旧结果保留为“过期/历史结果”。MVP 可先直接丢弃旧配置结果，或记录配置版本以便审计。
- 更轻量的替代方案是比较关键配置快照，但版本号更清晰且不容易遗漏字段。
- 为“请求 A 阻塞期间把目标更新为 B”增加集成测试，断言旧结果不覆盖新配置状态，且 B 能在合理时间内被探测。

**验收标准**
1. 旧目标的 in-flight probe 不能覆盖新配置的当前状态。
2. 配置变更后，新的配置不会被旧任务重置成一个完整 interval 后才首次执行。
3. 删除 Monitor 时，已有实现已尝试丢弃写回结果；该行为继续保持。
4. 配置版本变更和结果持久化在并发情况下具有可重复测试的语义。

### P1-02：管理 API 无认证是公网部署的硬性阻断项

**位置**
- `backend/src/main/java/dev/pulseops/monitor/api/MonitorController.java:26-85`
- `backend/src/main/resources/application.yml:28-30`
- `docs/adr/0003-defer-admin-auth.md`
- `docs/architecture/security.md:19-26`

**观察**
当前 Monitor CRUD 与历史查询接口没有认证/授权逻辑。默认 `SERVER_ADDRESS` 是 `127.0.0.1`，因此本地开发默认边界合理；但只要部署配置把监听地址改为 `0.0.0.0` 或通过反向代理暴露到公网，任何可访问者就可能读写/删除监控配置并利用探测能力。

**建议**
- 保留当前默认回环监听。
- 将“公网可访问”与“认证已启用”设为部署前硬性门槛，而不是普通的后续优化项。
- 在 Phase 4 先实现单用户认证和所有管理路由保护，再开放公网访问。
- 增加未认证访问 API 的集成测试，并在生产启动配置中防止误配置导致未认证服务暴露（例如独立的 production profile/启动检查）。
- 反向代理不应被视为唯一授权层，除非明确规定且验证只有受信任代理可访问后端端口。

**验收标准**
1. 未认证请求对所有管理 API 返回 401/403。
2. 心跳 token（未来阶段）和管理会话权限分离。
3. 生产配置不会把后端端口直接暴露到公网。
4. 认证缺失时生产启动失败，或明确禁止公开监听。

### P2-01：DNS 超时后取消 Future，不保证底层阻塞 DNS 查询已停止

**位置**
- `backend/src/main/java/dev/pulseops/monitor/target/TimeoutHostResolver.java:22-47`
- `backend/src/main/java/dev/pulseops/monitor/target/TargetPolicyConfiguration.java:14-20`

**观察**
每次解析都会启动一个虚拟线程执行 `InetAddress.getAllByName()`。超时后调用 `future.cancel(true)`，但 Java 的系统 DNS 查询/底层名称解析并不保证能响应线程中断。因此，调用方可以在 2 秒后返回，但底层解析线程可能仍然停留在系统调用中。若 DNS 服务长期异常且探测频率较高，可能积累未完成的解析任务。

**建议**
- 增加 DNS 延迟/黑洞模拟测试和观测指标，验证在连续超时后虚拟线程/解析任务是否持续积累。
- 若需要严格的资源上限，使用具有可取消性或受控线程池/队列的 DNS 客户端，或在操作系统/网络层设置可靠的 DNS 超时。
- 仅仅把线程换成虚拟线程并不能保证阻塞 DNS 可取消。
- 不要把此项描述成已证实的资源泄漏；目前属于应验证的运行时风险。

**验收标准**
在持续 DNS 超时压力测试下，未完成解析任务有界，不会导致探测线程持续堆积或应用失去响应。

### P2-02：进一步明确 SSRF 对特殊用途地址的策略

**位置**
- `backend/src/main/java/dev/pulseops/monitor/target/SsrfTargetPolicy.java:47-69`
- `backend/src/main/java/dev/pulseops/monitor/target/SsrfDnsResolver.java:17-29`
- `backend/src/main/java/dev/pulseops/monitor/probe/ProbeConfiguration.java:17-35`

**已有优点**
- 创建时检查目标。
- 建立 HTTP 连接时使用自定义 DNS resolver 再检查解析结果，降低 DNS rebinding 风险。
- 若一次 DNS 返回的地址中存在受限地址，当前实现会拒绝该组解析结果。
- 禁止自动重定向，避免未校验的跳转目标。

**可加固之处**
`SsrfTargetPolicy.isBlocked()` 覆盖了 any-local、loopback、link-local、site-local、multicast、IPv4 `0/8`、IPv4 CGNAT `100.64/10` 和 IPv6 ULA，但没有显式维护 IANA 特殊用途/非全球可路由地址清单。不同 Java `InetAddress` 方法并不等价于“全球可路由”判断。

**建议**
- 明确产品策略：是只禁止私网/本机/元数据地址，还是只允许全球可路由的公共地址。
- 如果目标是公共互联网监控，考虑采用维护良好的 IP 地址分类策略，覆盖文档网段、基准测试网段、保留地址和其他特殊用途前缀。
- IPv4、IPv6、IPv4-mapped IPv6、混合 DNS 答案都要有测试。
- 网络层仍应以防火墙/出站策略做纵深防御，应用层校验不能替代网络隔离。
- 避免仅依赖零散的手写 CIDR 列表而不注明来源和更新策略。

**验收标准**
目标地址策略有清晰文档，且对 IPv4/IPv6 特殊用途地址的测试能说明允许/拒绝原因。该项是加固建议，不代表当前代码已被证明可以访问私网地址。

### P2-03：部分配置变更不会立即探测

**位置**
- `backend/src/main/java/dev/pulseops/monitor/MonitorService.java:64-79`

**观察**
`needsImmediateCheck` 只在“从禁用变为启用、interval 变化、target URL 变化”时为 true。若用户只修改 `method`、`expectedStatusMin/Max`、`connectTimeoutMs` 或 `responseTimeoutMs`，不会主动把 `nextCheckAt` 调整为当前时间。

**影响**
用户更新了探测判定条件后，当前状态和最近一次结果仍可能在一段时间内反映旧配置。是否算缺陷取决于产品语义，但对“保存后立即验证新配置”的预期不友好。

**建议**
- 将所有会影响探测结果的配置字段纳入变化检测；若任一变化，则安排一次立即探测。
- 若不希望每次改名称都触发探测，明确分离“展示字段”和“探测语义字段”。
- 与 P1-01 的配置版本机制一起实现，避免两套配置变更逻辑不一致。

**验收标准**
对 method、expected status、timeouts 的更新都有明确且测试覆盖的调度行为。

### P2-04：调度去重只在单 JVM 内成立

**位置**
- `backend/src/main/java/dev/pulseops/monitor/probe/ProbeScheduler.java:25-30, 44-77`
- `backend/src/main/java/dev/pulseops/monitor/MonitorRepository.java:14-21`

**观察**
`inFlight` 是本地 `ConcurrentHashMap` 集合，`findDueMonitorIds()` 只查询到期 ID，没有数据库级任务认领/租约。两个应用实例连接同一个数据库时，可能同时拿到相同的到期 Monitor 并执行探测。

**建议**
- 当前产品定位是单 VPS、单实例时，可以暂时保留该设计。
- 在架构文档中明确“同一数据库只运行一个启用调度的应用实例”。
- 如果未来需要多实例/高可用，再引入数据库租约、原子 claim（例如 `FOR UPDATE SKIP LOCKED`）或独立调度机制；不要现在就为了假想规模引入复杂度。

**验收标准**
单实例运行边界有文档说明；未来扩展多实例前有专门设计与并发集成测试。

### P2-05：历史数据增长与保留策略应提前量化

**位置**
- `backend/src/main/resources/db/migration/V3__create_check_result.sql`
- `docs/product/roadmap.md` Phase 4 数据维护部分

**观察**
CheckResult 按 Monitor 和时间建立索引，查询方向合理，但目前没有自动保留/清理机制。按 60 秒间隔估算，一个 Monitor 每天产生约 1,440 条结果、每年约 525,600 条；10 个 Monitor 每年约 526 万条，实际存储量还包括索引和行开销。

**建议**
- 在 UI/认证阶段前就定义默认保留期限，例如 30 天（最终数值可由用户场景决定）。
- 清理任务分批删除，避免一次大事务；确保清理不影响近期历史查询。
- 对清理任务增加指标和日志，并在文档中说明保留策略。
- 备份策略要与保留策略一致。

**验收标准**
给定保留天数，旧记录会按批次清理；边界时间测试正确；清理任务失败可观察并可重试。

### P3-01：当前上传 ZIP 无法直接执行 Maven Wrapper

**观察**
本次上传 ZIP 的文件条目没有保留 Unix 文件模式，因此解压后 `backend/mvnw` 不可执行。尝试 `bash mvnw verify` 时，Wrapper 需要下载 Maven，但当前审查环境网络不可用；此外当前环境没有 Docker daemon，无法运行依赖 Testcontainers PostgreSQL 的完整集成测试。

**处理**
这可能只是上传/打包方式导致的权限丢失，不能据此认定 Git 仓库中的执行位有问题。报告包会为 `backend/mvnw` 设置可执行权限以便解压后使用；建议后续通过 GitHub Actions 验证干净 checkout 下的 `./mvnw verify`。

## 4. 测试覆盖建议

在现有测试基础上，新增以下回归测试：

1. `MonitorConfigRaceIT`：探测 A 期间更新目标为 B，旧结果不能覆盖 B 的状态，B 的探测不会被延迟一个完整 interval。
2. `MonitorConfigUpdateSchedulingIT`：修改 method、expected status、connect timeout、response timeout 后，验证立即探测策略。
3. `DnsTimeoutResilienceIT`：模拟 DNS 持续超时，检查解析任务、虚拟线程和探测池在压力下是否保持有界。
4. `SsrfAddressPolicyTest`：覆盖 IPv4/IPv6 特殊用途地址、IPv4-mapped IPv6、DNS 混合答案。
5. `ProductionSecurityIT`：未认证访问管理 API 必须拒绝；公网配置下认证不能被意外关闭。
6. `RetentionJobIT`：保留期限边界、批量清理、重试与并发查询。

## 5. 设计中建议保留的部分

- 事务只包围结果持久化，而不是整个网络请求。
- 自定义 DNS resolver 与执行期目标校验。
- 禁止自动重定向和自动重试。
- 不读取探测响应体，并对错误摘要长度做限制。
- 有界探测线程池与有限队列。
- 数据库 schema 约束、Flyway 迁移、分页查询与确定性排序。
- 当前 MVP 保持单实例、单体架构，不急于引入 Redis、Kafka 或微服务。

## 6. 验证限制与后续验证命令

本次审查没有成功在本环境中重新运行构建和集成测试，原因是 Maven Wrapper 下载网络不可用、Docker daemon 不存在。不能将仓库中 `phase1-acceptance.md` 记载的通过结果当作本次审查重新验证的结果。

在有 Docker 的干净开发环境中执行：

```bash
cd backend
./mvnw verify
```

然后执行前端生产构建：

```bash
cd frontend
npm ci
npm run build
```

建议把以上命令加入 CI，并要求 PR 通过后再合并。
