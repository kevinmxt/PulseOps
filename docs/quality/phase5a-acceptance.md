# Phase 5A 验收证据

本文件记录 Phase 5A 部署准备的本地验收证据。本文档由 #87 创建并记录异地加密备份恢复演练；其余 5A 验收项在 #93 汇总。

## 异地加密备份恢复演练（#87）

### 环境

| 项目 | 值 |
|---|---|
| 日期 | 2026-10-28（UTC） |
| 演练环境 | Multipass VM `pulseops-vps`，Ubuntu 24.04.5 LTS，2 vCPU / 3.8 GiB / 38.7 GiB |
| Compose 项目 | `pulseops`（postgres、backend、caddy 演练前后均 healthy） |
| 镜像 | `pulseops-backend:local`（生产镜像） |
| PostgreSQL | `postgres:17-alpine`，17.11 |
| Flyway | 12 条历史（版本 1–9、11–13），schema v13 |
| 源数据库 | `pulseops` |
| 恢复目标库 | `pulseops_restore` |
| 异地副本 | 本地 rclone remote：`/var/backups/pulseops-offsite/pulseops/db` |
| 密钥/配置 | `/etc/pulseops/age.key`、`/etc/pulseops/rclone.conf` 均为 600 |
| Healthchecks | 5A 未接真实服务；演练期间 `HEALTHCHECKS_PING_URL` 为空 |

### 演练步骤与结果

1. **冻结源库写入。** 为排除在线 HTTP 探测新增 `check_result` 行导致计数漂移，演练开始时停止 `backend`（Caddy 与 PostgreSQL 保持运行），完成备份与比对后恢复。演练未修改源库数据。
2. **生成新的异地备份。** 手动触发 `pulseops-backup.service`，最新异地对象为 `pulseops-20261028T045817Z.dump.age`。
3. **拉取与解密。** 使用 rclone 从本地异地 remote 拉取最新 `.dump.age`，用 `age` 私钥解密，得到 29,463 字节的 custom-format dump；`restore.sh` 的 `pg_restore --list` 自检通过。
4. **恢复到独立库。** `restore.sh --force <dump> pulseops_restore` 成功创建并恢复目标库。
5. **Flyway 校验。** 源库与恢复库均为 12 条迁移，按 `installed_rank`、版本、描述、校验和、成功标志逐行 `diff` 为空。
6. **核心表行数。** 冻结时刻源库与恢复库一致：

   | 表 | 行数（源库 = 恢复库） |
   |---|---|
   | `monitor` | 1 |
   | `check_result` | 10 |
   | `incident` | 0 |
   | `notification_delivery` | 0 |
   | `app_user` | 1 |
   | `heartbeat_credential` | 0 |

7. **抽样数据。** `monitor`（id/name/type/enabled）、`app_user`（username/role）与 `check_result` 前 5 条（id/monitor_id/success/failure_type）逐行 `diff` 为空。上述 Flyway、行数与抽样比对均在修改恢复库密码之前完成。
8. **用恢复库启动应用。** 比对完成后，仅在恢复库副本中临时覆盖 `admin` 的密码哈希用于登录验证；源库账号与哈希不变。使用运行中的生产镜像 `pulseops-backend:local`，连接 `pulseops_restore`，关闭探测、心跳扫描、通知投递与清理。
9. **健康与管理 API。**

   | 检查 | 结果 |
   |---|---|
   | `GET /actuator/health` | 200 `{"status":"UP"}` |
   | `GET /api/v1/monitors`（未登录） | 401 |
   | `POST /api/v1/auth/login` | 200 |
   | `GET /api/v1/monitors`（已登录） | 200 |

10. **清理。** 删除演练容器与 `pulseops_restore` 库，删除解密 dump、临时环境文件与 Cookie/响应文件；恢复源 `backend` 并确认 healthy。异地备份与源库保留。

### 复核命令与关键输出

以下命令在 VM 内执行；敏感值不记录。

```bash
C=(sudo docker compose --env-file /etc/pulseops/pulseops.env -f /opt/pulseops/compose.prod.yaml)

# 冻结源写入并生成新备份
"${C[@]}" stop backend
sudo systemctl start pulseops-backup.service

# 最新异地对象
sudo rclone --config /etc/pulseops/rclone.conf \
  lsf local:/var/backups/pulseops-offsite/pulseops/db --files-only \
  | grep -E '^pulseops-.*\.dump\.age$' | sort | tail -1
# pulseops-20261028T045817Z.dump.age

# 拉取与解密
sudo rclone --config /etc/pulseops/rclone.conf \
  copyto local:/var/backups/pulseops-offsite/pulseops/db/pulseops-20261028T045817Z.dump.age \
  /tmp/restore-87/latest.dump.age
sudo age --decrypt --identity /etc/pulseops/age.key \
  --output /tmp/restore-87/latest.dump /tmp/restore-87/latest.dump.age
sudo wc -c < /tmp/restore-87/latest.dump   # 29463

# 恢复到独立库
sudo bash -c 'set -a; . /etc/pulseops/pulseops.env; set +a; exec /opt/pulseops/scripts/restore.sh --force /tmp/restore-87/latest.dump pulseops_restore'
# Verifying dump with pg_restore --list...
# Restore completed: /tmp/restore-87/latest.dump -> pulseops_restore

# Flyway / 行数 / 抽样：两库各执行一次后 diff
flyway_q="SELECT installed_rank, version, description, checksum, success FROM flyway_schema_history ORDER BY installed_rank"
"${C[@]}" exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -F "|" -c "$1"' sh "$flyway_q" > /tmp/flyway-source.txt
"${C[@]}" exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d pulseops_restore -tA -F "|" -c "$1"' sh "$flyway_q" > /tmp/flyway-restore.txt
diff -u /tmp/flyway-source.txt /tmp/flyway-restore.txt   # 空
# 行数与抽样查询见步骤 6、7；diff 均为空

# 用恢复库启动应用
sudo docker run -d --name pulseops-restore-check --network pulseops_data \
  -p 127.0.0.1:18080:8080 --env-file /tmp/restore-check.env \
  pulseops-backend:local
# /tmp/restore-check.env: DB_URL=.../pulseops_restore; SERVER_ADDRESS=0.0.0.0;
# PULSEOPS_AUTH_COOKIE_SECURE=true; 探测/心跳/通知/清理关闭
curl -s http://127.0.0.1:18080/actuator/health          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/v1/monitors  # 401
# 登录后同一接口 200；Secure Cookie 由演练客户端手工携带

# 清理
sudo docker rm -f pulseops-restore-check
"${C[@]}" exec -T postgres sh -c 'exec psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE \"pulseops_restore\" WITH (FORCE);"'
sudo rm -rf /tmp/restore-87 /tmp/restore-check.env
"${C[@]}" start backend
```

### 与 4B 本地恢复演练的区别

| 维度 | 4B 本地演练（#63） | 5A 异地演练（#87） |
|---|---|---|
| 备份来源 | 本地 `backups/` 的未加密 `.dump` | 本地 rclone remote 上的 age 加密 `.dump.age` |
| 加密/传输 | 无 | age 解密后恢复；rclone 复制 |
| 数据规模 | 开发数据：13 个监控、1157 条结果、16 个 Incident | 生产化演练数据：1 个监控、10 条结果、无 Incident |
| 应用启动 | 源码 `./mvnw spring-boot:run`，端口 8081 | 生产镜像 `pulseops-backend:local`，端口 18080 |
| 源库状态 | 本地开发库 | 冻结源写入后备份与比对，完成后恢复 backend |
| 目标 | 验证 4B 备份脚本与恢复命令 | 验证 5A 自动备份链路产出的异地加密副本可完整恢复 |
| 外部依赖 | 无 | 5A 使用本地 rclone remote；5B 使用真实对象存储 |

### 限制

- 5A 的异地 remote 是 VM 内本地目录，Healthchecks 未接真实服务；5B 需替换为真实对象存储与 Healthchecks URL，并重跑同一演练。
- 演练期间 `backend` 短暂停止，Caddy UI 短暂不可用；未修改源库数据。
- 恢复库中的演练密码仅在副本生效，恢复库已删除；源库账号未变更。
- 本演练覆盖单个 HTTP 监控的小数据集，不替代 5B 的真实数据量与连续运行验收。
- `incident`、`notification_delivery`、`heartbeat_credential` 在冻结时刻为空，这三张表的相等为 0=0，证据强度有限；`monitor`/`check_result` 的行数与抽样比对更有意义。
- 登录通过本机 HTTP 并手工携带 `Secure` Cookie 完成；HTTPS/浏览器信任由 #84 验收覆盖。
- 验证中发现 VM 的环境文件为 CRLF 行尾；本次已规范为 LF，仓库已将 env 模板与 systemd 单元固定为 LF。

### 复核要点

- 备份/恢复脚本：`scripts/backup.sh`、`scripts/restore.sh`，参数与环境变量见 [`deployment.md`](deployment.md)。
- 加密与异地：`AGE_RECIPIENT`、`BACKUP_RCLONE_REMOTE`、`BACKUP_RCLONE_PATH`、`BACKUP_OFFSITE_RETENTION_COUNT=14`。
- 定时调度：`pulseops-backup.timer`，`OnCalendar=*-*-* 03:17:00 UTC`、`Persistent=true`。
- 失败通知：`pulseops-backup-failure.service` 由 `OnFailure` 触发，ping Healthchecks `/fail`（见 #86 完成报告）。
