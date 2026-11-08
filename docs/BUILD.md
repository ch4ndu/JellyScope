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
- macOS 15 or newer on Apple Silicon for desktop playback and packaging.

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

Install CMake **4.1.2** through the Android SDK Manager. The native module declares
that version and resolves it from the SDK selected by `sdk.dir`; avoid a
checkout-specific `cmake.dir` override or a duplicate toolchain under `.local/`.

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
[runtime checks](engineering/workflow.md#manual-and-platform-validation).

For a manually initiated Android TV media-entry link, sign in and exit any current
player, then invoke the item on that same server/account:

```bash
adb -s DEVICE_SERIAL shell \
  "am start -W -a android.intent.action.VIEW -p com.udnahc.jellyscope -d 'jellyscope://play/ITEM_ID?serverId=SERVER_ID&userId=USER_ID'"
```

Replace the four placeholders before running. The link opens typed Details/Series
without autoplay; choose Play or Resume there. It contains no token or media URL. Accepted inputs,
account checks, and active-player behavior are owned by
[Playback Links](engineering/tv-ux-behaviors.md#playback-links).

## Android launcher foundation

Launcher builds and artifact limits are documented in the dedicated
[launcher guide](launcher/README.md#build-and-install-the-launcher).

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

Normal development and packaging use the same project-built mpv runtime. The
[source recipe](../scripts/build-desktop-mpv-runtime.sh) invokes the upstream
Meson/Ninja, CMake and configure/Make builds for the pinned sources in
[`scripts/desktop-mpv-bundle/`](../scripts/desktop-mpv-bundle). Install Xcode's
macOS SDK, Python 3.10+, Make, CMake, Ninja, pkg-config, autoconf, automake and
GNU libtool before the first build. The recipe supplies its pinned Meson version;
it does not install tools. Package managers may supply build tools, but runtime
libraries must come from the isolated source build.

Shared reference repositories and native source/build caches live under
`~/Private/JellyScopeData/`. Keep each worktree's
`.local/` limited to current task records and temporary output. Reference checkouts
may be linked at `.local/reference-repos`; native cache paths must be real paths.
Do not copy the main checkout's entire `.local/` when creating a worktree.
Retained historical records belong under `task-archives/`, immutable shared
inputs under `inputs/`, and original release/source/notice sets under `releases/`
in the shared data root. Old checkout paths may link to these records for reading;
restore a task-owned copy before resuming an archived workflow.

Gradle prepares the runtime using `~/Private/JellyScopeData/caches/desktop-mpv`
unless `JELLYSCOPE_MPV_RUNTIME_CACHE_ROOT` overrides it. For direct native builds,
load the local shared environment first:

```bash
source "$HOME/Private/JellyScopeData/env.sh"
bash scripts/build-desktop-mpv-runtime.sh
```

The first build downloads and compiles the native graph for arm64 and macOS 15.
The configured cache retains sources and the completed runtime. An unconfigured
standalone builder falls back to `.local/native/desktop-mpv/`; loading the shared
environment avoids populating that fallback in each worktree. The pinned builder
recipe is unchanged, preserving the existing cache's recipe identity.
`JELLYSCOPE_MPV_RUNTIME_CACHE_ROOT` may select a dedicated absolute cache path
without symlinks or whitespace. A matching recipe, manifests and toolchain allow
reuse after the complete runtime passes validation; a valid warm cache works
offline. Staging and packaging check the actual versions, architecture, minimum
OS, dependency closure, required features, notices and signatures. Neither path
loads an installed Homebrew, MacPorts, `/usr/local`, or system `libmpv`.

macOS packaging also bundles pinned VLC **3.0.24 (arm64)**.
`scripts/fetch-desktop-vlc-runtime.sh` caches it under
`~/Private/JellyScopeData/caches/vlc-runtime/`; first use needs network unless
`JELLYSCOPE_VLC_APP` explicitly supplies that version. The stager verifies the
selected files against `scripts/vlc-bundle/manifest-3.0.24-arm64.txt` and checks
their architecture, dependency closure and signatures.

To build both local app images without Developer ID signing or notarization:

```bash
./gradlew -Pjellyscope.macos.signing.enabled=false \
    :desktop-app:createDistributable :desktop-app:createReleaseDistributable
```

This invocation skips credential loading and credentialed signing/notarization;
native ad-hoc signatures remain required. The property defaults to `true` for
normal release operations. Both images are validated at their own packaged
resource paths. DMG source artifacts and clean-source publication checks are
owned by [the release runbook](RELEASE.md#manual-macos-stages).

### macOS Metal experiment

The source-built Metal path requires Apple Silicon, macOS 15 or newer, Xcode,
and the [desktop native build prerequisites](#desktop-jvm). It uses the Standard
source recipe plus pinned MoltenVK and a static glslang compiler, with MPVKit's
caller-owned `CAMetalLayer` patch and the local resize repair. Build each graph
in its separate cache:

```bash
bash scripts/build-desktop-mpv-runtime.sh
metal_runtime="$(bash scripts/build-desktop-mpv-runtime.sh --metal)"
./gradlew --no-daemon :desktop-app:run \
  -Djellyscope.desktop.mpvMetalExperiment=true \
  "-Djellyscope.desktop.mpvMetalRuntimeDir=$metal_runtime"
```

The shared environment selects `~/Private/JellyScopeData/caches/desktop-mpv-metal`;
`JELLYSCOPE_MPV_METAL_RUNTIME_CACHE_ROOT` may select another dedicated cache.
`JELLYSCOPE_DATA_ROOT` changes the shared data root used by Gradle, VLC, source
retention, and the older experiment helper. Direct source builds require the
matching Standard/Metal cache environment variables.
Standard and Metal caches have separate ownership and cannot substitute for each
other. The [experiment notes](../scripts/desktop-mpv-metal/METAL-EXPERIMENT.md)
retain the older MPVKit binary investigation, which is not accepted by the
source-built package validator. Keep experiment outputs task-local and path checks
intact; the builder retains matching sources, licenses, patches and build records.

Both properties are required and also work with `:desktop-app:hotRun`. Select
mpv and do not combine them with `jellyscope.desktop.forceMpvOpenGlSurface`.
Restart without the pair to use the saved Settings choice. Distribution
arguments and the production runtime preparation do not consume this `-D` pair.

To build a local app with both runtime choices, use the **Gradle** `-P` property:

```bash
./gradlew :desktop-app:createDistributable \
  -Pjellyscope.macos.signing.enabled=false \
  "-Pjellyscope.desktop.mpvMetalRuntimeDir=$metal_runtime"
```

The app image is under `desktop-app/build/compose/binaries/main/app/`.
In Settings → Playback → mpv presentation, choose Standard or Metal — Experimental,
wait for the selection to save, then quit and reopen the app when prompted.
Standard is the initial default. The choice is app-wide, independent of the
Jellyfin account and Default player setting. Selecting the running mode again
clears the restart notice. A build without the Metal runtime disables that option.
Development launch overrides disable this selector for that process.

`-P` stages the complete validated Metal graph under the app's `mpv-metal`
resource directory; it never embeds the source cache or forces the active mode.
The similarly named `-D` pair forces a development launch but does not stage
libraries. Omitting `-P` removes only owned Metal staging before packaging.
Requested Metal staging is validated before run, resource preparation, packaging
and signing. Existing clean-source, license and signing gates still apply.

Metal source collection is explicitly selected with `--with-mpv-metal` in the
macOS source and license verification scripts; Gradle passes it when the `-P`
property is set. The matching Metal source sidecar contains the merged source
archives, notices, patches, build records and recipes. Unselected stale Metal
resources fail verification. Source-built distribution readiness remains subject
to the native build, corresponding-source inventory and package checks; this
work does not authorize signing or publication.

The experiment uses software decoding (`hwdec=no`). Begin with a fixed window
size and check cadence and audible sync before testing pointer movement.
Check resize/fullscreen geometry and paused redraw with the source-built runtime.
Controls, subtitles and close/reopen also need visual checks.
The [desktop playback guide](engineering/desktop-playback.md#desktop-player-presentation)
owns the lifetime and failure contracts. The retained prebuilt candidate is for local development and does not establish
source-built package readiness.

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

Native Debug builds use `kotlin.incremental.native=false`. With the pinned Kotlin
2.4.10/coroutines 1.11.0 toolchain, per-file incremental caches can emit an
unresolved `BufferedChannel` synthetic accessor. The failure survives a clean
rebuild of the coroutine cache and appears only when Xcode links the complete
app; creating the static Kotlin framework can still succeed.

Standard linking avoids that failure. This may increase Debug edit/build time;
optimized Release linking is unaffected. Re-enable native incremental compilation
only after the full app links successfully with the selected toolchain, rather
than using a framework-only result. Do not clear shared toolchain caches or
change dependency versions to work around this symptom.

`ios-framework` produces the static `SharedUi` framework and narrow Swift entry/
download bridge. [Architecture](engineering/architecture.md#source-layout-target)
owns composition, resource and feature boundaries.
Keep UI dependencies unexported from the framework. The existing
`:shared-ui:link{Debug,Release}FrameworkIos{Arm64,SimulatorArm64}` commands
remain compatibility aliases; their framework output is under
`ios-framework/build/bin/`, and Xcode staging is under
`ios-framework/build/xcode-frameworks/`.

The shared UI DI module uses `@HiddenFromObjC` to keep Koin out of Swift's API
while remaining public to other Kotlin modules. Preserve the iOS entry point
and background-download bridge when changing exported declarations.

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

For user installation and upgrade precautions, see [Getting started](user/getting-started.md#install-and-upgrade).
The artifact paths and commands below are for local development builds.

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
[development workflow](engineering/workflow.md) defines change-specific checks and
completion criteria. Broad changes use this baseline:

```bash
./scripts/verify.sh
./gradlew :core-model:jvmTest :diagnostics:jvmTest \
  :platform:jvmTest :network:jvmTest :player:jvmTest :playback-policy:jvmTest \
  :jellyfin-api:jvmTest :diagnostics-runtime:jvmTest :player-runtime:jvmTest :storage:jvmTest :transport:jvmTest :ui-foundation:jvmTest :settings-account:jvmTest \
  :browse:jvmTest :playback:jvmTest :detail:jvmTest \
  :auth:jvmTest :downloads:jvmTest
./gradlew :desktop-app:compileKotlin
./gradlew -Pjellyscope.macos.signing.enabled=false :desktop-app:packageReleaseDistributionForCurrentOS # macOS 15+ on Apple Silicon; local test DMG
./gradlew :ios-framework:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared-tvos:jvmTest
./gradlew :shared-tvos:linkDebugFrameworkTvosSimulatorArm64
./gradlew --stop
# tvOS app shell (requires the Xcode tvOS platform):
# xcodebuild -project tvos-app/tvosApp.xcodeproj -scheme tvosApp \
#   -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' build
```

Apply the workflow's [behavior verification and runtime-authorization rules](engineering/workflow.md#7-verify-the-final-candidate)
to UI, playback, and platform changes.

## Module map

See the [architecture ownership map](engineering/architecture.md#source-layout-target)
for modules and dependency direction.
