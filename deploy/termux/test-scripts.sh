#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
for script in "$SCRIPT_DIR"/*.sh "$SCRIPT_DIR"/services/*/run "$SCRIPT_DIR"/services/*/log/run; do
    bash -n "$script"
done

test_root="$(mktemp -d)"
cleanup() {
    rm -rf -- "$test_root"
}
trap cleanup EXIT
export AUTO_ACCOUNTING_CONFIG_ROOT="$test_root/config"
export AUTO_ACCOUNTING_DATA_ROOT="$test_root/data"
export AUTO_ACCOUNTING_STATE_ROOT="$test_root/state"
# shellcheck source=deploy/termux/lib.sh
source "$SCRIPT_DIR/lib.sh"

mkdir -p "$AA_CONFIG_ROOT" "$AA_RELEASES_ROOT/v0.1.0" "$AA_STATE_ROOT"
printf '%s\n' \
    'AUTO_ACCOUNTING_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/test' \
    'AUTO_ACCOUNTING_DATABASE_USER=test-user' \
    'export AUTO_ACCOUNTING_DATABASE_PASSWORD="test password"' > "$AA_BACKEND_ENV"

validate_release_tag v0.1.0
if (validate_release_tag invalid-tag >/dev/null 2>&1); then
    printf 'Invalid tag was accepted.\n' >&2
    exit 1
fi
if (validate_release_tag v01.0.0 >/dev/null 2>&1); then
    printf 'A non-semantic tag with leading zeroes was accepted.\n' >&2
    exit 1
fi
is_newer_release v0.2.0 v0.1.9
if is_newer_release v0.1.9 v0.2.0; then
    printf 'An older release was treated as newer.\n' >&2
    exit 1
fi
[[ "$(env_value AUTO_ACCOUNTING_DATABASE_USER "$AA_BACKEND_ENV")" == "test-user" ]]
[[ "$(env_value AUTO_ACCOUNTING_DATABASE_PASSWORD "$AA_BACKEND_ENV")" == "test password" ]]
atomic_current_link "$AA_RELEASES_ROOT/v0.1.0"
case "$(uname -s)" in
    MINGW*|MSYS*) [[ -e "$AA_CURRENT_LINK" ]] ;;
    *) [[ "$(readlink -f "$AA_CURRENT_LINK")" == "$AA_RELEASES_ROOT/v0.1.0" ]] ;;
esac
