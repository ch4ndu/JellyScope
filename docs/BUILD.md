# Building And Running JellyScope

Setup, build, and local-verification instructions for every platform shell,
including the broad verification baseline for wide changes.
[`README.md`](../README.md) is the product/capability entry point.

All commands run from the repository root.

Release variants may be built from a dirty worktree for local testing. Their
packaged metadata records that state. Only `scripts/build-release-artifacts.sh`
automatically requires a clean tree for Gradle-owned publishable artifacts. On
success, that script collects the versioned files in the ignored
`release-artifacts/` directory at the repository root.

## Toolchain

- **JDK 21, installed locally.** Modules target it through `jvmToolchain`, and
  no toolchain-download resolver is configured, so Gradle will not fetch it for
  you.
- Android SDK, for either Android shell.
- Android 7.1 / API 25 or newer for both Android shells, which keeps Fire OS 6
  devices installable. The mpv backend additionally requires API 26+ and
  reports typed unavailability below that.
- Xcode with the iOS platform, for `ios-app`; additionally the tvOS platform
  (`xcodebuild -downloadPlatform tvOS`) for `tvos-app`.
- macOS 13 or newer on Apple Silicon for desktop playback and packaging.

Dependencies are pinned in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml). Bumping Media3
means bumping `media3` and `media3Ffmpeg` together. The Android `android-libmpv` wrapper, its pinned AAR build
input, native ABI/license manifest, and corresponding-source process are owned by the
[`android-native-dependencies.md`](operations/android-native-dependencies.md)
runbook; its exact `dev.jdtech.mpv:libmpv:1.0.0` input pin is Android-only and
does not change the desktop libmpv path. The AAR is extracted during the
`android-libmpv` build and its original bridge is not packaged directly.

## Android mobile

```bash
./gradlew :android-app:assembleDebug
./gradlew :android-app:assembleRelease
./gradlew :android-app:bundleRelease
```

## Android TV

```bash
./gradlew :android-tv-app:assembleDebug
./gradlew :android-tv-app:assembleRelease
./gradlew :android-tv-app:bundleRelease
```

Debug builds can read optional developer server prefill values from the private
`~/Private/Keystores/dev-server.properties` file. Release builds always compile
empty prefill values and fall back to debug signing when no release keystore is
configured; see [`RELEASE.md`](RELEASE.md).

LibVLC playback is degraded on debug builds. Test playback on a release build.
The release candidate also carries the pinned Android mpv native payload and
must pass `bash scripts/verify-android-native-bundle.sh` against both release
APKs after assembly. That package check is separate from the physical mpv/
LibVLC coexistence and playback validation tracked in the internal
`.local/KNOWN-ISSUES.md` ledger.

## Desktop (JVM)

Development:

```bash
./gradlew :desktop-app:compileKotlin
./gradlew :desktop-app:run
```

Release package (macOS only today):

```bash
./gradlew :desktop-app:packageReleaseDistributionForCurrentOS
```

This direct command also works with local edits; a dirty package is for testing,
not publication.

That task produces a DMG and depends on `:desktop-app:verifyDesktopMpvBundle`,
which fetches or reuses the IINA 1.4.0 arm64 dylib set under
`~/Library/Caches/JellyScope/mpv-runtime/iina-1.4.0-arm64`, prepares its pinned
69-file closure, and rejects any architecture, deployment-target, or dependency
drift. The first native-runtime use requires network; a fully valid warm cache
works offline. Development `run` and `hotRun` use the same prepared runtime as
packaging. No path reads Homebrew, MacPorts, `/usr/local`, or a bare system
`libmpv`.

On Apple Silicon, macOS release packaging additionally bundles the audited VLC
runtime. `scripts/fetch-desktop-vlc-runtime.sh` downloads the pinned VLC
**3.0.23 (arm64)** DMG from VideoLAN once into
`~/Library/Caches/JellyScope/vlc-runtime/`, so packaging needs network on the
first run but no VLC install. `JELLYSCOPE_VLC_APP` overrides the source, and an
installed `/Applications/VLC.app` is the offline fallback; whichever source is
used must match `scripts/vlc-bundle/manifest-3.0.23-arm64.txt` or the build
fails.

## iOS

`ios-app/` hosts the shared Compose app through SwiftUI. With Xcode installed
and network access:

```bash
xcodebuild -project ios-app/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

## tvOS

`tvos-app/` hosts the native SwiftUI shell over the `SharedTv` framework
(Compose Multiplatform publishes no tvOS UI targets):

```bash
./gradlew :shared-tvos:jvmTest :shared-tvos:linkDebugFrameworkTvosSimulatorArm64
xcodebuild -project tvos-app/tvosApp.xcodeproj -scheme tvosApp \
  -configuration Debug \
  -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' build
```

## Local verification

```bash
./scripts/verify.sh
```

That is the local pre-flight check, and it stops the Gradle daemons when it
finishes. What it covers, what else a given change must run, and what counts as
verified are owned by [`guides/workflow.md`](guides/workflow.md); the broad
baseline for wide changes is the Verification Baseline section below.

## Verification Baseline

For broad changes:

```bash
./scripts/verify.sh
./gradlew :desktop-app:compileKotlin
./gradlew :desktop-app:packageReleaseDistributionForCurrentOS # macOS 13+ on Apple Silicon
./gradlew :shared-ui:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared-tvos:jvmTest
./gradlew :shared-tvos:linkDebugFrameworkTvosSimulatorArm64
./gradlew --stop
# tvOS app shell (requires the Xcode tvOS platform):
# xcodebuild -project tvos-app/tvosApp.xcodeproj -scheme tvosApp \
#   -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' build
```

Build success is not behavior verification. UI/player/platform work must also
exercise the affected click, D-pad, keyboard, route, playback, lifecycle, or
platform path against its owning contract.

## Module map

- `shared-core`: shared data, domain, DI, Jellyfin API facade, and playback
  planning/player contracts.
- `shared-ui`: shared Compose theme, resources, reusable components, and screen
  surfaces for mobile, desktop, and iOS.
- `android-app`: Android mobile application shell over the shared app root.
- `android-tv-app`: Android TV shell with TV-native navigation, focus, player,
  and Watch Next integration.
- `desktop-app`: Compose Desktop shell and JVM/libmpv playback path.
- `ios-app`: SwiftUI host for the shared Compose iOS app.
- `shared-tvos`: tvOS Kotlin presentation layer (presenters, Swift bridge,
  `SharedTv` framework) over `shared-core`.
- `tvos-app`: native SwiftUI Apple TV shell driven by `shared-tvos`.

Layers, platform seams, and source-layout rules are owned by
[`guides/architecture.md`](guides/architecture.md).

## Local server notes

Keep private Jellyfin server details (URLs, accounts, credentials) in
untracked files under `.local/`. Do not commit real server URLs or
credentials.

Code conventions and pull-request expectations are in
[`../CONTRIBUTING.md`](../CONTRIBUTING.md).
