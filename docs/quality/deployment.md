# 部署与运维规范

## 目标环境
- 单台 Linux VPS。
- 建议起步配置：2 vCPU、4 GB RAM、40 GB 以上 SSD。
- Docker Compose。
- Spring Boot + PostgreSQL + Caddy。
- 域名和 HTTPS 用于正式公网部署。

预算目标为全部基础设施尽量低于 S$50/月。采购前需核实机房、税费、续费、流量和备份价格。

## 生产拓扑与 Compose

生产编排为 [`compose.prod.yaml`](../../compose.prod.yaml)，设计决策与取舍见 [ADR-0010](../adr/0010-production-topology-and-trusted-proxy.md)。

```text
浏览器 / 外部监控
    │ HTTPS 443（5A: pulseops.test + 内部 CA；5B: 真实域名 + ACME）
    ▼
宿主 :80 :443 ──► caddy（非 root，容器内 8080/8443）
                    ├── /                  → 静态 dist（SPA fallback）
                    ├── /api/*             → backend:8080
                    └── /actuator/health*  → backend:8080

edge 172.28.0.0/24 ── caddy, backend
data 172.28.1.0/24 ── backend, postgres（无宿主端口）
```

- 三个服务：`caddy`、`backend`、`postgres`；项目名固定 `pulseops`，全部 `restart: unless-stopped`。
- 宿主只发布 80/443；backend 与 postgres 不发布宿主端口。Caddy 是唯一对外入口，不暴露 `/actuator/metrics`。
- 网络：`edge` 承载 Caddy→backend；`data` 承载 backend→postgres；postgres 不加入 `edge`。
- 资源限制：postgres 1 GB / 1 CPU / 256 pids；backend 768 MB / 1 CPU / 512 pids；Caddy 128 MB / 0.5 CPU / 128 pids。
- 容器加固：全部 `no-new-privileges`；backend 使用只读根文件系统、`tmpfs /tmp` 与 `cap_drop: [ALL]`；backend 与 Caddy 以非 root 运行（uid 10001 / 10002）。
- 卷：`pulseops-postgres-data`、`pulseops-caddy-data`、`pulseops-caddy-config`。
- 环境变量来自 `/etc/pulseops/pulseops.env`（600），模板见 [`deploy/env.prod.example`](../../deploy/env.prod.example)；`IMAGE_TAG` 使用 Git short SHA。
- CI 在 PR 中仅当部署相关文件变更时运行 `docker compose config` 与 `caddy validate`；master 每次 push 都运行校验并构建后端与 Caddy 镜像，但不推送（[`deploy.yml`](../../.github/workflows/deploy.yml)）。

## 网络边界
- 仅公开 80/443 与受控的 SSH 22；宿主端口映射见「生产拓扑与 Compose」。
- PostgreSQL 不直接暴露到公网，且只在 `data` 网络内可达。
- 使用独立且权限受限的数据库账号。
- 生产环境启用 HTTPS。
- 心跳令牌不得通过不安全的公开 HTTP 传输。
- 反向代理头只在直接对端来自 `edge` 子网时可信；客户端地址与心跳 HTTPS 判定规则见 [`../specs/auth.md#可信代理与客户端地址`](../specs/auth.md#可信代理与客户端地址)。

## 公网发布硬性门槛

- 任何管理入口（管理 API 或管理 UI）公网可达前，必须完成 Phase 4B 的单用户认证与授权。
- 反向代理（如 Caddy）只负责 TLS 终止、入口暴露和必要的网络控制，不视为唯一授权层；应用必须自行拒绝未认证请求并返回 401/403。
- 认证未启用时，不得将后端或管理入口公开监听或暴露到公网。
- Phase 4B（单用户认证、历史清理、备份恢复）已完成，验收证据见 [`phase4b-acceptance.md`](phase4b-acceptance.md)。完成 4B 不等于可以公网发布：Phase 5 的 TLS、反向代理、防火墙与日志轮转完成前，必须保持回环监听。

## 本地 HTTPS 演练（Phase 5A）

仅用于本地 VM 验证，不代表公网证书流程。证书导出与 HTTPS 服务证据在 VM 内取得；宿主信任库导入与浏览器验证在 Windows 侧完成。

1. 启动生产栈，确认 `SITE_ADDRESS=pulseops.test`、`TLS_ISSUER=internal`。
2. 在 VM 内导出 Caddy 内部根证书（公开文件，私钥始终留在 `pulseops-caddy-data` 卷内）：

   ```bash
   COMPOSE_ENV_FILE=/etc/pulseops/pulseops.env \
     ./scripts/export-caddy-root-ca.sh /home/ubuntu/caddy-root.crt
   ```

   脚本会把相对输出路径解析到仓库根目录；建议使用绝对路径。

3. 把证书复制到 Windows。例如证书位于 VM 家目录时：

   ```powershell
   multipass transfer pulseops-vps:/home/ubuntu/caddy-root.crt .
   ```

4. 把 VM IP 写入 Windows hosts，见 [`../plans/phase5a-vm-setup.md`](../plans/phase5a-vm-setup.md) 步骤 5。
5. 在当前用户信任库导入（无需管理员）：

   ```powershell
   certutil -user -addstore Root .\caddy-root.crt
   certutil -user -store Root | findstr /i "Caddy"
   ```

   Chrome/Edge 使用 Windows 证书库；Firefox 需启用 `security.enterprise_roots.enabled` 或单独导入。重复导入前先用第 7 步移除旧 Caddy 根证书，避免信任库残留多个同名根。

6. 访问 `https://pulseops.test`，确认 HTTP 80 返回 308 跳转到 HTTPS、登录与页面操作正常，且会话 Cookie 带 `Secure`。
7. 不再需要时移除（CN 中的年份随生成时间变化，以第一条命令输出为准）：

   ```powershell
   certutil -user -store Root | findstr /i "Caddy"
   certutil -user -delstore Root "Caddy Local Authority - 2026 ECC Root"
   ```

8. 切换到真实域名只改环境变量，不修改 Caddyfile：
   - `SITE_ADDRESS=<真实域名>`
   - `TLS_ISSUER=<ACME 邮箱>`

   确保 DNS 指向目标机器、80/443 可达；Caddy 会自动申请并续期证书。

## 防火墙与 SSH 加固

在 VM/VPS 内执行；禁止在 Windows 宿主或 WSL2 中运行 `harden-vps.sh`。

```bash
sudo ./scripts/harden-vps.sh --dry-run
sudo ./scripts/harden-vps.sh
```

- 脚本先 `ufw --force reset`，清空已有规则后只保留 22/80/443（22 使用 limit）；不要在有其他 ufw 规则的主机上运行。
- 默认拒绝入站、允许出站。当前未实现出站目标白名单：应用需要访问任意公网监控目标、DNS 与 Telegram，用户可控 URL 由应用层 SSRF 防护约束；这是 [`../architecture/security.md`](../architecture/security.md) 网络隔离要求的已知缺口。
- sshd 写入 `/etc/ssh/sshd_config.d/99-pulseops-hardening.conf`：仅密钥登录、禁用密码与交互式认证、root 仅密钥登录、`MaxAuthTries 3`；`sshd -t` 通过后才 reload，并断言 `sshd -T` 的实际值；前置 drop-in 覆盖设置时脚本失败退出。
- 执行时保留当前会话，另开一个 SSH 会话确认密钥登录成功后再断开旧会话。
- 加固前创建快照（Multipass 需先停止实例）：

  ```bash
  multipass stop <instance>
  multipass snapshot <instance> --name pre-harden
  multipass start <instance>
  ```

  恢复：先停止实例，再 `multipass restore <instance>.<snapshot>`；5B 使用 VPS 控制台或提供商快照。
- 从外部验证仅 22/80/443 可达，5432/8080 不可达。Docker 发布端口会绕过 ufw，因此生产只发布 80/443。

## 配置管理
- 真实凭证通过环境变量或安全部署配置提供。
- `.env.example` 只能包含占位值。
- 生产环境配置不得提交到 Git。
- 容器尽量以非 root 用户运行。
- 配置合理的重启策略、资源限制和日志轮转。

### 管理员密码重置

忘记密码时使用已记录的哈希生成命令（见 [`../specs/auth.md`](../specs/auth.md#密码哈希生成)），然后直接更新账号：

```bash
docker compose exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
UPDATE app_user
SET password_hash = '{bcrypt}$2y$12$REPLACE_WITH_GENERATED_HASH',
    updated_at = now()
WHERE username = 'admin';
SQL
```

替换哈希与用户名后执行，并重启应用使内存中的现有会话失效。不要通过重新设置引导变量覆盖已有账号。

## 镜像构建与构建上下文

- backend、frontend 镜像在目标机本地构建（`docker compose -f compose.prod.yaml build`），构建上下文分别是 `backend/` 与 `frontend/`。
- 向目标机传输源码时，打包排除规则必须**只匹配上下文根目录下的产物**（如 `backend/target`、`frontend/node_modules`、`frontend/dist`）。Windows 自带的 `tar.exe`（bsdtar）中 `*` 可跨 `/` 匹配，`--exclude "*/target"` 会连同源码包 `backend/src/main/java/dev/pulseops/monitor/target/` 一起删掉，构建时报 `package dev.pulseops.monitor.target does not exist`。
- `.dockerignore` 的模式只相对构建上下文根匹配，裸 `target/` **不会**误删嵌套的同名源码目录；无需为此加前导斜杠，也不要使用 `!` 反向放行（目录被排除后其内容通常无法重新包含）。
- 构建报 `package ... does not exist` 时，先在部署目录确认源码是否存在，再检查 `.dockerignore`；源码未送达时修改忽略规则无效。
- 逐步部署命令见 [`../plans/phase5a-deploy-runbook.md`](../plans/phase5a-deploy-runbook.md)。

## 日志

- 应用与 Caddy 只写 stdout/stderr，不使用宿主日志文件，也不引入 logrotate。
- Docker `json-file` 驱动统一 `max-size=10m`、`max-file=5`，单服务最多约 50 MB。查看：`docker compose -f compose.prod.yaml logs -f --tail=200`。
- Caddy 不开启访问日志；stderr 只包含 Caddy 的运行与错误日志。健康检查会产生持续噪声，排障时临时开启访问日志即可。
- 备份 systemd 单元与脚本输出进入 journald：`journalctl -u pulseops-backup.service -n 50 --no-pager`。
- 日志不得包含密钥、令牌、会话或外部服务凭据；认证日志字段见 [`../specs/auth.md#日志与敏感信息`](../specs/auth.md#日志与敏感信息)。

## 备份与恢复

备份包含 `app_user.password_hash` 与 `heartbeat_credential.token_hash` 等敏感数据，文件必须只允许部署账号读取。

### 执行备份

宿主机不需要安装 PostgreSQL 客户端。`scripts/backup.sh` 通过 `docker compose exec -T` 在 PostgreSQL 容器内执行 `pg_dump`：

```bash
./scripts/backup.sh
```

| 变量 | 默认值 | 说明 |
|---|---|---|
| `BACKUP_DIR` | `./backups` | 备份目录；不存在时创建并设为仅属主可访问 |
| `BACKUP_RETENTION_COUNT` | `7` | 本地每个数据库保留的份数，超出后删除最旧文件 |
| `BACKUP_OFFSITE_RETENTION_COUNT` | `14` | 每个数据库在异地保留的加密备份份数 |
| `COMPOSE_SERVICE` | `postgres` | 运行 PostgreSQL 的 Compose 服务名 |
| `AGE_RECIPIENT` | 空 | age 公钥；与 remote/path 同时设置才启用异地链路 |
| `BACKUP_RCLONE_REMOTE` | 空 | rclone remote 名称 |
| `BACKUP_RCLONE_PATH` | 空 | 远端前缀，例如 `pulseops/db` |
| `RCLONE_CONFIG` | `/etc/pulseops/rclone.conf` | rclone 配置路径（600） |
| `HEALTHCHECKS_PING_URL` | 空 | 成功 ping URL；留空则跳过 |

- 文件名为 `<数据库名>-<UTC 时间戳>.dump`，格式为 `pg_dump -Fc`。
- 备份先写入临时文件，`pg_restore --list` 自检通过后才改名为正式文件；失败不会留下半成品。
- 脚本使用 `umask 077` 并显式 `chmod 600`，在 Linux 上备份目录与文件仅属主可访问。
- 备份文件包含密码哈希与心跳令牌摘要，不得提交到 Git 或通过聊天工具传输；`.gitignore` 已忽略 `backups/`。
- 未配置 `AGE_RECIPIENT`/`BACKUP_RCLONE_REMOTE`/`BACKUP_RCLONE_PATH` 时仅执行本地备份。三者齐全时，脚本加密上传，校验远端对象大小后删除本地 `.dump.age`。
- 日志只包含文件名、大小与保留数量，不包含 age 密钥、rclone 凭据、Healthchecks URL 或备份内容。
- 异地链路使用 `rclone --contimeout 10s --timeout 5m`；Healthchecks 成功 ping 最多重试 3 次。
- systemd 单元设置 `UMask=0077`、`NoNewPrivileges=true`、`TimeoutStartSec=30min`，并排在 `docker.service` 之后启动。

### 自动调度与异地

1. 准备密钥与配置，权限均为 600：

   ```bash
   sudo age-keygen -o /etc/pulseops/age.key
   sudo chmod 600 /etc/pulseops/age.key
   sudo install -m 600 /dev/null /etc/pulseops/rclone.conf
   ```

   把 `age-keygen` 输出的公钥填入 `/etc/pulseops/pulseops.env` 的 `AGE_RECIPIENT`。5A 可用本地 rclone remote：

   ```ini
   [local]
   type = local
   ```

   并在环境文件设置 `BACKUP_RCLONE_REMOTE=local`、`BACKUP_RCLONE_PATH=/var/backups/pulseops-offsite/pulseops/db`。模板中的 `BACKUP_RCLONE_PATH` 是占位符：5A 用上述绝对本地路径，5B 改为对象存储前缀（如 `pulseops/db`）。5A 可用本地 HTTP 端点模拟 Healthchecks 成功/失败 ping，5B 换成真实 URL。
   环境文件还必须包含 `COMPOSE_FILE=/opt/pulseops/compose.prod.yaml` 与 `COMPOSE_PROJECT_NAME=pulseops`，systemd 单元才能找到生产编排。
   备份服务默认以 root 运行，因此迁移与恢复操作需使用 sudo；密钥与配置仍保持 600。异地链路依赖 `age`、`rclone`、`jq`；`provision-vm.sh` 已安装前两者与 `jq`。
   环境文件与 systemd 单元必须使用 LF 行尾；从 Windows 传输后可用 `sed -i 's/\r$//' /etc/pulseops/pulseops.env` 修正（仓库已固定这些文件为 LF）。

2. 安装并启用 systemd 单元：

   ```bash
   sudo install -m 0644 deploy/systemd/pulseops-backup.service /etc/systemd/system/
   sudo install -m 0644 deploy/systemd/pulseops-backup.timer /etc/systemd/system/
   sudo install -m 0644 deploy/systemd/pulseops-backup-failure.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now pulseops-backup.timer
   systemctl list-timers pulseops-backup.timer
   ```

   timer 使用 `OnCalendar=*-*-* 03:17:00 UTC` 与 `Persistent=true`；手动触发：

   ```bash
   sudo systemctl start pulseops-backup.service
   journalctl -u pulseops-backup.service -n 50 --no-pager
   ```

3. 流程：本地 dump 与自检 → 保留 7 份 → age 加密 → rclone `copyto` → 校验远端对象存在且大小一致 → 异地保留 14 份 → ping Healthchecks 成功 URL。
4. 任一步失败时服务以非零退出，`OnFailure=pulseops-backup-failure.service` 会 ping `${HEALTHCHECKS_PING_URL%/}/fail`。
5. age 私钥与 rclone 配置权限必须为 600，并且不进入 Git；私钥需离线备份一份。

### 恢复与演练

```bash
./scripts/restore.sh ./backups/pulseops-<timestamp>.dump pulseops_restore
```

- 目标库不存在时创建并恢复；已存在时脚本拒绝执行，不会静默覆盖。
- 确需覆盖时使用 `--force`，脚本会先执行 `DROP DATABASE ... WITH (FORCE)`，该操作不可撤销。
- 脚本始终拒绝恢复进 Compose 服务的源库（`POSTGRES_DB`）以及 `postgres`/`template0`/`template1` 维护库。
- 恢复使用 `pg_restore --no-owner --no-privileges --exit-on-error`；任一对象失败即停止并删除未完成的恢复库，不留下半成品。
- 推荐恢复到独立库并在其上启动应用验证，不要直接覆盖生产库。

从异地加密副本恢复：

```bash
sudo rclone --config /etc/pulseops/rclone.conf \
  copyto "<BACKUP_RCLONE_REMOTE>:<BACKUP_RCLONE_PATH>/<file>.dump.age" /tmp/backup.dump.age
sudo age --decrypt --identity /etc/pulseops/age.key \
  --output /tmp/backup.dump /tmp/backup.dump.age
sudo ./scripts/restore.sh /tmp/backup.dump pulseops_restore
```

`age.key` 权限必须为 600；`BACKUP_RCLONE_REMOTE` 与 `BACKUP_RCLONE_PATH` 使用环境文件中的值。

恢复演练至少覆盖：

1. 备份生成并通过 `pg_restore --list` 自检。
2. 恢复到独立库后，Flyway 历史版本与校验和、核心表行数与抽样数据均与源库一致。
3. 用恢复库启动应用，健康检查与管理 API 可用。

4B 本地演练证据见 [`phase4b-acceptance.md`](phase4b-acceptance.md)；5A 异地加密副本恢复证据见 [`phase5a-acceptance.md`](phase5a-acceptance.md)。仅确认备份文件存在，不代表恢复能力已验证。

## 发布与回滚

部署与回滚脚本在 VM/VPS 内以 root 运行，读取 `/etc/pulseops/pulseops.env`（600）。

- 环境文件必须权限 600 且使用 LF 行尾；CRLF 会导致解析失败。
- 可用环境变量：`PULSEOPS_ENV_FILE`（默认 `/etc/pulseops/pulseops.env`）、`PULSEOPS_STATE_DIR`（部署状态目录，默认仓库根）、`DEPLOY_TAG`（`--tag` 缺省时使用）、`DEPLOY_HEALTH_TIMEOUT_SECONDS` / `ROLLBACK_HEALTH_TIMEOUT_SECONDS`（健康门禁超时，默认 120 秒）。
- 磁盘余量低于 2 GiB 时预检失败并中止。

### 冒烟测试

`scripts/smoke-test.sh` 是部署、回滚与验收共用的独立冒烟脚本：

- `/` 返回 200。
- 匿名 `/api/v1/monitors` 返回 401。
- `/actuator/health` 返回 200 且状态为 `UP`。

5A 本地内部 CA 示例：

```bash
./scripts/smoke-test.sh --url https://pulseops.test \
  --resolve pulseops.test:443:127.0.0.1 --insecure
```

5B 使用真实域名与证书时去掉 `--resolve` 与 `--insecure`。

### 部署

```bash
sudo ./scripts/deploy.sh --dry-run --tag <git-short-sha>
sudo ./scripts/deploy.sh --tag <git-short-sha>
```

- 预检：env 文件权限、`compose config`、磁盘余量与当前服务健康状态；当前栈不健康时只警告并继续。
- 自动执行 `scripts/backup.sh`；备份失败时在构建前中止。
- 记录当前 tag 的 schema rank，然后使用新 tag 构建并启动，等待三个服务 healthy（默认 120 秒），再运行冒烟脚本。
- 健康门禁或冒烟失败时不自动回滚，打印 `rollback.sh <previous-tag>` 命令并非零退出。
- 成功后写入 `.deployed-tag`、`.previous-tag` 与 `.deployed-tags/<tag>.schema`。
- 清理每个镜像超过 5 个的旧 tag，并执行 `docker builder prune` 清理构建缓存。

### 回滚

```bash
sudo ./scripts/rollback.sh --dry-run [<tag>]
sudo ./scripts/rollback.sh [<tag>]
```

- 未指定 tag 时使用 `.previous-tag`；校验 `pulseops-backend` 与 `pulseops-caddy` 两个镜像存在，不重新构建。
- 切换 `IMAGE_TAG` 并启动，等待 healthy 与冒烟通过后更新部署状态。
- 目标 tag 的 schema rank 低于当前数据库时拒绝执行；缺少 schema 元数据时同样拒绝，先核对迁移并写入 `.deployed-tags/<tag>.schema` 后再重试。
- 健康门禁或冒烟失败时非零退出，不继续自动操作。

发布后仍应检查日志与资源占用，并确认外部监控能够探测服务（5B）。

### Schema 不兼容时的恢复流程

Flyway 只做前向迁移，镜像回滚不会撤销迁移。回滚脚本拒绝旧 schema 时：

1. 用部署前备份（`/var/backups/pulseops/*.dump` 或异地 `.dump.age`）通过 `scripts/restore.sh` 恢复到新数据库。
2. 校验 Flyway 历史、核心表行数与抽样数据。
3. 将 `/etc/pulseops/pulseops.env` 的 `DB_URL` 指向恢复库（Compose 会将该值注入 backend）。
4. 重新运行 `rollback.sh <tag>` 并完成健康检查与冒烟。

恢复库切换 `DB_URL` 是不可逆操作；切换前保留原库与备份。

## 独立外部监控

PulseOps 不应只依靠自己监控自己。VPS、数据库或 PulseOps 整体宕机时，告警链路不得经过 PulseOps。Phase 5A 完成选型与预案，不注册公网服务；Phase 5B 注册并验证。

### 免费档对比（2026-10-28 核对）

| 服务 | 免费额度与探测 | 免费告警渠道 | 结论 |
|---|---|---|---|
| UptimeRobot | 50 monitors，最小 5 分钟 | 邮件/推送；Telegram、Slack、Teams 属于付费集成 | 免费档不满足 Telegram 要求 |
| Better Stack | 10 monitors/heartbeats，最小 3 分钟 | 邮件 + Slack；支持 Telegram 集成，但免费档是否包含需注册时确认 | 推荐可用性监控；Telegram 免费资格需复核 |
| Healthchecks.io Hobbyist | 20 checks，每 check 100 log entries；被动 ping，period/grace 可配置（备份用 1 天 + 2 小时 grace） | 邮件、Telegram（@HealthchecksBot）、Slack 等 | 推荐备份死信检查 |

官方参考：[UptimeRobot pricing](https://uptimerobot.com/pricing/)、[Better Stack pricing](https://betterstack.com/pricing)、[Healthchecks.io pricing](https://healthchecks.io/pricing/)、[Healthchecks Telegram 集成](https://healthchecks.io/integrations/telegram/)。价格、额度与免费档集成范围会变化，具体以注册页为准；5B 注册前必须复核。

### 推荐方案

- **可用性监控：Better Stack 免费档。** 使用 3 分钟 HTTPS 探测；告警先配置邮件 + Slack。若注册时确认 Telegram 属于免费档，则增加 Telegram；否则使用邮件/Slack 作为可用性告警，Telegram 仅用于备份死信。若必须让所有告警只走 Telegram 且不接受付费，免费方案无法同时满足，需要评估 UptimeRobot Solo 等付费档。
- **备份死信：Healthchecks.io Hobbyist。** 备份成功后 ping 唯一 URL；超过 period + grace 未收到 ping 即告警。配置 Telegram（@HealthchecksBot）并保留邮件兜底；备份失败由 `OnFailure` 立即 ping `/fail`。
- **Telegram 告警接收人：** 单管理员与 @HealthchecksBot 所在的专用群 `PulseOps Alerts`（仅管理员，Bot 只授予发消息权限）。备份死信固定通过该群告警；可用性服务若免费档支持 Telegram，也加入该群，否则可用性告警走邮件/Slack。外部服务使用自己的告警通道，不依赖 PulseOps 自己的 Bot Token；同时保留管理员邮箱作为兜底。不要在 Git 中保存 Bot Token、Chat ID 或 Healthchecks Ping URL。
- **探测配置：**
  - Better Stack：HTTPS GET `https://<真实域名>/actuator/health`，每 3 分钟，超时 10 秒，期望 200 且响应包含 `"status":"UP"`；开启证书校验；连续 2 次失败再告警；不发送认证头。
  - Healthchecks.io：check 名称 `pulseops-backup`，period 1 天、grace 2 小时、时区 UTC；`HEALTHCHECKS_PING_URL` 写入 `/etc/pulseops/pulseops.env`；失败单元使用同 URL 的 `/fail` 端点。
  - UptimeRobot 备选：5 分钟 HTTPS + keyword 探测，邮件/推送告警；Telegram 需要付费档。

### PulseOps 自身宕机预案

1. 健康端点 DOWN、数据库不可用或 VPS 整体离线时，由外部可用性服务通过其已启用的告警通道（Telegram 若免费档可用，否则邮件/Slack）发送；告警链路不经过 PulseOps。备份死信由 Healthchecks.io 通过 Telegram/邮件发送。
2. 管理员通过 VPS 提供商控制台/快照或重启恢复；数据损坏时按本文档[备份与恢复](#备份与恢复)流程处理，再运行部署/回滚脚本并用 `smoke-test.sh` 验证。
3. 备份未按时 ping 时由 Healthchecks.io 告警；若 VPS 离线导致备份未运行，该告警与可用性告警相互独立。
4. 若 Telegram 不可用，使用管理员邮箱兜底；若邮件也不可达，使用 VPS 提供商状态页/控制台。5A 为单管理员，不引入值班轮换。
5. 恢复后确认外部监控回到 UP，并在 Healthchecks.io 确认检查恢复。

### 5A 边界

- 5A 只验证 VM 内公开健康端点的可达性与响应内容，不注册外部服务；`pulseops.test` 使用内部 CA，外部监控不信任，不能作为公网探测目标。
- 5B 使用真实域名、ACME 证书，注册 Better Stack、Healthchecks.io 与 Telegram，故意制造健康失败/备份失败验证告警，并记录证据。
- 实际注册、探测定时与告警接收验证属于 Phase 5B。
