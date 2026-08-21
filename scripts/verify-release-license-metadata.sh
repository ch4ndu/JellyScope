#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
metadata_dir="${1:?Usage: verify-release-license-metadata.sh <metadata-dir> [--require-clean|--require-binary-ready|--require-android-ready|--require-ios-ready|--require-macos-arm64-ready]}"
verification_mode="${2:-}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

required_files=(
    SOURCE_REVISION.txt
    SOURCE_URL.txt
    BUILD_STATE.txt
    PROJECT_FILES.git-tree
    LICENSE
    OPEN_SOURCE_NOTICES.md
    THIRD_PARTY_COMPONENTS.tsv
    MOBILE_RUNTIME_NOTICES.md
    DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv
    DESKTOP_JVM_RUNTIME_NOTICES.md
    dependency-inputs/libs.versions.toml
    platform-notices/android/ATTRIBUTION.md
    platform-notices/android/SOURCE_MANIFEST.md
    platform-notices/android/manifest-1.0.0.txt
    platform-notices/ios-vlckit/ATTRIBUTION.md
    platform-notices/ios-vlckit/LGPL-2.1.txt
    platform-notices/ios-vlckit/manifest-4.0.0a23.txt
    platform-notices/macos-mpv/ATTRIBUTION.md
    platform-notices/macos-mpv/licenses/GPL-2.0-or-later.txt
    platform-notices/macos-mpv/licenses/GPL-3.0-only.txt
    platform-notices/macos-mpv/manifest-iina-1.4.0-arm64.txt
    platform-notices/macos-vlc/ATTRIBUTION.md
    platform-notices/macos-vlc/manifest-3.0.23-arm64.txt
)

for file in "${required_files[@]}"; do
    [[ -f "$metadata_dir/$file" ]] || die "Release license metadata is missing $file"
done

project_tree_file="$(mktemp)"
trap 'rm -f "$project_tree_file"' EXIT

revision="$(tr -d '\r\n' < "$metadata_dir/SOURCE_REVISION.txt")"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || die "SOURCE_REVISION.txt is not a full Git revision"
[[ "$revision" == "$(git -C "$repo_root" rev-parse HEAD)" ]] \
    || die "SOURCE_REVISION.txt does not match the repository HEAD"
grep -Fx "https://github.com/ch4ndu/JellyScope/tree/$revision" "$metadata_dir/SOURCE_URL.txt" >/dev/null \
    || die "SOURCE_URL.txt does not identify the recorded revision"
git -C "$repo_root" ls-tree -r --full-tree HEAD > "$project_tree_file"
cmp -s "$project_tree_file" "$metadata_dir/PROJECT_FILES.git-tree" \
    || die "PROJECT_FILES.git-tree does not match the recorded revision"
cmp -s "$repo_root/LICENSE" "$metadata_dir/LICENSE" \
    || die "Packaged project license differs from the repository LICENSE"
cmp -s "$repo_root/distribution/OPEN_SOURCE_NOTICES.md" "$metadata_dir/OPEN_SOURCE_NOTICES.md" \
    || die "Packaged open-source notice differs from the repository notice"
cmp -s "$repo_root/distribution/THIRD_PARTY_COMPONENTS.tsv" "$metadata_dir/THIRD_PARTY_COMPONENTS.tsv" \
    || die "Packaged third-party inventory differs from the repository inventory"
cmp -s "$repo_root/distribution/MOBILE_RUNTIME_NOTICES.md" "$metadata_dir/MOBILE_RUNTIME_NOTICES.md" \
    || die "Packaged mobile runtime notices differ from the repository notices"
cmp -s "$repo_root/distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv" \
    "$metadata_dir/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv" \
    || die "Packaged desktop JVM runtime inventory differs from the repository inventory"
cmp -s "$repo_root/distribution/DESKTOP_JVM_RUNTIME_NOTICES.md" \
    "$metadata_dir/DESKTOP_JVM_RUNTIME_NOTICES.md" \
    || die "Packaged desktop JVM runtime notices differ from the repository notices"
cmp -s "$repo_root/gradle/libs.versions.toml" "$metadata_dir/dependency-inputs/libs.versions.toml" \
    || die "Packaged version catalog differs from the repository input"
diff -qr "$repo_root/scripts/android-mpv-bundle" "$metadata_dir/platform-notices/android" >/dev/null \
    || die "Packaged Android native notices differ from the repository notice set"
cmp -s "$repo_root/scripts/vlckit-bundle/ATTRIBUTION.md" \
    "$metadata_dir/platform-notices/ios-vlckit/ATTRIBUTION.md" \
    || die "Packaged VLCKit attribution differs from the repository notice"
cmp -s "$repo_root/scripts/vlckit-bundle/manifest-4.0.0a23.txt" \
    "$metadata_dir/platform-notices/ios-vlckit/manifest-4.0.0a23.txt" \
    || die "Packaged VLCKit manifest differs from the repository manifest"
cmp -s "$repo_root/scripts/vlc-bundle/LGPL-2.1.txt" \
    "$metadata_dir/platform-notices/ios-vlckit/LGPL-2.1.txt" \
    || die "Packaged VLCKit LGPL text differs from the repository license text"
diff -qr "$repo_root/scripts/desktop-mpv-bundle" "$metadata_dir/platform-notices/macos-mpv" >/dev/null \
    || die "Packaged macOS mpv notices differ from the repository notice set"
diff -qr "$repo_root/scripts/vlc-bundle" "$metadata_dir/platform-notices/macos-vlc" >/dev/null \
    || die "Packaged macOS VLC notices differ from the repository notice set"
grep -F "JellyScope-owned source is licensed under MPL-2.0" "$metadata_dir/OPEN_SOURCE_NOTICES.md" >/dev/null \
    || die "Open-source notice does not preserve the active MPL-2.0 source status"

if [[ "$verification_mode" == "--require-clean" ||
    "$verification_mode" == "--require-binary-ready" ||
    "$verification_mode" == "--require-android-ready" ||
    "$verification_mode" == "--require-ios-ready" ||
    "$verification_mode" == "--require-macos-arm64-ready" ]]; then
    grep -Fx "tracked-worktree-dirty=false" "$metadata_dir/BUILD_STATE.txt" >/dev/null \
        || die "Release source binding requires a clean candidate"
fi

require_platform_ready() {
    local platform="$1"

    awk -F '\t' -v platform="$platform" '$1 == platform { found = 1 } END { exit !found }' \
        "$metadata_dir/THIRD_PARTY_COMPONENTS.tsv" \
        || die "$platform license inventory has no platform entries"
    if awk -F '\t' -v platform="$platform" \
        '$1 == platform && $5 == "review-required" { found = 1 } END { exit !found }' \
        "$metadata_dir/THIRD_PARTY_COMPONENTS.tsv"; then
        die "$platform license-metadata readiness is blocked by a review-required inventory entry"
    fi
}

if [[ "$verification_mode" == "--require-binary-ready" ]]; then
    if grep -F $'\treview-required\t' "$metadata_dir/THIRD_PARTY_COMPONENTS.tsv" >/dev/null; then
        die "Binary license-metadata readiness is blocked by review-required third-party inventory entries"
    fi
elif [[ "$verification_mode" == "--require-android-ready" ]]; then
    require_platform_ready android
elif [[ "$verification_mode" == "--require-ios-ready" ]]; then
    require_platform_ready ios
elif [[ "$verification_mode" == "--require-macos-arm64-ready" ]]; then
    require_platform_ready macos-arm64
elif [[ -n "$verification_mode" && "$verification_mode" != "--require-clean" ]]; then
    die "Unknown option: $verification_mode"
fi

echo "PASS: release license metadata is complete, MPL-2.0 for JellyScope-owned source, and tied to $revision"
