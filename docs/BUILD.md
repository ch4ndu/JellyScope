# Building And Running JellyScope

Run commands from the repository root. Product capabilities: [`README.md`](../README.md).

Release variants may be built from a dirty worktree for local testing. Their
packaged metadata records that state. Only `scripts/build-release-artifacts.sh`
automatically requires a clean tree for Gradle-owned publishable artifacts. On
success, that script collects the versioned files in the ignored
`release-artifacts/` directory at the repository root.

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
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml). Bumping Media3
means bumping `media3` and `media3Ffmpeg` together. The Android
`android-libmpv` wrapper, its pinned AAR build input, native ABI/license
manifest, and corresponding-source process are owned by the
[`android-native-dependencies.md`](operations/android-native-dependencies.md)
runbook. That Android-only input does not change the desktop libmpv path; the
AAR is extracted during the `android-libmpv` build and its original bridge is
not packaged directly. The patched native AAR downloads automatically from the
public JellyScope mpv GitHub Release without GitHub tokens or a sibling repository.
Normal Android builds compile only the JNI bridge, not mpv itself.

`compileSdk` selects build APIs; `targetSdk` selects Android compatibility
behavior and is currently API 36. Neither raises the installation floors above.
The version catalog and native module are the authoritative version owners.
Use the committed Gradle wrapper. Check the selected Kotlin version against
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

The file is read by local Android Debug/Release, iOS Debug/Release Run/Test,
tvOS Debug, and desktop/macOS builds. `CI=true` or `CI=1` (case-insensitive),
`StoreRelease`, and an explicit `-PjellyscopeDeveloperPropertiesEnabled=false`
compile empty values without reading it; `CI=false` remains local mode. Release
tooling also force-disables the file before Gradle, which remains authoritative
even when another Gradle property requests enablement. A persisted
OpenSubtitles key remains authoritative; the local value is an unpersisted
fallback only and is never displayed or logged.

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

Both Android shells fall back to debug signing without release credentials;
see [`RELEASE.md`](RELEASE.md).

LibVLC playback is degraded on debug builds. Test playback on a release build.
The release candidate also carries the pinned Android mpv native payload and
must pass `bash scripts/verify-android-native-bundle.sh` against both release
APKs after assembly. That package check is separate from physical mpv/LibVLC
coexistence and playback validation, which requires minified release APKs on
representative hardware under the
[runtime-validation rules](guides/workflow.md#manual-and-platform-validation).

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

The fetch script reuses a prepared framework on later builds. Use `devRelease`
for local Release Run/Test and `storeRelease` for distribution work. The
enforced archive/install boundary is the **StoreRelease configuration**; all
committed iOS schemes already select it for Archive. It exports the Release
Kotlin framework build type and disables developer properties before Gradle.
Archive/install actions under other configurations stop before Gradle. The
StoreRelease package-license phase applies the iOS release-ready check, while
ordinary local Release uses the non-distribution check.

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

Choose the platform artifact. Build commands above produce local
artifacts; the [release runbook](RELEASE.md) owns signed distribution output
and publication. A source checkout or build command does not imply that a
public download or store listing is available.

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
