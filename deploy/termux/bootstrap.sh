#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/termux/lib.sh
source "$SCRIPT_DIR/lib.sh"

mode="${1:---inspect}"
[[ "$mode" == "--inspect" || "$mode" == "--provision" ]] ||
    die "Usage: bootstrap.sh [--inspect|--provision]"
created_role=false
created_database=false
provisioning_complete=false
nginx_changed=false
nginx_conf=""
nginx_backup=""

cleanup_provisioning_failure() {
    local exit_code=$?
    if (( exit_code != 0 )) && [[ "$mode" == "--provision" ]]; then
        set +e
        if [[ "$created_database" == "true" ]]; then
            dropdb --if-exists auto_accounting >/dev/null 2>&1
        fi
        if [[ "$created_role" == "true" ]]; then
            dropuser --if-exists auto_accounting >/dev/null 2>&1
        fi
        if [[ "$nginx_changed" == "true" && -f "$nginx_backup" ]]; then
            cp "$nginx_backup" "$nginx_conf"
            rm -f "$PREFIX/etc/nginx/conf.d/auto-accounting.conf"
        fi
        if [[ "$provisioning_complete" != "true" ]]; then
            rm -f "$AA_BACKEND_ENV"
            rm -rf -- \
                "$HOME/.local/lib/auto-accounting-deploy" \
                "$PREFIX/var/service/auto-accounting-backend" \
                "$PREFIX/var/service/auto-accounting-release-watcher"
            rm -f "$HOME/.termux/boot/start-auto-accounting"
        fi
        set -e
    fi
    exit "$exit_code"
}
trap cleanup_provisioning_failure EXIT

printf 'Identity: '
id
printf 'Architecture: '
uname -m
printf 'Home: %s\nPrefix: %s\n' "$HOME" "${PREFIX:-unset}"
for command_name in java nginx psql pg_dump pg_isready sv jq curl openssl; do
    if command -v "$command_name" >/dev/null 2>&1; then
        printf '%-12s %s\n' "$command_name" "$(command -v "$command_name")"
    else
        printf '%-12s missing\n' "$command_name"
    fi
done
java -version 2>&1 | head -n 1 || true
nginx -v 2>&1 || true
psql --version 2>&1 || true
pg_isready || true
(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null || true) | head -n 30
df -h "$HOME"

[[ "$mode" == "--provision" ]] || exit 0
[[ -n "${PREFIX:-}" ]] || die "PREFIX is not set; run inside Termux."
require_command psql
require_command pg_isready
pg_isready >/dev/null || die "PostgreSQL is not ready."

role_exists="$(psql --dbname=template1 --tuples-only --no-align \
    --command="SELECT 1 FROM pg_roles WHERE rolname = 'auto_accounting'" | tr -d '[:space:]')"
database_exists="$(psql --dbname=template1 --tuples-only --no-align \
    --command="SELECT 1 FROM pg_database WHERE datname = 'auto_accounting'" | tr -d '[:space:]')"
if [[ "$role_exists" == "1" || "$database_exists" == "1" ]]; then
    die "The auto_accounting role or database already exists. Inspection must stop before any change."
fi
for target in \
    "$AA_BACKEND_ENV" \
    "$HOME/.local/lib/auto-accounting-deploy" \
    "$PREFIX/etc/nginx/conf.d/auto-accounting.conf" \
    "$PREFIX/var/service/auto-accounting-backend" \
    "$PREFIX/var/service/auto-accounting-release-watcher" \
    "$HOME/.termux/boot/start-auto-accounting"; do
    [[ ! -e "$target" ]] || die "Provisioning target already exists: $target"
done

if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null || true) |
    grep -Eq '(^|[[:space:]:])(8080|18080)([[:space:]]|$)'; then
    die "Port 8080 or 18080 is already in use."
fi

pkg install --yes openjdk-17 jq termux-services coreutils findutils openssl
for command_name in java nginx psql pg_dump pg_isready sv jq curl openssl; do
    require_command "$command_name"
done
java -version 2>&1 | head -n 1 | grep -Eq 'version "17([."]|$)' ||
    die "Termux deployment requires Java 17."

db_password="$(openssl rand -hex 32)"
auth_pepper="$(openssl rand -hex 48)"
printf "CREATE ROLE auto_accounting LOGIN PASSWORD '%s';\n" "$db_password" |
    psql --dbname=template1 --set=ON_ERROR_STOP=1 >/dev/null
created_role=true
createdb --owner=auto_accounting auto_accounting
created_database=true

mkdir -p "$AA_CONFIG_ROOT" "$AA_RELEASES_ROOT" "$AA_BACKUPS_ROOT" "$AA_STATE_ROOT"
umask 077
backend_env_temp="$AA_CONFIG_ROOT/backend.env.tmp"
{
    printf 'AUTO_ACCOUNTING_HOST=127.0.0.1\n'
    printf 'AUTO_ACCOUNTING_PORT=18080\n'
    printf 'AUTO_ACCOUNTING_TRUST_PROXY_HEADERS=true\n'
    printf 'AUTO_ACCOUNTING_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/auto_accounting\n'
    printf 'AUTO_ACCOUNTING_DATABASE_USER=auto_accounting\n'
    printf 'AUTO_ACCOUNTING_DATABASE_PASSWORD=%s\n' "$db_password"
    printf 'AUTO_ACCOUNTING_AUTH_PEPPER=%s\n' "$auth_pepper"
    printf 'AUTO_ACCOUNTING_SMS_PROVIDER=\n'
    printf 'AUTO_ACCOUNTING_EMAIL_PROVIDER=\n'
    printf 'AUTO_ACCOUNTING_AI_PROVIDER=\n'
    printf 'AUTO_ACCOUNTING_WECHAT_APP_ID=\n'
    printf 'AUTO_ACCOUNTING_WECHAT_APP_SECRET=\n'
} > "$backend_env_temp"
unset db_password auth_pepper
mv "$backend_env_temp" "$AA_BACKEND_ENV"
chmod 600 "$AA_BACKEND_ENV"

install_root="$HOME/.local/lib/auto-accounting-deploy"
mkdir -p "$install_root"
cp "$SCRIPT_DIR"/*.sh "$install_root/"
chmod 700 "$install_root"/*.sh

nginx_conf="$PREFIX/etc/nginx/nginx.conf"
nginx_backup="$nginx_conf.auto-accounting.$(date -u '+%Y%m%dT%H%M%SZ').bak"
[[ -f "$nginx_conf" ]] || die "Nginx configuration is missing."
cp "$nginx_conf" "$nginx_backup"
nginx_changed=true
mkdir -p "$PREFIX/etc/nginx/conf.d"
if ! grep -Eq 'include[[:space:]]+.*conf\.d/\*\.conf' "$nginx_conf"; then
    closing_line="$(grep -n '^}' "$nginx_conf" | tail -n 1 | cut -d: -f1)"
    [[ -n "$closing_line" ]] || die "Cannot find the closing http block in nginx.conf."
    {
        head -n "$((closing_line - 1))" "$nginx_conf"
        printf '    include %s/etc/nginx/conf.d/*.conf;\n' "$PREFIX"
        tail -n "+$closing_line" "$nginx_conf"
    } > "$nginx_conf.tmp"
    mv "$nginx_conf.tmp" "$nginx_conf"
fi
cp "$SCRIPT_DIR/nginx-auto-accounting.conf" \
    "$PREFIX/etc/nginx/conf.d/auto-accounting.conf"
if ! nginx -t; then
    cp "$nginx_backup" "$nginx_conf"
    rm -f "$PREFIX/etc/nginx/conf.d/auto-accounting.conf"
    nginx_changed=false
    die "Nginx validation failed; the original configuration was restored."
fi

for service_name in auto-accounting-backend auto-accounting-release-watcher; do
    service_root="$PREFIX/var/service/$service_name"
    mkdir -p "$service_root/log"
    cp "$SCRIPT_DIR/services/$service_name/run" "$service_root/run"
    cp "$SCRIPT_DIR/services/$service_name/log/run" "$service_root/log/run"
    chmod 700 "$service_root/run" "$service_root/log/run"
done
touch "$PREFIX/var/service/auto-accounting-release-watcher/down"

mkdir -p "$HOME/.termux/boot"
cp "$SCRIPT_DIR/start-auto-accounting-boot.sh" \
    "$HOME/.termux/boot/start-auto-accounting"
chmod 700 "$HOME/.termux/boot/start-auto-accounting"

if pgrep -x nginx >/dev/null 2>&1; then
    nginx -s reload
elif [[ -d "$PREFIX/var/service/nginx" ]]; then
    sv up nginx
else
    nginx
fi
if ! pgrep -f 'runsvdir.*var/service' >/dev/null 2>&1; then
    # shellcheck disable=SC1091
    source "$PREFIX/etc/profile.d/start-services.sh"
fi
sv up auto-accounting-backend
provisioning_complete=true
if github_api "https://api.github.com/repos/$AA_REPOSITORY" >/dev/null 2>&1; then
    rm -f "$PREFIX/var/service/auto-accounting-release-watcher/down"
    sv up auto-accounting-release-watcher
    log "Provisioning complete. Anonymous GitHub release polling is enabled."
else
    log "Provisioning complete. Configure the GitHub token before enabling release polling."
fi
