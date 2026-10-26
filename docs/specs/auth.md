# 管理认证与授权规范

## 目标

在管理 API 或管理 UI 公网可达前，由应用自身拒绝未认证访问，并提供可撤销的服务端登录态。认证不可通过配置关闭；反向代理不视为唯一授权层。

## 账号与凭据

- 账号唯一事实来源是数据库表 `app_user`：`id`、`username`、`password_hash`、`role`、`created_at`、`updated_at`。
- `username` 唯一，1–64 字符，匹配 `[A-Za-z0-9._-]`，区分大小写；输入前后空白会被裁剪。
- `password_hash` 使用 Spring Security `DelegatingPasswordEncoder`，格式为 `{bcrypt}$2a$12$...`（也接受 `$2b$`、`$2y$`）。数据库不保存明文或可逆密文。
- `role` 取 `ADMIN`，`CHECK` 约束暂只允许该值；将来增加角色时通过迁移扩展。
- 认证使用数据库查询得到的 `UserDetails`；MVP 只有一个管理员账号。
- 密码规则：12–128 字符，不强制字符组合，前后不裁剪空白；不得与当前密码相同。

### 首次引导

- `app_user` 为空时，启动阶段使用 `PULSEOPS_AUTH_BOOTSTRAP_USERNAME` 与 `PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH` 创建唯一管理员。
- 用户表为空且任一变量缺失、或哈希格式非法时，应用拒绝启动。
- 用户表非空时忽略引导变量；若仍提供变量，记录 WARN 而不覆盖已有账号。
- 引导变量只用于首次建号。忘记密码时按 [`../quality/deployment.md`](../quality/deployment.md) 的管理员密码重置流程生成新哈希、更新对应账号并重启应用，不通过重新启动覆盖。

## 登录与会话

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| GET | `/api/v1/auth/session` | 公开 | 返回 `{authenticated, username, role, csrfToken}`；同时下发或刷新 `XSRF-TOKEN` Cookie；未登录返回 200 且 `authenticated = false` |
| POST | `/api/v1/auth/login` | 公开 + CSRF | 请求 `{username, password}`；成功 200 `{username, role}`；失败 401 `INVALID_CREDENTIALS`；限流 429 `RATE_LIMITED` |
| POST | `/api/v1/auth/logout` | 公开 + CSRF | 始终返回 204；会话不存在时不报错 |
| PUT | `/api/v1/auth/password` | 需认证 + CSRF | 请求 `{currentPassword, newPassword}`；成功 204；当前密码错误 400 `INVALID_CURRENT_PASSWORD` |

- 登录成功时轮换 Session ID，防止会话固定。
- 登录响应不包含会话 ID、密码或哈希；会话只通过 Cookie 传递。
- 不创建独立的 `/api/v1/auth/csrf` 端点；CSRF 引导合并到 session 响应。

### 会话属性

- 会话保存在应用内存；应用重启后所有会话失效，需要重新登录。
- Cookie 名称 `PULSEOPS_SESSION`，属性：`HttpOnly`、`SameSite=Lax`、`Path=/`；`Secure` 由 `pulseops.auth.cookie-secure` 控制。
- 空闲超时 30 分钟；绝对期限 12 小时。绝对期限通过会话创建时间校验，到期后即使持续活动也必须重新登录。
- 会话不用于心跳上报；心跳上报只接受 Heartbeat Token。

## CSRF 防护

- 采用双提交 Cookie：服务端下发可被前端读取的 `XSRF-TOKEN` Cookie，前端在所有非 GET 管理请求中带 `X-XSRF-TOKEN` 头。
- 登录、登出、改密以及所有 `POST`/`PUT`/`DELETE` 管理请求必须校验。
- 心跳上报 `POST /api/v1/heartbeats/{token}` 不使用 Cookie 认证，豁免 CSRF。
- CSRF 缺失或不匹配返回 403 `CSRF_INVALID`。
- 前端收到 `CSRF_INVALID` 时允许重新调用 session 引导并重试一次；再次失败必须提示错误，不得无限重试。

## 受保护边界

公开访问：

- `GET /api/v1/auth/session`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/logout`
- `POST /api/v1/heartbeats/{token}`
- `GET /actuator/health` 及其健康分组

其余 `/api/v1/**` 与 `/actuator/metrics` 全部要求已认证且具备 `ADMIN` 角色。未认证返回 401，不得重定向到 HTML 登录页。

静态前端资源不属于管理 API；Phase 5 托管时前端壳可以公开，数据仍由上述边界保护。

## 错误契约

所有认证错误沿用 `{code, message, traceId}`：

| HTTP | code | 场景 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 缺少会话、会话过期或已失效 |
| 401 | `INVALID_CREDENTIALS` | 登录用户名或密码错误（不区分原因） |
| 403 | `CSRF_INVALID` | CSRF 令牌缺失或不匹配 |
| 403 | `FORBIDDEN` | 已认证但角色不足（当前单角色下仅保留契约） |
| 429 | `RATE_LIMITED` | 登录尝试配额已满（进行中与失败合计 5 次），响应带 `Retry-After` |
| 400 | `INVALID_CURRENT_PASSWORD` | 改密时当前密码错误 |
| 400 | `VALIDATION_ERROR` | 输入字段不合法 |

## 登录失败限流

- 计数键为 `username` 与客户端远端地址的组合，窗口 15 分钟，配额 5。
- 配额按“进行中或已失败的认证尝试”合并计数：每次登录在密码校验前原子占用一个名额；校验失败后结算为一次失败并释放名额；成功登录清除该键的全部状态。
- 占用与检查是一次原子操作，同一键在窗口内进行中与失败的尝试合计最多 5 次，并发请求不能无界越过配额。
- 前 5 次失败返回 401；配额用尽后的尝试（包括并发中的第 6 次及以后）返回 429 `RATE_LIMITED`，`Retry-After` 为剩余窗口秒数，向上取整且至少为 1。
- 窗口过期后配额重置；跨过窗口边界才结算的旧尝试不写入新窗口。
- 成功登录只清除当前键的状态，不影响其他独立键；计数保存在内存，并设置键数量上限防止内存无限增长。
- 4B 只使用请求的远端地址；反向代理头与真实客户端 IP 的信任规则在 Phase 5 与 Caddy 联调时统一处理。
- 限流不产生账号锁定状态，避免管理员被恶意锁定后无法自助恢复。

## 修改密码

- 需要有效会话与 CSRF。
- 校验当前密码；失败返回 400 `INVALID_CURRENT_PASSWORD`，不改变任何状态。
- 新密码须满足密码规则且与当前密码不同。
- 成功后更新 `password_hash` 与 `updated_at`，并使该用户的全部会话失效；当前请求返回 204，随后任何管理请求返回 401。

## 启动校验

应用启动时依次校验，任一失败拒绝启动并输出可操作的错误信息：

1. `app_user` 为空且缺少引导用户名或哈希。
2. 引导用户名不符合格式，或哈希不以 `{bcrypt}$2` 开头、BCrypt 格式非法。
3. `server.address` 不是回环地址（`127.0.0.1`、`::1`、`localhost`）且 `pulseops.auth.cookie-secure=false`。
4. `app_user` 非空且仍提供引导变量：忽略变量并记录 WARN。

Phase 5 在容器内监听 `0.0.0.0` 时会触发第 3 条；必须显式启用 Secure Cookie，并由前置代理终止 TLS。

## 密码哈希生成

使用 Docker 生成 BCrypt 哈希（成本因子 12）：

```bash
docker run --rm httpd:2.4-alpine \
  htpasswd -bnBC 12 "" 'your-password' | cut -d: -f2 | tr -d '\n'
```

将输出加上 `{bcrypt}` 前缀后写入环境变量，使用单引号避免 shell 展开 `$`：

```bash
export PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH='{bcrypt}$2y$12$...'
```

## 日志与敏感信息

- 记录登录成功/失败、登出、改密结果、限流触发与启动校验结果。
- 认证日志只包含结果、远端地址与 `traceId`；不得记录用户名、密码、密码哈希、会话 Cookie、CSRF 令牌或请求头原文。
- 错误响应不返回堆栈、数据库连接串或内部配置。

## 前端交互约束

- `/login` 公开，其余管理路由需要会话；认证状态只保存在内存，不写 `localStorage`/`sessionStorage`。
- 任一管理请求返回 401 时清空状态并跳转 `/login?redirect=<站内路径>&expired=1`；登录成功后只接受以单个 `/` 开头的站内路径作为回跳目标。
- 登录页在已认证时直接进入 `/monitors`。
- 顶栏显示用户名、设置入口与登出。
- 改密成功后提示“请重新登录”并跳转登录页。
- 登录页对 401 显示通用“用户名或密码错误”，对 429 显示可重试秒数。

## 验收

- 未认证访问任何管理接口返回 401 错误信封。
- 缺少 CSRF 的写请求返回 403 `CSRF_INVALID`。
- 正确凭据可登录、登出；错误凭据不泄露账号是否存在。
- 连续失败或并发尝试触发 429 且不越过配额，`Retry-After` 为正整数；成功后计数清零。
- 会话空闲与绝对期限生效；重启后需要重新登录。
- 改密后旧密码失效，已有会话全部失效。
- 空用户表缺少引导变量、非法哈希、非回环监听且未启用 Secure Cookie 时拒绝启动。
- 心跳上报与 `/actuator/health` 在未登录时仍按各自规范工作。
