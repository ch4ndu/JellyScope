#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
manifest="$repo_root/scripts/android-mpv-bundle/manifest-1.0.0.txt"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

[[ -f "$manifest" ]] || die "Android native manifest is missing: $manifest"

manifest_value() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2); exit }' "$manifest"
}

require_manifest_value() {
    local key="$1"
    local value
    value="$(manifest_value "$key")"
    [[ -n "$value" ]] || die "Manifest value is missing: $key"
    printf '%s\n' "$value"
}

manifest_version="$(require_manifest_value manifest-version)"
[[ "$manifest_version" == "2" ]] || die "Unsupported Android native manifest version: $manifest_version"

[[ "$(require_manifest_value artifact-role)" == "pinned-build-input-only" ]] \
    || die "Android mpv artifact must be recorded as a build input, not a runtime dependency"
[[ "$(require_manifest_value project-module)" == "android-libmpv" ]] \
    || die "APK manifest does not identify the project-owned android-libmpv module"
[[ "$(require_manifest_value wrapper-build)" == "android-libmpv/build.gradle.kts" ]] \
    || die "APK manifest does not identify the project wrapper build"
[[ "$(require_manifest_value wrapper-patches)" == "typed-log-request,no-raw-logcat,idempotent-destroy" ]] \
    || die "APK manifest wrapper patch list differs from the reviewed source"
[[ "$(require_manifest_value wrapper-upstream-commit)" == "$(require_manifest_value provider-source-commit)" ]] \
    || die "Wrapper and provider source commits differ"
for key in \
    artifact-coordinate artifact-url source-repository source-tag source-commit \
    source-archive source-notices provider-artifact-coordinate \
    native-modified-entries native-patches native-mpv-source-commit \
    media3-ffmpeg-artifact-coordinate \
    media3-ffmpeg-source-commit \
    media3-ffmpeg-declared-license \
    libvlc-artifact-coordinate \
    libvlc-embedded-version \
    libvlc-embedded-source \
    libvlc-declared-license; do
    require_manifest_value "$key" >/dev/null
done
IFS=',' read -r -a shipped_abis <<< "$(require_manifest_value shipped-abis)"
IFS=',' read -r -a excluded_abis <<< "$(require_manifest_value excluded-abis)"
IFS=',' read -r -a mpv_libraries <<< "$(require_manifest_value mpv-libraries)"
IFS=',' read -r -a wrapper_libraries <<< "$(require_manifest_value wrapper-libraries)"
IFS=',' read -r -a media3_ffmpeg_libraries <<< "$(require_manifest_value media3-ffmpeg-libraries)"
IFS=',' read -r -a libvlc_libraries <<< "$(require_manifest_value libvlc-libraries)"
IFS=',' read -r -a license_assets <<< "$(require_manifest_value license-assets)"

for wrapper_library in "${wrapper_libraries[@]}"; do
    for unchanged_library in "${mpv_libraries[@]}"; do
        [[ "$wrapper_library" != "$unchanged_library" ]] \
            || die "Wrapper library is also listed as an unchanged native library: $wrapper_library"
    done
done

if (( $# == 0 )); then
    apk_paths=(
        "$repo_root/android-app/build/outputs/apk/release/android-app-release.apk"
        "$repo_root/android-tv-app/build/outputs/apk/release/android-tv-app-release.apk"
    )
else
    apk_paths=("$@")
fi

(( ${#apk_paths[@]} == 2 )) || die "Expected both mobile and TV release APKs"

for command in awk cmp grep unzip; do
    command -v "$command" >/dev/null 2>&1 || die "Required command is missing: $command"
done

temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

require_entry() {
    local listing="$1"
    local entry="$2"
    printf '%s\n' "$listing" | grep -F -x "$entry" >/dev/null \
        || die "APK is missing required entry: $entry"
}

expected_abi_text="$(printf '%s\n' "${shipped_abis[@]}" | sort -u)"

verify_apk() {
    local apk="$1"
    local apk_name listing actual_abi_text x86_entries excluded_entries asset_name asset_path asset_file metadata_root
    apk_name="$(basename "$apk")"
    [[ -f "$apk" ]] || die "Release APK is missing: $apk"
    listing="$(unzip -Z1 "$apk")"

    x86_entries="$(printf '%s\n' "$listing" | awk -F/ '$1 == "lib" && $2 == "x86" { print }')"
    [[ -z "$x86_entries" ]] || die "$apk_name contains excluded x86 native entries"
    for excluded_abi in "${excluded_abis[@]}"; do
        excluded_entries="$(printf '%s\n' "$listing" | awk -F/ -v abi="$excluded_abi" '$1 == "lib" && $2 == abi { print }')"
        [[ -z "$excluded_entries" ]] || die "$apk_name contains excluded ABI: $excluded_abi"
    done

    actual_abi_text="$(printf '%s\n' "$listing" | awk -F/ '$1 == "lib" && $3 == "libmpv.so" { print $2 }' | sort -u)"
    [[ "$actual_abi_text" == "$expected_abi_text" ]] \
        || die "$apk_name mpv ABI set differs: expected [$expected_abi_text], got [$actual_abi_text]"

    for abi in "${shipped_abis[@]}"; do
        for library in "${mpv_libraries[@]}"; do
            entry="lib/$abi/$library"
            require_entry "$listing" "$entry"
        done
        for library in "${wrapper_libraries[@]}"; do
            entry="lib/$abi/$library"
            require_entry "$listing" "$entry"
        done
        for library in "${media3_ffmpeg_libraries[@]}"; do
            entry="lib/$abi/$library"
            require_entry "$listing" "$entry"
        done
        for library in "${libvlc_libraries[@]}"; do
            entry="lib/$abi/$library"
            require_entry "$listing" "$entry"
        done
        entry="lib/$abi/libc++_shared.so"
        require_entry "$listing" "$entry"
    done

    for asset_name in ATTRIBUTION.md SOURCE_MANIFEST.md manifest-1.0.0.txt; do
        asset_path="assets/$asset_name"
        require_entry "$listing" "$asset_path"
    done
    for asset_name in \
        SOURCE_REVISION.txt SOURCE_URL.txt BUILD_STATE.txt PROJECT_FILES.git-tree LICENSE \
        OPEN_SOURCE_NOTICES.md THIRD_PARTY_COMPONENTS.tsv MOBILE_RUNTIME_NOTICES.md; do
        require_entry "$listing" "assets/license-metadata/$asset_name"
    done
    for asset_path in \
        assets/license-metadata/dependency-inputs/libs.versions.toml \
        assets/license-metadata/platform-notices/android/ATTRIBUTION.md \
        assets/license-metadata/platform-notices/android/SOURCE_MANIFEST.md \
        assets/license-metadata/platform-notices/android/manifest-1.0.0.txt; do
        require_entry "$listing" "$asset_path"
    done
    for asset_name in "${license_assets[@]}"; do
        asset_path="assets/licenses/$asset_name"
        require_entry "$listing" "$asset_path"
    done

    asset_file="$temporary_root/$apk_name.manifest"
    unzip -p "$apk" assets/manifest-1.0.0.txt > "$asset_file"
    cmp -s "$manifest" "$asset_file" \
        || die "$apk_name contains a native manifest different from the reviewed source manifest"

    metadata_root="$temporary_root/$apk_name-license-metadata"
    mkdir -p "$metadata_root"
    unzip -qq "$apk" 'assets/license-metadata/*' -d "$metadata_root"
    bash "$repo_root/scripts/verify-release-license-metadata.sh" \
        "$metadata_root/assets/license-metadata" >/dev/null

    echo "PASS: $apk_name contains the pinned mpv, Media3 FFmpeg, and LibVLC payloads with complete license metadata"
}

for apk in "${apk_paths[@]}"; do
    verify_apk "$apk"
done
