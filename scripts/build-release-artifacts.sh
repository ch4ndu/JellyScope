#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
android_signing_properties="$repo_root/.local/keystore.properties"
macos_signing_properties="$repo_root/.local/macos-signing.properties"

usage() {
    cat <<'EOF'
Usage: ./scripts/build-release-artifacts.sh

Builds the signed release candidates owned by Gradle:
  - Android mobile Release APK
  - Android TV Release APK
  - macOS Release DMG, including Developer ID signing, notarization, and stapling

iOS and tvOS release archives remain Xcode-owned and are not built by this script.
The release candidate must be committed, the Git worktree must be clean, and
Android and macOS release credentials must be configured. This script does not
create or upload a GitHub Release.
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

read_property() {
    local properties_file="$1"
    local property_key="$2"

    [[ -f "$properties_file" ]] || return 1
    awk -F= -v key="$property_key" '
        /^[[:space:]]*#/ { next }
        {
            name = $1
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", name)
            if (name == key) {
                value = substr($0, index($0, "=") + 1)
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
                print value
                exit
            }
        }
    ' "$properties_file"
}

credential_is_set() {
    local property_key="$1"
    local environment_key="$2"
    local environment_value="${!environment_key-}"
    local configured_value

    configured_value="$(read_property "$macos_signing_properties" "$property_key" || true)"
    if [[ -n "$configured_value" ]]; then
        return 0
    fi

    [[ -n "$environment_value" ]]
}

stop_gradle() {
    echo "==> Stopping Gradle daemons"
    "$repo_root/gradlew" --stop || true
}

if [[ $# -gt 0 ]]; then
    case "$1" in
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "Unknown argument: $1"
            ;;
    esac
fi

[[ "$(uname -s)" == "Darwin" ]] || die "Release artifacts must be built on macOS"
[[ "$(uname -m)" == "arm64" ]] || die "The supported macOS release target is arm64"
command -v awk >/dev/null 2>&1 || die "Required command is missing: awk"
command -v codesign >/dev/null 2>&1 || die "Required command is missing: codesign"
command -v spctl >/dev/null 2>&1 || die "Required command is missing: spctl"
command -v xcrun >/dev/null 2>&1 || die "Required command is missing: xcrun"

[[ -f "$android_signing_properties" ]] \
    || die "Create .local/keystore.properties before building signed Android release candidates"
for property_key in storeFile storePassword keyAlias keyPassword; do
    [[ -n "$(read_property "$android_signing_properties" "$property_key" || true)" ]] \
        || die "Android signing property is missing or blank: $property_key"
done
android_keystore="$(read_property "$android_signing_properties" storeFile)"
if [[ "$android_keystore" != /* ]]; then
    android_keystore="$repo_root/$android_keystore"
fi
[[ -f "$android_keystore" ]] || die "Android release keystore was not found: $android_keystore"

credential_is_set signingIdentity APPLE_SIGNING_IDENTITY \
    || die "Set signingIdentity or APPLE_SIGNING_IDENTITY for the macOS release"

if credential_is_set appleId APPLE_ID &&
    credential_is_set appleAppSpecificPassword APPLE_APP_SPECIFIC_PASSWORD &&
    credential_is_set teamId APPLE_TEAM_ID; then
    macos_notarization_task=":desktop-app:notarizeReleaseDmg"
    notarization_method="Apple ID app-specific password"
elif credential_is_set notaryKeyId APPLE_NOTARY_KEY_ID &&
    credential_is_set notaryIssuerId APPLE_NOTARY_ISSUER_ID &&
    credential_is_set notaryPrivateKeyPath APPLE_NOTARY_PRIVATE_KEY_PATH; then
    macos_notarization_task=":desktop-app:notarizeReleaseDmgWithApiKey"
    notarization_method="App Store Connect API key"
else
    die "Configure one complete macOS notarization credential method in .local/macos-signing.properties or the documented environment variables"
fi

desktop_package_version="$(
    awk -F= '$1 == "jellyscope.desktop.packageVersion" { print $2; exit }' \
        "$repo_root/gradle.properties"
)"
[[ -n "$desktop_package_version" ]] || die "jellyscope.desktop.packageVersion is missing"

android_apk="$repo_root/android-app/build/outputs/apk/release/android-app-release.apk"
android_tv_apk="$repo_root/android-tv-app/build/outputs/apk/release/android-tv-app-release.apk"
macos_app="$repo_root/desktop-app/build/compose/binaries/main-release/app/JellyScope.app"
macos_dmg_dir="$repo_root/desktop-app/build/compose/binaries/main-release/dmg"
macos_dmg="$macos_dmg_dir/JellyScope-$desktop_package_version.dmg"

cd "$repo_root"
trap stop_gradle EXIT

echo "==> Android signing: configured release keystore"
echo "==> macOS notarization: $notarization_method"
echo "==> Running the repository release preflight"
./scripts/verify.sh

echo "==> Building Android mobile, Android TV, and the complete macOS DMG"
./gradlew \
    :android-app:assembleRelease \
    :android-tv-app:assembleRelease \
    "$macos_notarization_task"

[[ -f "$android_apk" ]] || die "Android mobile Release APK was not produced"
[[ -f "$android_tv_apk" ]] || die "Android TV Release APK was not produced"
[[ -d "$macos_app" ]] || die "macOS Release app was not produced"
[[ -f "$macos_dmg" ]] || die "macOS Release DMG was not produced"
release_revision="$(git rev-parse HEAD)"
[[ -f "$macos_dmg_dir/JellyScope-source-$release_revision.tar.gz" ]] \
    || die "macOS revision-named JellyScope source archive was not produced"
[[ -f "$macos_dmg_dir/vlc-3.0.23.tar.xz" ]] \
    || die "macOS VLC corresponding-source archive was not produced"
[[ -f "$macos_dmg_dir/SOURCE_MANIFEST.txt" ]] \
    || die "macOS corresponding-source manifest was not produced"

echo "==> Validating the signed and notarized macOS artifacts"
codesign --verify --deep --strict --verbose=2 "$macos_app"
codesign --verify --strict --verbose=2 "$macos_dmg"
xcrun stapler validate "$macos_dmg"
spctl -a -t exec -vv "$macos_app"

trap - EXIT
stop_gradle

echo
echo "Signed release candidates are ready for the remaining manual release checks:"
echo "  Android mobile:              $android_apk"
echo "  Android TV:                  $android_tv_apk"
echo "  macOS signed/notarized DMG:  $macos_dmg"
echo "  macOS corresponding source: $macos_dmg_dir"
echo "  iOS/tvOS:                    build and archive through Xcode"
echo "  GitHub publication:          not performed"
