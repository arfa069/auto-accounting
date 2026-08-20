#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/termux/lib.sh
source "$SCRIPT_DIR/lib.sh"

target_tag="${1:-}"
validate_release_tag "$target_tag"
target_directory="$BKS_RELEASES_ROOT/$target_tag"
[[ -d "$target_directory" ]] || die "Release is not installed: $target_tag"
previous_target="$(readlink -f "$BKS_CURRENT_LINK" 2>/dev/null || true)"

sv down bks-release-watcher
log "Release watcher stopped; re-enable it explicitly after rollback acceptance."
load_database_config
backup_path="$BKS_BACKUPS_ROOT/$(date -u '+%Y%m%dT%H%M%SZ')-before-rollback-${target_tag}.dump"
PGPASSWORD="$BKS_DATABASE_PASSWORD" pg_dump \
    --no-password \
    --username="$BKS_DATABASE_USER" \
    --dbname="${BKS_DATABASE_URL#jdbc:}" \
    --format=custom \
    --file="$backup_path"
chmod 600 "$backup_path"

atomic_current_link "$target_directory"
sv restart "$BKS_SERVICE_NAME"
if ! application_health_check; then
    if [[ -n "$previous_target" && -d "$previous_target" ]]; then
        atomic_current_link "$previous_target"
        sv restart "$BKS_SERVICE_NAME"
    fi
    die "Rollback health check failed; the previous application was restored."
fi
printf '%s\n' "$target_tag" > "$BKS_DEPLOYED_VERSION"
chmod 600 "$BKS_DEPLOYED_VERSION"
log "Application rolled back to $target_tag. The database was not restored."
