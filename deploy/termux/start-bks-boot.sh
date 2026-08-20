#!/data/data/com.termux/files/usr/bin/sh

set -u

state_root="${BKS_STATE_ROOT:-$HOME/.local/state/bks}"
mkdir -p "$state_root"
chmod 700 "$state_root"
umask 077
exec >> "$state_root/boot.log" 2>&1

printf 'bks boot start: %s\n' "$(date)"
termux-wake-lock || true

database_ready() {
    pg_isready -h 127.0.0.1 -p 5432 >/dev/null 2>&1
}

if ! database_ready; then
    if ! pg_ctl status -D "$PREFIX/var/lib/postgresql" >/dev/null 2>&1; then
        pg_ctl \
            -D "$PREFIX/var/lib/postgresql" \
            -l "$state_root/postgresql-boot.log" \
            start
    fi
    database_attempt=0
    while ! database_ready && [ "$database_attempt" -lt 30 ]; do
        database_attempt=$((database_attempt + 1))
        sleep 1
    done
    if ! database_ready; then
        printf 'PostgreSQL was not ready after 30 seconds.\n' >&2
        exit 1
    fi
fi

# shellcheck disable=SC1091
. "$PREFIX/etc/profile.d/start-services.sh"

for service_name in \
    bks-nginx \
    bks-backend \
    bks-release-watcher; do
    service_path="$PREFIX/var/service/$service_name"
    if [ ! -d "$service_path" ]; then
        printf 'Missing service directory: %s\n' "$service_path" >&2
        exit 1
    fi
    service_attempt=0
    until sv up "$service_path"; do
        service_attempt=$((service_attempt + 1))
        if [ "$service_attempt" -ge 15 ]; then
            printf 'Unable to start service: %s\n' "$service_name" >&2
            exit 1
        fi
        sleep 1
    done
done

printf 'bks boot complete: %s\n' "$(date)"
