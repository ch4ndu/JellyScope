#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

destination="${1:?Usage: prepare-desktop-mpv-bundle.sh <destination-dir>}"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: automatic desktop libmpv bundling is currently macOS-only." >&2
    exit 1
fi

die() {
    echo "ERROR: $*" >&2
    exit 1
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
manifest="$script_dir/desktop-mpv-bundle/manifest-iina-1.4.0-arm64.txt"
notice_dir="$script_dir/desktop-mpv-bundle"

for command in awk cmp codesign grep install_name_tool lipo mktemp otool sort; do
    command -v "$command" >/dev/null 2>&1 || die "Required command is missing: $command"
done

[[ -f "$manifest" ]] || die "Desktop mpv manifest is missing: $manifest"
runtime_dir="$(bash "$script_dir/fetch-desktop-mpv-runtime.sh")"
[[ -d "$runtime_dir" ]] || die "Desktop mpv fetcher did not return a runtime directory"

mkdir -p "$destination"
destination="$(cd -- "$destination" && pwd -P)"
[[ "$destination" != "/" ]] || die "Refusing to use the filesystem root as the bundle destination"

manifest_names="$(mktemp)"
expected_inventory="$(mktemp)"
actual_inventory="$(mktemp)"
cleanup() {
    rm -f "$manifest_names" "$expected_inventory" "$actual_inventory"
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
    source="$runtime_dir/$filename"
    [[ -f "$source" && ! -L "$source" ]] || die "Runtime is missing $filename"
    entry_count=$((entry_count + 1))
done < "$manifest"
[[ "$entry_count" -eq 69 ]] || die "Desktop mpv manifest must contain exactly 69 dylibs"

LC_ALL=C sort "$manifest_names" > "$expected_inventory"
find "$runtime_dir" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; \
    | LC_ALL=C sort > "$actual_inventory"
cmp -s "$expected_inventory" "$actual_inventory" \
    || die "Runtime inventory differs from the pinned manifest"

# The prepared resource root is reused by Gradle. Retire root-level mpv dylibs
# left by an older manifest before copying the exact current inventory.
find "$destination" -mindepth 1 -maxdepth 1 -type f -name '*.dylib' -delete
while IFS= read -r filename; do
    cp -f "$runtime_dir/$filename" "$destination/$filename"
done < "$manifest_names"

rm -rf "$destination/mpv-notices"
mkdir -p "$destination/mpv-notices/licenses"
cp -f "$manifest" "$destination/mpv-notices/$(basename "$manifest")"
cp -f "$notice_dir/ATTRIBUTION.md" "$destination/mpv-notices/ATTRIBUTION.md"
cp -f "$notice_dir/licenses/GPL-2.0-or-later.txt" "$destination/mpv-notices/licenses/GPL-2.0-or-later.txt"
cp -f "$notice_dir/licenses/GPL-3.0-only.txt" "$destination/mpv-notices/licenses/GPL-3.0-only.txt"

while IFS= read -r filename; do
    library="$destination/$filename"
    architecture="$(lipo -archs "$library" 2>/dev/null)" \
        || die "Bundled file is not a Mach-O dylib: $filename"
    [[ "$architecture" == "arm64" ]] || die "Architecture mismatch for $filename: $architecture"

    chmod u+w "$library"
    install_name_tool -id "@loader_path/$filename" "$library"
    while IFS= read -r dependency; do
        case "$dependency" in
            @rpath/*)
                dependency_name="${dependency#@rpath/}"
                grep -Fxq "$dependency_name" "$manifest_names" \
                    || die "$filename references undeclared runtime dependency: $dependency"
                install_name_tool -change "$dependency" "@loader_path/$dependency_name" "$library"
                ;;
            @loader_path/*)
                dependency_name="${dependency#@loader_path/}"
                grep -Fxq "$dependency_name" "$manifest_names" \
                    || die "$filename references missing runtime dependency: $dependency"
                ;;
            /System/* | /usr/lib/*) ;;
            /*) die "$filename references unexpected absolute dependency: $dependency" ;;
            @*) die "$filename references unexpected relocatable dependency: $dependency" ;;
            *) die "$filename references unexpected dependency: $dependency" ;;
        esac
    done < <(otool -L "$library" | awk 'NR > 2 { print $1 }')
done < "$manifest_names"

while IFS= read -r filename; do
    library="$destination/$filename"
    install_id="$(otool -D "$library" | awk 'NR == 2 { print $1 }')"
    [[ "$install_id" == "@loader_path/$filename" ]] \
        || die "Unexpected install name for $filename: $install_id"

    min_os="$(otool -l "$library" | awk '
        $1 == "cmd" && $2 == "LC_BUILD_VERSION" { build = 1; next }
        build && $1 == "minos" { print $2; exit }
        $1 == "cmd" && $2 == "LC_VERSION_MIN_MACOSX" { legacy = 1; next }
        legacy && $1 == "version" { print $2; exit }
    ')"
    [[ -n "$min_os" ]] || die "Could not determine the deployment target for $filename"
    awk -v version="$min_os" 'BEGIN {
        split(version, part, ".")
        if ((part[1] + 0) > 13 || ((part[1] + 0) == 13 && (part[2] + 0) > 0)) exit 1
    }' || die "$filename targets macOS $min_os, newer than JellyScope's macOS 13 floor"

    while IFS= read -r dependency; do
        case "$dependency" in
            @loader_path/*)
                dependency_name="${dependency#@loader_path/}"
                [[ -f "$destination/$dependency_name" ]] \
                    || die "$filename references missing @loader_path/$dependency_name"
                ;;
            /System/* | /usr/lib/*) ;;
            *) die "$filename retains unexpected dependency after relocation: $dependency" ;;
        esac
    done < <(otool -L "$library" | awk 'NR > 2 { print $1 }')

    if otool -l "$library" | grep -E '/opt/homebrew|/usr/local|/opt/local' >/dev/null; then
        die "$filename retains a Homebrew, /usr/local, or MacPorts path"
    fi

    # IINA publishes ad-hoc-signed dylibs. Relocation invalidates those
    # signatures, so restore a valid development signature; credentialed
    # release packaging replaces it with the configured Developer ID.
    codesign --force --sign - "$library" >/dev/null \
        || die "Could not ad-hoc sign relocated $filename"
done < "$manifest_names"

echo "Prepared $entry_count pinned IINA libmpv runtime libraries in $destination"
