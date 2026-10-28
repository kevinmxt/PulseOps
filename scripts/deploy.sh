#!/usr/bin/env bash
set -euo pipefail
umask 022

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

ENV_FILE="${PULSEOPS_ENV_FILE:-/etc/pulseops/pulseops.env}"
STATE_DIR="${PULSEOPS_STATE_DIR:-${ROOT_DIR}}"
HEALTH_TIMEOUT="${DEPLOY_HEALTH_TIMEOUT_SECONDS:-120}"
DRY_RUN=0
TAG=""

usage() {
    cat <<'EOF'
Usage: sudo scripts/deploy.sh [--dry-run] [--tag <tag>]

Environment:
  PULSEOPS_ENV_FILE              Production env file (default /etc/pulseops/pulseops.env)
  PULSEOPS_STATE_DIR             Deployment state directory (default repo root)
  DEPLOY_TAG                     Image tag when --tag is omitted
  DEPLOY_HEALTH_TIMEOUT_SECONDS  Health gate timeout (default 120)
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
        --tag)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            TAG="$2"
            shift
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

[[ "${EUID}" -eq 0 ]] || die "run as root: sudo $0"
[[ -f "${ENV_FILE}" ]] || die "env file not found: ${ENV_FILE}"
[[ "$(stat -c '%a' "${ENV_FILE}")" == "600" ]] || die "${ENV_FILE} must have mode 600"
if grep -q $'\r' "${ENV_FILE}"; then
    die "${ENV_FILE} contains CRLF; run: sed -i 's/\\r$//' ${ENV_FILE}"
fi
[[ "${HEALTH_TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || die "DEPLOY_HEALTH_TIMEOUT_SECONDS must be a positive integer"

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

if [[ -z "${TAG}" ]]; then
    TAG="${DEPLOY_TAG:-}"
fi
if [[ -z "${TAG}" ]]; then
    if TAG="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null)"; then
        :
    else
        die "cannot determine image tag; pass --tag <tag> or DEPLOY_TAG"
    fi
fi
[[ "${TAG}" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "invalid image tag: ${TAG}"

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

deploy_failed() {
    local reason="$1"
    printf '\nerror: %s\n' "${reason}" >&2
    "${COMPOSE[@]}" ps 2>/dev/null || true
    if [[ -n "${PREVIOUS_TAG}" && "${PREVIOUS_TAG}" != "unknown" && "${PREVIOUS_TAG}" != "${TAG}" ]]; then
        printf 'Rollback was not performed automatically. To roll back:\n  sudo %s/scripts/rollback.sh %s\n' \
            "${ROOT_DIR}" "${PREVIOUS_TAG}" >&2
    else
        printf 'No usable previous tag. Restore the pre-deploy backup into a new database, point DB_URL to it, then start the previous image.\n' >&2
    fi
    exit 1
}

abort_without_changes() {
    printf '\nerror: %s\n' "$1" >&2
    printf 'No new images were started; the current stack is unchanged. No rollback is required.\n' >&2
    exit 1
}

cleanup_tags() {
    local repo="$1"
    local -a tags=() entries=() ordered=() sorted=()
    local tag created keep

    mapfile -t tags < <(docker image ls --format '{{.Tag}}' "${repo}" 2>/dev/null | grep -v '^<none>$' || true)
    if (( ${#tags[@]} == 0 )); then
        return 0
    fi
    for tag in "${tags[@]}"; do
        created="$(docker image inspect -f '{{.Created}}' "${repo}:${tag}" 2>/dev/null || true)"
        if [[ -n "${created}" ]]; then
            entries+=("${created} ${tag}")
        fi
    done
    if (( ${#entries[@]} > 0 )); then
        mapfile -t sorted < <(printf '%s\n' "${entries[@]}" | sort -r | awk '{print $2}')
    fi

    ordered=("${TAG}")
    if [[ -n "${PREVIOUS_TAG}" && "${PREVIOUS_TAG}" != "unknown" && "${PREVIOUS_TAG}" != "${TAG}" ]]; then
        ordered+=("${PREVIOUS_TAG}")
    fi
    keep=" ${ordered[*]} "
    for tag in "${sorted[@]}"; do
        [[ "${keep}" == *" ${tag} "* ]] && continue
        ordered+=("${tag}")
        keep+=" ${tag} "
        (( ${#ordered[@]} >= 5 )) && break
    done

    for tag in "${tags[@]}"; do
        if [[ "${keep}" != *" ${tag} "* ]]; then
            log "Removing old image tag: ${repo}:${tag}"
            docker rmi "${repo}:${tag}" >/dev/null 2>&1 || true
        fi
    done
}

CURRENT_TAG="$(read_state "${STATE_DIR}/.deployed-tag")"
if [[ -z "${CURRENT_TAG}" ]]; then
    CURRENT_TAG="$(running_tag)"
fi
[[ -n "${CURRENT_TAG}" ]] || CURRENT_TAG="unknown"
PREVIOUS_TAG="${CURRENT_TAG}"

log "Deploy tag: ${TAG} (current: ${CURRENT_TAG}, previous: ${PREVIOUS_TAG})"

log "Preflight checks"
"${COMPOSE[@]}" config -q || abort_without_changes "compose configuration is invalid"
free_kb="$(df -Pk "${ROOT_DIR}" | awk 'NR == 2 {print $4}')"
[[ "${free_kb}" =~ ^[0-9]+$ ]] || abort_without_changes "could not determine free disk space"
if (( free_kb < 2097152 )); then
    abort_without_changes "less than 2 GiB free on ${ROOT_DIR}"
fi

current_ok=1
for service in postgres backend caddy; do
    cid="$("${COMPOSE[@]}" ps -q "${service}" 2>/dev/null || true)"
    state="missing"
    if [[ -n "${cid}" ]]; then
        state="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo unknown)"
    fi
    printf '  %s: %s\n' "${service}" "${state}"
    [[ "${state}" == "healthy" ]] || current_ok=0
done
if (( current_ok == 0 )); then
    log "warning: current stack is not fully healthy; deploy will continue"
fi

if [[ "${CURRENT_TAG}" != "unknown" && ! -f "${STATE_DIR}/.deployed-tags/${CURRENT_TAG}.schema" ]]; then
    rank="$(schema_rank || true)"
    if [[ "${rank}" =~ ^[1-9][0-9]*$ ]]; then
        if [[ "${DRY_RUN}" -eq 1 ]]; then
            log "Would record schema rank ${rank} for current tag ${CURRENT_TAG}"
        else
            mkdir -p "${STATE_DIR}/.deployed-tags"
            printf '%s\n' "${rank}" > "${STATE_DIR}/.deployed-tags/${CURRENT_TAG}.schema"
            chmod 644 "${STATE_DIR}/.deployed-tags/${CURRENT_TAG}.schema"
        fi
    fi
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
    log "Plan"
    printf '  backup: %s/scripts/backup.sh\n' "${ROOT_DIR}"
    printf '  build: IMAGE_TAG=%s %s build\n' "${TAG}" "${COMPOSE[*]}"
    printf '  start: IMAGE_TAG=%s %s up -d\n' "${TAG}" "${COMPOSE[*]}"
    printf '  health gate: wait up to %ss for postgres/backend/caddy\n' "${HEALTH_TIMEOUT}"
    printf '  smoke: %s/scripts/smoke-test.sh\n' "${ROOT_DIR}"
    printf '  state: %s/.deployed-tag, .previous-tag, .deployed-tags/%s.schema\n' \
        "${STATE_DIR}" "${TAG}"
    printf '  cleanup: keep 5 image tags per image and prune build cache\n'
    exit 0
fi

log "Pre-deploy backup"
if ! "${ROOT_DIR}/scripts/backup.sh"; then
    abort_without_changes "pre-deploy backup failed; deployment aborted before build"
fi

log "Building images with tag ${TAG}"
export IMAGE_TAG="${TAG}"
"${COMPOSE[@]}" build || abort_without_changes "image build failed"

log "Starting services"
"${COMPOSE[@]}" up -d || deploy_failed "compose up failed"

log "Waiting for health (timeout ${HEALTH_TIMEOUT}s)"
if ! wait_healthy; then
    deploy_failed "health gate timed out"
fi

log "Running smoke test"
if ! smoke; then
    deploy_failed "smoke test failed"
fi

rank="$(schema_rank || true)"
[[ "${rank}" =~ ^[1-9][0-9]*$ ]] || deploy_failed "could not read Flyway schema rank"

mkdir -p "${STATE_DIR}/.deployed-tags"
previous_to_write="${PREVIOUS_TAG}"
if [[ "${previous_to_write}" == "unknown" ]]; then
    previous_to_write=""
fi
printf '%s\n' "${TAG}" > "${STATE_DIR}/.deployed-tag"
printf '%s\n' "${previous_to_write}" > "${STATE_DIR}/.previous-tag"
printf '%s\n' "${rank}" > "${STATE_DIR}/.deployed-tags/${TAG}.schema"
chmod 644 "${STATE_DIR}/.deployed-tag" "${STATE_DIR}/.previous-tag" \
    "${STATE_DIR}/.deployed-tags/${TAG}.schema"

log "Cleaning old image tags"
cleanup_tags "pulseops-backend"
cleanup_tags "pulseops-caddy"
log "Pruning build cache"
if ! docker builder prune -f >/dev/null 2>&1; then
    log "warning: build cache prune failed"
fi

log "Deployment complete: ${TAG} (previous: ${previous_to_write:-none}, schema rank: ${rank})"
