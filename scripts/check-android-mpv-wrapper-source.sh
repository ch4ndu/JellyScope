#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

require_text() {
    local file="$1"
    local text="$2"
    grep -F -- "$text" "$repo_root/$file" >/dev/null \
        || die "$file is missing required source marker: $text"
}

require_absent() {
    local file="$1"
    local text="$2"
    if grep -F -- "$text" "$repo_root/$file" >/dev/null; then
        die "$file contains forbidden source marker: $text"
    fi
}

manifest_value() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2); exit }' \
        "$repo_root/scripts/android-mpv-bundle/manifest-1.0.0.txt"
}

wrapper="android-libmpv/src/main/java/dev/jdtech/mpv/MPVLib.kt"
bridge="android-libmpv/src/main/cpp/main.cpp"
events="android-libmpv/src/main/cpp/event.cpp"

require_text "$wrapper" 'Disabled("no")'
require_text "$wrapper" 'Error("error")'
require_text "$wrapper" "nativeRequestLogMessages"
require_text "$wrapper" 'if (nativeInstance == 0L) return'
require_text "$wrapper" 'nativeInstance = 0L'
require_text "$bridge" "nativeRequestLogMessages"
require_text "$bridge" 'mpv_request_log_messages(mpv_instance->mpv, level)'
require_absent "$bridge" 'mpv_request_log_messages(mpv_instance->mpv, "v")'
require_absent "$events" 'ALOGV("[%s:%s] %s"'
require_absent "$events" 'ALOGV("event: %s'

[[ "$(manifest_value artifact-role)" == "pinned-build-input-only" ]] \
    || die "manifest does not mark the Maven AAR as a build input only"
[[ "$(manifest_value project-module)" == "android-libmpv" ]] \
    || die "manifest does not identify android-libmpv"
[[ "$(manifest_value wrapper-build)" == "android-libmpv/build.gradle.kts" ]] \
    || die "manifest wrapper build route changed"
[[ "$(manifest_value source-commit)" == "fcf6745703dc1265bca88f12fee8fc355ddf251e" ]] \
    || die "manifest source commit is not the pinned upstream commit"
[[ "$(manifest_value wrapper-upstream-commit)" == "$(manifest_value source-commit)" ]] \
    || die "manifest wrapper/source commits differ"
[[ "$(manifest_value shipped-abis)" == "arm64-v8a,armeabi-v7a,x86_64" ]] \
    || die "manifest shipped ABI set changed"
[[ "$(manifest_value excluded-abis)" == "x86" ]] \
    || die "manifest excluded ABI set changed"
[[ "$(manifest_value native-ndk)" == "29.0.14206865" && "$(manifest_value native-cmake)" == "4.1.2" ]] \
    || die "manifest native toolchain pin changed"
[[ "$(manifest_value wrapper-libraries)" == "libplayer.so" ]] \
    || die "manifest wrapper library is not libplayer.so"
[[ "$(manifest_value wrapper-patches)" == "typed-log-request,no-raw-logcat,idempotent-destroy" ]] \
    || die "manifest wrapper patch list changed"
case ",$(manifest_value mpv-libraries)," in
    *,libplayer.so,*) die "original bridge remains in the unchanged mpv library set" ;;
esac

require_text "settings.gradle.kts" 'include(":android-libmpv")'
require_text "build.gradle.kts" "alias(libs.plugins.android.library) apply false"
require_text "android-libmpv/build.gradle.kts" "extractPinnedMpvNative"
require_text "android-libmpv/build.gradle.kts" "ANDROID_STL=none"
# The module toolchain pins must move together with the manifest's
# native-ndk/native-cmake values checked above.
require_text "android-libmpv/build.gradle.kts" 'ndkVersion = "29.0.14206865"'
require_text "android-libmpv/build.gradle.kts" 'version = "4.1.2"'
require_text "android-libmpv/build.gradle.kts" 'exclude("jni/x86/**", "jni/**/libplayer.so")'
require_text "android-libmpv/src/main/cpp/CMakeLists.txt" 'add_library(mpv SHARED IMPORTED)'
require_text "android-libmpv/src/main/cpp/CMakeLists.txt" 'add_library(cxx SHARED IMPORTED)'
require_text "shared-core/build.gradle.kts" 'implementation(project(":android-libmpv"))'
require_text "android-app/build.gradle.kts" 'implementation(project(":android-libmpv"))'
require_text "android-tv-app/build.gradle.kts" 'implementation(project(":android-libmpv"))'
require_absent "shared-core/build.gradle.kts" "implementation(libs.libmpv)"
require_absent "android-app/build.gradle.kts" "implementation(libs.libmpv)"
require_absent "android-tv-app/build.gradle.kts" "implementation(libs.libmpv)"

echo "PASS: Android mpv wrapper source and manifest guard"
