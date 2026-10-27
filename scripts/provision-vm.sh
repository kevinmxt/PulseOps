#!/usr/bin/env bash
set -euo pipefail

# Provisions base packages for PulseOps on Ubuntu 24.04.
# Installs Docker Engine + Compose v2, ufw, age and rclone, configures a
# Docker registry mirror and sets the timezone to UTC.
# Does not configure firewall rules or SSH hardening; see harden-vps.sh.
#
# Usage: sudo ./scripts/provision-vm.sh
#
# Environment:
#   DOCKER_REGISTRY_MIRROR  Registry mirror to add (default: https://docker.m.daocloud.io)
#   DOCKER_GROUP_USER       User to add to the docker group (default: SUDO_USER, else ubuntu)
#   DOCKER_APT_BASE         Docker apt repository base (default: download.docker.com,
#                           which falls back to the Aliyun mirror when unreachable;
#                           any other value is used as-is)

DOCKER_REGISTRY_MIRROR="${DOCKER_REGISTRY_MIRROR:-https://docker.m.daocloud.io}"
DOCKER_GROUP_USER="${DOCKER_GROUP_USER:-}"
DOCKER_APT_OFFICIAL="https://download.docker.com/linux/ubuntu"
DOCKER_APT_FALLBACK="https://mirrors.aliyun.com/docker-ce/linux/ubuntu"
DOCKER_APT_BASE="${DOCKER_APT_BASE:-}"

log() {
    printf '==> %s\n' "$*"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

if [[ "${EUID}" -ne 0 ]]; then
    die "run as root: sudo $0"
fi

if [[ ! -r /etc/os-release ]]; then
    die "/etc/os-release not found; Ubuntu 24.04 is required"
fi

# shellcheck disable=SC1091
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" || "${VERSION_ID:-}" != "24.04" ]]; then
    die "Ubuntu 24.04 is required (found ${PRETTY_NAME:-unknown})"
fi

while [[ "${DOCKER_REGISTRY_MIRROR}" == */ ]]; do
    DOCKER_REGISTRY_MIRROR="${DOCKER_REGISTRY_MIRROR%/}"
done
if [[ -z "${DOCKER_REGISTRY_MIRROR}" ]]; then
    die "DOCKER_REGISTRY_MIRROR must not be empty"
fi

export DEBIAN_FRONTEND=noninteractive
APT_GET=(
    apt-get
    -o Acquire::Retries=3
    -o Acquire::http::Timeout=30
    -o Acquire::https::Timeout=30
)

log "Installing base packages"
"${APT_GET[@]}" update
"${APT_GET[@]}" install -y --no-install-recommends \
    ca-certificates \
    curl \
    gnupg \
    jq \
    ufw \
    age \
    rclone

install -m 0755 -d /etc/apt/keyrings
architecture="$(dpkg --print-architecture)"
codename="${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}"
[[ -n "${codename}" ]] || die "could not determine the Ubuntu codename"

docker_source="/etc/apt/sources.list.d/docker.list"
if [[ -z "${DOCKER_APT_BASE}" ]]; then
    existing_base=""
    if [[ -f "${docker_source}" ]]; then
        existing_bases="$(sed -n 's/^deb \[[^]]*\] \([^ ]*\) .*/\1/p' "${docker_source}")"
        existing_base="${existing_bases%%$'\n'*}"
    fi
    DOCKER_APT_BASE="${existing_base:-${DOCKER_APT_OFFICIAL}}"
fi

write_docker_repo() {
    local base="$1" key_tmp source_tmp
    if [[ ! -s /etc/apt/keyrings/docker.asc ]]; then
        key_tmp="$(mktemp /etc/apt/keyrings/.docker.asc.XXXXXX)" || return 1
        if ! curl -fsSL --retry 3 --connect-timeout 10 --max-time 60 \
            "${base%/}/gpg" -o "${key_tmp}"; then
            rm -f "${key_tmp}"
            return 1
        fi
        if ! chmod a+r "${key_tmp}" \
            || ! mv -f "${key_tmp}" /etc/apt/keyrings/docker.asc; then
            rm -f "${key_tmp}"
            return 1
        fi
    fi
    source_tmp="$(mktemp /etc/apt/sources.list.d/.docker.list.XXXXXX)" || return 1
    if ! printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] %s %s stable\n' \
        "${architecture}" "${base}" "${codename}" > "${source_tmp}" \
        || ! chmod 0644 "${source_tmp}"; then
        rm -f "${source_tmp}"
        return 1
    fi
    if ! cmp -s "${source_tmp}" "${docker_source}"; then
        mv -f "${source_tmp}" "${docker_source}" \
            || { rm -f "${source_tmp}"; return 1; }
    else
        rm -f "${source_tmp}"
    fi
    chmod 0644 "${docker_source}" || return 1
}

log "Adding the Docker apt repository (${DOCKER_APT_BASE})"
if ! write_docker_repo "${DOCKER_APT_BASE}"; then
    if [[ "${DOCKER_APT_BASE}" == "${DOCKER_APT_OFFICIAL}" ]]; then
        log "warning: ${DOCKER_APT_BASE} is unreachable; falling back to ${DOCKER_APT_FALLBACK}"
        write_docker_repo "${DOCKER_APT_FALLBACK}" \
            || die "could not reach Docker apt repository ${DOCKER_APT_FALLBACK}"
    else
        die "could not reach Docker apt repository ${DOCKER_APT_BASE}"
    fi
fi

"${APT_GET[@]}" update
"${APT_GET[@]}" install -y --no-install-recommends \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin

if [[ -z "${DOCKER_GROUP_USER}" ]]; then
    if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
        DOCKER_GROUP_USER="${SUDO_USER}"
    elif id ubuntu >/dev/null 2>&1; then
        DOCKER_GROUP_USER="ubuntu"
    fi
fi

if [[ -n "${DOCKER_GROUP_USER}" ]]; then
    id "${DOCKER_GROUP_USER}" >/dev/null 2>&1 \
        || die "DOCKER_GROUP_USER does not exist: ${DOCKER_GROUP_USER}"
    user_groups="$(id -nG "${DOCKER_GROUP_USER}")"
    if [[ " ${user_groups} " != *" docker "* ]]; then
        log "Adding ${DOCKER_GROUP_USER} to the docker group"
        usermod -aG docker "${DOCKER_GROUP_USER}"
        log "Re-login is required for the docker group to take effect for ${DOCKER_GROUP_USER}"
    fi
fi

log "Configuring Docker registry mirror"
install -d -m 0755 /etc/docker
daemon_config="/etc/docker/daemon.json"
daemon_tmp="$(mktemp /etc/docker/.daemon.json.XXXXXX)"
cleanup() {
    rm -f "${daemon_tmp}"
}
trap cleanup EXIT

if [[ -f "${daemon_config}" ]]; then
    jq -e . "${daemon_config}" >/dev/null 2>&1 \
        || die "${daemon_config} is not valid JSON; fix it before re-running"
    jq --arg mirror "${DOCKER_REGISTRY_MIRROR}" \
        '."registry-mirrors" = (((."registry-mirrors" // []) | map(select((gsub("/+$"; "")) != $mirror))) + [$mirror])' \
        "${daemon_config}" > "${daemon_tmp}"
else
    jq -n --arg mirror "${DOCKER_REGISTRY_MIRROR}" \
        '{"registry-mirrors": [$mirror]}' > "${daemon_tmp}"
fi
chmod 0644 "${daemon_tmp}"

daemon_changed=0
if ! cmp -s "${daemon_tmp}" "${daemon_config}"; then
    mv -f "${daemon_tmp}" "${daemon_config}"
    daemon_changed=1
fi

log "Enabling Docker"
systemctl enable docker
if [[ "${daemon_changed}" -eq 1 ]]; then
    systemctl restart docker
else
    systemctl start docker
fi

log "Setting the timezone to UTC"
if [[ "$(timedatectl show -p Timezone --value)" != "UTC" ]]; then
    timedatectl set-timezone UTC
fi

log "Verifying installed components"
docker --version
docker compose version
ufw version | sed -n '1p'
age --version
rclone version | sed -n '1p'
printf 'Timezone: %s\n' "$(timedatectl show -p Timezone --value)"
printf 'Docker mirrors: %s\n' "$(docker info --format '{{json .RegistryConfig.Mirrors}}')"

log "Provisioning complete"
