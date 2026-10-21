# ADR-0002: 使用 Apache HttpClient 5 与自定义 DNS 解析实现 SSRF 防护

- 状态：Accepted
- 日期：2026-10-21

## 背景

Monitor 的目标 URL 由用户配置，属于不可信输入。`docs/architecture/security.md` 要求只允许 HTTP/HTTPS、拒绝私有地址与链路本地地址、对解析后的 IP 执行校验，并考虑 DNS 重绑定风险。JDK 21 的 `HttpClient` 没有按请求注入 DNS 解析器的扩展点，无法在校验后固定实际连接地址。

## 决策

探测使用 Apache HttpClient 5，并通过自定义 `DnsResolver` 在解析后校验全部地址、只返回校验通过的地址，使连接目标与校验结果一致。关闭自动重定向，3xx 作为普通响应按预期状态码判定。连接、响应均设置超时，不读取完整响应正文。

## 备选方案

- JDK 21 `HttpClient`：零新增依赖，但只能「先解析校验、再按域名连接」，存在 DNS rebinding 时间窗。
- Spring WebClient / Reactor Netty：同样支持自定义 resolver，但会把响应式运行时引入 Servlet 应用。

## 后果

- 新增一个由 Spring Boot 管理版本的依赖，偏离 `docs/architecture/overview.md` 原先列出的客户端选项。
- 需要维护 IPv4/IPv6、私有/链路本地/云元数据地址的校验规则及对应安全测试。
- 不跟随重定向意味着重定向需要用户通过预期状态码显式接受。
