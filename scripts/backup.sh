#!/usr/bin/env bash
set -euo pipefail

# Backups contain password hashes and heartbeat token digests. Keep them private.
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_RETENTION_COUNT="${BACKUP_RETENTION_COUNT:-7}"
COMPOSE_SERVICE="${COMPOSE_SERVICE:-postgres}"

if ! [[ "${BACKUP_RETENTION_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: BACKUP_RETENTION_COUNT must be a positive integer" >&2
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

cleanup() {
    rm -f "${tmp_file}"
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
trap - EXIT

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

echo "Retained backups for '${db_name}': $(( ${#backups[@]} > BACKUP_RETENTION_COUNT ? BACKUP_RETENTION_COUNT : ${#backups[@]} ))/${BACKUP_RETENTION_COUNT}"
