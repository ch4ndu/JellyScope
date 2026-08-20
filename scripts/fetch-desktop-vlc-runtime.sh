#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Fetches the pinned VLC runtime used by prepare-desktop-vlc-bundle.sh so
# packaging does not depend on whatever /Applications/VLC.app happens to be
# (auto-updates past the audited version would fail the bundle manifest).
# Downloads the official VideoLAN DMG once,
# extracts VLC.app into a per-user cache, and prints the VLC.app path.
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: the desktop VLC runtime fetch is macOS-only." >&2
    exit 1
fi

# Pinned to the input recorded in scripts/vlc-bundle/manifest-3.0.23-arm64.txt.
# Re-run the dependency and licensing review before changing any of these.
vlc_version="3.0.23"
vlc_arch="arm64"
dmg_name="vlc-${vlc_version}-${vlc_arch}.dmg"
dmg_url="https://download.videolan.org/pub/videolan/vlc/${vlc_version}/macosx/${dmg_name}"

cache_root="${HOME}/Library/Caches/JellyScope/vlc-runtime/${vlc_version}-${vlc_arch}"
cached_app="${cache_root}/VLC.app"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

if [[ -d "$cached_app" ]]; then
    echo "$cached_app"
    exit 0
fi

mkdir -p "$cache_root"
workdir="$(mktemp -d)"
mount_point=""
cleanup() {
    if [[ -n "$mount_point" ]]; then
        hdiutil detach "$mount_point" -quiet || true
    fi
    rm -rf "$workdir"
}
trap cleanup EXIT

dmg_path="${workdir}/${dmg_name}"
echo "Fetching ${dmg_url}" >&2
curl -fL --retry 3 -o "$dmg_path" "$dmg_url" >&2 \
    || die "Could not download ${dmg_name}."

mount_point="${workdir}/mount"
mkdir -p "$mount_point"
hdiutil attach "$dmg_path" -mountpoint "$mount_point" -nobrowse -quiet -readonly \
    || die "Could not mount ${dmg_name}."
[[ -d "${mount_point}/VLC.app" ]] || die "${dmg_name} does not contain VLC.app."

staging="${cache_root}/.staging.$$"
rm -rf "$staging"
cp -R "${mount_point}/VLC.app" "$staging" || die "Could not copy VLC.app from the image."
hdiutil detach "$mount_point" -quiet || true
mount_point=""
mv "$staging" "$cached_app"

echo "$cached_app"
