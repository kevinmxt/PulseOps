#!/usr/bin/env bash
set -euo pipefail

# Backups contain password hashes and heartbeat token digests. Keep them private.
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_RETENTION_COUNT="${BACKUP_RETENTION_COUNT:-7}"
BACKUP_OFFSITE_RETENTION_COUNT="${BACKUP_OFFSITE_RETENTION_COUNT:-14}"
COMPOSE_SERVICE="${COMPOSE_SERVICE:-postgres}"
AGE_RECIPIENT="${AGE_RECIPIENT:-}"
BACKUP_RCLONE_REMOTE="${BACKUP_RCLONE_REMOTE:-}"
BACKUP_RCLONE_PATH="${BACKUP_RCLONE_PATH:-}"
RCLONE_CONFIG="${RCLONE_CONFIG:-/etc/pulseops/rclone.conf}"
HEALTHCHECKS_PING_URL="${HEALTHCHECKS_PING_URL:-}"

if ! [[ "${BACKUP_RETENTION_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: BACKUP_RETENTION_COUNT must be a positive integer" >&2
    exit 1
fi
if ! [[ "${BACKUP_OFFSITE_RETENTION_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: BACKUP_OFFSITE_RETENTION_COUNT must be a positive integer" >&2
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is required" >&2
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "error: docker compose (v2) is required" >&2
    exit 1
fi

offsite_enabled=0
if [[ -n "${AGE_RECIPIENT}" || -n "${BACKUP_RCLONE_REMOTE}" || -n "${BACKUP_RCLONE_PATH}" ]]; then
    if [[ -z "${AGE_RECIPIENT}" || -z "${BACKUP_RCLONE_REMOTE}" || -z "${BACKUP_RCLONE_PATH}" ]]; then
        echo "error: AGE_RECIPIENT, BACKUP_RCLONE_REMOTE and BACKUP_RCLONE_PATH must be set together" >&2
        exit 1
    fi
    command -v age >/dev/null 2>&1 || { echo "error: age is required for offsite backups" >&2; exit 1; }
    command -v rclone >/dev/null 2>&1 || { echo "error: rclone is required for offsite backups" >&2; exit 1; }
    command -v jq >/dev/null 2>&1 || { echo "error: jq is required for offsite backups" >&2; exit 1; }
    if [[ ! -r "${RCLONE_CONFIG}" ]]; then
        echo "error: rclone config is not readable: ${RCLONE_CONFIG}" >&2
        exit 1
    fi
    RCLONE_OPTS=(--config "${RCLONE_CONFIG}" --contimeout 10s --timeout 5m)
    offsite_enabled=1
fi

if [[ -n "${HEALTHCHECKS_PING_URL}" ]] && ! command -v curl >/dev/null 2>&1; then
    echo "error: curl is required for Healthchecks pings" >&2
    exit 1
fi

if [[ ! -d "${BACKUP_DIR}" ]]; then
    mkdir -p "${BACKUP_DIR}"
    chmod 700 "${BACKUP_DIR}"
fi

db_name="$(docker compose exec -T "${COMPOSE_SERVICE}" \
    sh -c 'printf %s "${POSTGRES_DB:-pulseops}"' | tr -d '\r')"
if [[ -z "${db_name}" ]]; then
    echo "error: could not read POSTGRES_DB from service ${COMPOSE_SERVICE}" >&2
    exit 1
fi
if ! [[ "${db_name}" =~ ^[A-Za-z0-9_.-]{1,63}$ ]]; then
    echo "error: unsupported POSTGRES_DB name for backup filename: '${db_name}'" >&2
    exit 1
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="${BACKUP_DIR%/}/${db_name}-${timestamp}.dump"
tmp_file="${backup_file}.partial"
encrypted_file="${backup_file}.age"

cleanup() {
    rm -f "${tmp_file}" "${encrypted_file}"
}
trap cleanup EXIT

echo "Backing up database '${db_name}' from service '${COMPOSE_SERVICE}'..."
docker compose exec -T "${COMPOSE_SERVICE}" \
    sh -c 'exec pg_dump -U "${POSTGRES_USER:-pulseops}" -d "${POSTGRES_DB:-pulseops}" --format=custom' \
    > "${tmp_file}"

if [[ ! -s "${tmp_file}" ]]; then
    echo "error: pg_dump produced an empty file" >&2
    exit 1
fi

echo "Verifying dump with pg_restore --list..."
if ! docker compose exec -T "${COMPOSE_SERVICE}" pg_restore --list < "${tmp_file}" > /dev/null; then
    echo "error: dump self-check failed; backup discarded" >&2
    exit 1
fi

chmod 600 "${tmp_file}"
mv -f "${tmp_file}" "${backup_file}"

echo "Backup written: ${backup_file} ($(du -h "${backup_file}" | cut -f1))"
echo "note: the dump contains password hashes and heartbeat token digests; keep it private."

shopt -s nullglob
backups=("${BACKUP_DIR%/}/${db_name}-"*.dump)
if (( ${#backups[@]} > BACKUP_RETENTION_COUNT )); then
    excess=$(( ${#backups[@]} - BACKUP_RETENTION_COUNT ))
    for ((i = 0; i < excess; i++)); do
        echo "Removing old backup: ${backups[$i]}"
        rm -f -- "${backups[$i]}"
    done
fi
shopt -u nullglob

echo "Retained local backups for '${db_name}': $(( ${#backups[@]} > BACKUP_RETENTION_COUNT ? BACKUP_RETENTION_COUNT : ${#backups[@]} ))/${BACKUP_RETENTION_COUNT}"

if [[ "${offsite_enabled}" -eq 1 ]]; then
    echo "Encrypting backup with age..."
    rm -f "${encrypted_file}"
    if ! age --encrypt --recipient "${AGE_RECIPIENT}" \
        --output "${encrypted_file}" "${backup_file}"; then
        echo "error: age encryption failed" >&2
        exit 1
    fi
    chmod 600 "${encrypted_file}"

    remote_dir="${BACKUP_RCLONE_REMOTE}:${BACKUP_RCLONE_PATH%/}"
    remote_file="${remote_dir}/$(basename "${encrypted_file}")"

    echo "Uploading encrypted backup to ${BACKUP_RCLONE_REMOTE}:${BACKUP_RCLONE_PATH%/}..."
    if ! rclone "${RCLONE_OPTS[@]}" copyto "${encrypted_file}" "${remote_file}"; then
        echo "error: rclone upload failed" >&2
        exit 1
    fi

    local_size="$(wc -c < "${encrypted_file}" | tr -d '[:space:]')"
    if ! remote_size_json="$(rclone "${RCLONE_OPTS[@]}" size "${remote_file}" --json)"; then
        echo "error: could not read remote object size" >&2
        exit 1
    fi
    remote_count="$(printf '%s' "${remote_size_json}" | jq -r '.count')"
    remote_bytes="$(printf '%s' "${remote_size_json}" | jq -r '.bytes')"
    if [[ "${remote_count}" != "1" || "${remote_bytes}" != "${local_size}" ]]; then
        echo "error: remote object verification failed (count=${remote_count:-?}, bytes=${remote_bytes:-?}, local=${local_size})" >&2
        exit 1
    fi
    echo "Verified remote object: $(basename "${encrypted_file}") (${remote_bytes} bytes)"

    if ! remote_listing="$(rclone "${RCLONE_OPTS[@]}" lsf "${remote_dir}" --files-only)"; then
        echo "error: could not list remote backups" >&2
        exit 1
    fi
    remote_backups=()
    while IFS= read -r name; do
        if [[ "${name}" == "${db_name}-"*.dump.age ]]; then
            remote_backups+=("${name}")
        fi
    done < <(printf '%s\n' "${remote_listing}" | sort)
    if (( ${#remote_backups[@]} > BACKUP_OFFSITE_RETENTION_COUNT )); then
        excess=$(( ${#remote_backups[@]} - BACKUP_OFFSITE_RETENTION_COUNT ))
        for ((i = 0; i < excess; i++)); do
            echo "Removing old remote backup: ${remote_backups[$i]}"
            if ! rclone "${RCLONE_OPTS[@]}" deletefile "${remote_dir}/${remote_backups[$i]}"; then
                echo "error: could not remove remote backup ${remote_backups[$i]}" >&2
                exit 1
            fi
        done
    fi
    echo "Retained remote backups: $(( ${#remote_backups[@]} > BACKUP_OFFSITE_RETENTION_COUNT ? BACKUP_OFFSITE_RETENTION_COUNT : ${#remote_backups[@]} ))/${BACKUP_OFFSITE_RETENTION_COUNT}"

    rm -f "${encrypted_file}"
else
    echo "Offsite backup skipped: AGE_RECIPIENT, BACKUP_RCLONE_REMOTE and BACKUP_RCLONE_PATH are not all set."
fi

if [[ -n "${HEALTHCHECKS_PING_URL}" ]]; then
    if ! curl -fsS --max-time 10 --retry 3 "${HEALTHCHECKS_PING_URL}" >/dev/null 2>&1; then
        echo "error: Healthchecks success ping failed" >&2
        exit 1
    fi
    echo "Healthchecks success ping sent."
else
    echo "Healthchecks success ping skipped: HEALTHCHECKS_PING_URL is not set."
fi

echo "Backup complete."
