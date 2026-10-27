# Phase 5A：部署准备（本地可验证）— 设计共识

## 状态与用途

- 本文件是 grilling 设计访谈的产物，作为 `to-spec` 与 `to-tickets` 的输入。
- 长期行为规范以 [`docs/quality/deployment.md`](../quality/deployment.md)、[`docs/specs/auth.md`](../specs/auth.md)、[`docs/architecture/security.md`](../architecture/security.md) 为准；本文件保留决策背景与被否决方案。
- 领域术语不在此重复，见 [`GLOSSARY.md`](../../GLOSSARY.md)。
- 阶段范围与完成条件以 [`docs/product/roadmap.md`](../product/roadmap.md) 为准。
- 本地演练环境安装步骤见 [`phase5a-vm-setup.md`](phase5a-vm-setup.md)。

## 目标与完成条件

Phase 5A 的目标是完成部署配置、脚本和文档，并在本地 Linux 虚拟机验证，缩短拿到 VPS 后的上线时间。

完成条件（来自路线图）：

- 生产 Compose 可在本地或虚拟机启动并通过健康检查。
- Caddy 配置校验通过且本地 HTTPS 可访问。
- 备份调度、加密与恢复演练通过。
- 回滚 dry-run 通过。

非目标（属 Phase 5B）：真实域名证书签发、公网可达性验收、在 VPS 上执行加固、连续运行 7 天验收。

## 现状事实

- 仓库只有开发用 `compose.yaml`，且仅包含 PostgreSQL；没有后端或前端 Dockerfile，没有 Caddy 配置。
- 后端为 Spring Boot 3.5 / Java 21，默认 `server.address=127.0.0.1`；非回环监听时必须开启 `pulseops.auth.cookie-secure`，否则拒绝启动。
- `/actuator/health` 已公开；`/actuator/metrics` 需要 `ADMIN`。
- 前端为 Vue 3 + Vite，使用相对路径 `/api`，开发时由 Vite 代理到 `localhost:8080`。
- `scripts/backup.sh` 与 `scripts/restore.sh` 已通过 4B 演练；定时调度、加密、异地存放与失败告警尚未实现。
- 登录限流只使用 `request.getRemoteAddr()`；引入反向代理后真实客户端 IP 尚未处理（4B 遗留）。
- 本机为 Windows 10 Pro，Hyper-V 已启用；将用 Multipass 建 Ubuntu 24.04 VM（2 vCPU / 4 GB / 40 GB）作为 5A 与 5B 的预演环境。
- CI 目前有后端 `verify` 与前端 `build/test` 两个 job，不校验部署产物。

## 已确认决策

| 主题 | 决策 |
|---|---|
| 生产拓扑 | Caddy 容器托管前端静态文件，反向代理 `/api/*` 与 `/actuator/health*` 到 backend 容器；前后端同源 |
| 前端托管 | 静态前端壳公开；数据仍由后端认证边界保护 |
| 镜像构建 | 在目标机器上 `docker compose -f compose.prod.yaml build`；镜像 tag 使用 Git short SHA；不引入镜像仓库 |
| Compose 组织 | 新增 `compose.prod.yaml`；保留 `compose.yaml` 仅用于本地开发；脚本遵守 `COMPOSE_FILE` |
| 本地演练环境 | Multipass + Hyper-V，Ubuntu 24.04 LTS，2 vCPU / 4 GB / 40 GB；数据目录放 `G:\work\VM\Multipass`；5A 验收与 5B 预演共用 |
| 本地 HTTPS | 单一 Caddyfile，用 `SITE_ADDRESS` 与 `TLS_ISSUER` 环境变量区分 5A/5B；5A 使用 `pulseops.test` + `tls internal` |
| Caddy 端口 | 容器内监听 8080/8443，宿主发布 80/443；Caddy 以非 root 运行 |
| backend 加固 | 非 root、只读根文件系统 + tmpfs `/tmp`、`cap_drop: [ALL]`；不发布宿主端口 |
| postgres 加固 | 使用官方镜像默认 `postgres` 用户与数据卷；不使用 `cap_drop: ALL`（会破坏入口脚本降权） |
| 资源限制 | backend 768 MB / postgres 1 GB / Caddy 128 MB；固定 CPU 与 pids 上限 |
| 网络 | 固定子网：`edge` 172.28.0.0/24（Caddy + backend），`data` 172.28.1.0/24（backend + postgres） |
| 可信代理 | 后端启用 `server.forward-headers-strategy=native`，Tomcat `remoteip.internal-proxies` 只信任 `edge` 子网 |
| 日志 | 全部输出 stdout/stderr；Docker `json-file` 驱动 `max-size=10m`、`max-file=5`；Caddy 只开错误日志 |
| 备份调度 | systemd timer，每日 03:17 UTC，`Persistent=true` |
| 备份加密 | `age` 非对称加密；私钥 `/etc/pulseops/age.key`（600），离线备份一份 |
| 异地存放 | `rclone` 推送 S3 兼容对象存储；5A 用本地 rclone remote 验证，5B 接真实桶 |
| 备份保留 | 本地 7 份；异地 14 份 |
| 备份告警 | 成功后 ping Healthchecks.io 成功 URL；失败由 `OnFailure` 单元 ping `/fail` |
| 外部健康检查 | 复用公开 `/actuator/health`，不改应用代码；5A 输出服务选型与告警预案，5B 实际接入 |
| 回滚 | 前向迁移 + 镜像 tag 回退；schema 不兼容时从部署前备份恢复，不写 down 迁移 |
| 部署脚本 | `deploy.sh` 预检 → 备份 → 构建 → 启动 → 健康门禁 → 冒烟；失败不回滚，打印回滚命令；支持 `--dry-run` |
| 回滚脚本 | `rollback.sh [tag]` 切换镜像并健康检查；支持 `--dry-run` |
| 防火墙 | ufw 默认拒绝入站、允许出站、`limit 22`、允许 80/443 |
| SSH | 仅密钥；禁用密码与交互式认证；root 仅密钥登录；保持 22 端口；不引入 fail2ban |
| CI | PR 只跑 `compose config` 与 `caddy validate`；master 额外构建前后端镜像（不推送） |
| 脚本入库 | 提交 `scripts/provision-vm.sh`；Windows 宿主配置只保留文档 |
| 生产路径 | 代码 `/opt/pulseops`；环境文件 `/etc/pulseops/pulseops.env`（600）；备份 `/var/backups/pulseops` |
| 镜像保留 | 最近 5 个 tag；部署后清理更旧 tag 与 build cache |
| 文档 | 新增 ADR-0010、本文件与 `quality/phase5a-acceptance.md`；更新 `deployment.md`、`auth.md`、`index.md`、README、roadmap |

## 部署拓扑

```text
Windows 浏览器 / 外部监控
        │ HTTPS 443（5A: pulseops.test + 内部 CA；5B: 真实域名 + ACME）
        ▼
┌──────────────────────────────────────────────┐
│ 宿主 / VM                                     │
│   :80  :443  ──►  caddy 容器（非 root，8080/8443）│
│                    ├── /              → 静态 dist（SPA fallback）
│                    ├── /api/*          → backend:8080
│                    └── /actuator/health* → backend:8080
│                                              │
│  edge 172.28.0.0/24 ── caddy, backend        │
│  data 172.28.1.0/24 ── backend, postgres     │
│                                              │
│  backend 容器（非 root，只读，0.0.0.0:8080）   │
│        └── postgres:5432（不发布宿主端口）      │
└──────────────────────────────────────────────┘
```

- 宿主只发布 80/443；backend 与 postgres 不发布任何宿主端口。
- backend 在容器内监听 `0.0.0.0:8080`，因此必须显式设置 `PULSEOPS_AUTH_COOKIE_SECURE=true`，启动校验才会通过。
- Caddy 是唯一对外入口；应用自身仍执行认证与 CSRF，不依赖代理授权。
- `/actuator/health*` 只反代公开健康端点；`/actuator/metrics` 不由 Caddy 暴露。

## 镜像与 Compose

### backend 镜像

- 多阶段构建：Maven 构建 → `eclipse-temurin:21-jre` 运行层。
- 创建非 root 用户运行；运行层安装 `curl` 仅用于健康检查（或改用无需额外包的健康检查方式，在实现时定稿）。
- 健康检查：`GET http://127.0.0.1:8080/actuator/health`。
- 构建上下文 `backend/`，新增 `.dockerignore` 排除 `target/` 等。

### 前端镜像

- 多阶段构建：`node:24.15.0-alpine`（对齐 `.nvmrc`）执行 `npm ci && npm run build` → `caddy:2-alpine` 复制 `dist/` 与 `frontend/deploy/Caddyfile`。
- 构建上下文 `frontend/`，新增 `.dockerignore` 排除 `node_modules/`、`dist/`。
- 镜像 tag 统一为 `${IMAGE_TAG}`（Git short SHA）。

### compose.prod.yaml

- 项目名固定 `pulseops`；三个服务：`postgres`、`backend`、`caddy`。
- `restart: unless-stopped`；`depends_on` 使用健康条件：postgres → backend → caddy。
- 资源限制：`mem_limit` / `cpus` / `pids_limit`；postgres 1 GB，backend 768 MB，Caddy 128 MB。
- 安全选项：backend `read_only` + `tmpfs: /tmp` + `cap_drop: [ALL]`；Caddy 非 root；所有服务 `security_opt: no-new-privileges:true`。
- 日志：`logging.options.max-size=10m`、`max-file=5`。
- 卷：`pulseops-postgres-data`、`pulseops-caddy-data`、`pulseops-caddy-config`。
- Caddy 若因官方镜像用户与卷权限冲突无法直接以非 root 启动，允许用固定 UID 的初始化步骤修正卷属主；最终必须满足"非 root 运行"。

### 配置与密钥

- 生产环境文件 `/etc/pulseops/pulseops.env`，权限 600，属主为部署账号。
- 仓库提供 `deploy/env.prod.example`（占位值，不使用 `.env.prod.example` 命名，避免被 `.gitignore` 的 `.env.*` 规则忽略）。
- 必填变量：数据库凭据、`SERVER_ADDRESS=0.0.0.0`、`PULSEOPS_AUTH_COOKIE_SECURE=true`、`HEARTBEAT_REQUIRE_HTTPS=true`、首次引导账号变量、`SITE_ADDRESS`、`TLS_ISSUER`、`IMAGE_TAG`。
- 可选变量：Telegram、登录限流容量、备份相关参数。
- 备份密钥、rclone 配置与 Healthchecks URL 只存在于 `/etc/pulseops/` 下的 600 文件中，不进入 Git。

## TLS 与本地验证

Caddyfile 放在 `frontend/deploy/Caddyfile`（位于前端构建上下文内），使用环境变量：

```caddyfile
{
	http_port 8080
	https_port 8443
}

{$SITE_ADDRESS:pulseops.test} {
	tls {$TLS_ISSUER:internal}

	encode zstd gzip

	@backend path /api/* /actuator/health /actuator/health/*
	handle @backend {
		reverse_proxy backend:8080
	}

	handle {
		root * /srv
		try_files {path} /index.html
		file_server
	}
}
```

使用互斥的 `handle` 块，避免 `try_files` 的 SPA 重写先于 `reverse_proxy` 生效，导致 `/api/*` 被回退到 `index.html`。

- 5A：`SITE_ADDRESS=pulseops.test`、`TLS_ISSUER=internal`；Windows 宿主 hosts 指向 VM IP，导入 Caddy 根 CA 到用户证书库，浏览器访问 `https://pulseops.test`。
- 5B：改 `SITE_ADDRESS=<真实域名>`、`TLS_ISSUER=<ACME 邮箱>`，Caddyfile 不变。
- HTTP 80 自动跳转 HTTPS。
- Caddy 根证书从 `pulseops-caddy-data` 卷导出：`/data/caddy/pki/authorities/local/root.crt`。
- Caddy 仅开启错误日志（默认 stderr）；不开启访问日志，避免每分钟健康检查刷屏。

## 代理信任与真实客户端 IP

- backend 配置：
  - `server.forward-headers-strategy=native`
  - `server.tomcat.remoteip.internal-proxies=172\.28\.0\.\d{1,3}`
  - 默认 `x-forwarded-for` / `x-forwarded-proto` 头名不变。
- 只信任 `edge` 子网内的代理；backend 未发布宿主端口，形成纵深防御。
- 登录限流与认证日志改为使用经代理还原后的客户端地址。
- 心跳上报的 HTTPS 判断继续接受 Caddy 设置的 `X-Forwarded-Proto: https`。
- 测试要求：
  - 受信任来源的 `X-Forwarded-For` 决定限流键。
  - 非受信任来源伪造 `X-Forwarded-For` 不影响限流键。
  - 现有认证、CSRF、心跳测试全部保持通过。
- `docs/specs/auth.md` 中"反向代理头信任规则留待 Phase 5"改为本节的最终规则。

## 日志

- 应用与 Caddy 不写宿主日志文件；全部输出到容器 stdout/stderr。
- Docker `json-file` 驱动统一 `max-size=10m`、`max-file=5`，单服务最多约 50 MB。
- Caddy 只记录错误与启动信息；故障排查需要访问日志时临时开启，不修改默认配置。
- systemd timer 与脚本输出进入 journald，`journalctl -u pulseops-backup` 查看。
- 不引入 logrotate。

## 备份、加密与异地

### 调度

- `/etc/systemd/system/pulseops-backup.service`：`Type=oneshot`，`EnvironmentFile=/etc/pulseops/pulseops.env`，`WorkingDirectory=/opt/pulseops`，`ExecStart=/opt/pulseops/scripts/backup.sh`。
- `/etc/systemd/system/pulseops-backup.timer`：`OnCalendar=*-*-* 03:17:00 UTC`，`Persistent=true`。
- 失败单元 `pulseops-backup-failure.service` 由 `OnFailure=` 触发，ping Healthchecks `/fail` URL。
- Compose 相关脚本通过 `COMPOSE_FILE=/opt/pulseops/compose.prod.yaml` 与 `COMPOSE_PROJECT_NAME=pulseops` 保证连接生产库。

### 流程

1. `backup.sh` 生成未加密 dump，写入 `BACKUP_DIR=/var/backups/pulseops`，做 `pg_restore --list` 自检，本地保留 7 份（沿用现有逻辑）。
2. 使用 `age -r <公钥>` 加密为 `.dump.age`。
3. `rclone copyto` 上传到对象存储的 `pulseops/db/` 前缀；异地保留 14 份。
4. 上传后校验远端对象存在且大小一致。
5. 成功 ping Healthchecks 成功 URL；任一步失败以非零退出，触发 `OnFailure`。

### 密钥与配置

- `age` 私钥：`/etc/pulseops/age.key`，600，属主部署账号；离线备份一份，不进入 Git。
- rclone 配置：`/etc/pulseops/rclone.conf`，600；remote 名称与路径由环境变量提供。
- 5A 使用 rclone 本地 remote（目录位于 VM 内另一路径）验证加密、上传、保留与恢复全链路；5B 换成真实桶，脚本不变。

### 恢复演练

- 从异地副本拉取最新的 `.dump.age`，`age -d` 解密。
- 用 `restore.sh` 恢复到独立库 `pulseops_restore`。
- 在恢复库上启动应用（关闭调度），校验 Flyway 历史、核心表行数与抽样数据、`/actuator/health` 与管理 API。
- 演练证据写入 `quality/phase5a-acceptance.md`，并明确"异地副本恢复"与 4B 本地演练的区别。

## 外部健康检查

- 外部服务探测 `https://<域名>/actuator/health`；该端点公开并反映数据库健康。
- 5A 输出选型对比与预案，不实际注册公网服务：
  - 可用性监控候选：UptimeRobot、Better Stack；关注免费档探测间隔与告警渠道。
  - 备份失效监控：Healthchecks.io 死信检查（与备份 ping 配套）。
- 告警接收沿用 Telegram；明确"PulseOps 自身宕机时告警链路不依赖 PulseOps"。
- 5B 实际接入并验证探测与告警。

## 部署与回滚

### deploy.sh

1. 预检：`compose.prod.yaml` 可解析、环境文件存在且权限为 600、Docker 可用、磁盘余量、当前健康状态。
2. 记录当前 `IMAGE_TAG`（写入 `/opt/pulseops/.deployed-tag`）并执行一次备份。
3. `docker compose -f compose.prod.yaml build`，镜像打上新 SHA tag。
4. `docker compose -f compose.prod.yaml up -d`，等待全部服务健康，超时 120 秒。
5. 冒烟：`/` 返回 200、匿名 `/api/v1/monitors` 返回 401、`/actuator/health` 返回 UP。
6. 更新 `.deployed-tag`；清理超过 5 个的旧镜像 tag 与 build cache。
7. 任一步失败：非零退出，打印明确的 `rollback.sh <previous-tag>` 命令；不自动回滚。

### rollback.sh

- 参数：目标 tag；默认取上一条记录。
- 校验镜像存在 → 设置 `IMAGE_TAG` → `up -d` → 健康检查。
- 若该版本与当前 schema 不兼容，脚本拒绝继续并提示按"从部署前备份恢复"流程处理。
- `--dry-run` 只打印将执行的命令与判定结果。

### 迁移与恢复边界

- Flyway 只前向迁移，不提供 down 迁移。
- 镜像回滚不撤销迁移；部署前备份是 schema 不兼容时唯一的恢复路径。
- 备份恢复流程：恢复到新库 → 校验 → 将 `DB_URL` 指向新库 → 启动旧镜像 → 冒烟。文档必须写明该流程及其不可逆点。

## 防火墙与 SSH

`scripts/harden-vps.sh` 在 VM 与 VPS 上共用，幂等并支持 `--dry-run`。

- ufw：默认拒绝入站、允许出站；`limit 22/tcp`；允许 80/443；按顺序先放行 22 再启用。
- sshd：`/etc/ssh/sshd_config.d/99-pulseops-hardening.conf` 设置仅密钥登录、禁用密码与交互式认证、`PermitRootLogin prohibit-password`、`MaxAuthTries 3`。
- 应用前执行 `sshd -t`，通过后才 `systemctl reload ssh`。
- 执行提示：保留当前会话，另开一个会话验证登录成功后才断开旧会话。
- 5A 在 VM 中演练幂等性与规则生效；锁死时用加固前快照恢复。
- 5B 才在真实 VPS 上执行。
- 注意：Docker 发布端口会绕过 ufw 链。本项目只发布 80/443，backend 与 postgres 不发布端口；验收必须从外部验证 5432/8080 不可达。

## 本地 VM 环境

- 安装与基础配置见 [`phase5a-vm-setup.md`](phase5a-vm-setup.md)。
- 规格对齐目标 VPS：2 vCPU / 4 GB / 40 GB，Ubuntu 24.04 LTS。
- VM 内安装 Docker Engine + Compose v2、ufw、age、rclone；配置 Docker Hub 镜像源。
- 基础配置完成后打快照 `base`。
- 所有 5A 验收命令在 VM 内执行，证据来自 VM，而非 Windows 宿主。

## CI 校验

- 新增部署产物 job，带路径过滤：
  - PR 触发条件：`compose.prod.yaml`、`deploy/**`、`frontend/deploy/**`、`backend/Dockerfile`、`frontend/Dockerfile`、`scripts/**`、`.github/workflows/**`。
  - 步骤：`docker compose -f compose.prod.yaml config -q`；`caddy validate`；不构建镜像。
  - master：在上述基础上构建 backend 与 frontend 镜像（`push: false`），使用构建缓存。
- 不在 CI 中推送镜像、不引入镜像仓库凭证。
- 5A 完成证据引用 master 的 CI 运行链接。

## 计划新增/修改文件

| 文件 | 用途 |
|---|---|
| `compose.prod.yaml` | 生产三服务编排 |
| `frontend/deploy/Caddyfile` | 静态托管与反代 |
| `deploy/env.prod.example` | 生产环境变量占位 |
| `deploy/systemd/pulseops-backup.service` / `.timer` / `-failure.service` | 备份调度与失败告警模板 |
| `backend/Dockerfile`、`backend/.dockerignore` | 后端镜像 |
| `frontend/Dockerfile`、`frontend/.dockerignore` | 前端 + Caddy 镜像 |
| `scripts/provision-vm.sh` | VM/VPS 共用基础配置 |
| `scripts/deploy.sh`、`scripts/rollback.sh` | 部署与回滚 |
| `scripts/backup.sh`（扩展） | 加密、上传、ping |
| `scripts/harden-vps.sh` | 防火墙与 SSH 加固 |
| `docs/adr/0010-*.md` | 生产拓扑与可信代理边界 |
| `docs/quality/phase5a-acceptance.md` | 验收证据 |
| `docs/quality/deployment.md`、`docs/specs/auth.md`、`docs/index.md`、`README.md`、`docs/product/roadmap.md` | 规范与状态更新 |

## 测试与验收计划

### 自动化

- 后端：代理头信任与限流键的集成测试；现有认证、CSRF、心跳、清理测试保持通过。
- 前端：现有 Vitest 与构建通过。
- 配置：CI 中 `compose config` 与 `caddy validate` 通过。
- 最终运行一次后端 `./mvnw verify` 与前端 `npm test && npm run build`。

### VM 内手工验收

1. `docker compose -f compose.prod.yaml up -d` 后三个服务全部 healthy。
2. 浏览器通过 `https://pulseops.test` 访问 UI，登录、创建监控、查看历史可用；HTTP 自动跳转 HTTPS。
3. `docker inspect` 确认日志驱动与轮转参数；服务日志只出现在 `docker compose logs`。
4. 手动触发 `pulseops-backup.timer`：本地 dump、自检、加密、rclone 上传、Healthchecks ping 全部成功；`OnFailure` 路径用一次故意失败验证。
5. 从异地加密副本恢复 `pulseops_restore`，用恢复库启动应用并访问健康检查与管理 API。
6. `deploy.sh` 与 `rollback.sh` dry-run；执行一次真实镜像回退并验证健康。
7. `harden-vps.sh --dry-run` 输出正确；在 VM 上实际执行后，从 Windows 宿主验证仅 22/80/443 可达，5432/8080 不可达；第二个 SSH 会话验证后再断开。
8. 重启 VM 后容器、timer 与快照恢复路径符合预期。
9. 用 `docker stats` 或 `docker inspect` 核对资源限制生效。

### 证据与限制

- 证据写入 `quality/phase5a-acceptance.md`，包含命令、关键输出、时间与 VM 规格。
- 必须记录的限制：内部 CA 不被外部监控信任；VM IP 动态；5A 未接入真实域名、真实对象存储与真实外部监控；未做 7 天连续运行。

## 被否决方案

| 方案 | 否决原因 |
|---|---|
| 后端托管前端静态文件 | 与"Caddy 作为反向代理和 HTTPS 入口"的架构约束不一致；前后端同源由 Caddy 统一处理更简单 |
| 独立 nginx 容器 | 增加一个组件，Caddy 已能同时完成 TLS 与静态托管 |
| CI 构建并推送镜像到 GHCR | 单人单机项目增加 registry 凭证与网络依赖；目标机构建 + SHA tag 已满足回滚 |
| 单 Compose 文件 + profiles | 开发与生产的服务集合、端口、重启策略差异大，分文件更不易误操作 |
| WSL2 作为演练环境 | ufw/sshd 行为不真实，无法验证端口暴露与锁死风险 |
| 宿主 cron | 失败可观测性弱；systemd timer 支持补跑与 `OnFailure` |
| restic / borg 替代现有脚本 | 与现有已验证脚本重复；age + rclone 已满足加密、异地与恢复要求 |
| GPG / OpenSSL 对称加密 | 密钥分发与轮换更麻烦；age 更简单且足够 |
| 新增专用健康端点 | `/actuator/health` 已公开且包含数据库健康，外部监控正需要该语义 |
| 健康检查失败自动回滚 | 前向迁移下自动回滚可能让旧镜像运行在新 schema 上，制造数据损坏 |
| 为每个迁移编写 down 脚本 | 维护成本高且容易与真实数据状态不一致；备份恢复是更可靠的兜底 |
| 修改 SSH 端口 / fail2ban / deploy 用户 | 单管理员场景收益有限、锁死风险更高；deploy 用户加入 docker 组后与 root 等价 |
| 开启 Caddy 访问日志 | 健康检查每分钟产生噪声；需要时临时开启 |
| 应用与 Caddy 写宿主日志文件 + logrotate | 多一套轮转边界；Docker 驱动统一管理更易验证 |

## 风险与限制

- 官方 Caddy/Postgres 镜像的非 root 与卷权限需要在 VM 内实测；若冲突，需增加初始化步骤，但不得放弃非 root 目标。
- Docker 发布端口会绕过 ufw；本项目只发布 80/443，验收必须从外部确认其他端口不可达。
- Multipass VM 的 IP 由 DHCP 分配，重启可能变化；hosts 记录与浏览器验证需重跑。
- 5A 的 Caddy 根 CA 只用于本地信任，不代表公网证书流程已验证。
- Docker Hub 镜像源可能缺失个别镜像；必要时手工导入或在 5A 记录限制。
- 登录限流按真实 IP 计数后，同一 NAT 后的多个客户端共享配额；单管理员场景可接受，需写入文档。
- 备份加密私钥丢失会导致异地备份不可恢复；离线副本是唯一保障。

## Phase 5B 衔接

- 在 VPS 上执行 `harden-vps.sh` 与 `provision-vm.sh`。
- 真实域名 DNS、ACME 签发与公网 HTTPS 验证。
- 接入真实对象存储与外部监控，验证探测与 Telegram 告警。
- 公网部署验收：重启恢复、备份恢复、端口暴露检查。
- 连续运行 7 天且无未解决的严重故障。
