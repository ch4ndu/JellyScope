#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

dmg_path="${1:?Usage: ensure-macos-dmg-layout.sh <dmg-path> <app-name>}"
app_name="${2:?Usage: ensure-macos-dmg-layout.sh <dmg-path> <app-name>}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

[[ "$(uname -s)" == "Darwin" ]] || die "DMG layout preparation is macOS-only"
[[ "$dmg_path" == /* && "$dmg_path" != "/" ]] || die "DMG path must be an absolute file path"
[[ -f "$dmg_path" && ! -L "$dmg_path" ]] || die "DMG is missing or is a symbolic link: $dmg_path"
[[ -n "$app_name" && "$app_name" != */* && "$app_name" != "." && "$app_name" != ".." ]] \
    || die "Application name must be one path segment"

for command in hdiutil iconutil mktemp mv readlink rm; do
    command -v "$command" >/dev/null 2>&1 || die "Required command is missing: $command"
done
[[ -x /usr/libexec/PlistBuddy ]] || die "Required command is missing: /usr/libexec/PlistBuddy"

dmg_dir="$(cd -- "$(dirname -- "$dmg_path")" && pwd -P)"
dmg_path="$dmg_dir/$(basename -- "$dmg_path")"
workdir="$(mktemp -d "$dmg_dir/.dmg-layout.XXXXXX")"
mount_point="$workdir/mount"
mounted=false

cleanup() {
    if [[ "$mounted" == true ]]; then
        hdiutil detach "$mount_point" -quiet >/dev/null 2>&1 || true
    fi
    rm -rf -- "$workdir"
}
trap cleanup EXIT

validate_layout() {
    local app="$mount_point/$app_name.app"
    local applications_link="$mount_point/Applications"
    local info_plist="$app/Contents/Info.plist"
    local icon_file
    local icon_name
    local iconset_dir="$workdir/icon-validation.iconset"

    [[ -d "$app" ]] || die "DMG does not contain $app_name.app"
    [[ -L "$applications_link" ]] || die "DMG does not contain an Applications symbolic link"
    [[ "$(readlink "$applications_link")" == "/Applications" ]] \
        || die "DMG Applications link does not target /Applications"
    [[ -f "$info_plist" ]] || die "$app_name.app does not contain Info.plist"
    icon_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIconFile' "$info_plist" 2>/dev/null)" \
        || die "$app_name.app does not declare CFBundleIconFile"
    [[ -n "$icon_name" && "$icon_name" != */* ]] || die "$app_name.app declares an invalid icon name"
    icon_file="$app/Contents/Resources/$icon_name"
    [[ -f "$icon_file" ]] \
        || die "$app_name.app does not contain its declared icon resource"
    rm -rf -- "$iconset_dir"
    iconutil -c iconset -o "$iconset_dir" "$icon_file" >/dev/null 2>&1 \
        || die "$app_name.app contains an invalid icon resource"
    [[ -f "$iconset_dir/icon_512x512@2x.png" ]] \
        || die "$app_name.app icon resource is missing its 1024-pixel representation"
    rm -rf -- "$iconset_dir"
}

mkdir -p "$mount_point"
writable_image="$workdir/writable.dmg"
final_image="$workdir/final.dmg"

hdiutil convert "$dmg_path" -format UDRW -ov -o "$writable_image" -quiet \
    || die "Could not create a writable DMG"
[[ -f "$writable_image" ]] || die "Writable DMG was not created"

hdiutil attach "$writable_image" -mountpoint "$mount_point" -nobrowse -quiet -readwrite \
    || die "Could not mount the writable DMG"
mounted=true
applications_link="$mount_point/Applications"
if [[ -d "$applications_link" && ! -L "$applications_link" ]]; then
    die "DMG contains an Applications directory instead of an install link"
fi
rm -f -- "$applications_link"
ln -s /Applications "$applications_link"
validate_layout
hdiutil detach "$mount_point" -quiet || die "Could not detach the writable DMG"
mounted=false

hdiutil convert "$writable_image" -format UDZO -imagekey zlib-level=9 -ov -o "$final_image" -quiet \
    || die "Could not create the final compressed DMG"
[[ -f "$final_image" ]] || die "Final compressed DMG was not created"
mv -f -- "$final_image" "$dmg_path"

hdiutil attach "$dmg_path" -mountpoint "$mount_point" -nobrowse -quiet -readonly \
    || die "Could not mount the final DMG for verification"
mounted=true
validate_layout
hdiutil detach "$mount_point" -quiet || die "Could not detach the verified DMG"
mounted=false

echo "Prepared and verified $app_name DMG installation layout"
