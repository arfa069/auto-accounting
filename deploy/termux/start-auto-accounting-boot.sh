#!/data/data/com.termux/files/usr/bin/sh

termux-wake-lock
# shellcheck disable=SC1091
. "$PREFIX/etc/profile.d/start-services.sh"
if [ -d "$PREFIX/var/service/postgresql" ]; then
    sv up postgresql
elif ! pg_isready >/dev/null 2>&1; then
    pg_ctl \
        -D "$PREFIX/var/lib/postgresql" \
        -l "$PREFIX/var/log/postgresql.log" \
        start
fi
if [ -d "$PREFIX/var/service/nginx" ]; then
    sv up nginx
elif ! pgrep -x nginx >/dev/null 2>&1; then
    nginx
fi
