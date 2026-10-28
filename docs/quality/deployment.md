# 部署与运维规范

## 目标环境
- 单台 Linux VPS。
- 建议起步配置：2 vCPU、4 GB RAM、40 GB 以上 SSD。
- Docker Compose。
- Spring Boot + PostgreSQL + Caddy。
- 域名和 HTTPS 用于正式公网部署。

预算目标为全部基础设施尽量低于 S$50/月。采购前需核实机房、税费、续费、流量和备份价格。

## 网络边界
- 仅公开必要的 HTTP/HTTPS 入口和受控的 SSH 入口。
- PostgreSQL 不直接暴露到公网。
- 使用独立且权限受限的数据库账号。
- 生产环境启用 HTTPS。
- 心跳令牌不得通过不安全的公开 HTTP 传输。

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
发布前：
1. 运行测试和构建。
2. 检查迁移脚本。
3. 备份关键数据。
4. 检查环境变量和密钥。
5. 准备回滚方案。

发布后：
1. 检查应用健康状态。
2. 检查数据库连接和迁移状态。
3. 执行关键功能冒烟测试。
4. 检查日志和资源占用。
5. 确认外部监控能够探测服务。

## 独立外部监控
PulseOps 不应只依靠自己监控自己。应使用独立的外部服务检查 PulseOps 的健康端点，避免 VPS 宕机后告警系统同时失效。
