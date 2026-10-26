#!/usr/bin/env bash
set -euo pipefail

umask 077

usage() {
    cat <<'EOF'
Usage: scripts/restore.sh [--force] <backup-file> <target-db>

Restores a pg_dump custom-format backup into a separate database.
The target database is created first. If it already exists the script
refuses to continue unless --force is passed, in which case the existing
database is dropped permanently. Restoring into the compose service's own
database (POSTGRES_DB) or a PostgreSQL maintenance database is always refused.

Environment:
  COMPOSE_SERVICE  Compose service running PostgreSQL (default: postgres)

Example:
  scripts/restore.sh ./backups/pulseops-20261026T120000Z.dump pulseops_restore
EOF
}

force=0
if [[ "${1:-}" == "--force" ]]; then
    force=1
    shift
fi

if [[ $# -ne 2 ]]; then
    usage >&2
    exit 2
fi

backup_file="$1"
target_db="$2"

if [[ ! -f "${backup_file}" ]]; then
    echo "error: backup file not found: ${backup_file}" >&2
    exit 1
fi
if [[ ! -s "${backup_file}" ]]; then
    echo "error: backup file is empty: ${backup_file}" >&2
    exit 1
fi

# PostgreSQL identifiers cannot be parameterized in DDL. Validate before use.
if ! [[ "${target_db}" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]]; then
    echo "error: target-db must match ^[A-Za-z_][A-Za-z0-9_]{0,62}$" >&2
    exit 1
fi

backup_file="$(cd "$(dirname "${backup_file}")" && pwd)/$(basename "${backup_file}")"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

COMPOSE_SERVICE="${COMPOSE_SERVICE:-postgres}"

if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is required" >&2
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "error: docker compose (v2) is required" >&2
    exit 1
fi

source_db="$(docker compose exec -T "${COMPOSE_SERVICE}" \
    sh -c 'printf %s "${POSTGRES_DB:-pulseops}"' | tr -d '\r')"

case "${target_db}" in
    postgres|template0|template1)
        echo "error: refusing to restore into PostgreSQL maintenance database '${target_db}'" >&2
        exit 1
        ;;
esac

if [[ "${target_db}" == "${source_db}" ]]; then
    echo "error: refusing to restore into the source database '${source_db}'; use a separate target database" >&2
    exit 1
fi

echo "Verifying dump with pg_restore --list..."
if ! docker compose exec -T "${COMPOSE_SERVICE}" pg_restore --list < "${backup_file}" > /dev/null; then
    echo "error: backup file is not a readable pg_dump custom-format archive" >&2
    exit 1
fi

exists="$(docker compose exec -T "${COMPOSE_SERVICE}" \
    sh -c 'exec psql -U "${POSTGRES_USER:-pulseops}" -d postgres -tAc "$1"' \
    sh "SELECT 1 FROM pg_database WHERE datname = '${target_db}'" | tr -d '[:space:]')"

if [[ -n "${exists}" ]]; then
    if (( force == 0 )); then
        echo "error: database '${target_db}' already exists; pass --force to replace it" >&2
        exit 1
    fi
    echo "Dropping existing database '${target_db}' (--force)..."
    docker compose exec -T "${COMPOSE_SERVICE}" \
        sh -c 'exec psql -U "${POSTGRES_USER:-pulseops}" -d postgres -v ON_ERROR_STOP=1 -c "$1"' \
        sh "DROP DATABASE \"${target_db}\" WITH (FORCE);"
fi

echo "Creating database '${target_db}'..."
docker compose exec -T "${COMPOSE_SERVICE}" \
    sh -c 'exec psql -U "${POSTGRES_USER:-pulseops}" -d postgres -v ON_ERROR_STOP=1 -c "$1"' \
    sh "CREATE DATABASE \"${target_db}\";"

echo "Restoring '${backup_file}' into '${target_db}'..."
if ! docker compose exec -T "${COMPOSE_SERVICE}" \
    sh -c 'exec pg_restore -U "${POSTGRES_USER:-pulseops}" -d "$1" --no-owner --no-privileges --exit-on-error' \
    sh "${target_db}" < "${backup_file}"; then
    echo "error: restore failed; dropping partially restored database '${target_db}'" >&2
    docker compose exec -T "${COMPOSE_SERVICE}" \
        sh -c 'exec psql -U "${POSTGRES_USER:-pulseops}" -d postgres -v ON_ERROR_STOP=1 -c "$1"' \
        sh "DROP DATABASE \"${target_db}\" WITH (FORCE);" || true
    exit 1
fi

echo "Restore completed: ${backup_file} -> ${target_db}"
echo "For a read-only check, start the application with scheduling disabled:"
echo "  cd backend && DB_URL=jdbc:postgresql://localhost:5432/${target_db} \\"
echo "    PULSEOPS_PROBE_SCHEDULING_ENABLED=false \\"
echo "    PULSEOPS_HEARTBEAT_SCHEDULING_ENABLED=false \\"
echo "    PULSEOPS_NOTIFICATION_SCHEDULING_ENABLED=false \\"
echo "    PULSEOPS_CLEANUP_SCHEDULING_ENABLED=false \\"
echo "    ./mvnw spring-boot:run"
