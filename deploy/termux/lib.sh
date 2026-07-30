#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

AA_CONFIG_ROOT="${AUTO_ACCOUNTING_CONFIG_ROOT:-$HOME/.config/auto-accounting}"
AA_DATA_ROOT="${AUTO_ACCOUNTING_DATA_ROOT:-$HOME/.local/share/auto-accounting}"
AA_STATE_ROOT="${AUTO_ACCOUNTING_STATE_ROOT:-$HOME/.local/state/auto-accounting}"
AA_RELEASES_ROOT="$AA_DATA_ROOT/releases"
AA_CURRENT_LINK="$AA_DATA_ROOT/current"
AA_BACKEND_ENV="$AA_CONFIG_ROOT/backend.env"
AA_GITHUB_CURL_CONFIG="$AA_CONFIG_ROOT/github.curl.conf"
# Consumed by scripts that source this shared library.
# shellcheck disable=SC2034
AA_DEPLOYED_VERSION="$AA_STATE_ROOT/deployed-version"
# shellcheck disable=SC2034
AA_BACKUPS_ROOT="$AA_STATE_ROOT/backups"
AA_SERVICE_NAME="auto-accounting-backend"
# shellcheck disable=SC2034
AA_REPOSITORY="${AUTO_ACCOUNTING_GITHUB_REPOSITORY:-arfa069/auto-accounting}"
if [[ -n "${PREFIX:-}" ]]; then
    export SVDIR="${SVDIR:-$PREFIX/var/service}"
fi

log() {
    printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

die() {
    log "ERROR: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command is missing: $1"
}

validate_release_tag() {
    [[ "$1" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
        die "Invalid release tag: $1"
}

is_newer_release() {
    local candidate="$1"
    local current="$2"
    validate_release_tag "$candidate"
    validate_release_tag "$current"
    [[ "$candidate" != "$current" ]] &&
        [[ "$(printf '%s\n%s\n' "$candidate" "$current" | sort -V | tail -n 1)" == "$candidate" ]]
}

env_value() {
    local key="$1"
    local file="$2"
    local line value first last
    [[ -f "$file" ]] || die "Configuration file is missing: $file"
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line#"${line%%[![:space:]]*}"}"
        [[ -z "$line" || "$line" == \#* ]] && continue
        if [[ "$line" == export\ * ]]; then
            line="${line#export }"
            line="${line#"${line%%[![:space:]]*}"}"
        fi
        [[ "$line" == "$key="* ]] || continue
        value="${line#*=}"
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        if [[ -n "$value" ]]; then
            first="${value:0:1}"
            last="${value: -1}"
            if [[ "$first" == "$last" && ( "$first" == "'" || "$first" == '"' ) ]]; then
                value="${value:1:${#value}-2}"
            fi
        fi
        printf '%s' "$value"
        return 0
    done < "$file"
    return 1
}

load_database_config() {
    AA_DATABASE_URL="$(env_value AUTO_ACCOUNTING_DATABASE_URL "$AA_BACKEND_ENV")" ||
        die "AUTO_ACCOUNTING_DATABASE_URL is missing."
    AA_DATABASE_USER="$(env_value AUTO_ACCOUNTING_DATABASE_USER "$AA_BACKEND_ENV")" ||
        die "AUTO_ACCOUNTING_DATABASE_USER is missing."
    AA_DATABASE_PASSWORD="$(env_value AUTO_ACCOUNTING_DATABASE_PASSWORD "$AA_BACKEND_ENV")" ||
        die "AUTO_ACCOUNTING_DATABASE_PASSWORD is missing."
    [[ "$AA_DATABASE_URL" == jdbc:postgresql://* ]] ||
        die "AUTO_ACCOUNTING_DATABASE_URL must use PostgreSQL."
    export AA_DATABASE_URL AA_DATABASE_USER AA_DATABASE_PASSWORD
}

github_api() {
    local config_args=()
    if [[ -f "$AA_GITHUB_CURL_CONFIG" ]]; then
        config_args=(--config "$AA_GITHUB_CURL_CONFIG")
    fi
    curl "${config_args[@]}" \
        --fail --silent --show-error --location \
        --header "Accept: application/vnd.github+json" \
        --header "X-GitHub-Api-Version: 2022-11-28" \
        "$@"
}

github_asset() {
    local config_args=()
    if [[ -f "$AA_GITHUB_CURL_CONFIG" ]]; then
        config_args=(--config "$AA_GITHUB_CURL_CONFIG")
    fi
    curl "${config_args[@]}" \
        --fail --silent --show-error --location \
        --header "Accept: application/octet-stream" \
        --header "X-GitHub-Api-Version: 2022-11-28" \
        "$@"
}

atomic_current_link() {
    local target="$1"
    local next_link="$AA_DATA_ROOT/.current.next"
    [[ "$target" == "$AA_RELEASES_ROOT"/v* ]] || die "Refusing unsafe release target."
    rm -f -- "$next_link"
    ln -s "$target" "$next_link"
    mv -fT "$next_link" "$AA_CURRENT_LINK"
}

safe_remove_release() {
    local target="$1"
    [[ "$target" == "$AA_RELEASES_ROOT"/v* ]] || die "Refusing unsafe release removal."
    [[ "$target" != "$(readlink -f "$AA_CURRENT_LINK" 2>/dev/null || true)" ]] ||
        die "Refusing to remove the current release."
    rm -rf -- "$target"
}

database_check() {
    load_database_config
    PGPASSWORD="$AA_DATABASE_PASSWORD" psql \
        --no-password \
        --username="$AA_DATABASE_USER" \
        --dbname="${AA_DATABASE_URL#jdbc:}" \
        --tuples-only --no-align \
        --command="SELECT 1" | grep -qx "1"
}

application_health_check() {
    local deadline=$((SECONDS + 60))
    while (( SECONDS < deadline )); do
        if sv status "$AA_SERVICE_NAME" 2>/dev/null | grep -q "^run:" &&
            database_check &&
            [[ "$(curl --fail --silent --show-error http://127.0.0.1:18080/health 2>/dev/null)" == '{"status":"ok"}' ]] &&
            [[ "$(curl --fail --silent --show-error http://127.0.0.1:8080/health 2>/dev/null)" == '{"status":"ok"}' ]]; then
            return 0
        fi
        sleep 2
    done
    return 1
}
