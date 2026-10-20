# 架构与技术选型

## 架构原则
- 模块化单体。
- 单台 VPS + Docker Compose。
- 先满足个人使用，不预先拆微服务。
- 依赖越少越好，但不能以牺牲可靠性和安全为代价。

## 技术栈
- Java 21
- Spring Boot 3.x 稳定补丁版本
- Maven Wrapper
- PostgreSQL
- Spring Data JPA；复杂查询可使用 SQL/JDBC
- Flyway
- Java HttpClient 或 Spring WebClient
- Spring Scheduler
- Vue 3 + TypeScript + Vite
- Docker / Docker Compose
- Caddy 作为反向代理和 HTTPS 入口
- JUnit 5、Spring Boot Test；数据库集成测试优先考虑 Testcontainers

在项目初始化时选择相互兼容的稳定版本并锁定，不追逐未经验证的最新版本。

## 后端模块边界
- `monitor`：监控项管理和配置校验
- `probe`：HTTP 探测及结果记录
- `heartbeat`：后台任务心跳
- `incident`：故障状态与恢复
- `notification`：通知渠道和发送任务
- `scheduler`：定时调度和超时扫描
- `history`：历史查询和清理
- `auth`：身份认证和授权
- `common`：公共异常、时间和通用基础能力

可以按实际代码结构调整命名，但要保持职责清晰。不要为了形式统一而建立没有实际价值的抽象层。

## 关键架构约束
- 调度与探测执行分离。
- 网络请求必须设置连接和响应超时。
- 探测使用有界线程池。
- 单个目标异常不能拖垮整个应用。
- 故障状态和通知发送分离。
- 数据库持久化是监控配置、Incident 和通知状态的可靠来源。
- MVP 单实例运行；不得宣称已支持多实例分布式调度。
- 不引入 Redis、Kafka 或 Kubernetes，除非有明确需求并经确认。

## 运行与时间
- 数据库存储时间统一使用 UTC。
- 前端按用户时区显示。
- 所有可配置的时间间隔和超时必须有合法范围。
- 历史查询必须分页；数据清理必须分批执行。

## 重大架构变更
影响技术栈、部署模型、数据兼容性或模块边界时，先写简短方案并在 `docs/adr/` 中记录决策。
