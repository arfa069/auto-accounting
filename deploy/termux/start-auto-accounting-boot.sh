#!/data/data/com.termux/files/usr/bin/sh

termux-wake-lock
# shellcheck disable=SC1091
. "$PREFIX/etc/profile.d/start-services.sh"
if [ -d "$PREFIX/var/service/postgresql" ]; then
    sv up postgresql
elif [ -d "$PREFIX/var/service/postgres" ]; then
    sv up postgres
elif ! pg_isready >/dev/null 2>&1; then
    pg_ctl \
        -D "$PREFIX/var/lib/postgresql" \
        -l "$PREFIX/var/log/postgresql.log" \
        start
fi
sv up auto-accounting-nginx
sv up auto-accounting-backend
