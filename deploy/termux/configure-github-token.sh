#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/termux/lib.sh
source "$SCRIPT_DIR/lib.sh"

[[ -t 0 ]] || die "Run this command in an interactive Termux terminal."
read -r -s -p "Fine-grained GitHub token (Contents: read): " github_token
printf '\n'
[[ ${#github_token} -ge 20 && "$github_token" =~ ^[A-Za-z0-9_]+$ ]] ||
    die "GitHub token has an unexpected format."
mkdir -p "$AA_CONFIG_ROOT"
umask 077
temporary_config="$AA_CONFIG_ROOT/github.curl.conf.tmp"
cleanup() {
    rm -f -- "$temporary_config"
}
trap cleanup EXIT
{
    printf 'header = "Authorization: Bearer %s"\n' "$github_token"
    printf 'header = "X-GitHub-Api-Version: 2022-11-28"\n'
} > "$temporary_config"
unset github_token
curl --config "$temporary_config" \
    --fail --silent --show-error --location \
    --header "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$AA_REPOSITORY" >/dev/null
mv "$temporary_config" "$AA_GITHUB_CURL_CONFIG"
chmod 600 "$AA_GITHUB_CURL_CONFIG"
log "GitHub release access verified."
rm -f "$PREFIX/var/service/auto-accounting-release-watcher/down"
sv up auto-accounting-release-watcher
