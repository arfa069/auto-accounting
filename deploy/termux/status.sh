#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/termux/lib.sh
source "$SCRIPT_DIR/lib.sh"
export SVDIR="${SVDIR:-$PREFIX/var/service}"

printf 'Deployed version: %s\n' "$(
    if [[ -f "$BKS_DEPLOYED_VERSION" ]]; then
        tr -d '\r\n' < "$BKS_DEPLOYED_VERSION"
    else
        printf 'none'
    fi
)"
sv status bks-backend || true
sv status bks-nginx || true
sv status bks-release-watcher || true
pg_isready || true
curl --silent --show-error --max-time 3 http://127.0.0.1:18080/health || true
printf '\n'
curl --silent --show-error --max-time 3 http://127.0.0.1:8080/health || true
printf '\n'
printf 'Installed releases: %s\n' "$(find "$BKS_RELEASES_ROOT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l)"
printf 'Database backups: %s\n' "$(find "$BKS_BACKUPS_ROOT" -maxdepth 1 -type f -name '*.dump' 2>/dev/null | wc -l)"
