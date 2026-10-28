#!/usr/bin/env bash
set -euo pipefail

# Hardens ufw and sshd on the Linux VM/VPS.
# Run inside the guest, never on the Windows host.
#
# Usage: sudo ./scripts/harden-vps.sh [--dry-run]

DRY_RUN=0
SSH_CONFIG="/etc/ssh/sshd_config"
SSHD_CONFIG_DIR="/etc/ssh/sshd_config.d"
SSHD_DROPIN="${SSHD_CONFIG_DIR}/99-pulseops-hardening.conf"

usage() {
    cat <<'EOF'
Usage: sudo ./scripts/harden-vps.sh [--dry-run]

  --dry-run  Print the planned changes without modifying the system.
EOF
}

log() {
    printf '==> %s\n' "$*"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

run() {
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
    else
        "$@"
    fi
}

assert_sshd_setting() {
    local key="$1" expected="$2" actual
    actual="$("${SSHD_BIN}" -T | awk -v key="${key}" '$1 == key && !seen {print $2; seen=1}')"
    if [[ "${actual}" != "${expected}" ]]; then
        die "sshd effective ${key}=${actual:-<missing>}; expected ${expected}. Check earlier ${SSHD_CONFIG_DIR}/*.conf files."
    fi
}

while (($#)); do
    case "$1" in
        --dry-run)
            DRY_RUN=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "unknown argument: $1"
            ;;
    esac
    shift
done

if [[ "$(uname -s)" != "Linux" ]]; then
    die "run this script inside the Linux VM/VPS, not on the Windows host"
fi

if grep -qi microsoft /proc/version 2>/dev/null; then
    die "WSL is not supported for firewall/SSH hardening; run inside the VM/VPS"
fi

if [[ "${EUID}" -ne 0 ]]; then
    die "run as root: sudo $0"
fi

command -v ufw >/dev/null 2>&1 || die "ufw is required"
command -v systemctl >/dev/null 2>&1 || die "systemctl is required"

SSHD_BIN="$(command -v sshd || true)"
if [[ -z "${SSHD_BIN}" ]]; then
    for candidate in /usr/sbin/sshd /sbin/sshd; do
        if [[ -x "${candidate}" ]]; then
            SSHD_BIN="${candidate}"
            break
        fi
    done
fi
[[ -n "${SSHD_BIN}" ]] || die "sshd is required"

if ! grep -Eq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' "${SSH_CONFIG}"; then
    die "${SSH_CONFIG} does not include ${SSHD_CONFIG_DIR}/*.conf"
fi

sshd_port="$("${SSHD_BIN}" -T | awk '$1 == "port" && !seen {print $2; seen=1}')"
if [[ "${sshd_port}" != "22" ]]; then
    die "sshd listens on port ${sshd_port:-<unknown>}; this script only allows 22/tcp"
fi

log "Resetting ufw to the minimal rule set"
run ufw --force reset
run ufw limit 22/tcp
run ufw allow 80/tcp
run ufw allow 443/tcp
run ufw default deny incoming
run ufw default allow outgoing
run ufw --force enable

if [[ "${DRY_RUN}" -eq 0 ]]; then
    unexpected_rules="$(ufw status | awk '$0 ~ /(ALLOW|LIMIT) IN/ {print $1}' \
        | grep -vE '^(22|80|443)/tcp$' || true)"
    if [[ -n "${unexpected_rules}" ]]; then
        die "unexpected ufw rules present: ${unexpected_rules}"
    fi
else
    log "Would verify that ufw only has 22, 80 and 443 allow/limit rules"
fi

sshd_content="$(cat <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
PermitRootLogin prohibit-password
MaxAuthTries 3
EOF
)"

log "Configuring sshd"
if [[ "${DRY_RUN}" -eq 1 ]]; then
    log "Would write ${SSHD_DROPIN}:"
    printf '%s\n' "${sshd_content}"
    log "Would run: ${SSHD_BIN} -t"
    log "Would run: systemctl reload ssh"
else
    install -d -m 0755 "${SSHD_CONFIG_DIR}"
    backup=""
    if [[ -f "${SSHD_DROPIN}" ]]; then
        backup="$(mktemp "${SSHD_CONFIG_DIR}/.99-pulseops-hardening.XXXXXX")"
        cp -p "${SSHD_DROPIN}" "${backup}"
    fi
    tmp="$(mktemp "${SSHD_CONFIG_DIR}/.99-pulseops-hardening.XXXXXX")"
    printf '%s\n' "${sshd_content}" > "${tmp}"
    chmod 0644 "${tmp}"
    mv -f "${tmp}" "${SSHD_DROPIN}"
    if ! "${SSHD_BIN}" -t; then
        if [[ -n "${backup}" ]]; then
            mv -f "${backup}" "${SSHD_DROPIN}"
        else
            rm -f "${SSHD_DROPIN}"
        fi
        die "sshd configuration validation failed; reverted ${SSHD_DROPIN}"
    fi
    rm -f "${backup}"
    systemctl reload ssh
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
    log "Would run: ufw status verbose"
    log "Would run: sshd -T (passwordauthentication, kbdinteractiveauthentication, pubkeyauthentication, permitrootlogin, maxauthtries)"
    log "Would assert: passwordauthentication=no kbdinteractiveauthentication=no pubkeyauthentication=yes permitrootlogin=without-password maxauthtries=3"
else
    log "ufw status"
    ufw status verbose
    log "sshd effective settings"
    "${SSHD_BIN}" -T | grep -E '^(passwordauthentication|kbdinteractiveauthentication|pubkeyauthentication|permitrootlogin|maxauthtries)'
    assert_sshd_setting passwordauthentication no
    assert_sshd_setting kbdinteractiveauthentication no
    assert_sshd_setting pubkeyauthentication yes
    root_login="$("${SSHD_BIN}" -T | awk '$1 == "permitrootlogin" && !seen {print $2; seen=1}')"
    case "${root_login}" in
        without-password|prohibit-password) ;;
        *) die "sshd effective permitrootlogin=${root_login:-<missing>}; expected prohibit-password" ;;
    esac
    assert_sshd_setting maxauthtries 3
fi

log "Keep the current SSH session open. Open a second SSH session and verify key-based login before closing this one."
log "Recovery: restore the pre-hardening snapshot (multipass restore <instance>.<snapshot>) or use the hypervisor console."

log "Hardening complete"
