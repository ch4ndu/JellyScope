#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
signing_directory="${HOME:?HOME must be set}/Private/Keystores"
android_signing_properties="$signing_directory/keystore.properties"
macos_signing_properties="$signing_directory/macos-signing.properties"

usage() {
    cat <<'EOF'
Usage: ./scripts/build-release-artifacts.sh

Builds the signed release candidates owned by Gradle:
  - Android mobile Release APK
  - Android mobile Release AAB
  - Android TV Release APK
  - Android TV Release AAB
  - macOS Release DMG, including Developer ID signing, notarization, and stapling

Moves the completed files into release-artifacts/ with versioned names.

iOS and tvOS release archives remain Xcode-owned and are not built by this script.
The release candidate must be committed, the Git worktree must be clean, and
Android and macOS release credentials must be configured. This script does not
create or upload a GitHub Release. Both APKs must match the configured Android
release key, as must both AABs.
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
command -v jarsigner >/dev/null 2>&1 || die "Required command is missing: jarsigner"
command -v keytool >/dev/null 2>&1 || die "Required command is missing: keytool"
command -v openssl >/dev/null 2>&1 || die "Required command is missing: openssl"
command -v spctl >/dev/null 2>&1 || die "Required command is missing: spctl"
command -v xcrun >/dev/null 2>&1 || die "Required command is missing: xcrun"

cd "$repo_root"
export JELLYSCOPE_FORCE_DISABLE_DEVELOPER_PROPERTIES=true
export ORG_GRADLE_PROJECT_jellyscopeDeveloperPropertiesEnabled=false
trap stop_gradle EXIT

echo "==> Verifying clean release source binding"
./gradlew verifyCleanSourceReleaseBinding

[[ -f "$android_signing_properties" ]] \
    || die "Create $android_signing_properties before building signed Android release candidates"
for property_key in storeFile storePassword keyAlias keyPassword; do
    [[ -n "$(read_property "$android_signing_properties" "$property_key" || true)" ]] \
        || die "Android signing property is missing or blank: $property_key"
done
android_keystore="$(read_property "$android_signing_properties" storeFile)"
android_store_password="$(read_property "$android_signing_properties" storePassword)"
android_key_alias="$(read_property "$android_signing_properties" keyAlias)"
if [[ "$android_keystore" != /* ]]; then
    android_keystore="$signing_directory/$android_keystore"
fi
[[ -f "$android_keystore" ]] || die "Android release keystore was not found: $android_keystore"

android_sdk="$(read_property "$repo_root/local.properties" sdk.dir || true)"
if [[ -z "$android_sdk" ]]; then
    android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
fi
[[ -d "$android_sdk/build-tools" ]] || die "Android SDK build tools were not found"
android_apksigner="$(
    find "$android_sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print \
        | sort -V \
        | tail -n 1
)"
[[ -x "$android_apksigner" ]] || die "Android apksigner was not found"

if ! android_release_certificate_sha256="$(
    JELLYSCOPE_KEYSTORE_PASSWORD="$android_store_password" \
        keytool \
        -exportcert \
        -alias "$android_key_alias" \
        -keystore "$android_keystore" \
        -storepass:env JELLYSCOPE_KEYSTORE_PASSWORD \
        | openssl dgst -sha256 -r \
        | awk '{ print $1 }'
)"; then
    die "Could not read the configured Android release key"
fi
[[ -n "$android_release_certificate_sha256" ]] \
    || die "Could not identify the configured Android release certificate"

verify_android_release_apk() {
    local apk="$1"
    local certificate_sha256

    if ! certificate_sha256="$(
        "$android_apksigner" verify --print-certs "$apk" \
            | awk -F': ' '$1 == "Signer #1 certificate SHA-256 digest" { print $2; exit }'
    )"; then
        die "Android signature verification failed: $apk"
    fi
    [[ -n "$certificate_sha256" ]] || die "Android signer certificate was not found: $apk"
    [[ "$certificate_sha256" == "$android_release_certificate_sha256" ]] \
        || die "Android APK is not signed with the configured release key: $apk"
}

verify_android_release_bundle() {
    local bundle="$1"
    local certificate_sha256
    local verification_status=0

    jarsigner -verify -strict "$bundle" >/dev/null 2>&1 || verification_status=$?
    # Exit 4 means the self-signed Android key is outside the JVM trust store.
    [[ "$verification_status" -eq 0 || "$verification_status" -eq 4 ]] \
        || die "Android bundle signature verification failed: $bundle"
    if ! certificate_sha256="$(
        keytool -printcert -rfc -jarfile "$bundle" \
            | openssl x509 -noout -fingerprint -sha256 \
            | awk -F= 'NF > 1 { print $2; exit }' \
            | tr -d ':' \
            | tr '[:upper:]' '[:lower:]'
    )"; then
        die "Android bundle signer certificate could not be read: $bundle"
    fi
    [[ -n "$certificate_sha256" ]] \
        || die "Android bundle signer certificate was not found: $bundle"
    [[ "$certificate_sha256" == "$android_release_certificate_sha256" ]] \
        || die "Android AAB is not signed with the configured release key: $bundle"
}

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
    die "Configure one complete macOS notarization credential method in $macos_signing_properties or the documented environment variables"
fi

android_version_name="$(read_property "$repo_root/gradle.properties" jellyscope.versionName || true)"
desktop_version_name="$(read_property "$repo_root/gradle.properties" jellyscope.desktop.version || true)"
desktop_package_version="$(read_property "$repo_root/gradle.properties" jellyscope.desktop.packageVersion || true)"
[[ -n "$android_version_name" ]] || die "jellyscope.versionName is missing"
[[ -n "$desktop_version_name" ]] || die "jellyscope.desktop.version is missing"
[[ -n "$desktop_package_version" ]] || die "jellyscope.desktop.packageVersion is missing"

android_apk="$repo_root/android-app/build/outputs/apk/release/android-app-release.apk"
android_bundle="$repo_root/android-app/build/outputs/bundle/release/android-app-release.aab"
android_tv_apk="$repo_root/android-tv-app/build/outputs/apk/release/android-tv-app-release.apk"
android_tv_bundle="$repo_root/android-tv-app/build/outputs/bundle/release/android-tv-app-release.aab"
macos_app="$repo_root/desktop-app/build/compose/binaries/main-release/app/JellyScope.app"
macos_dmg_dir="$repo_root/desktop-app/build/compose/binaries/main-release/dmg"
macos_dmg="$macos_dmg_dir/JellyScope-$desktop_package_version.dmg"
release_output_dir="$repo_root/release-artifacts"
release_android_apk="$release_output_dir/JellyScope-$android_version_name-android.apk"
release_android_bundle="$release_output_dir/JellyScope-$android_version_name-android.aab"
release_android_tv_apk="$release_output_dir/JellyScope-$android_version_name-android-tv.apk"
release_android_tv_bundle="$release_output_dir/JellyScope-$android_version_name-android-tv.aab"
release_macos_dmg="$release_output_dir/JellyScope-$desktop_version_name-macos-arm64.dmg"

echo "==> Android signing: configured release keystore"
echo "==> macOS notarization: $notarization_method"
echo "==> Running the repository release preflight"
./scripts/verify.sh

echo "==> Building Android mobile, Android TV, and the complete macOS DMG"
./gradlew \
    :android-app:assembleRelease \
    :android-app:bundleRelease \
    :android-tv-app:assembleRelease \
    :android-tv-app:bundleRelease \
    "$macos_notarization_task"

echo "==> Rechecking clean release source binding"
./gradlew verifyCleanSourceReleaseBinding

[[ -f "$android_apk" ]] || die "Android mobile Release APK was not produced"
[[ -f "$android_bundle" ]] || die "Android mobile Release AAB was not produced"
[[ -f "$android_tv_apk" ]] || die "Android TV Release APK was not produced"
[[ -f "$android_tv_bundle" ]] || die "Android TV Release AAB was not produced"
[[ -d "$macos_app" ]] || die "macOS Release app was not produced"
[[ -f "$macos_dmg" ]] || die "macOS Release DMG was not produced"
echo "==> Verifying Android release signatures"
verify_android_release_apk "$android_apk"
verify_android_release_bundle "$android_bundle"
verify_android_release_apk "$android_tv_apk"
verify_android_release_bundle "$android_tv_bundle"
release_revision="$(git rev-parse HEAD)"
jellyscope_source="$macos_dmg_dir/JellyScope-source-$release_revision.tar.gz"
vlc_source="$macos_dmg_dir/vlc-3.0.23.tar.xz"
source_manifest="$macos_dmg_dir/SOURCE_MANIFEST.txt"
[[ -f "$jellyscope_source" ]] \
    || die "macOS revision-named JellyScope source archive was not produced"
[[ -f "$vlc_source" ]] \
    || die "macOS VLC corresponding-source archive was not produced"
[[ -f "$source_manifest" ]] \
    || die "macOS corresponding-source manifest was not produced"

echo "==> Validating the signed and notarized macOS artifacts"
codesign --verify --deep --strict --verbose=2 "$macos_app"
codesign --verify --strict --verbose=2 "$macos_dmg"
xcrun stapler validate "$macos_dmg"
spctl -a -t exec -vv "$macos_app"

release_jellyscope_source_name="JellyScope-$desktop_version_name-source-$release_revision.tar.gz"
release_vlc_source_name="JellyScope-$desktop_version_name-vlc-3.0.23-source.tar.xz"
release_source_manifest_name="JellyScope-$desktop_version_name-source-manifest.txt"
release_jellyscope_source="$release_output_dir/$release_jellyscope_source_name"
release_vlc_source="$release_output_dir/$release_vlc_source_name"
release_source_manifest="$release_output_dir/$release_source_manifest_name"

echo "==> Moving release artifacts into $release_output_dir"
mkdir -p "$release_output_dir"
mv -f "$android_apk" "$release_android_apk"
mv -f "$android_bundle" "$release_android_bundle"
mv -f "$android_tv_apk" "$release_android_tv_apk"
mv -f "$android_tv_bundle" "$release_android_tv_bundle"
mv -f "$macos_dmg" "$release_macos_dmg"
mv -f "$jellyscope_source" "$release_jellyscope_source"
mv -f "$vlc_source" "$release_vlc_source"
mv -f "$source_manifest" "$release_source_manifest"

source_manifest_tmp="$release_source_manifest.tmp"
awk -F= \
    -v jellyscope_archive="$release_jellyscope_source_name" \
    -v vlc_archive="$release_vlc_source_name" '
        $1 == "jellyscope-archive" { print "jellyscope-archive=" jellyscope_archive; next }
        $1 == "vlc-archive" { print "vlc-archive=" vlc_archive; next }
        { print }
    ' "$release_source_manifest" > "$source_manifest_tmp"
mv -f "$source_manifest_tmp" "$release_source_manifest"

grep -Fx "jellyscope-archive=$release_jellyscope_source_name" "$release_source_manifest" >/dev/null \
    || die "Collected source manifest does not name the JellyScope source archive"
grep -Fx "vlc-archive=$release_vlc_source_name" "$release_source_manifest" >/dev/null \
    || die "Collected source manifest does not name the VLC source archive"

trap - EXIT
stop_gradle

echo
echo "Signed release candidates are ready for the remaining manual release checks:"
echo "  Output directory:            $release_output_dir"
echo "  Android mobile APK:          $release_android_apk"
echo "  Android mobile AAB:          $release_android_bundle"
echo "  Android TV APK:              $release_android_tv_apk"
echo "  Android TV AAB:              $release_android_tv_bundle"
echo "  macOS signed/notarized DMG:  $release_macos_dmg"
echo "  JellyScope source:           $release_jellyscope_source"
echo "  VLC source:                  $release_vlc_source"
echo "  Source manifest:             $release_source_manifest"
echo "  iOS/tvOS:                    build and archive through Xcode"
echo "  GitHub publication:          not performed"
