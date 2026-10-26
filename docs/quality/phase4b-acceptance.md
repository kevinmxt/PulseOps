# Phase 4B 验收证据

本文件汇总 Phase 4B（认证与数据维护）的验收证据。当前先记录 4B-6（备份恢复脚本与演练）；其余 ticket 的 CI 证据与阶段收口在 4B-7 补齐。

- #63 Phase 4B-6：备份恢复脚本与演练

## 备份恢复演练（#63）

### 环境

| 项目 | 值 |
|---|---|
| 日期 | 2026-10-26 |
| 宿主机 | Windows 10 Pro 10.0.19045，Git Bash |
| Docker | 29.6.1 |
| PostgreSQL | `postgres:17-alpine`，17.11 |
| JDK | 21（Maven Wrapper） |
| 源数据库 | `pulseops`（本地 Compose 服务 `postgres`） |
| 恢复目标库 | `pulseops_restore` |

### 演练步骤与结果

#### 1. 备份

```bash
./scripts/backup.sh
```

输出：

```text
Backing up database 'pulseops' from service 'postgres'...
Verifying dump with pg_restore --list...
Backup written: ./backups/pulseops-20261026T050841Z.dump (52K)
note: the dump contains password hashes and heartbeat token digests; keep it private.
Retained backups for 'pulseops': 1/7
```

- 备份文件：`backups/pulseops-20261026T050841Z.dump`，50,915 字节。
- `pg_restore --list` 自检通过，归档为 `CUSTOM` 格式，数据库版本 17.11。可用以下命令复核归档头：

  ```bash
  docker compose exec -T postgres pg_restore --list < backups/pulseops-20261026T050841Z.dump | head -12
  ```

- 文件保留在本地 `backups/`（已被 `.gitignore` 忽略），未提交到 Git。

保留份数验证：在临时目录放入 3 个旧备份，保留数设为 2 后执行备份，最旧的 2 个被删除：

```bash
TDIR=$(mktemp -d)
touch "$TDIR/pulseops-20200101T000000Z.dump" \
      "$TDIR/pulseops-20200201T000000Z.dump" \
      "$TDIR/pulseops-20200301T000000Z.dump"
BACKUP_DIR="$TDIR" BACKUP_RETENTION_COUNT=2 ./scripts/backup.sh
```

```text
Backup written: /tmp/.../pulseops-20261026T051018Z.dump (52K)
Removing old backup: /tmp/.../pulseops-20200101T000000Z.dump
Removing old backup: /tmp/.../pulseops-20200201T000000Z.dump
Retained backups for 'pulseops': 2/2
```

临时目录在验证后删除。

#### 2. 恢复到独立库

```bash
./scripts/restore.sh backups/pulseops-20261026T050841Z.dump pulseops_restore
```

输出：

```text
Verifying dump with pg_restore --list...
Creating database 'pulseops_restore'...
CREATE DATABASE
Restoring '/g/work/workspace/2026/PulseOps/backups/pulseops-20261026T050841Z.dump' into 'pulseops_restore'...
Restore completed: ... -> pulseops_restore
```

覆盖保护验证：

- 对已存在的 `pulseops_restore` 再次执行同一命令，脚本以退出码 1 拒绝，并提示使用 `--force`：

  ```text
  error: database 'pulseops_restore' already exists; pass --force to replace it
  ```

- 脚本无条件拒绝恢复进 Compose 源库与 PostgreSQL 维护库（与是否传 `--force` 无关）：

  ```text
  $ ./scripts/restore.sh backups/pulseops-20261026T050841Z.dump pulseops
  error: refusing to restore into the source database 'pulseops'; use a separate target database

  $ ./scripts/restore.sh backups/pulseops-20261026T050841Z.dump postgres
  error: refusing to restore into PostgreSQL maintenance database 'postgres'
  ```

#### 3. Flyway 历史与核心表校验

源库与恢复库的 Flyway 历史均为 12 条迁移（版本 1–9、11–13），全部 `success = true`，版本、描述与校验和聚合一致：

```text
flyway|12|e10902088b1e1941962fd2dfabc760cf
```

核心表行数与整表内容摘要（按主键排序的 `md5`）在两库之间完全一致：

| 表 | 行数 | 内容摘要（源库 = 恢复库） |
|---|---|---|
| `flyway_schema_history` | 12 | `e10902088b1e1941962fd2dfabc760cf` |
| `app_user` | 1 | `e8c7c4d934c2187b47c83701b61f886c` |
| `monitor` | 13 | `0a6abd298f64567a270d51daed87805b` |
| `check_result` | 1157 | `ae10c8fc83468171d383a31d1210b4d5` |
| `incident` | 16 | `4e2c8c4a454771ca014471aa69f2ea79` |
| `notification_delivery` | 12 | `0fd03e503c76c2e3d7d4bcb2d70effa1` |
| `heartbeat_credential` | 7 | `1d1656fff3c99345debe97eba7c74770` |

复核方式：对每个数据库分别执行同样的查询并 `diff`。普通表示例：

```bash
docker compose exec -T postgres psql -U pulseops -d pulseops -At -c \
  "select 'monitor|' || count(*) || '|' || md5(string_agg(t::text, '|' order by id)) from monitor t"
```

Flyway 历史使用 `version || ':' || description || ':' || checksum` 按 `installed_rank` 聚合；其余表使用 `t::text` 按 `id` 聚合。两库输出逐行 `diff` 为空。

抽样数据同样一致：

- `monitor`：13 条（HTTP 与 Heartbeat 混合，含禁用项 `id=13`）。
- `check_result`：最新 5 条（`id=1158`、`1157`、`1156`、`1155`、`1154`）的监控项、结果、HTTP 状态码与时间一致。
- `incident`：最新 5 条（`id=17`、`16`、`15`、`13`、`12`）的状态、开始与恢复时间一致。

#### 4. 用恢复库启动应用

```bash
cd backend
DB_URL=jdbc:postgresql://localhost:5432/pulseops_restore \
SERVER_PORT=8081 \
PULSEOPS_PROBE_SCHEDULING_ENABLED=false \
PULSEOPS_HEARTBEAT_SCHEDULING_ENABLED=false \
PULSEOPS_NOTIFICATION_SCHEDULING_ENABLED=false \
PULSEOPS_CLEANUP_SCHEDULING_ENABLED=false \
./mvnw spring-boot:run
```

关闭调度器是为了让演练只读，避免探测、心跳扫描、通知投递或清理修改恢复库。

结果：

| 检查 | 结果 |
|---|---|
| `GET /actuator/health` | 200 `{"status":"UP"}` |
| `GET /api/v1/auth/session`（未登录） | 200，`authenticated=false`，下发 CSRF Token |
| `POST /api/v1/auth/login` | 200 `{"username":"drill_admin","role":"ADMIN"}` |
| `GET /api/v1/monitors`（已登录） | 200，`totalElements=13`、`items=13` |
| `GET /api/v1/monitors`（未登录） | 401 |

#### 5. 清理

- 停止端口 8081 的演练后端进程。
- 删除 `pulseops_restore` 演练库，避免在本地保留带已知演练密码的数据副本。
- 删除演练过程中产生的 Cookie、登录响应与临时校验输出文件；仅保留备份文件于本地 `backups/`（已被 `.gitignore` 忽略）。

### 验收对照

| 验收条件 | 证据 |
|---|---|
| 脚本通过 `docker compose exec -T` 执行，宿主机无需客户端 | 上述备份与恢复输出；脚本实现 |
| `BACKUP_DIR` / `BACKUP_RETENTION_COUNT` / `COMPOSE_SERVICE` 默认值 | 脚本参数；保留份数验证 |
| 文件名含 UTC 时间戳 | `pulseops-20261026T050841Z.dump` |
| `pg_restore --list` 自检、`umask 077`、按份数删除最旧 | 备份输出；“保留份数验证”删除了 2 个最旧文件 |
| `restore.sh <file> <target-db>` 创建并恢复，不静默覆盖 | 恢复输出；重复执行退出码 1；源库与维护库保护输出 |
| `.gitignore` 忽略 `backups/` | `.gitignore` |
| 演练：Flyway、行数、抽样、健康检查与管理 API | 本文件第 3、4 节 |
| 更新部署文档与验收记录 | `docs/quality/deployment.md`、本文件 |

### 已知限制

- **演练账号**：源库唯一管理员 `admin` 的密码未记录在本仓库中。为验证已认证的管理 API，仅在 `pulseops_restore` 中临时插入 `drill_admin`（已知密码的 BCrypt 哈希），随后随演练库一并删除；源库未改动。行数与校验和对比在插入该账号之前完成。
- **权限位**：演练宿主机为 Windows NTFS + Git Bash，不强制 POSIX 权限位，`ls -l` 仍显示 644。脚本中的 `umask 077` 与 `chmod 600` 在 Linux 上生效，需在 Phase 5 VPS 上复核。
- **失败清理未触发**：演练未人为制造 `pg_restore` 中途失败，恢复失败后删除半成品库的分支没有运行证据。
- **未覆盖自动化**：定时备份、异地存放、加密与备份监控属于 Phase 5；本阶段只提供可重复执行的手动脚本与一次真实恢复证据。
- **敏感数据**：备份文件包含密码哈希与心跳令牌摘要；正式环境必须限制访问权限并避免明文传输。
