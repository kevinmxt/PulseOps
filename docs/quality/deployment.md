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
| `BACKUP_RETENTION_COUNT` | `7` | 每个数据库保留的份数，超出后删除最旧文件 |
| `COMPOSE_SERVICE` | `postgres` | 运行 PostgreSQL 的 Compose 服务名 |

- 文件名为 `<数据库名>-<UTC 时间戳>.dump`，格式为 `pg_dump -Fc`。
- 备份先写入临时文件，`pg_restore --list` 自检通过后才改名为正式文件；失败不会留下半成品。
- 脚本使用 `umask 077` 并显式 `chmod 600`，在 Linux 上备份目录与文件仅属主可访问。
- 备份文件包含密码哈希与心跳令牌摘要，不得提交到 Git 或通过聊天工具传输；`.gitignore` 已忽略 `backups/`。
- 定时调度、异地存放与加密属于 Phase 5A 部署准备任务。

### 恢复与演练

```bash
./scripts/restore.sh ./backups/pulseops-<timestamp>.dump pulseops_restore
```

- 目标库不存在时创建并恢复；已存在时脚本拒绝执行，不会静默覆盖。
- 确需覆盖时使用 `--force`，脚本会先执行 `DROP DATABASE ... WITH (FORCE)`，该操作不可撤销。
- 脚本始终拒绝恢复进 Compose 服务的源库（`POSTGRES_DB`）以及 `postgres`/`template0`/`template1` 维护库。
- 恢复使用 `pg_restore --no-owner --no-privileges --exit-on-error`；任一对象失败即停止并删除未完成的恢复库，不留下半成品。
- 推荐恢复到独立库并在其上启动应用验证，不要直接覆盖生产库。

恢复演练至少覆盖：

1. 备份生成并通过 `pg_restore --list` 自检。
2. 恢复到独立库后，Flyway 历史版本与校验和、核心表行数与抽样数据均与源库一致。
3. 用恢复库启动应用，健康检查与管理 API 可用。

演练证据记录在 [`phase4b-acceptance.md`](phase4b-acceptance.md)。仅确认备份文件存在，不代表恢复能力已验证。

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
