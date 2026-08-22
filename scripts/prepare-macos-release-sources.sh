#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
output_dir="${1:?Usage: prepare-macos-release-sources.sh <release-output-dir>}"
manifest="$repo_root/scripts/macos-source-bundle/manifest-macos-arm64.txt"
cache_root="${JELLYSCOPE_MACOS_SOURCE_CACHE_ROOT:-${HOME:?HOME must be set}/Library/Caches/JellyScope/release-sources}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

manifest_value() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$manifest"
}

for command in awk cp curl find git grep gzip mktemp mv rm tar; do
    command -v "$command" >/dev/null 2>&1 || die "Required command is missing: $command"
done

[[ -f "$manifest" ]] || die "macOS source manifest is missing: $manifest"
[[ "$cache_root" == /* && "$cache_root" != "/" ]] \
    || die "macOS source cache root must be an absolute directory below the filesystem root"

bundle_id="$(manifest_value bundle-id)"
vlc_version="$(manifest_value vlc-version)"
vlc_archive="$(manifest_value vlc-archive)"
vlc_url="$(manifest_value vlc-url)"
iina_source_route="$(manifest_value iina-source-route)"
iina_mpv_build_route="$(manifest_value iina-mpv-build-route)"
mpv_source_route="$(manifest_value mpv-source-route)"
[[ "$bundle_id" == "macos-arm64-vlc-3.0.23" ]] || die "Unexpected source bundle identity: $bundle_id"
[[ "$vlc_version" == "3.0.23" ]] || die "Unexpected VLC source version: $vlc_version"
[[ "$vlc_archive" == "vlc-3.0.23.tar.xz" ]] || die "Unexpected VLC source archive: $vlc_archive"
[[ "$vlc_url" == "https://download.videolan.org/pub/videolan/vlc/3.0.23/vlc-3.0.23.tar.xz" ]] \
    || die "Unexpected VLC source URL"
[[ "$iina_source_route" == "https://github.com/iina/iina/tree/v1.4.0" ]] \
    || die "Unexpected IINA source route"
[[ "$iina_mpv_build_route" == "https://github.com/iina/homebrew-mpv-iina" ]] \
    || die "Unexpected IINA mpv build route"
[[ "$mpv_source_route" == "https://github.com/mpv-player/mpv/tree/c0dd2b3" ]] \
    || die "Unexpected mpv source route"

mkdir -p "$output_dir" "$cache_root"
output_dir="$(cd -- "$output_dir" && pwd -P)"
cache_root="$(cd -- "$cache_root" && pwd -P)"
[[ "$output_dir" != "/" ]] || die "Refusing to use the filesystem root as release output"
[[ "$cache_root" != "/" ]] || die "Refusing to use the filesystem root as the macOS source cache"
rm -f "$output_dir/SOURCE_ARTIFACTS.sha256"

workdir="$(mktemp -d)"
cleanup() {
    rm -rf "$workdir"
}
trap cleanup EXIT

cached_vlc="$cache_root/$vlc_archive"
validate_vlc_source() {
    local source_archive="$1"

    [[ -f "$source_archive" && ! -L "$source_archive" ]] || return 1
    tar -tJf "$source_archive" >/dev/null 2>&1 || return 1
    tar -tJf "$source_archive" | grep -Fx "vlc-$vlc_version/" >/dev/null
}

if ! validate_vlc_source "$cached_vlc"; then
    downloaded_vlc="$workdir/$vlc_archive"
    echo "Fetching VLC $vlc_version source archive" >&2
    curl --fail --location --silent --show-error "$vlc_url" --output "$downloaded_vlc"
    validate_vlc_source "$downloaded_vlc" \
        || die "Downloaded VLC source archive failed structural validation"
    mv -f "$downloaded_vlc" "$cached_vlc"
fi
validate_vlc_source "$cached_vlc" || die "Cached VLC source archive failed structural validation"

revision="$(git -C "$repo_root" rev-parse HEAD)"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || die "Git did not return a full release revision"
project_archive="JellyScope-source-$revision.tar.gz"
project_tar="$workdir/JellyScope-source-$revision.tar"
git -C "$repo_root" archive \
    --format=tar \
    --prefix="JellyScope-$revision/" \
    --output="$project_tar" \
    "$revision"
archive_revision="$(git -C "$repo_root" get-tar-commit-id < "$project_tar")"
[[ "$archive_revision" == "$revision" ]] \
    || die "Generated JellyScope source archive does not identify $revision"
gzip -9 -n < "$project_tar" > "$workdir/$project_archive"

find "$output_dir" -mindepth 1 -maxdepth 1 -type f -name 'JellyScope-source-*.tar.gz' \
    ! -name "$project_archive" -delete
cp -f "$workdir/$project_archive" "$output_dir/$project_archive"
cp -f "$cached_vlc" "$output_dir/$vlc_archive"
cp -f "$manifest" "$output_dir/SOURCE_MANIFEST.txt"
printf 'jellyscope-revision=%s\njellyscope-archive=%s\n' "$revision" "$project_archive" \
    >> "$output_dir/SOURCE_MANIFEST.txt"

echo "Prepared JellyScope $revision and VLC $vlc_version sources in $output_dir"
