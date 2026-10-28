#!/usr/bin/env bash
set -euo pipefail

# Shared post-deploy smoke test. Used by deploy.sh, rollback.sh and acceptance.
#
# Usage: scripts/smoke-test.sh [--url URL] [--resolve HOST:PORT:ADDR] [--insecure]
#
# Environment:
#   SMOKE_BASE_URL  Base URL (default: https://pulseops.test)
#   SMOKE_RESOLVE   Optional curl --resolve value
#   SMOKE_INSECURE  1 to skip TLS verification (local internal CA only)

BASE_URL="${SMOKE_BASE_URL:-https://pulseops.test}"
RESOLVE="${SMOKE_RESOLVE:-}"
INSECURE="${SMOKE_INSECURE:-0}"

usage() {
    cat <<'EOF'
Usage: scripts/smoke-test.sh [--url URL] [--resolve HOST:PORT:ADDR] [--insecure]

Checks:
  /                     -> 200
  /api/v1/monitors      -> 401 anonymously
  /actuator/health      -> 200 with {"status":"UP"}
EOF
}

while (($#)); do
    case "$1" in
        --url)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            BASE_URL="$2"
            shift
            ;;
        --resolve)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            RESOLVE="$2"
            shift
            ;;
        --insecure)
            INSECURE=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
    shift
done

BASE_URL="${BASE_URL%/}"
[[ -n "${BASE_URL}" ]] || { echo "error: base URL is empty" >&2; exit 2; }

CURL=(curl -sS --connect-timeout 5 --max-time 15)
if [[ -n "${RESOLVE}" ]]; then
    CURL+=(--resolve "${RESOLVE}")
fi
if [[ "${INSECURE}" == "1" ]]; then
    CURL+=(--insecure)
fi

root_code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${BASE_URL}/")"
if [[ "${root_code}" != "200" ]]; then
    echo "error: GET / returned ${root_code}, expected 200" >&2
    exit 1
fi

anonymous_code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${BASE_URL}/api/v1/monitors")"
if [[ "${anonymous_code}" != "401" ]]; then
    echo "error: anonymous GET /api/v1/monitors returned ${anonymous_code}, expected 401" >&2
    exit 1
fi

health_response="$("${CURL[@]}" -w $'\n%{http_code}' "${BASE_URL}/actuator/health")"
health_code="${health_response##*$'\n'}"
health_body="${health_response%$'\n'*}"
if [[ "${health_code}" != "200" ]] || [[ "${health_body}" != *'"status":"UP"'* ]]; then
    echo "error: /actuator/health returned ${health_code} ${health_body}, expected 200 UP" >&2
    exit 1
fi

echo "Smoke test passed: root=200 anonymous_monitors=401 health=UP"
