#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
manifest="$script_dir/desktop-mpv-bundle/manifest-iina-1.4.0-arm64.txt"
cache_root="${JELLYSCOPE_MPV_RUNTIME_CACHE_ROOT:-${HOME:?HOME must be set}/Library/Caches/JellyScope/mpv-runtime}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

manifest_value() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$manifest"
}

for command in awk cmp curl find grep mktemp mv sort; do
    command -v "$command" >/dev/null 2>&1 || die "Required command is missing: $command"
done

[[ -f "$manifest" ]] || die "Desktop mpv manifest is missing: $manifest"
[[ "$cache_root" == /* && "$cache_root" != "/" ]] \
    || die "Desktop mpv cache root must be an absolute directory below the filesystem root"

bundle_id="$(manifest_value bundle-id)"
base_url="$(manifest_value base-url)"
expected_count="$(manifest_value file-count)"
[[ "$bundle_id" == "iina-1.4.0-arm64" ]] || die "Unexpected desktop mpv bundle identity: $bundle_id"
[[ "$base_url" == "https://iina.io/dylibs/1.4.0/arm64/" ]] || die "Unexpected desktop mpv base URL"
[[ "$expected_count" == "69" ]] || die "Unexpected desktop mpv file count: $expected_count"
cache_dir="$cache_root/$bundle_id"

expected_inventory="$(mktemp)"
actual_inventory="$(mktemp)"
manifest_names="$(mktemp)"
download_dir=""
install_lock=""
cleanup() {
    rm -f "$expected_inventory" "$actual_inventory" "$manifest_names"
    if [[ -n "$download_dir" && -d "$download_dir" ]]; then
        rm -rf "$download_dir"
    fi
    if [[ -n "$install_lock" && -d "$install_lock" ]]; then
        rmdir "$install_lock" 2>/dev/null || true
    fi
}
trap cleanup EXIT

entry_count=0
while IFS= read -r entry; do
    case "$entry" in
        file=*) filename="${entry#file=}" ;;
        *) continue ;;
    esac
    [[ -n "$filename" ]] || die "Manifest contains an empty filename"
    case "$filename" in
        .* | *[!A-Za-z0-9._+-]*) die "Manifest contains an unsafe filename: $filename" ;;
    esac
    grep -Fxq "$filename" "$manifest_names" && die "Manifest repeats filename: $filename"
    printf '%s\n' "$filename" >> "$manifest_names"
    entry_count=$((entry_count + 1))
done < "$manifest"
[[ "$entry_count" -eq "$expected_count" ]] \
    || die "Manifest declares $expected_count files but contains $entry_count file records"
LC_ALL=C sort "$manifest_names" > "$expected_inventory"

validate_runtime() {
    local runtime_dir="$1"
    local filename

    [[ -d "$runtime_dir" && ! -L "$runtime_dir" ]] || return 1
    if [[ -n "$(find "$runtime_dir" -mindepth 1 -maxdepth 1 ! -type f -print -quit)" ]]; then
        return 1
    fi
    find "$runtime_dir" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; \
        | LC_ALL=C sort > "$actual_inventory"
    cmp -s "$expected_inventory" "$actual_inventory" || return 1

    while IFS= read -r filename; do
        [[ -f "$runtime_dir/$filename" && ! -L "$runtime_dir/$filename" ]] || return 1
    done < "$manifest_names"
}

if validate_runtime "$cache_dir"; then
    printf '%s\n' "$cache_dir"
    exit 0
fi

cache_parent="$(dirname -- "$cache_dir")"
mkdir -p "$cache_parent"
download_dir="$(mktemp -d "$cache_parent/.${bundle_id}.download.XXXXXX")"
echo "Fetching desktop mpv runtime $bundle_id" >&2
while IFS= read -r filename; do
    curl --fail --location --silent --show-error \
        "${base_url}${filename}" \
        --output "$download_dir/$filename"
done < "$manifest_names"

validate_runtime "$download_dir" \
    || die "Downloaded desktop mpv runtime failed inventory validation"

# Serialize only the final cache replacement. Concurrent callers may download
# in parallel, but one valid installation wins and the others discard theirs.
lock_path="$cache_parent/.${bundle_id}.install-lock"
lock_attempt=0
until mkdir "$lock_path" 2>/dev/null; do
    lock_attempt=$((lock_attempt + 1))
    [[ "$lock_attempt" -lt 120 ]] || die "Timed out waiting to install the desktop mpv runtime cache"
    sleep 0.25
done
install_lock="$lock_path"

if validate_runtime "$cache_dir"; then
    rm -rf "$download_dir"
    download_dir=""
    printf '%s\n' "$cache_dir"
    exit 0
fi

previous_cache=""
if [[ -e "$cache_dir" || -L "$cache_dir" ]]; then
    previous_cache="$(mktemp -d "$cache_parent/.${bundle_id}.previous.XXXXXX")"
    rmdir "$previous_cache"
    mv "$cache_dir" "$previous_cache"
fi
if mv "$download_dir" "$cache_dir"; then
    download_dir=""
    if [[ -n "$previous_cache" ]]; then
        rm -rf "$previous_cache"
    fi
else
    if [[ -n "$previous_cache" && ! -e "$cache_dir" ]]; then
        mv "$previous_cache" "$cache_dir"
    fi
    die "Could not atomically install the desktop mpv runtime cache"
fi

printf '%s\n' "$cache_dir"
