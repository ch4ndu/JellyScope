# Building And Running JellyScope

Run commands from the repository root. Product capabilities: [`README.md`](../README.md).

Release variants allow local dirty-tree builds and record that state.
`scripts/build-release-artifacts.sh` instead requires a clean tree and collects
versioned publishable output in ignored `release-artifacts/`.

## Toolchain

- **JDK 21, installed locally.** Modules select it through `jvmToolchain`; no
  toolchain-download resolver is configured.
- Android SDK, for either Android shell. Install the platform matching
  `compileSdk` in the version catalog (currently API 37), plus the side-by-side
  NDK and CMake versions pinned by
  [`android-libmpv/build.gradle.kts`](../android-libmpv/build.gradle.kts).
  Use Android Studio's SDK Manager, including **Show Package Details** for
  native tools. Set `sdk.dir` in the ignored root `local.properties` to the
  SDK directory, or configure `ANDROID_HOME`.
- Android 7.1 / API 25 or newer for both Android shells, which keeps Fire OS 6
  devices installable. The mpv backend additionally requires API 26+ and
  reports typed unavailability below that.
- Xcode with the iOS platform, for `ios-app`; additionally the tvOS platform
  (`xcodebuild -downloadPlatform tvOS`) for `tvos-app`.
- macOS 13 or newer on Apple Silicon for desktop playback and packaging.

Dependencies are pinned in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml); update `media3` and
`media3Ffmpeg` together. The
[Android native dependency runbook](operations/android-native-dependencies.md)
owns the `android-libmpv` AAR, ABI/license manifest, source obligations, and
verification. Normal Android builds download that public input and compile only
the JNI bridge; desktop libmpv is separate.

`compileSdk` selects build APIs and `targetSdk` selects Android compatibility
behavior (currently API 36); neither raises the installation floor. Use the
committed Gradle wrapper and authoritative versions in the catalog/native module.
Check Kotlin against
the [Kotlin Multiplatform compatibility table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
when selecting Xcode or changing Gradle/AGP. Successful local verification of a
combination does not extend the vendor's supported range. `xcodebuild -version`
and `xcode-select -p` identify the active Xcode installation.

## Local developer prefill

An optional repo-root `developer.properties` can prefill one private server and
an OpenSubtitles consumer key for local development. Start from the committed
example and keep the copied file untracked:

```bash
cp developer.properties.example developer.properties
```

Its only supported keys are `devServerUrl`, `devUsername`, `devPassword`, and
`openSubtitlesApiKey`. Leave unused values blank. `local.properties` remains
Android SDK configuration only; do not put developer credentials in it.

Local Android, iOS Run/Test, tvOS Debug, and desktop builds read the file.
`CI=true`/`CI=1`, `StoreRelease`, release tooling, or
`-PjellyscopeDeveloperPropertiesEnabled=false` disable it. CI matching is
case-insensitive; release tooling's force-disable wins even when another Gradle
property requests enablement. `CI=false` remains local mode. A persisted
OpenSubtitles key wins over the unpersisted fallback, which is never displayed
or logged.

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

Without release credentials, both Android shells use debug signing; see
[`RELEASE.md`](RELEASE.md).

LibVLC is degraded in debug builds; test playback with minified release APKs on
representative hardware. After assembly, run
`bash scripts/verify-android-native-bundle.sh` against both release APKs.
Package verification does not replace the
[runtime checks](guides/workflow.md#manual-and-platform-validation).

For a manually initiated Android TV playback link, sign in and exit any current
player, then invoke the item on that same server/account:

```bash
adb -s DEVICE_SERIAL shell \
  "am start -W -a android.intent.action.VIEW -p com.udnahc.jellyscope -d 'jellyscope://play/ITEM_ID?serverId=SERVER_ID&userId=USER_ID'"
```

Replace the four placeholders before running. The link uses current playback
settings and starts at zero; it contains no token or media URL. Accepted inputs,
account checks, and active-player behavior are owned by
[Playback Links](guides/tv-ux-behaviors.md#playback-links).

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

That task produces a DMG and depends on `:desktop-app:verifyDesktopMpvBundle`.
It fetches or reuses the pinned IINA 1.4.0 arm64 closure under
`~/Library/Caches/JellyScope/mpv-runtime/`; first use needs network and a valid
warm cache works offline. Development and packaging use the same runtime and
never read Homebrew, MacPorts, `/usr/local`, or system `libmpv`.

macOS release packaging also bundles audited VLC **3.0.23 (arm64)**.
`scripts/fetch-desktop-vlc-runtime.sh` caches it under
`~/Library/Caches/JellyScope/vlc-runtime/`; first use needs network unless
`JELLYSCOPE_VLC_APP` or `/Applications/VLC.app` supplies a runtime matching
`scripts/vlc-bundle/manifest-3.0.23-arm64.txt`.

## iOS

`ios-app/` hosts the shared Compose app through SwiftUI. Before opening the
project in Xcode or running the first build, prepare its local Swift package's
binary target. With Xcode installed and network access:

```bash
./scripts/fetch-vlckit.sh
xcodebuild -project ios-app/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

The fetch script reuses its prepared framework. Use `devRelease` for local
Release Run/Test and `storeRelease` for distribution. All committed Archive
schemes select **StoreRelease**, which exports the Release Kotlin framework,
disables developer properties, and runs the iOS release-ready license check.
Other configurations cannot archive/install; local Release uses the
non-distribution check.

## tvOS

`tvos-app/` hosts the native SwiftUI shell over the `SharedTv` framework
(Compose Multiplatform publishes no tvOS UI targets). It uses the matching tvOS
slices from the same local VLCKit package as iOS. Prepare that package before
opening Xcode:

```bash
./scripts/fetch-vlckit.sh
./gradlew :shared-tvos:jvmTest :shared-tvos:linkDebugFrameworkTvosSimulatorArm64
xcodebuild -project tvos-app/tvosApp.xcodeproj -scheme tvosApp \
  -configuration Debug \
  -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' build
```

Only tvOS Debug uses the optional developer server prefill; tvOS Release always
uses empty values.

## Installing and upgrading

Choose the platform artifact below. These commands produce local builds; the
[release runbook](RELEASE.md) owns signed publication. A source checkout does
not imply a public download or store listing.

| Platform | Artifact and installation |
| --- | --- |
| Android phone/tablet | Use the mobile APK. Local Release output is `android-app/build/outputs/apk/release/android-app-release.apk`. Open it on the device with installation allowed for the source app, or use Android SDK platform-tools: `adb install -r path/to/mobile.apk`. |
| Android TV / Fire TV | Use the TV APK, locally `android-tv-app/build/outputs/apk/release/android-tv-app-release.apk`. Install it on the TV, for example with `adb install -r path/to/tv.apk` through an authorized ADB connection. |
| macOS | On a supported Apple Silicon Mac, open the DMG and drag JellyScope to Applications, then launch that copy. Direct packaging writes under `desktop-app/build/compose/binaries/main-release/dmg/`; signed release collection is described in the runbook. |
| iOS | Use the distribution route supplied with the build, such as a TestFlight invitation or a provisioned development/ad hoc install. See [iOS release artifacts](RELEASE.md#ios-release-artifacts); an arbitrary IPA or simulator app is not a general device installer. |
| tvOS | Build the Xcode shell above and use Xcode's signing/install route for development. This guide does not advertise a public tvOS release. |

An Android AAB is a store publishing bundle, not a file to install directly.
Local bundles are under each Android module's `build/outputs/bundle/release/`;
Debug APKs are under `build/outputs/apk/debug/`.

For Android upgrades, retain the same variant and signing key and install a
compatible newer version over the existing app. Mobile and TV share the
`com.udnahc.jellyscope` application ID, so they cannot coexist as separate installs
on one device. A differently signed APK cannot update the installed app;
uninstalling to change signing removes its local app data and downloads.
Older builds using `com.jellyscope` are a separate application: installing this
package does not update them or migrate their login, settings, or downloads.
[Release signing](RELEASE.md#android-release-keystore) differs from the local
debug-signing fallback. On macOS, quit JellyScope before replacing its copy in
Applications. Follow the original distribution route for Apple mobile/TV
updates. After installation, see the [usage guide](USAGE.md).

## Verification

The preflight stops Gradle daemons when finished. The
[development workflow](guides/workflow.md) defines change-specific checks and
completion criteria. Broad changes use this baseline:

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

Apply the workflow's [behavior verification and runtime-authorization rules](guides/workflow.md#7-verify-the-final-candidate)
to UI, playback, and platform changes.

## Module map

- `shared-core`: shared data, domain, DI, Jellyfin API facade, and playback
  planning/player contracts.
- `shared-ui`: shared Compose theme, resources, reusable components, and screen
  surfaces for mobile, desktop, and iOS.
- `android-app`: Android mobile application shell over the shared app root.
- `android-tv-app`: Android TV shell with TV-native navigation, focus, player,
  and Watch Next integration.
- `android-libmpv`: project-owned Android mpv JNI bridge and extraction of the
  pinned native build inputs.
- `desktop-app`: Compose Desktop shell and JVM/libmpv playback path.
- `ios-app`: SwiftUI host for the shared Compose iOS app.
- `shared-tvos`: tvOS Kotlin presentation layer (presenters, Swift bridge,
  `SharedTv` framework) over `shared-core`.
- `tvos-app`: native SwiftUI Apple TV shell driven by `shared-tvos`.

Layers, platform seams, and source-layout rules are owned by
[`guides/architecture.md`](guides/architecture.md).

Change requirements are in [`../CONTRIBUTING.md`](../CONTRIBUTING.md). Detailed
code conventions live in the
[development workflow](guides/workflow.md#4-write-clear-maintainable-code).
