#!/usr/bin/env bash
set -euo pipefail

# Invoked by the systemd OnFailure unit. The Healthchecks URL is a secret;
# never print it in logs or output.

url="${HEALTHCHECKS_PING_URL:-}"
if [[ -z "${url}" ]]; then
    echo "Backup failure notification skipped: HEALTHCHECKS_PING_URL is not set."
    exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "Backup failure notification skipped: curl is not available."
    exit 0
fi

if curl -fsS --max-time 10 --retry 2 "${url%/}/fail" >/dev/null 2>&1; then
    echo "Backup failure notification sent."
else
    echo "Backup failure notification failed." >&2
fi

exit 0
