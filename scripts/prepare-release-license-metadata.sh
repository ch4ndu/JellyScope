#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
output_dir="${1:?Usage: prepare-release-license-metadata.sh <output-dir> [--require-clean]}"
require_clean="${2:-}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

for command in git; do
    command -v "$command" >/dev/null 2>&1 || die "Required command is missing: $command"
done

git -C "$repo_root" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || die "Release metadata must be prepared from a Git worktree"

revision="$(git -C "$repo_root" rev-parse HEAD)"
dirty=false
if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=normal)" ]]; then
    dirty=true
fi
if [[ "$require_clean" == "--require-clean" && "$dirty" == true ]]; then
    die "Tracked files differ from HEAD; commit the release candidate before preparing strict metadata"
fi
if [[ -n "$require_clean" && "$require_clean" != "--require-clean" ]]; then
    die "Unknown option: $require_clean"
fi

absolute_output_dir="$output_dir"
if [[ "$absolute_output_dir" != /* ]]; then
    absolute_output_dir="$(pwd -P)/$absolute_output_dir"
fi
absolute_output_dir="${absolute_output_dir%/}"
[[ -n "$absolute_output_dir" ]] || absolute_output_dir="/"

# Resolve ordinary macOS temporary-directory aliases, but do not permit a
# caller-controlled symlink to redirect an existing output or its parent.
path_components=()
IFS='/' read -r -a path_components <<< "${absolute_output_dir#/}"
path_cursor="/"
for component in "${path_components[@]}"; do
    case "$component" in
        "" | ".")
            ;;
        "..")
            path_cursor="$(dirname -- "$path_cursor")"
            ;;
        *)
            if [[ "$path_cursor" == "/" ]]; then
                path_cursor="/$component"
            else
                path_cursor="$path_cursor/$component"
            fi
            if [[ -L "$path_cursor" ]]; then
                case "$path_cursor" in
                    /var | /tmp)
                        ;;
                    *)
                        die "Refusing symlinked metadata path component: $path_cursor"
                        ;;
                esac
            fi
            ;;
    esac
done

existing_parent="$absolute_output_dir"
new_leaf_components=()
while [[ ! -e "$existing_parent" && ! -L "$existing_parent" ]]; do
    parent="$(dirname -- "$existing_parent")"
    [[ "$parent" != "$existing_parent" ]] \
        || die "Unable to find an existing parent for metadata output: $output_dir"
    previous_leaf_components=("${new_leaf_components[@]-}")
    new_leaf_components=("$(basename -- "$existing_parent")")
    if (( ${#previous_leaf_components[@]} > 0 )); then
        new_leaf_components+=("${previous_leaf_components[@]}")
    fi
    existing_parent="$parent"
done
[[ ! -L "$existing_parent" ]] \
    || die "Refusing symlinked metadata output directory: $output_dir"
[[ -d "$existing_parent" ]] \
    || die "Metadata output parent is not a directory: $existing_parent"

canonical_output_dir="$(cd -P -- "$existing_parent" && pwd -P)"
if (( ${#new_leaf_components[@]} > 0 )); then
    for component in "${new_leaf_components[@]}"; do
    case "$component" in
        "" | ".")
            ;;
        "..")
            canonical_output_dir="$(dirname -- "$canonical_output_dir")"
            ;;
        *)
            if [[ "$canonical_output_dir" == "/" ]]; then
                canonical_output_dir="/$component"
            else
                canonical_output_dir="$canonical_output_dir/$component"
            fi
            ;;
        esac
    done
fi

effective_home="$(cd -P -- "${HOME:?HOME must be set}" && pwd -P)" \
    || die "Unable to resolve the effective home directory"
[[ "$canonical_output_dir" != "/" &&
    "$canonical_output_dir" != "$repo_root" &&
    "$canonical_output_dir" != "$effective_home" ]] \
    || die "Refusing unsafe metadata output directory: $output_dir"

output_dir="$canonical_output_dir"
if [[ -e "$output_dir" ]]; then
    [[ -d "$output_dir" ]] \
        || die "Refusing to replace a non-directory metadata output: $output_dir"
    marker="$output_dir/.jellyscope-license-metadata"
    if [[ -e "$marker" || -L "$marker" ]]; then
        [[ -f "$marker" && ! -L "$marker" ]] \
            || die "Refusing an invalid metadata ownership marker: $marker"
        [[ -z "$(find "$output_dir" -type l -print -quit)" ]] \
            || die "Refusing metadata output containing a symlink: $output_dir"
        rm -rf -- "$output_dir"
    else
        shopt -s nullglob dotglob
        existing_children=("$output_dir"/*)
        shopt -u nullglob dotglob
        (( ${#existing_children[@]} == 0 )) \
            || die "Refusing to replace an unmarked non-empty metadata output: $output_dir"
    fi
elif [[ -L "$output_dir" ]]; then
    die "Refusing symlinked metadata output directory: $output_dir"
fi
mkdir -p \
    "$output_dir/dependency-inputs" \
    "$output_dir/platform-notices/android" \
    "$output_dir/platform-notices/ios-vlckit" \
    "$output_dir/platform-notices/macos-mpv" \
    "$output_dir/platform-notices/macos-vlc"
touch "$output_dir/.jellyscope-license-metadata"

printf '%s\n' "$revision" > "$output_dir/SOURCE_REVISION.txt"
printf '%s\n' "https://github.com/ch4ndu/JellyScope/tree/$revision" > "$output_dir/SOURCE_URL.txt"
printf 'tracked-worktree-dirty=%s\n' "$dirty" > "$output_dir/BUILD_STATE.txt"
git -C "$repo_root" ls-tree -r --full-tree HEAD > "$output_dir/PROJECT_FILES.git-tree"

cp "$repo_root/LICENSE" "$output_dir/LICENSE"
cp "$repo_root/distribution/OPEN_SOURCE_NOTICES.md" "$output_dir/OPEN_SOURCE_NOTICES.md"
cp "$repo_root/distribution/THIRD_PARTY_COMPONENTS.tsv" "$output_dir/THIRD_PARTY_COMPONENTS.tsv"
cp "$repo_root/distribution/MOBILE_RUNTIME_NOTICES.md" \
    "$output_dir/MOBILE_RUNTIME_NOTICES.md"
cp "$repo_root/distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv" \
    "$output_dir/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv"
cp "$repo_root/distribution/DESKTOP_JVM_RUNTIME_NOTICES.md" \
    "$output_dir/DESKTOP_JVM_RUNTIME_NOTICES.md"
cp "$repo_root/gradle/libs.versions.toml" "$output_dir/dependency-inputs/libs.versions.toml"

cp -R "$repo_root/scripts/android-mpv-bundle/." "$output_dir/platform-notices/android/"
cp -R "$repo_root/scripts/vlckit-bundle/." "$output_dir/platform-notices/ios-vlckit/"
cp "$repo_root/scripts/vlc-bundle/LGPL-2.1.txt" \
    "$output_dir/platform-notices/ios-vlckit/LGPL-2.1.txt"
cp -R "$repo_root/scripts/desktop-mpv-bundle/." "$output_dir/platform-notices/macos-mpv/"
cp -R "$repo_root/scripts/vlc-bundle/." "$output_dir/platform-notices/macos-vlc/"

echo "Prepared release license metadata for $revision (tracked-worktree-dirty=$dirty) at $output_dir"
