#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/termux/lib.sh
source "$SCRIPT_DIR/lib.sh"

require_command curl
require_command jq
require_command pg_dump
require_command psql
require_command sha256sum
require_command sort
require_command sv
require_command tar

mkdir -p "$BKS_RELEASES_ROOT" "$BKS_STATE_ROOT" "$BKS_BACKUPS_ROOT"
lock_directory="$BKS_STATE_ROOT/deploy.lock"
if ! mkdir "$lock_directory" 2>/dev/null; then
    die "Another deployment is already running."
fi
temporary_directory="$(mktemp -d "$BKS_STATE_ROOT/deploy.XXXXXX")"
switched=false
previous_target=""

cleanup() {
    local exit_code=$?
    if (( exit_code != 0 )) && [[ "$switched" == "true" ]]; then
        log "Deployment failed after switching releases; restoring the previous application."
        set +e
        if [[ -n "$previous_target" && -d "$previous_target" ]]; then
            atomic_current_link "$previous_target"
            ensure_service_runsv "$BKS_SERVICE_NAME" >/dev/null 2>&1 || true
            sv restart "$BKS_SERVICE_NAME" >/dev/null
        else
            sv down "$BKS_SERVICE_NAME" >/dev/null
        fi
        set -e
    fi
    rm -rf -- "$temporary_directory"
    rmdir "$lock_directory" 2>/dev/null || true
    exit "$exit_code"
}
trap cleanup EXIT

github_api \
    "https://api.github.com/repos/$BKS_REPOSITORY/releases?per_page=20" \
    --output "$temporary_directory/releases.json"
release_json="$(
    jq -c '
        [
            .[]
            | select(.draft == false)
            | select(.prerelease == true)
            | select(.tag_name | test("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"))
        ]
        | sort_by(.published_at)
        | last // empty
    ' "$temporary_directory/releases.json"
)"
[[ -n "$release_json" ]] || die "No published version release is available."
release_tag="$(jq -r '.tag_name' <<< "$release_json")"
validate_release_tag "$release_tag"

if [[ -f "$BKS_DEPLOYED_VERSION" ]]; then
    deployed_tag="$(tr -d '\r\n' < "$BKS_DEPLOYED_VERSION")"
    validate_release_tag "$deployed_tag"
    if [[ "$deployed_tag" == "$release_tag" ]]; then
        log "$release_tag is already deployed."
        exit 0
    fi
    if ! is_newer_release "$release_tag" "$deployed_tag"; then
        log "Latest prerelease $release_tag is not newer than deployed $deployed_tag."
        exit 0
    fi
fi

backend_asset="bks-backend-${release_tag}.tar.gz"
for asset_name in "$backend_asset" release-manifest.json checksums.sha256; do
    asset_url="$(
        jq -r --arg name "$asset_name" \
            '.assets[] | select(.name == $name) | .url' <<< "$release_json"
    )"
    [[ -n "$asset_url" && "$asset_url" != "null" ]] ||
        die "Release asset is missing: $asset_name"
    github_asset "$asset_url" --output "$temporary_directory/$asset_name"
done

verify_checksum() {
    local asset_name="$1"
    local checksum_line
    checksum_line="$(
        awk -v name="$asset_name" '$2 == name { print; found = 1 } END { if (!found) exit 1 }' \
            "$temporary_directory/checksums.sha256"
    )" || die "Checksum entry is missing: $asset_name"
    (
        cd "$temporary_directory"
        printf '%s\n' "$checksum_line" | sha256sum --check -
    )
}

verify_checksum "$backend_asset"
verify_checksum release-manifest.json
jq -e \
    --arg tag "$release_tag" \
    --arg asset "$backend_asset" \
    '.tag == $tag
        and .backendAsset == $asset
        and .javaMajor == 17
        and .internalHttpPrerelease == true
        and (.commit | test("^[0-9a-f]{40}$"))' \
    "$temporary_directory/release-manifest.json" >/dev/null

tar -tzf "$temporary_directory/$backend_asset" > "$temporary_directory/archive-files.txt"
if grep -Eq '(^/|(^|/)\.\.(/|$))' "$temporary_directory/archive-files.txt"; then
    die "Release archive contains an unsafe path."
fi
top_level_count="$(
    cut -d/ -f1 "$temporary_directory/archive-files.txt" |
        sed '/^$/d' |
        sort -u |
        wc -l |
        tr -d ' '
)"
[[ "$top_level_count" == "1" ]] || die "Release archive must contain one top-level directory."

load_database_config
backup_path="$BKS_BACKUPS_ROOT/$(date -u '+%Y%m%dT%H%M%SZ')-${release_tag}.dump"
log "Creating PostgreSQL backup for $release_tag."
PGPASSWORD="$BKS_DATABASE_PASSWORD" pg_dump \
    --no-password \
    --username="$BKS_DATABASE_USER" \
    --dbname="${BKS_DATABASE_URL#jdbc:}" \
    --format=custom \
    --file="$backup_path"
chmod 600 "$backup_path"

release_directory="$BKS_RELEASES_ROOT/$release_tag"
if [[ -e "$release_directory" ]]; then
    safe_remove_release "$release_directory"
fi
mkdir "$release_directory"
tar -xzf "$temporary_directory/$backend_asset" \
    --strip-components=1 \
    --directory="$release_directory"
cp "$temporary_directory/release-manifest.json" "$release_directory/release-manifest.json"
chmod -R a-w "$release_directory"

previous_target="$(readlink -f "$BKS_CURRENT_LINK" 2>/dev/null || true)"
atomic_current_link "$release_directory"
switched=true
ensure_service_runsv "$BKS_SERVICE_NAME" ||
    die "Runit supervision for $BKS_SERVICE_NAME could not be restored."
sv restart "$BKS_SERVICE_NAME"
application_health_check || die "Application health checks did not pass within 60 seconds."

printf '%s\n' "$release_tag" > "$BKS_DEPLOYED_VERSION"
chmod 600 "$BKS_DEPLOYED_VERSION"
switched=false
log "Deployment of $release_tag succeeded."

mapfile -t old_releases < <(
    find "$BKS_RELEASES_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'v*' \
        -printf '%T@ %p\n' |
        sort -nr |
        sed -n '6,$s/^[^ ]* //p'
)
for old_release in "${old_releases[@]}"; do
    safe_remove_release "$old_release"
done
mapfile -t old_backups < <(
    find "$BKS_BACKUPS_ROOT" -mindepth 1 -maxdepth 1 -type f -name '*.dump' \
        -printf '%T@ %p\n' |
        sort -nr |
        sed -n '8,$s/^[^ ]* //p'
)
for old_backup in "${old_backups[@]}"; do
    [[ "$old_backup" == "$BKS_BACKUPS_ROOT"/*.dump ]] ||
        die "Refusing unsafe backup removal."
    rm -f -- "$old_backup"
done
