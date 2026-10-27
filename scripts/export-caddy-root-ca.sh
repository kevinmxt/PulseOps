#!/usr/bin/env bash
set -euo pipefail

# Exports the Caddy local root CA from the running production stack.
# The certificate is public; it must be imported into the host trust store
# only for local Phase 5A verification.
#
# Usage:
#   ./scripts/export-caddy-root-ca.sh [output-file]
#
# Environment:
#   COMPOSE_FILE      Compose file (default: <repo>/compose.prod.yaml)
#   COMPOSE_ENV_FILE  Optional env file passed to Compose
#   COMPOSE_SERVICE   Caddy service name (default: caddy)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

OUTPUT_FILE="${1:-${ROOT_DIR}/caddy-root.crt}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/compose.prod.yaml}"
COMPOSE_SERVICE="${COMPOSE_SERVICE:-caddy}"
CADDY_CA_PATH="/data/caddy/pki/authorities/local/root.crt"

if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is required" >&2
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "error: docker compose (v2) is required" >&2
    exit 1
fi

compose_args=(-f "${COMPOSE_FILE}")
if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
    compose_args+=(--env-file "${COMPOSE_ENV_FILE}")
fi

if ! tmp_file="$(mktemp "${OUTPUT_FILE}.XXXXXX")"; then
    echo "error: cannot create a temporary file next to ${OUTPUT_FILE}" >&2
    exit 1
fi
trap 'rm -f "${tmp_file}"' EXIT

if ! docker compose "${compose_args[@]}" exec -T "${COMPOSE_SERVICE}" \
    cat "${CADDY_CA_PATH}" > "${tmp_file}"; then
    echo "error: could not read ${CADDY_CA_PATH} from service '${COMPOSE_SERVICE}'" >&2
    echo "note: start the production stack first and check COMPOSE_ENV_FILE" >&2
    exit 1
fi

if ! grep -q 'BEGIN CERTIFICATE' "${tmp_file}"; then
    echo "error: exported file is not a PEM certificate" >&2
    exit 1
fi

chmod 644 "${tmp_file}"
mv -f "${tmp_file}" "${OUTPUT_FILE}"
trap - EXIT

echo "Root CA exported: ${OUTPUT_FILE}"
sha256sum "${OUTPUT_FILE}"
