#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
prepare_script="$repo_root/scripts/prepare-release-license-metadata.sh"
temporary_root="$(mktemp -d)"
trap 'rm -rf -- "$temporary_root"' EXIT

expect_success() {
    "$prepare_script" "$1" >/dev/null
}

expect_failure() {
    if "$prepare_script" "$1" >/dev/null 2>&1; then
        echo "ERROR: expected metadata preparation to reject $1" >&2
        exit 1
    fi
}

assert_success_output() {
    local output_dir="$1"
    local revision
    local dirty=false
    revision="$(git -C "$repo_root" rev-parse HEAD)"
    if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=normal)" ]]; then
        dirty=true
    fi

    [[ -f "$output_dir/.jellyscope-license-metadata" && ! -L "$output_dir/.jellyscope-license-metadata" ]]
    [[ "$(tr -d '\r\n' < "$output_dir/SOURCE_REVISION.txt")" == "$revision" ]]
    grep -Fx "tracked-worktree-dirty=$dirty" "$output_dir/BUILD_STATE.txt" >/dev/null
    cmp -s "$repo_root/LICENSE" "$output_dir/LICENSE"
    cmp -s "$repo_root/distribution/OPEN_SOURCE_NOTICES.md" "$output_dir/OPEN_SOURCE_NOTICES.md"
    cmp -s "$repo_root/distribution/MOBILE_RUNTIME_NOTICES.md" "$output_dir/MOBILE_RUNTIME_NOTICES.md"
    cmp -s \
        "$repo_root/distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv" \
        "$output_dir/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv"
    cmp -s \
        "$repo_root/distribution/DESKTOP_JVM_RUNTIME_NOTICES.md" \
        "$output_dir/DESKTOP_JVM_RUNTIME_NOTICES.md"
    grep -F "JellyScope-owned source is licensed under MPL-2.0" "$output_dir/OPEN_SOURCE_NOTICES.md" >/dev/null
}

empty_output="$temporary_root/empty"
mkdir "$empty_output"
expect_success "$empty_output"
[[ -f "$empty_output/.jellyscope-license-metadata" ]]
assert_success_output "$empty_output"
expect_success "$empty_output"
assert_success_output "$empty_output"

expect_success "$temporary_root/new/leaf"
[[ -f "$temporary_root/new/leaf/.jellyscope-license-metadata" ]]
assert_success_output "$temporary_root/new/leaf"

visible_nonempty="$temporary_root/visible"
mkdir "$visible_nonempty"
touch "$visible_nonempty/file"
expect_failure "$visible_nonempty"

hidden_nonempty="$temporary_root/hidden"
mkdir "$hidden_nonempty"
touch "$hidden_nonempty/.hidden"
expect_failure "$hidden_nonempty"

file_output="$temporary_root/file-output"
touch "$file_output"
expect_failure "$file_output"

symlink_target="$temporary_root/symlink-target"
mkdir "$symlink_target"
symlink_output="$temporary_root/symlink-output"
ln -s "$symlink_target" "$symlink_output"
expect_failure "$symlink_output"

alias_parent="$temporary_root/alias-parent"
mkdir "$alias_parent"
alias_target="$temporary_root/alias-target"
mkdir "$alias_target"
mkdir "$alias_target/child"
ln -s "$alias_target" "$alias_parent/link"
expect_failure "$alias_parent/link/child"
expect_failure "$alias_parent/link/missing"

expect_failure "/"
expect_failure "$repo_root"
expect_failure "$HOME"
expect_failure "$repo_root/../$(basename "$repo_root")"

echo "PASS: release metadata output safety regression"
