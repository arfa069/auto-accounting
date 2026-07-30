#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/termux/lib.sh
source "$SCRIPT_DIR/lib.sh"

target_tag="${1:-}"
validate_release_tag "$target_tag"
target_directory="$AA_RELEASES_ROOT/$target_tag"
[[ -d "$target_directory" ]] || die "Release is not installed: $target_tag"
previous_target="$(readlink -f "$AA_CURRENT_LINK" 2>/dev/null || true)"

sv down auto-accounting-release-watcher
log "Release watcher stopped; re-enable it explicitly after rollback acceptance."
load_database_config
backup_path="$AA_BACKUPS_ROOT/$(date -u '+%Y%m%dT%H%M%SZ')-before-rollback-${target_tag}.dump"
PGPASSWORD="$AA_DATABASE_PASSWORD" pg_dump \
    --no-password \
    --username="$AA_DATABASE_USER" \
    --dbname="${AA_DATABASE_URL#jdbc:}" \
    --format=custom \
    --file="$backup_path"
chmod 600 "$backup_path"

atomic_current_link "$target_directory"
sv restart "$AA_SERVICE_NAME"
if ! application_health_check; then
    if [[ -n "$previous_target" && -d "$previous_target" ]]; then
        atomic_current_link "$previous_target"
        sv restart "$AA_SERVICE_NAME"
    fi
    die "Rollback health check failed; the previous application was restored."
fi
printf '%s\n' "$target_tag" > "$AA_DEPLOYED_VERSION"
chmod 600 "$AA_DEPLOYED_VERSION"
log "Application rolled back to $target_tag. The database was not restored."
