#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

BKS_CONFIG_ROOT="${BKS_CONFIG_ROOT:-$HOME/.config/bks}"
BKS_DATA_ROOT="${BKS_DATA_ROOT:-$HOME/.local/share/bks}"
BKS_STATE_ROOT="${BKS_STATE_ROOT:-$HOME/.local/state/bks}"
BKS_RELEASES_ROOT="$BKS_DATA_ROOT/releases"
BKS_CURRENT_LINK="$BKS_DATA_ROOT/current"
BKS_BACKEND_ENV="$BKS_CONFIG_ROOT/backend.env"
BKS_GITHUB_CURL_CONFIG="$BKS_CONFIG_ROOT/github.curl.conf"
# Consumed by scripts that source this shared library.
# shellcheck disable=SC2034
BKS_DEPLOYED_VERSION="$BKS_STATE_ROOT/deployed-version"
# shellcheck disable=SC2034
BKS_BACKUPS_ROOT="$BKS_STATE_ROOT/backups"
BKS_SERVICE_NAME="bks-backend"
# shellcheck disable=SC2034
BKS_REPOSITORY="${BKS_GITHUB_REPOSITORY:-arfa069/bks}"
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

# Runsv is normally kept alive by runsvdir (service-daemon). If it is missing,
# `sv restart` would fail and the backend process could be left without a
# supervisor. Restore the runit supervision tree before touching the service.
ensure_service_runsv() {
    local service_name="$1"
    if sv status "$service_name" >/dev/null 2>&1; then
        return 0
    fi
    command -v service-daemon >/dev/null 2>&1 || return 1
    log "Runit supervisor is missing for $service_name; restoring service-daemon."
    service-daemon start >/dev/null 2>&1 || true
    local attempt=0
    until sv status "$service_name" >/dev/null 2>&1; do
        attempt=$((attempt + 1))
        [[ "$attempt" -ge 15 ]] && break
        sleep 1
    done
    sv status "$service_name" >/dev/null 2>&1
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
    BKS_DATABASE_URL="$(env_value BKS_DATABASE_URL "$BKS_BACKEND_ENV")" ||
        die "BKS_DATABASE_URL is missing."
    BKS_DATABASE_USER="$(env_value BKS_DATABASE_USER "$BKS_BACKEND_ENV")" ||
        die "BKS_DATABASE_USER is missing."
    BKS_DATABASE_PASSWORD="$(env_value BKS_DATABASE_PASSWORD "$BKS_BACKEND_ENV")" ||
        die "BKS_DATABASE_PASSWORD is missing."
    [[ "$BKS_DATABASE_URL" == jdbc:postgresql://* ]] ||
        die "BKS_DATABASE_URL must use PostgreSQL."
    export BKS_DATABASE_URL BKS_DATABASE_USER BKS_DATABASE_PASSWORD
}

github_api() {
    local config_args=()
    if [[ -f "$BKS_GITHUB_CURL_CONFIG" ]]; then
        config_args=(--config "$BKS_GITHUB_CURL_CONFIG")
    fi
    curl "${config_args[@]}" \
        --fail --silent --show-error --location \
        --header "Accept: application/vnd.github+json" \
        --header "X-GitHub-Api-Version: 2022-11-28" \
        "$@"
}

github_asset() {
    local config_args=()
    if [[ -f "$BKS_GITHUB_CURL_CONFIG" ]]; then
        config_args=(--config "$BKS_GITHUB_CURL_CONFIG")
    fi
    curl "${config_args[@]}" \
        --fail --silent --show-error --location \
        --header "Accept: application/octet-stream" \
        --header "X-GitHub-Api-Version: 2022-11-28" \
        "$@"
}

atomic_current_link() {
    local target="$1"
    local next_link="$BKS_DATA_ROOT/.current.next"
    [[ "$target" == "$BKS_RELEASES_ROOT"/v* ]] || die "Refusing unsafe release target."
    rm -f -- "$next_link"
    ln -s "$target" "$next_link"
    mv -fT "$next_link" "$BKS_CURRENT_LINK"
}

safe_remove_release() {
    local target="$1"
    [[ "$target" == "$BKS_RELEASES_ROOT"/v* ]] || die "Refusing unsafe release removal."
    [[ "$target" != "$(readlink -f "$BKS_CURRENT_LINK" 2>/dev/null || true)" ]] ||
        die "Refusing to remove the current release."
    if [[ -d "$target" && ! -L "$target" ]]; then
        chmod -R u+w -- "$target"
    fi
    rm -rf -- "$target"
}

database_check() {
    load_database_config
    PGPASSWORD="$BKS_DATABASE_PASSWORD" psql \
        --no-password \
        --username="$BKS_DATABASE_USER" \
        --dbname="${BKS_DATABASE_URL#jdbc:}" \
        --tuples-only --no-align \
        --command="SELECT 1" | grep -qx "1"
}

application_health_check() {
    local timeout_seconds="${BKS_HEALTH_TIMEOUT_SECONDS:-60}"
    [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] ||
        die "BKS_HEALTH_TIMEOUT_SECONDS must be a positive integer."
    local deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < deadline )); do
        if sv status "$BKS_SERVICE_NAME" 2>/dev/null | grep -q "^run:" &&
            database_check &&
            [[ "$(curl --fail --silent --show-error http://127.0.0.1:18080/health 2>/dev/null)" == '{"status":"ok"}' ]] &&
            [[ "$(curl --fail --silent --show-error http://127.0.0.1:8080/health 2>/dev/null)" == '{"status":"ok"}' ]]; then
            return 0
        fi
        sleep 2
    done
    return 1
}
