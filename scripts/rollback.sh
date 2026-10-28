#!/usr/bin/env bash
set -euo pipefail
umask 022

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

ENV_FILE="${PULSEOPS_ENV_FILE:-/etc/pulseops/pulseops.env}"
STATE_DIR="${PULSEOPS_STATE_DIR:-${ROOT_DIR}}"
HEALTH_TIMEOUT="${ROLLBACK_HEALTH_TIMEOUT_SECONDS:-${DEPLOY_HEALTH_TIMEOUT_SECONDS:-120}}"
DRY_RUN=0
TARGET=""

usage() {
    cat <<'EOF'
Usage: sudo scripts/rollback.sh [--dry-run] [<tag>]

Without <tag>, uses .previous-tag. Refuses when the target image's recorded
schema rank is older than the current database schema, or when its schema
metadata is missing.

Environment:
  PULSEOPS_ENV_FILE               Production env file
  PULSEOPS_STATE_DIR              Deployment state directory
  ROLLBACK_HEALTH_TIMEOUT_SECONDS Health gate timeout (default 120)
EOF
}

log() {
    printf '==> %s\n' "$*"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
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
        -*)
            usage >&2
            die "unknown argument: $1"
            ;;
        *)
            [[ -z "${TARGET}" ]] || { usage >&2; die "only one tag may be specified"; }
            TARGET="$1"
            ;;
    esac
    shift
done

[[ "${EUID}" -eq 0 ]] || die "run as root: sudo $0"
[[ -f "${ENV_FILE}" ]] || die "env file not found: ${ENV_FILE}"
[[ "$(stat -c '%a' "${ENV_FILE}")" == "600" ]] || die "${ENV_FILE} must have mode 600"
if grep -q $'\r' "${ENV_FILE}"; then
    die "${ENV_FILE} contains CRLF; run: sed -i 's/\\r$//' ${ENV_FILE}"
fi
[[ "${HEALTH_TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || die "health timeout must be a positive integer"

set -a
# shellcheck source=/dev/null
. "${ENV_FILE}"
set +a

COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/compose.prod.yaml}"
[[ -f "${COMPOSE_FILE}" ]] || die "compose file not found: ${COMPOSE_FILE}"
export COMPOSE_FILE
if [[ -z "${COMPOSE_PROJECT_NAME:-}" ]]; then
    export COMPOSE_PROJECT_NAME=pulseops
fi
COMPOSE=(docker compose -f "${COMPOSE_FILE}")

command -v docker >/dev/null 2>&1 || die "docker is required"
command -v curl >/dev/null 2>&1 || die "curl is required"
docker compose version >/dev/null 2>&1 || die "docker compose (v2) is required"

read_state() {
    local file="$1"
    if [[ -f "${file}" ]]; then
        tr -d '[:space:]' < "${file}"
    fi
}

running_tag() {
    local cid image
    cid="$("${COMPOSE[@]}" ps -q backend 2>/dev/null || true)"
    [[ -n "${cid}" ]] || return 0
    image="$(docker inspect -f '{{.Config.Image}}' "${cid}" 2>/dev/null || true)"
    printf '%s' "${image##*:}"
}

schema_rank() {
    # shellcheck disable=SC2016
    "${COMPOSE[@]}" exec -T postgres \
        sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT COALESCE(MAX(installed_rank),0) FROM flyway_schema_history WHERE success"' \
        2>/dev/null | tr -d '[:space:]'
}

wait_healthy() {
    local deadline=$((SECONDS + HEALTH_TIMEOUT)) service cid state healthy
    while ((SECONDS < deadline)); do
        healthy=1
        for service in postgres backend caddy; do
            cid="$("${COMPOSE[@]}" ps -q "${service}" 2>/dev/null || true)"
            if [[ -z "${cid}" ]]; then
                healthy=0
                break
            fi
            state="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo unknown)"
            if [[ "${state}" != "healthy" ]]; then
                healthy=0
                break
            fi
        done
        ((healthy)) && return 0
        sleep 3
    done
    return 1
}

smoke() {
    local site="${SITE_ADDRESS:-pulseops.test}"
    local args=(--url "https://${site}")
    if [[ "${TLS_ISSUER:-internal}" == "internal" ]]; then
        args+=(--insecure)
    fi
    if [[ "${site}" == "pulseops.test" ]]; then
        args+=(--resolve "pulseops.test:443:127.0.0.1")
    fi
    "${ROOT_DIR}/scripts/smoke-test.sh" "${args[@]}"
}

schema_refusal() {
    local target_rank="$1" current_rank="$2" detail="$3"
    printf '\nerror: refusing rollback to %s: %s\n' "${TARGET}" "${detail}" >&2
    printf 'Target tag schema rank: %s; current database schema rank: %s.\n' \
        "${target_rank:-unknown}" "${current_rank}" >&2
    printf 'Do not start the old image on a newer schema. Recovery flow:\n' >&2
    printf '  1. Restore the pre-deploy backup into a new database with scripts/restore.sh.\n' >&2
    printf '  2. Verify Flyway history and core tables on the restored database.\n' >&2
    printf '  3. Point DB_URL in %s to the restored database.\n' "${ENV_FILE}" >&2
    printf '  4. Re-run this rollback.\n' >&2
    exit 1
}

rollback_failed() {
    local reason="$1"
    printf '\nerror: %s\n' "${reason}" >&2
    "${COMPOSE[@]}" ps 2>/dev/null || true
    printf 'The rollback was not completed. Restore the pre-deploy backup if the schema is incompatible, then retry.\n' >&2
    exit 1
}

CURRENT_TAG="$(read_state "${STATE_DIR}/.deployed-tag")"
RUNNING_TAG="$(running_tag)"
[[ -n "${CURRENT_TAG}" ]] || CURRENT_TAG="${RUNNING_TAG:-unknown}"

if [[ -z "${TARGET}" ]]; then
    TARGET="$(read_state "${STATE_DIR}/.previous-tag")"
fi
[[ -n "${TARGET}" ]] || die "no target tag; pass <tag> or ensure ${STATE_DIR}/.previous-tag exists"
[[ "${TARGET}" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "invalid image tag: ${TARGET}"

log "Rollback target: ${TARGET} (current: ${CURRENT_TAG})"

for repo in pulseops-backend pulseops-caddy; do
    if ! docker image inspect "${repo}:${TARGET}" >/dev/null 2>&1; then
        die "image not found: ${repo}:${TARGET}"
    fi
done

current_rank="$(schema_rank || true)"
[[ "${current_rank}" =~ ^[1-9][0-9]*$ ]] || die "could not read current Flyway schema rank; ensure postgres is running"

target_schema_file="${STATE_DIR}/.deployed-tags/${TARGET}.schema"
target_rank=""
if [[ -f "${target_schema_file}" ]]; then
    target_rank="$(tr -d '[:space:]' < "${target_schema_file}")"
fi

if [[ -n "${target_rank}" ]]; then
    [[ "${target_rank}" =~ ^[1-9][0-9]*$ ]] || die "invalid schema rank in ${target_schema_file}"
    if (( target_rank < current_rank )); then
        schema_refusal "${target_rank}" "${current_rank}" \
            "target image predates the current database schema"
    fi
    log "Schema check: target rank ${target_rank}, current rank ${current_rank}"
else
    schema_refusal "unknown" "${current_rank}" \
        "schema metadata is missing for ${TARGET}; verify the migrations and record the expected rank in ${target_schema_file} before retrying"
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
    log "Plan"
    printf '  start: IMAGE_TAG=%s %s up -d\n' "${TARGET}" "${COMPOSE[*]}"
    printf '  health gate: wait up to %ss for postgres/backend/caddy\n' "${HEALTH_TIMEOUT}"
    printf '  smoke: %s/scripts/smoke-test.sh\n' "${ROOT_DIR}"
    printf '  state: .deployed-tag=%s, .previous-tag=%s\n' "${TARGET}" "${CURRENT_TAG}"
    exit 0
fi

log "Starting image tag ${TARGET}"
export IMAGE_TAG="${TARGET}"
"${COMPOSE[@]}" up -d || rollback_failed "compose up failed"

log "Waiting for health (timeout ${HEALTH_TIMEOUT}s)"
if ! wait_healthy; then
    rollback_failed "health gate timed out"
fi

log "Running smoke test"
if ! smoke; then
    rollback_failed "smoke test failed"
fi

printf '%s\n' "${TARGET}" > "${STATE_DIR}/.deployed-tag"
previous_to_write="${RUNNING_TAG:-${CURRENT_TAG}}"
if [[ -z "${previous_to_write}" || "${previous_to_write}" == "unknown" || "${previous_to_write}" == "${TARGET}" ]]; then
    previous_to_write="${CURRENT_TAG}"
fi
if [[ "${previous_to_write}" == "${TARGET}" ]]; then
    previous_to_write=""
fi
printf '%s\n' "${previous_to_write}" > "${STATE_DIR}/.previous-tag"
chmod 644 "${STATE_DIR}/.deployed-tag" "${STATE_DIR}/.previous-tag"

log "Rollback complete: ${TARGET} (previous: ${previous_to_write:-none})"
