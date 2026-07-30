#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
for script in "$SCRIPT_DIR"/*.sh "$SCRIPT_DIR"/services/*/run "$SCRIPT_DIR"/services/*/log/run; do
    bash -n "$script"
done

test_root="$(mktemp -d)"
cleanup() {
    chmod -R u+w "$test_root" 2>/dev/null || true
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

fake_bin="$test_root/bin"
fake_curl_args="$test_root/curl-args"
mkdir -p "$fake_bin"
cat > "$fake_bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$FAKE_CURL_ARGS"
EOF
chmod +x "$fake_bin/curl"
export FAKE_CURL_ARGS="$fake_curl_args"
original_path="$PATH"
PATH="$fake_bin:$PATH"

github_api "https://api.github.com/repos/example/public" >/dev/null
if grep -Fxq -- "--config" "$fake_curl_args"; then
    printf 'Anonymous GitHub request unexpectedly required a curl config.\n' >&2
    exit 1
fi
printf '%s\n' 'header = "Authorization: Bearer test-token"' > "$AA_GITHUB_CURL_CONFIG"
github_asset "https://api.github.com/repos/example/releases/assets/1" >/dev/null
grep -Fxq -- "--config" "$fake_curl_args"
grep -Fxq -- "$AA_GITHUB_CURL_CONFIG" "$fake_curl_args"

deploy_fixture="$test_root/deploy-fixture"
mkdir -p "$deploy_fixture/archive/backend-v9.9.9/bin"
printf '#!/usr/bin/env sh\nexit 0\n' \
    > "$deploy_fixture/archive/backend-v9.9.9/bin/backend"
chmod +x "$deploy_fixture/archive/backend-v9.9.9/bin/backend"
tar -czf "$deploy_fixture/auto-accounting-backend-v9.9.9.tar.gz" \
    -C "$deploy_fixture/archive" backend-v9.9.9
cat > "$deploy_fixture/release-manifest.json" <<'EOF'
{
  "tag": "v9.9.9",
  "commit": "0123456789abcdef0123456789abcdef01234567",
  "backendAsset": "auto-accounting-backend-v9.9.9.tar.gz",
  "javaMajor": 17,
  "internalHttpPrerelease": true
}
EOF
backend_checksum="$(
    sha256sum "$deploy_fixture/auto-accounting-backend-v9.9.9.tar.gz" |
        awk '{ print $1 }'
)"
manifest_checksum="$(
    sha256sum "$deploy_fixture/release-manifest.json" |
        awk '{ print $1 }'
)"
printf '%s  %s\n%s  %s\n' \
    "$backend_checksum" auto-accounting-backend-v9.9.9.tar.gz \
    "$manifest_checksum" release-manifest.json \
    > "$deploy_fixture/checksums-valid.sha256"
printf '%064d  %s\n%s  %s\n' \
    0 auto-accounting-backend-v9.9.9.tar.gz \
    "$manifest_checksum" release-manifest.json \
    > "$deploy_fixture/checksums-invalid.sha256"
cat > "$deploy_fixture/releases.json" <<'EOF'
[
  {
    "draft": false,
    "prerelease": true,
    "tag_name": "v9.9.9",
    "published_at": "2099-01-01T00:00:00Z",
    "assets": [
      {
        "name": "auto-accounting-backend-v9.9.9.tar.gz",
        "url": "https://example.invalid/backend"
      },
      {
        "name": "release-manifest.json",
        "url": "https://example.invalid/manifest"
      },
      {
        "name": "checksums.sha256",
        "url": "https://example.invalid/checksums"
      }
    ]
  }
]
EOF

cat > "$fake_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
output=""
url=""
while (( $# > 0 )); do
    case "$1" in
        --output)
            output="$2"
            shift 2
            ;;
        http://*|https://*)
            url="$1"
            shift
            ;;
        *)
            shift
            ;;
    esac
done
case "$url" in
    *"/releases?per_page=20")
        cp "$FAKE_RELEASES_JSON" "$output"
        ;;
    https://example.invalid/backend)
        cp "$FAKE_BACKEND_ASSET" "$output"
        ;;
    https://example.invalid/manifest)
        cp "$FAKE_MANIFEST_ASSET" "$output"
        ;;
    https://example.invalid/checksums)
        cp "$FAKE_CHECKSUM_ASSET" "$output"
        ;;
    http://127.0.0.1:18080/health|http://127.0.0.1:8080/health)
        exit 22
        ;;
    *)
        printf 'Unexpected fake curl URL: %s\n' "$url" >&2
        exit 2
        ;;
esac
EOF
cat > "$fake_bin/pg_dump" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
output=""
for argument in "$@"; do
    case "$argument" in
        --file=*) output="${argument#--file=}" ;;
    esac
done
[[ -n "$output" ]]
: > "$output"
EOF
cat > "$fake_bin/psql" <<'EOF'
#!/usr/bin/env bash
printf '1\n'
EOF
cat > "$fake_bin/sv" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "status" ]]; then
    printf 'run: %s\n' "${2:-service}"
fi
EOF
chmod +x "$fake_bin/curl" "$fake_bin/pg_dump" "$fake_bin/psql" "$fake_bin/sv"

export FAKE_RELEASES_JSON="$deploy_fixture/releases.json"
export FAKE_BACKEND_ASSET="$deploy_fixture/auto-accounting-backend-v9.9.9.tar.gz"
export FAKE_MANIFEST_ASSET="$deploy_fixture/release-manifest.json"
export PATH="$fake_bin:$original_path"

write_test_environment() {
    local root="$1"
    mkdir -p "$root/config"
    printf '%s\n' \
        'AUTO_ACCOUNTING_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/test' \
        'AUTO_ACCOUNTING_DATABASE_USER=test-user' \
        'AUTO_ACCOUNTING_DATABASE_PASSWORD=test-password' \
        > "$root/config/backend.env"
}

invalid_checksum_root="$test_root/invalid-checksum"
write_test_environment "$invalid_checksum_root"
export AUTO_ACCOUNTING_CONFIG_ROOT="$invalid_checksum_root/config"
export AUTO_ACCOUNTING_DATA_ROOT="$invalid_checksum_root/data"
export AUTO_ACCOUNTING_STATE_ROOT="$invalid_checksum_root/state"
export FAKE_CHECKSUM_ASSET="$deploy_fixture/checksums-invalid.sha256"
if bash "$SCRIPT_DIR/deploy-release.sh" \
    > "$invalid_checksum_root/output.log" 2>&1; then
    printf 'Deployment accepted an invalid checksum.\n' >&2
    exit 1
fi
grep -q 'FAILED' "$invalid_checksum_root/output.log"
[[ ! -e "$invalid_checksum_root/data/current" ]]
[[ ! -e "$invalid_checksum_root/state/deployed-version" ]]
[[ -z "$(find "$invalid_checksum_root/state/backups" -type f 2>/dev/null)" ]]

case "$(uname -s)" in
    MINGW*|MSYS*)
        # Git for Windows emulates directory symlinks as directories, which
        # cannot exercise the atomic replacement used on Termux and Linux.
        ;;
    *)
        failed_health_root="$test_root/failed-health"
        write_test_environment "$failed_health_root"
        mkdir -p \
            "$failed_health_root/data/releases/v0.1.0" \
            "$failed_health_root/state"
        printf 'old release\n' \
            > "$failed_health_root/data/releases/v0.1.0/marker"
        ln -s "$failed_health_root/data/releases/v0.1.0" \
            "$failed_health_root/data/current"
        printf 'v0.1.0\n' > "$failed_health_root/state/deployed-version"
        export AUTO_ACCOUNTING_CONFIG_ROOT="$failed_health_root/config"
        export AUTO_ACCOUNTING_DATA_ROOT="$failed_health_root/data"
        export AUTO_ACCOUNTING_STATE_ROOT="$failed_health_root/state"
        export AUTO_ACCOUNTING_HEALTH_TIMEOUT_SECONDS=1
        export FAKE_CHECKSUM_ASSET="$deploy_fixture/checksums-valid.sha256"
        if bash "$SCRIPT_DIR/deploy-release.sh" \
            > "$failed_health_root/output.log" 2>&1; then
            printf 'Deployment accepted a failed health check.\n' >&2
            exit 1
        fi
        if ! grep -q 'restoring the previous application' \
            "$failed_health_root/output.log"; then
            printf 'Failed health deployment did not reach rollback.\n' >&2
            sed -n '1,80p' "$failed_health_root/output.log" >&2
            exit 1
        fi
        [[ "$(
            readlink -f "$failed_health_root/data/current"
        )" == "$failed_health_root/data/releases/v0.1.0" ]]
        [[ "$(
            tr -d '\r\n' < "$failed_health_root/state/deployed-version"
        )" == "v0.1.0" ]]
        find "$failed_health_root/state/backups" \
            -type f -name '*.dump' -print -quit |
            grep -q .
        ;;
esac

boot_test_root="$test_root/boot"
mkdir -p \
    "$boot_test_root/bin" \
    "$boot_test_root/home" \
    "$boot_test_root/prefix/etc/profile.d" \
    "$boot_test_root/prefix/var/lib/postgresql"
for service_name in \
    auto-accounting-nginx \
    auto-accounting-backend \
    auto-accounting-release-watcher; do
    mkdir -p "$boot_test_root/prefix/var/service/$service_name"
done
cat > "$boot_test_root/bin/termux-wake-lock" <<'EOF'
#!/usr/bin/env bash
printf 'wake-lock\n' >> "$BOOT_TEST_EVENTS"
EOF
cat > "$boot_test_root/bin/pg_isready" <<'EOF'
#!/usr/bin/env bash
[[ -f "$BOOT_TEST_DB_READY" ]]
EOF
cat > "$boot_test_root/bin/pg_ctl" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == "status" ]]; then
    exit 1
fi
printf 'pg_ctl start\n' >> "$BOOT_TEST_EVENTS"
touch "$BOOT_TEST_DB_READY"
EOF
cat > "$boot_test_root/bin/sv" <<'EOF'
#!/usr/bin/env bash
printf 'sv %s %s\n' "$1" "$2" >> "$BOOT_TEST_EVENTS"
EOF
cat > "$boot_test_root/prefix/etc/profile.d/start-services.sh" <<'EOF'
printf 'start-services\n' >> "$BOOT_TEST_EVENTS"
EOF
chmod +x "$boot_test_root/bin/"*
touch "$boot_test_root/database-ready"
HOME="$boot_test_root/home" \
    PREFIX="$boot_test_root/prefix" \
    BOOT_TEST_EVENTS="$boot_test_root/events" \
    BOOT_TEST_DB_READY="$boot_test_root/database-ready" \
    PATH="$boot_test_root/bin:$original_path" \
    sh "$SCRIPT_DIR/start-auto-accounting-boot.sh"
grep -Fxq 'wake-lock' "$boot_test_root/events"
grep -Fxq 'start-services' "$boot_test_root/events"
for service_name in \
    auto-accounting-nginx \
    auto-accounting-backend \
    auto-accounting-release-watcher; do
    grep -Fxq \
        "sv up $boot_test_root/prefix/var/service/$service_name" \
        "$boot_test_root/events"
done
if grep -q 'postgres' "$boot_test_root/events"; then
    printf 'Boot script changed PostgreSQL runit state.\n' >&2
    exit 1
fi
if grep -q 'pg_ctl' "$boot_test_root/events"; then
    printf 'Boot script restarted an already-ready PostgreSQL server.\n' >&2
    exit 1
fi

rm -f "$boot_test_root/database-ready"
HOME="$boot_test_root/home" \
    PREFIX="$boot_test_root/prefix" \
    BOOT_TEST_EVENTS="$boot_test_root/events-database-start" \
    BOOT_TEST_DB_READY="$boot_test_root/database-ready" \
    PATH="$boot_test_root/bin:$original_path" \
    sh "$SCRIPT_DIR/start-auto-accounting-boot.sh"
grep -Fxq 'pg_ctl start' "$boot_test_root/events-database-start"
if grep -q 'sv .*postgres' "$boot_test_root/events-database-start"; then
    printf 'Boot script enabled PostgreSQL through runit.\n' >&2
    exit 1
fi
