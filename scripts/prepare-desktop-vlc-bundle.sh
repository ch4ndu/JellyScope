#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

destination="${1:?Usage: prepare-desktop-vlc-bundle.sh <destination-dir>}"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: automatic desktop LibVLC bundling is currently macOS-only." >&2
    exit 1
fi

die() {
    echo "ERROR: $*" >&2
    exit 1
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
manifest="$script_dir/vlc-bundle/manifest-3.0.23-arm64.txt"

# Source order: explicit override, then the pinned fetch
# (deterministic; immune to /Applications auto-updates), then an installed
# VLC as the offline fallback. The manifest gate below validates whichever
# source wins.
vlc_app="${JELLYSCOPE_VLC_APP:-}"
if [[ -z "$vlc_app" ]]; then
    vlc_app="$(bash "$script_dir/fetch-desktop-vlc-runtime.sh" 2>/dev/null || true)"
fi
if [[ -z "$vlc_app" || ! -d "$vlc_app" ]]; then
    echo "WARNING: pinned VLC runtime fetch unavailable; falling back to /Applications/VLC.app" >&2
    vlc_app="/Applications/VLC.app"
fi
info_plist="$vlc_app/Contents/Info.plist"
runtime_root="$vlc_app/Contents/MacOS"

[[ -d "$vlc_app" ]] || die "VLC application not found: $vlc_app"
[[ -f "$info_plist" ]] || die "VLC Info.plist not found: $info_plist"
[[ -d "$runtime_root" ]] || die "VLC runtime root not found: $runtime_root"
[[ -f "$manifest" ]] || die "VLC bundle manifest not found: $manifest"

manifest_value() {
    local key="$1"
    awk -F': ' -v key="$key" '$1 == key { print $2; exit }' "$manifest"
}

manifest_version="$(manifest_value "vlc-version")"
manifest_arch="$(manifest_value "vlc-arch")"
[[ "$manifest_version" == "3.0.23" ]] || die "Unexpected manifest VLC version: $manifest_version"
[[ "$manifest_arch" == "arm64" ]] || die "Unexpected manifest VLC architecture: $manifest_arch"

vlc_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$info_plist" 2>/dev/null)" \
    || die "Could not read CFBundleShortVersionString from $info_plist"
[[ "$vlc_version" == 3.* ]] || die "VLC version must be 3.x, found $vlc_version"
[[ "$vlc_version" == "$manifest_version" ]] || die "VLC version $vlc_version does not match manifest $manifest_version"

mkdir -p "$destination"
destination="$(cd -- "$destination" && pwd -P)"
[[ "$destination" != "/" ]] || die "Refusing to use the filesystem root as the bundle destination"

# The prepared resource directory is owned by this task. Remove only the
# layouts this script creates so stale excluded plugins or plugin caches cannot
# survive a later run.
rm -rf "$destination/lib" "$destination/plugins" "$destination/licenses"
mkdir -p "$destination/lib" "$destination/plugins" "$destination/licenses"

# macOS ships bash 3.2, which has no associative arrays; track seen manifest
# paths in a temp file exactly like prepare-desktop-mpv-bundle.sh does.
manifest_seen_file="$(mktemp)"
trap 'rm -f "$manifest_seen_file"' EXIT
declare -a manifest_paths=()

while IFS= read -r entry; do
    case "$entry" in
        'file: '*) relative_path="${entry#file: }" ;;
        *) continue ;;
    esac
    case "$relative_path" in
        "" | /* | ../* | */../* | */..) die "Manifest contains an unsafe bundle path: $relative_path" ;;
    esac
    case "$relative_path" in
        plugins/libmacosx_plugin.dylib | \
        plugins/libosx_notifications_plugin.dylib | \
        plugins/libdvdread_plugin.dylib | \
        plugins/libdvdnav_plugin.dylib | \
        plugins/liblibbluray_plugin.dylib | \
        plugins/libbluray-*.jar | \
        plugins/plugins.dat)
            die "Manifest includes an excluded VLC file: $relative_path"
            ;;
        lib/*.dylib | plugins/*.dylib) ;;
        *) die "Manifest contains an unsupported bundle path: $relative_path" ;;
    esac
    if grep -Fxq "$relative_path" "$manifest_seen_file"; then
        die "Manifest repeats $relative_path"
    fi
    echo "$relative_path" >> "$manifest_seen_file"
    manifest_paths+=("$relative_path")

    source="$runtime_root/$relative_path"
    [[ -f "$source" ]] || die "Manifest file is missing from VLC.app: $relative_path"
    actual_arch="$(lipo -archs "$source")"
    [[ "$actual_arch" == "$manifest_arch" ]] || die "Architecture mismatch for $relative_path: $actual_arch"

    target="$destination/$relative_path"
    mkdir -p "$(dirname "$target")"
    cp -f "$source" "$target"
done < "$manifest"

(( ${#manifest_paths[@]} > 0 )) || die "VLC bundle manifest contains no files"

for required_file in \
    lib/libvlc.dylib \
    lib/libvlc.5.dylib \
    lib/libvlccore.dylib \
    lib/libvlccore.9.dylib; do
    grep -Fxq "$required_file" "$manifest_seen_file" \
        || die "Manifest omits required VLC runtime file: $required_file"
done

for license_file in ATTRIBUTION.md GPL-2.0.txt LGPL-2.1.txt; do
    source="$script_dir/vlc-bundle/$license_file"
    [[ -f "$source" ]] || die "VLC attribution file is missing: $source"
    cp -f "$source" "$destination/licenses/$license_file"
done

shopt -s nullglob
bundled_libraries=("$destination"/lib/*.dylib "$destination"/plugins/*.dylib)
(( ${#bundled_libraries[@]} > 0 )) || die "No VLC dylibs were copied"

bundle_file_for_basename() {
    local basename="$1"
    local candidate
    for candidate in "$destination/lib/$basename" "$destination/plugins/$basename"; do
        if [[ -f "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

loader_reference_for_target() {
    local library="$1"
    local target="$2"
    local library_dir target_dir target_basename
    library_dir="$(dirname "$library")"
    target_dir="$(dirname "$target")"
    target_basename="$(basename "$target")"

    if [[ "$library_dir" == "$destination/lib" ]]; then
        if [[ "$target_dir" == "$destination/lib" ]]; then
            printf '@loader_path/%s\n' "$target_basename"
        elif [[ "$target_dir" == "$destination/plugins" ]]; then
            printf '@loader_path/../plugins/%s\n' "$target_basename"
        else
            return 1
        fi
    elif [[ "$library_dir" == "$destination/plugins" ]]; then
        if [[ "$target_dir" == "$destination/plugins" ]]; then
            printf '@loader_path/%s\n' "$target_basename"
        elif [[ "$target_dir" == "$destination/lib" ]]; then
            printf '@loader_path/../lib/%s\n' "$target_basename"
        else
            return 1
        fi
    else
        return 1
    fi
}

rpaths_for_library() {
    otool -l "$1" | awk '$1 == "path" { print $2 }'
}

dependency_resolves_inside_bundle() {
    local library="$1"
    local dependency="$2"
    local candidate rpath rpath_base relative

    case "$dependency" in
        /usr/lib/* | /System/*)
            return 0
            ;;
        @loader_path/*)
            candidate="$(dirname "$library")/${dependency#@loader_path/}"
            [[ -f "$candidate" ]] && return 0
            ;;
        @executable_path/*)
            candidate="$destination/${dependency#@executable_path/}"
            [[ -f "$candidate" ]] && return 0
            ;;
        @rpath/*)
            relative="${dependency#@rpath/}"
            while read -r rpath; do
                [[ -n "${rpath:-}" ]] || continue
                case "$rpath" in
                    @loader_path)
                        rpath_base="$(dirname "$library")"
                        ;;
                    @loader_path/*)
                        rpath_base="$(dirname "$library")/${rpath#@loader_path/}"
                        ;;
                    @executable_path)
                        rpath_base="$destination"
                        ;;
                    @executable_path/*)
                        rpath_base="$destination/${rpath#@executable_path/}"
                        ;;
                    /*)
                        rpath_base="$rpath"
                        ;;
                    *)
                        continue
                        ;;
                esac
                candidate="$rpath_base/$relative"
                [[ -f "$candidate" ]] && return 0
            done < <(rpaths_for_library "$library")
            # VLC's upstream dylibs use @rpath names but carry no private
            # LC_RPATH. Verify the sibling layout supplies the named file; do
            # not rewrite a valid upstream @rpath reference speculatively.
            bundle_file_for_basename "$(basename "$relative")" >/dev/null && return 0
            ;;
        /*)
            ;;
        *)
            candidate="$(dirname "$library")/$dependency"
            [[ -f "$candidate" ]] && return 0
            ;;
    esac
    return 1
}

rewrite_unresolved_dependency() {
    local library="$1"
    local dependency="$2"
    local target replacement
    target="$(bundle_file_for_basename "$(basename "$dependency")")" \
        || die "Dependency outside the bundle: $(basename "$library") -> $dependency"
    replacement="$(loader_reference_for_target "$library" "$target")" \
        || die "Cannot express bundle-relative dependency for $dependency"
    echo "Rewriting $(basename "$library"): $dependency -> $replacement"
    install_name_tool -change "$dependency" "$replacement" "$library"
}

rewrite_id_if_needed() {
    local library="$1"
    local identifier target replacement
    identifier="$(
        {
            otool -D "$library" 2>/dev/null || true
        } | sed -n '2p' | sed 's/^[[:space:]]*//'
    )"
    [[ -n "$identifier" ]] || return 0

    case "$identifier" in
        /usr/lib/* | /System/*)
            return 0
            ;;
        @rpath/*)
            bundle_file_for_basename "$(basename "${identifier#@rpath/}")" >/dev/null \
                || die "Dylib ID outside the bundle: $(basename "$library") -> $identifier"
            return 0
            ;;
        @loader_path/* | @executable_path/*)
            dependency_resolves_inside_bundle "$library" "$identifier" && return 0
            ;;
    esac

    target="$(bundle_file_for_basename "$(basename "$identifier")")" \
        || die "Dylib ID outside the bundle: $(basename "$library") -> $identifier"
    replacement="$(loader_reference_for_target "$library" "$target")" \
        || die "Cannot express bundle-relative dylib ID for $identifier"
    echo "Rewriting $(basename "$library") ID: $identifier -> $replacement"
    install_name_tool -id "$replacement" "$library"
}

for library in "${bundled_libraries[@]}"; do
    chmod u+w "$library"
    rewrite_id_if_needed "$library"

    while read -r dependency; do
        [[ -n "${dependency:-}" ]] || continue
        if ! dependency_resolves_inside_bundle "$library" "$dependency"; then
            case "$dependency" in
                /usr/lib/* | /System/*)
                    ;;
                *)
                    rewrite_unresolved_dependency "$library" "$dependency"
                    ;;
            esac
        fi
    done < <(otool -L "$library" | awk 'NR > 2 { print $1 }')
done

# Every copied Mach-O is checked, so dependencies of plugins and both VLC
# libraries are covered transitively without relying on the source app's load
# paths. System libraries/frameworks remain the only allowed external paths.
for library in "${bundled_libraries[@]}"; do
    while read -r dependency; do
        [[ -n "${dependency:-}" ]] || continue
        if ! dependency_resolves_inside_bundle "$library" "$dependency"; then
            die "Unresolved dependency in $(basename "$library"): $dependency"
        fi
    done < <(otool -L "$library" | awk 'NR > 2 { print $1 }')
done

echo "Prepared VLC $vlc_version (${#bundled_libraries[@]} dylibs) in $destination"
