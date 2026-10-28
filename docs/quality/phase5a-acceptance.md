# Phase 5A 验收证据

本文件记录 Phase 5A 部署准备在本地虚拟机的验收证据。下面先汇总 #93 的收口验收（全部完成条件），随后保留 #90 外部健康端点与 #87 异地加密备份恢复演练的详细记录。

## 5A 收口验收（#93）

### 环境

| 项目 | 值 |
|---|---|
| 日期 | 2026-10-28（UTC；宿主时区 UTC+8） |
| 执行时段 | 约 11:30–12:10 UTC（宿主 19:30–20:10） |
| 演练环境 | Multipass VM `pulseops-vps`，Ubuntu 24.04.5 LTS，2 vCPU / 3.8 GiB / 38.7 GiB |
| VM IPv4 | 重启前 `172.21.51.253`；重启后 `172.21.55.183` |
| 部署目录 | `/opt/pulseops`；环境文件 `/etc/pulseops/pulseops.env`（600，LF） |
| Compose 项目 | `pulseops`（postgres、backend、caddy 均 healthy） |
| 镜像 | `pulseops-backend:local`、`pulseops-caddy:local`；上一 tag 为 `deploy-a` |
| 站点 | `https://pulseops.test`（Caddy 内部 CA） |
| 外部探测源 | Windows 10 宿主（VM 外部） |

> VM IP 由 Multipass DHCP 分配，重启后会变化；所有 `--resolve`、hosts 与外部端口检查都必须使用当次 IP。
>
> 部署、备份、恢复、加固与容器检查在 VM 内执行；TLS 链、外部端口与冒烟从 Windows 宿主发起；全量测试在开发宿主执行。外部可用性探测必须来自 PulseOps 之外。

### 1. Compose、Caddy/TLS 与冒烟

在 VM 内与 Windows 宿主分别执行：

```bash
# VM 内
docker compose -f compose.prod.yaml config -q          # COMPOSE_OK
caddy validate --config /etc/caddy/Caddyfile          # Valid configuration
docker compose -f compose.prod.yaml ps                # 三服务 healthy

# Windows 宿主
openssl s_client -connect <VM_IP>:443 -servername pulseops.test \
  -CAfile caddy-root.crt                              # Verify return code: 0 (ok), TLSv1.3
curl --ssl-no-revoke --cacert caddy-root.crt \
  --resolve pulseops.test:443:<VM_IP> https://pulseops.test/actuator/health
# {"status":"UP"}
curl -s -o /dev/null -w '%{http_code} %{redirect_url}' \
  --resolve pulseops.test:80:<VM_IP> http://pulseops.test/
# 308 https://pulseops.test/
./scripts/smoke-test.sh --url https://pulseops.test \
  --resolve pulseops.test:443:<VM_IP> --insecure
# Smoke test passed: root=200 anonymous_monitors=401 health=UP
```

| 检查 | 结果 |
|---|---|
| Compose 配置 | `docker compose config -q` 通过 |
| Caddy 配置 | `caddy validate` 通过 |
| 三服务健康 | postgres、backend、caddy 均 healthy |
| TLS | 内部 CA 链校验通过（`Verify return code: 0`），TLSv1.3 |
| HTTP 80 | 308 跳转 `https://pulseops.test/` |
| UI 壳与登录路由 | `/` 返回 200 `text/html`，标题 `PulseOps`；`/login` 返回 200 |
| 会话端点 | `/api/v1/auth/session` 返回 200 且 `authenticated=false` |
| 冒烟 | `/` 200、匿名 `/api/v1/monitors` 401、`/actuator/health` UP |

本次未使用自动化浏览器执行登录与创建监控流程；UI 壳、登录路由、会话端点与冒烟已验证，浏览器证书信任与页面交互由 #84 验收覆盖。

### 2. 容器加固、资源限制与日志

`docker inspect` 关键字段：

| 服务 | 运行用户 | 日志驱动 | 只读根 | cap_drop | 内存 | CPU | pids | 重启策略 | no-new-privileges |
|---|---|---|---|---|---|---|---|---|---|
| postgres | 镜像默认（entrypoint 降权） | json-file 10m×5 | 否 | 无 | 1 GiB | 1 | 256 | unless-stopped | 是 |
| backend | `pulseops` | json-file 10m×5 | 是 | ALL | 768 MiB | 1 | 512 | unless-stopped | 是 |
| caddy | `caddy` | json-file 10m×5 | 否 | 无 | 128 MiB | 0.5 | 128 | unless-stopped | 是 |

- 宿主仅发布 80/443；backend 与 postgres 无宿主端口。
- `docker inspect .Config.User`：backend 为 `pulseops:pulseops`，Caddy 为 `caddy:caddy`；两者均非 root。
- `docker stats --no-stream` 快照：caddy 55.6 MiB / 128 MiB，backend 284.4 MiB / 768 MiB，postgres 82.6 MiB / 1 GiB。
- Caddyfile 未配置访问日志；应用与 Caddy 日志只经 Docker `json-file` 驱动输出，`/var/log` 下没有 PulseOps/Caddy 日志文件。
- 备份单元输出进入 journald；`pulseops-backup.service` 配置 `UMask=0077`、`NoNewPrivileges=true`、`TimeoutStartSec=30min`、`OnFailure=pulseops-backup-failure.service`。

### 3. 自动备份、加密、异地与恢复

```bash
sudo systemctl start pulseops-backup.service
# Verified remote object: pulseops-20261028T115211Z.dump.age (30588 bytes)
# Retained local backups for 'pulseops': 7/7
# Retained remote backups: 8/14
# Healthchecks success ping skipped: HEALTHCHECKS_PING_URL is not set.
# Backup complete.
```

- `pulseops-backup.timer` 为 enabled，`OnCalendar=*-*-* 03:17:00 UTC`、`Persistent=true`；手动触发成功。
- 新备份 `pulseops-20261028T115211Z.dump` 为 30,388 字节、权限 600；本地保留 7/7。
- age 加密后异地对象 30,588 字节；该次异地保留 8/14。
- 成功 ping 模拟：用本地 HTTP 接收器模拟 Healthchecks，执行 `backup.sh` 后输出 `Healthchecks success ping sent.`，接收器收到 `GET /ping`；最新对象为 `pulseops-20261028T120802Z.dump.age`（30,611 字节），异地保留 10/14。
- 失败 ping 模拟：接收器收到 `GET /ping/fail`，脚本输出 `Backup failure notification sent.`；`pulseops-backup.service` 已配置 `OnFailure=pulseops-backup-failure.service`。
- 恢复验证（从最新异地副本执行）：

```bash
latest=pulseops-20261028T120802Z.dump.age
sudo rclone --config /etc/pulseops/rclone.conf \
  copyto "local:/var/backups/pulseops-offsite/pulseops/db/$latest" /tmp/accept93/latest.dump.age
sudo age --decrypt --identity /etc/pulseops/age.key \
  --output /tmp/accept93/latest.dump /tmp/accept93/latest.dump.age
sudo wc -c < /tmp/accept93/latest.dump          # 30411
sudo ./scripts/restore.sh --force /tmp/accept93/latest.dump pulseops_accept93
# Verifying dump with pg_restore --list...
# CREATE DATABASE
# Restore completed: /tmp/accept93/latest.dump -> pulseops_accept93
# pulseops flyway=12 monitor=1 check_result=53
# pulseops_accept93 flyway=12 monitor=1 check_result=53
# FLYWAY_IDENTICAL
```

Flyway 历史 12 条与源库逐行一致；`monitor` 与 `check_result` 行数相等。验证后删除恢复库与临时文件。完整的“用恢复库启动应用并验证健康/管理 API”与抽样数据比对见下文 #87 演练。

5A 未设置真实 Healthchecks URL；成功与失败 ping 均用本地接收器模拟，真实服务与对象存储属 5B。

### 4. 部署与回滚

```bash
sudo env PULSEOPS_ENV_FILE=/etc/pulseops/pulseops.env ./scripts/deploy.sh --dry-run --tag local
# postgres/backend/caddy: healthy
# Plan: backup -> build -> up -d -> health gate 120s -> smoke -> state/cleanup

sudo env PULSEOPS_ENV_FILE=/etc/pulseops/pulseops.env ./scripts/rollback.sh --dry-run
# Rollback target: deploy-a (current: local)
# Schema check: target rank 12, current rank 12
# Plan: up -d deploy-a -> health gate 120s -> smoke
```

两项 dry-run 均通过且未改变运行状态。随后执行一次真实镜像回退并重新部署：

```bash
sudo env PULSEOPS_ENV_FILE=/etc/pulseops/pulseops.env ./scripts/rollback.sh deploy-a
# Rollback target: deploy-a (current: local)
# Schema check: target rank 12, current rank 12
# Smoke test passed: root=200 anonymous_monitors=401 health=UP
# Rollback complete: deploy-a (previous: local)

sudo env PULSEOPS_ENV_FILE=/etc/pulseops/pulseops.env ./scripts/deploy.sh --tag local
# Backup -> build -> up -d -> health gate -> smoke
# Smoke test passed: root=200 anonymous_monitors=401 health=UP
# Deployment complete: local (previous: deploy-a, schema rank: 12)
```

真实回退切到 `deploy-a` 并通过健康检查与冒烟；随后重新部署 `local`，部署状态恢复为 `.deployed-tag=local`、`.previous-tag=deploy-a`。

### 5. 防火墙与 SSH

```bash
sudo ufw status verbose
# Status: active
# Default: deny (incoming), allow (outgoing), deny (routed)
# 22/tcp LIMIT IN Anywhere
# 80/tcp ALLOW IN Anywhere
# 443/tcp ALLOW IN Anywhere

sudo sshd -T | grep -E '^(port|maxauthtries|permitrootlogin|passwordauthentication|kbdinteractiveauthentication|pubkeyauthentication) '
# port 22
# maxauthtries 3
# permitrootlogin without-password
# passwordauthentication no
# kbdinteractiveauthentication no
# pubkeyauthentication yes
```

`harden-vps.sh --dry-run` 输出计划中的 ufw 规则、sshd drop-in 与第二会话验证提示，未修改系统；实际生效值已由上表确认。

从 Windows 宿主逐端口探测：

```bash
for p in 22 80 443 5432 8080; do
  timeout 4 bash -c "</dev/tcp/$VM_IP/$p" >/dev/null 2>&1 \
    && echo "$p open" || echo "$p closed/filtered"
done
# 22 open / 80 open / 443 open / 5432 closed/filtered / 8080 closed/filtered
```

Docker 发布端口未绕过 ufw 暴露数据库或应用端口。加固后 `multipass exec` 仍能建立新会话，验证密钥登录可用；本轮未使用密码登录。

### 6. 重启恢复

```bash
multipass restart pulseops-vps
multipass list                     # 172.21.55.183
docker compose -f compose.prod.yaml ps
# postgres/backend/caddy: Up ... (healthy)
systemctl list-timers pulseops-backup.timer --all
# NEXT 2026-10-29 03:17:00 UTC
```

- 重启后 postgres、backend、caddy 按 `restart: unless-stopped` 自动启动并恢复 healthy；`pulseops-backup.timer` 仍为 enabled，下次运行 03:17 UTC。
- 环境文件、age 密钥、rclone 配置与本地/异地备份均保留（后续备份与恢复命令继续使用这些文件）。
- VM IP 从 `172.21.51.253` 变为 `172.21.55.183`；重启后重新执行外部端口、TLS、健康端点与冒烟检查，结果与重启前一致。
- 验收过程中连续快速执行 SSH 命令触发 ufw `limit 22` 的连接速率限制：22 端口短暂不可达，443 与容器保持可用；等待限速窗口后恢复。为同时验证重启恢复，执行了 `multipass restart pulseops-vps`。该事件未造成数据丢失，容器与定时器在重启后正常。

### 7. 全量测试

- 后端 `cd backend && ./mvnw -B -ntp verify`：surefire 213 + failsafe 222（共 435），0 failures，0 errors，BUILD SUCCESS（约 5m51s）。
- 前端 `cd frontend && npm test`：20 个测试文件 / 158 tests passed；`npm run build`：vue-tsc + vite build 通过。
- 测试在 Windows 宿主执行，集成测试使用 Testcontainers `postgres:17-alpine`；CI 在 PR 上运行同一套检查。

### 已知限制与 5B 边界

- 5A 使用内部 CA 与 `pulseops.test`；真实域名、ACME 证书与公网可达性属于 5B。
- 异地 remote 是 VM 内本地目录，Healthchecks 未设置 URL，外部监控未注册；5B 需替换为真实对象存储与 Healthchecks，并验证告警。
- 恢复验证覆盖小数据集；`incident`、`notification_delivery`、`heartbeat_credential` 在部分演练中为空，0=0 证据强度有限。
- 未使用自动化浏览器重放登录与监控创建流程；由 #84 与集成测试覆盖，5B 再做公网浏览器验收。
- VM IP 为 DHCP，重启后变化；外部探测与 hosts 需要更新。
- 未做 7 天连续运行；出站目标白名单仍未实现，依赖应用层 SSRF 防护。
- 重启由 ufw `limit 22` 导致的 SSH 短暂不可达触发，同时验证了重启恢复；它不是预先计划的独立演练。连续高频 SSH 自动化会再次触发该限速，等待窗口即可恢复。

## 外部健康端点验证（#90）

### 环境

| 项目 | 值 |
|---|---|
| 日期 | 2026-10-28（UTC） |
| 探测源 | Windows 10 宿主（VM 外部） |
| 目标 | VM `pulseops-vps`（172.21.51.253），生产栈 `pulseops`，三服务 healthy |
| 站点 | `https://pulseops.test`（5A 内部 CA） |

> 说明：外部可用性探测必须从 PulseOps 之外发起，因此本节证据来自 Windows 宿主。部署、备份、恢复与容器检查在 VM 内执行；TLS、外部端口与冒烟从宿主发起，具体分工见 #93 节的环境说明。

### 命令与结果

```bash
curl -ksS --resolve pulseops.test:443:172.21.51.253 https://pulseops.test/actuator/health
# {"status":"UP"}

curl -s -o /dev/null -w '%{http_code} %{redirect_url}' \
  --resolve pulseops.test:80:172.21.51.253 http://pulseops.test/
# 308 https://pulseops.test/

curl -ksS --resolve pulseops.test:443:172.21.51.253 \
  -o /tmp/metrics.out -w '%{http_code} %{content_type}' \
  https://pulseops.test/actuator/metrics
# 200 text/html；Caddy 未反代 metrics，返回 SPA 入口
```

| 检查 | 结果 |
|---|---|
| 公开健康端点（宿主外部访问） | 200，响应体仅 `{"status":"UP"}` |
| 响应字段 | 仅 `status`，无 `details`、`components`、数据库或版本信息 |
| HTTP 80 | 308 跳转 `https://pulseops.test/` |
| `/actuator/metrics` 经 Caddy | 200 HTML（SPA 回退），未暴露 actuator metrics |

### 结论与边界

- 公开健康端点可从 VM 外部访问，响应不泄露数据库、组件或版本细节，具备作为外部可用性探测目标的响应语义。
- 本次使用 `-k` 与私有地址，只验证路径和响应内容，未验证证书链与公网路径；TLS/公网可达性属于 5B 真实域名验收，不能据此认定公网监控已可用。
- 5A 使用内部 CA 与 `pulseops.test`，外部监控服务不信任该证书；不在 5A 注册公网探测。
- 5B 使用真实域名与 ACME 证书后注册 Better Stack/Healthchecks.io，并配置 Telegram/邮件集成（若 Better Stack 免费档不含 Telegram，可用性告警走邮件/Slack 或付费档），验证探测与告警。
- 外部服务选型、探测频率与告警预案见 [`deployment.md`](deployment.md#独立外部监控)。

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
