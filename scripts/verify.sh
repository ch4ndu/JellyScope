#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

stop_gradle() {
    echo "==> Stopping Gradle daemons"
    ./gradlew --stop
}

trap stop_gradle EXIT

echo "==> Checking release metadata output safety"
bash scripts/test-prepare-release-license-metadata.sh

echo "==> Checking the project-owned Android mpv wrapper source"
bash scripts/check-android-mpv-wrapper-source.sh

echo "==> Running ktlint, shared tests, TV route tests, and Android release assemblies"
./gradlew ktlintCheck verifyDeveloperPropertiesIsolation :shared-core:testAndroidHostTest :shared-ui:testAndroidHostTest \
    :shared-ui:verifyReleaseDevServerConfig \
    :shared-tvos:verifyDevServerLiteralEscaping \
    :android-tv-app:testDebugUnitTest :android-app:assembleRelease :android-tv-app:assembleRelease

echo "==> Checking Compose resources are packaged in both release APKs"
# Note: grep must drain the pipe (no -q) or unzip dies of SIGPIPE and
# pipefail reports a false failure.
for apk in \
    android-app/build/outputs/apk/release/android-app-release.apk \
    android-tv-app/build/outputs/apk/release/android-tv-app-release.apk; do
    apk_listing=$(unzip -l "$apk")
    if ! echo "$apk_listing" \
        | grep "assets/composeResources/com.jellyscope.ui.generated.resources/values/strings.commonMain.cvr" \
            > /dev/null; then
        echo "ERROR: shared-ui Compose resources missing from $apk (string lookups will crash at runtime)."
        exit 1
    fi
done

echo "==> Checking the pinned Android mpv/LibVLC native bundle"
bash scripts/verify-android-native-bundle.sh

trap - EXIT
stop_gradle
