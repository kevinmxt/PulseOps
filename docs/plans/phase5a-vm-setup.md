# Phase 5A 本地演练环境：Windows + Hyper-V + Multipass

## 用途与边界

- 本文件是 Phase 5A 与 Phase 5B 的**本地预演环境**安装手册，不是生产部署规范。
- 5A 验收与 5B 上线前预演都在这台 VM 内完成；验收证据只认 VM 内的执行结果。
- Windows 宿主机不参与部署验收，也不要在宿主机上执行防火墙或 SSH 加固。
- 生产规范见 [`../quality/deployment.md`](../quality/deployment.md)。

## 本机已验证条件

截至 2026-10-27，本机已完成安装与基础配置；以下步骤保留用于重建环境。

| 项目 | 结果 |
|---|---|
| Hyper-V | 已启用（`vmms` 服务 Running） |
| G: 文件系统 / 可用空间 | NTFS / 1.66 TB |
| Docker Desktop | 已安装，使用 WSL2 后端（存在 `docker-desktop` 发行版） |
| 当前终端权限 | 非管理员；安装 Multipass 与修改 hosts 需要管理员 PowerShell |
| Multipass | 1.16.4 已安装，服务 Running |
| 数据目录 | `C:\ProgramData\Multipass` 为 junction，指向 `G:\work\VM\Multipass` |
| 演练 VM | `pulseops-vps` Running；Ubuntu 24.04.5 LTS；2 vCPU / 3.8 GiB / 38.7 GiB；1 个快照 |
| VM 内组件 | systemd、Docker、Compose v5.6.1、ufw、age、rclone 已安装 |

## 路径策略

- Multipass 程序本体（几十 MB）由官方 MSI 安装到 C:，官方不支持可靠地修改程序目录。
- **所有 VM 磁盘、镜像缓存、快照放在 `G:\work\VM\Multipass`**。
- 通过目录联接（junction）把 `C:\ProgramData\Multipass` 指向 G:，使 Multipass 以为数据仍在默认位置。相比官方 `MULTIPASS_STORAGE` 环境变量，junction 不会触发已知的守护进程认证问题（canonical/multipass#3184、#4166）。
- 目标布局：

```text
G:\work\VM\
  Multipass\      # Multipass 数据、镜像缓存、VM 磁盘与快照
  downloads\      # 可选：手工下载的安装包
```

> 迁移必须在**首次启动任何实例之前**完成；否则需要手工修改实例记录里的绝对路径。

## 步骤 1：安装 Multipass 1.16.4

在**管理员 PowerShell**中执行：

```powershell
winget install --id Canonical.Multipass --exact `
  --accept-package-agreements --accept-source-agreements
```

如果 winget 或 GitHub 下载过慢，可手工下载 MSI 后安装：

- URL：`https://github.com/canonical/multipass/releases/download/v1.16.4/multipass-1.16.4+win-win64.msi`
- SHA256：`b0c417fb8254fa61e4aa63895f59354bb3717c58100816651a9300dd98124aea`

```powershell
$msi = "G:\work\VM\downloads\multipass-1.16.4+win-win64.msi"
Get-FileHash $msi -Algorithm SHA256   # 核对上面的 SHA256
msiexec /i $msi /qn
```

安装完成后确认：

```powershell
multipass version
Get-Service *multipass*
```

## 步骤 2：把数据目录迁移到 G:

在**管理员 PowerShell**中执行。脚本可重复执行：若 junction 已存在会先跳过迁移。

```powershell
$ErrorActionPreference = "Stop"
$target = "G:\work\VM\Multipass"
$source = "C:\ProgramData\Multipass"

New-Item -ItemType Directory -Force $target | Out-Null

$item = Get-Item $source -ErrorAction SilentlyContinue
if ($item -and $item.LinkType -ne "Junction") {
    Get-Service Multipass -ErrorAction SilentlyContinue | Stop-Service -Force

    robocopy $source $target /E /COPYALL /R:1 /W:1
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $LASTEXITCODE" }

    Rename-Item $source "Multipass.bak"
    cmd /c mklink /J "$source" "$target"
    if ($LASTEXITCODE -ne 0) { throw "mklink failed: $LASTEXITCODE" }

    Start-Service Multipass
    Start-Sleep -Seconds 3
    Remove-Item "C:\ProgramData\Multipass.bak" -Recurse -Force
}
elseif (-not $item) {
    Get-Service Multipass -ErrorAction SilentlyContinue | Stop-Service -Force
    cmd /c mklink /J "$source" "$target"
    if ($LASTEXITCODE -ne 0) { throw "mklink failed: $LASTEXITCODE" }
    Start-Service Multipass
}

Get-Item $source | Select-Object FullName,LinkType,Target
Get-Service Multipass | Select-Object Name,Status,StartType
multipass version
```

验收标准：`LinkType` 为 `Junction`，`Target` 为 `G:\work\VM\Multipass`；`multipass version` 能同时打印客户端与守护进程版本。

> 备选方案（官方文档）：把机器级环境变量 `MULTIPASS_STORAGE` 设为 `G:\work\VM\Multipass` 并复制数据。该方式存在已知的客户端/守护进程认证回归，仅在 junction 方案不可用时使用。

## 步骤 3：启动 Ubuntu 24.04 VM

在普通 PowerShell 中执行（Multipass 客户端不需要管理员）：

```powershell
multipass launch 24.04 --name pulseops-vps --cpus 2 --memory 4G --disk 40G --timeout 3600
```

- 规格对齐目标 VPS：2 vCPU / 4 GB RAM / 40 GB 磁盘。
- 首次启动需要下载约 600 MB 镜像；`--timeout 3600` 给足时间。若网络过慢，先保持命令运行，或告知我改用本地镜像方案。
- 确认实例与网络：

```powershell
multipass info pulseops-vps
```

- 确认磁盘落在 G:：

```powershell
Get-ChildItem G:\work\VM\Multipass -Recurse -Filter *.vhdx |
  Select-Object FullName, @{n="GB";e={[math]::Round($_.Length/1GB,2)}}
```

## 步骤 4：VM 基础配置

```powershell
multipass shell pulseops-vps
```

以下命令在 VM 内执行。

也可以从仓库直接运行幂等脚本完成 4.1 的时区与基础包安装、4.2 的 Docker 安装、4.3 的镜像配置，并输出组件版本；不包含主机名、`hello-world` 拉取验证与防火墙/SSH 加固：

```powershell
multipass transfer scripts/provision-vm.sh pulseops-vps:/tmp/provision-vm.sh
multipass exec pulseops-vps -- sudo bash /tmp/provision-vm.sh
```

- 脚本默认使用 `https://docker.m.daocloud.io` 作为 Docker registry mirror；可用 `DOCKER_REGISTRY_MIRROR` 覆盖。
- Docker apt 源默认使用 `download.docker.com`，不可达时自动回退到阿里云镜像；可用 `DOCKER_APT_BASE` 指定。
- 默认把 `SUDO_USER`（否则 `ubuntu`）加入 `docker` 组；可用 `DOCKER_GROUP_USER` 覆盖。
- 脚本会安装 `jq` 用于合并 `/etc/docker/daemon.json`，不会覆盖文件中的其他键；4.3 的手动示例会整体覆盖该文件。

以下手动命令保留用于排查与离线环境。

### 4.1 时区、主机名与基础包

```bash
sudo timedatectl set-timezone UTC
sudo hostnamectl set-hostname pulseops-vps
sudo apt-get update
sudo apt-get -y full-upgrade
sudo apt-get install -y ca-certificates curl gnupg ufw age rclone
```

如果 Ubuntu 官方源过慢，可先切换到国内镜像（示例为清华源），再执行 `apt-get update`：

```bash
sudo sed -i \
  -e 's|http://archive.ubuntu.com/ubuntu/|https://mirrors.tuna.tsinghua.edu.cn/ubuntu/|g' \
  -e 's|http://security.ubuntu.com/ubuntu/|https://mirrors.tuna.tsinghua.edu.cn/ubuntu/|g' \
  /etc/apt/sources.list.d/ubuntu.sources
```

### 4.2 安装 Docker Engine 与 Compose v2

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu \
$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" |
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker ubuntu
```

退出并重新进入 VM，使 docker 组生效：

```bash
exit
multipass shell pulseops-vps
docker version
docker compose version
```

如果 `download.docker.com` 过慢，演练环境可退回发行版包；注意这与 5B 的官方安装方式有差异，需在验收文档中注明：

```bash
sudo apt-get install -y docker.io docker-compose-v2
```

### 4.3 Docker Hub 镜像加速

本项目网络访问 Docker Hub 需要镜像源。写入 `daemon.json` 后重启 Docker：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "registry-mirrors": ["https://docker.m.daocloud.io"]
}
EOF
sudo systemctl enable --now docker
sudo systemctl restart docker
docker run --rm hello-world
```

### 4.4 基础验证

```bash
ps -p 1 -o comm=            # 应输出 systemd
systemctl is-system-running # running 或 degraded
ufw version
age --version
rclone version
docker compose version
```

## 步骤 5：Windows 侧域名解析

Multipass 的 Hyper-V 默认交换机通过 DHCP 分配地址，**重启后 IP 可能变化**。获取当前 IP：

```powershell
(multipass info pulseops-vps --format json | ConvertFrom-Json).info.'pulseops-vps'.ipv4[0]
```

在管理员 PowerShell 中写入 hosts（`pulseops.test` 为保留 TLD，不会与真实域名冲突）：

```powershell
$ip = (multipass info pulseops-vps --format json | ConvertFrom-Json).info.'pulseops-vps'.ipv4[0]
$hosts = "$env:SystemRoot\System32\drivers\etc\hosts"
$lines = Get-Content $hosts | Where-Object { $_ -notmatch '\spulseops\.test$' }
Set-Content -Path $hosts -Value $lines -Encoding ASCII
Add-Content -Path $hosts -Value "$ip pulseops.test" -Encoding ASCII
ipconfig /flushdns
```

VM 重启后如 IP 变化，重跑这段脚本即可。

## 步骤 6：基线快照

基础环境完成后打快照，后续加固或演练失败可快速恢复：

```powershell
multipass stop pulseops-vps
multipass snapshot pulseops-vps --name base
multipass start pulseops-vps
```

- 快照存放在 G:，与实例同一介质。
- 恢复前需停止实例：`multipass restore pulseops-vps.base`（具体子命令以 `multipass help snapshot` 为准）。
- 快照不是备份，不替代 5A 的数据库备份演练。

## 步骤 7：重启验证

```powershell
multipass restart pulseops-vps
multipass info pulseops-vps
```

```bash
# VM 内
systemctl is-system-running
docker ps
```

重启后确认：systemd 正常、Docker 自动启动、hosts 记录仍指向正确 IP（必要时重跑步骤 5）。

> 环境就绪后的部署、备份调度与回滚步骤见 [`phase5a-deploy-runbook.md`](phase5a-deploy-runbook.md)。

## 清理与卸载

- 删除实例：`multipass delete pulseops-vps --purge`（会删除 G: 下的实例数据）。
- 卸载 Multipass 前，先在管理员 PowerShell 中**移除 junction**，再卸载，避免卸载程序跟随联接删除 G: 数据：

```powershell
Stop-Service Multipass
cmd /c rmdir "C:\ProgramData\Multipass"
Get-Item "C:\ProgramData\Multipass" -ErrorAction SilentlyContinue  # 应不存在
```

- 确认 `G:\work\VM\Multipass` 中已无需要保留的数据后，再手工删除该目录。

## 已知注意事项

- 宿主机睡眠或休眠会暂停 VM；5A 的重启恢复与定时任务演练前关闭睡眠。
- Docker Desktop 与 Multipass 可共存，前提是 Docker Desktop 使用 WSL2 后端（本机已满足）。
- 镜像缓存与 VM 磁盘会随使用增长；40 GB 为稀疏磁盘，实际占用按需增长，快照会额外占用。
- WSL2 不作为本阶段演练环境：其 ufw/sshd 行为不真实，不能用于防火墙与 SSH 加固验收。

## 参考

- Multipass 数据目录与迁移：<https://canonical.com/multipass/docs/stable/how-to-guides/customise-multipass/configure-where-multipass-stores-external-data/>
- Multipass 快照：<https://documentation.ubuntu.com/multipass/stable/explanation/snapshot/>
- 存储路径相关问题：<https://github.com/canonical/multipass/issues/3184>、<https://github.com/canonical/multipass/issues/4166>
