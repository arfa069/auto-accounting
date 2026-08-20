#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
poll_seconds="${BKS_RELEASE_POLL_SECONDS:-300}"
[[ "$poll_seconds" =~ ^[0-9]+$ ]] || {
    printf 'BKS_RELEASE_POLL_SECONDS must be an integer.\n' >&2
    exit 1
}

while true; do
    "$SCRIPT_DIR/deploy-release.sh" || true
    sleep "$poll_seconds"
done
