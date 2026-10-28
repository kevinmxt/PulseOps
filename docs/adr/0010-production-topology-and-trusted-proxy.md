# ADR-0010: 生产部署拓扑、可信代理边界与备份自动化

- 状态：Accepted
- 日期：2026-10-28

## 背景

Phase 5A 需要把本地开发栈（仅包含 PostgreSQL 的 `compose.yaml`）变成可在单台 VPS 运行的生产栈。这一阶段同时确定多个长期边界：谁是对外入口、容器之间哪些网络可达、反向代理头在什么条件下可信、备份如何自动加密并异地存放。这些选择决定安全边界和日常运维方式，修改成本高于普通功能实现。

具体操作、命令与阈值以 [`../quality/deployment.md`](../quality/deployment.md) 为唯一事实来源；代理信任与客户端地址规则以 [`../specs/auth.md`](../specs/auth.md) 为唯一事实来源。本 ADR 只记录决策、被否决方案和后果，不复制上述细节。

## 决策

- 使用单台 Linux VPS + Docker Compose。生产编排为 `compose.prod.yaml`，项目名固定 `pulseops`；`compose.yaml` 只保留本地 PostgreSQL。
- 三个服务：`caddy` 是唯一对外入口，托管前端静态文件并把 `/api/*`、`/actuator/health*` 反向代理到 `backend`；`backend` 运行 Spring Boot；`postgres` 只通过内部网络访问。
- 固定两个 bridge 网络：`edge` 承载 `caddy` 与 `backend`，`data` 承载 `backend` 与 `postgres`；`postgres` 不加入 `edge`。
- 宿主只发布 80/443 到 Caddy 容器；HTTP 自动跳转 HTTPS。`backend` 与 `postgres` 不发布宿主端口，避免 Docker 发布端口绕过 ufw。
- 使用单一 Caddyfile，通过 `SITE_ADDRESS` 与 `TLS_ISSUER` 切换环境：5A 为本地域名 + Caddy 内部 CA，5B 为真实域名 + ACME。Caddy 不开启访问日志。
- 可信代理：以 Caddy 所在的 `edge` 子网作为唯一可信来源，后端启用 `server.forward-headers-strategy=native`；只有直接对端属于该子网时才采纳转发头。具体配置与客户端地址规则见 [`../specs/auth.md`](../specs/auth.md)。
- 心跳 HTTPS 判定在生产走原生 TLS 或经 Tomcat 还原的 HTTPS 请求；保留回环回退用于本地直连和测试，不是生产信任路径。
- 容器基线：非 root 运行；backend 只读根文件系统 + `tmpfs /tmp` + `cap_drop: [ALL]`；所有服务启用 `no-new-privileges`、`restart: unless-stopped` 并设置显式资源限制。postgres 沿用官方镜像默认用户。
- 日志：应用与 Caddy 只写 stdout/stderr；Docker `json-file` 驱动统一轮转；systemd 与脚本输出进入 journald；不引入 logrotate，也不写宿主日志文件。
- 备份自动化：systemd timer 定时执行 `backup.sh`；流程为 `pg_dump -Fc` + `pg_restore --list` 自检 → `age` 非对称加密 → `rclone` 异地 → 远端对象大小校验 → Healthchecks ping。带本地与异地保留策略；失败由 `OnFailure` 单元 ping `/fail`；私钥离线备份。
- 发布与回滚：在目标机器构建镜像，tag 使用 Git short SHA，不引入镜像仓库。`deploy.sh` 执行预检 → 备份 → 构建 → 健康门禁 → 冒烟，失败不自动回滚。Flyway 只做前向迁移；schema 不兼容时从部署前备份恢复，不编写 down 迁移。
- 加固：ufw 默认拒绝入站，仅放行 SSH 与 HTTP/HTTPS；sshd 仅密钥登录、禁用密码与交互式认证、root 仅密钥登录，保持 22 端口；不引入 fail2ban。
- 外部监控：公开 `/actuator/health` 供外部可用性服务探测；Healthchecks.io 负责备份死信。告警链路不经过 PulseOps 本身。

## 备选方案

- **后端托管前端静态文件**：静态资源与认证边界耦合，且与“Caddy 作为 TLS 入口”的约束不一致。
- **独立 nginx 容器**：多一个组件；Caddy 已能同时完成 TLS 终止、反代与静态托管。
- **CI 构建并推送镜像到 GHCR**：单人单机项目增加 registry 凭证与网络依赖；目标机构建 + SHA tag 已满足回滚需求。
- **单 Compose 文件 + profiles**：开发与生产的服务集合、端口和重启策略差异大，分文件更不易误操作。
- **无条件信任 `X-Forwarded-For`**：任何能访问 backend 的进程都可伪造客户端地址，击穿登录限流与日志审计。
- **改用 CIDR 配置而非 Tomcat 正则**：Tomcat 只提供 `internal-proxies` 正则；固定 `edge` 子网且不发布 backend 端口已形成足够边界。
- **宿主 cron / restic / borg**：现有备份脚本已验证；age + rclone 满足加密、异地与恢复要求；systemd timer 提供补跑和失败钩子。
- **GPG / OpenSSL 对称加密**：密钥分发与轮换更复杂；age 更简单且足够。
- **健康检查失败自动回滚**：前向迁移下旧镜像可能运行在新 schema 上，制造数据损坏；人工判断更安全。
- **为每个迁移编写 down 脚本**：维护成本高且容易与真实数据状态不一致，备份恢复是更可靠的兜底。
- **修改 SSH 端口 / fail2ban / 独立 deploy 用户**：单管理员场景收益有限、锁死风险更高；deploy 用户加入 docker 组后与 root 等价。
- **Caddy 访问日志 / 宿主日志文件 + logrotate**：健康检查会产生持续噪声，Docker 统一轮转边界更易验证。

## 后果

- 单机是唯一故障域；VPS 整体故障时只能借助提供商控制台、快照和异地备份恢复。
- 固定子网与单机部署限制多主机扩展；引入多实例前必须重新设计代理信任、调度租约与数据库连接。
- backend 只读根文件系统要求所有运行时写入走 `/tmp` 或数据库；新增本地缓存或上传功能时需要显式挂载卷。
- 部署依赖目标机的构建能力，构建期间占用 CPU 与磁盘；`up -d` 重建容器会造成短暂不可用。
- 日志按 Docker 驱动上限轮转，更早日志会丢失；需要长期审计时必须另行导出。
- 出站目标白名单未实现，应用可访问任意公网目标，只能依赖应用层 SSRF 防护；这是已知缺口。
- 备份加密私钥丢失会导致异地备份不可恢复，离线副本是唯一保障。
- 可信代理边界依赖固定 `edge` 子网与 backend 不发布宿主端口；若未来发布 backend 端口或增加新的代理，必须重新评审信任规则。
- 5A 的内部 CA 不被外部监控信任；真实域名、ACME、对象存储与外部监控注册属于 Phase 5B。
