# Releasing JellyScope

Release credentials stay local. Android `assembleRelease` uses release signing
only when `~/Private/Keystores/keystore.properties` is complete; otherwise it
uses the CI-compatible debug fallback. The maintained release script requires
Android release signing plus macOS signing and notarization credentials.

Never commit `.local/`, keystores, passwords, Apple private keys, or generated
signing output.

## Android release keystore

1. Install a JDK with `keytool` and create the private credentials directory:

   ```sh
   mkdir -p "$HOME/Private/Keystores"
   ```

2. Generate a release key. Let `keytool` prompt for the store and key
   passwords so they do not appear in shell history:

   ```sh
   keytool -genkeypair \
     -v \
     -keystore "$HOME/Private/Keystores/jellyscope-android-key.jks" \
     -storetype JKS \
     -alias jellyscope-release \
     -keyalg RSA \
     -keysize 4096 \
     -validity 10000
   ```

3. Create `~/Private/Keystores/keystore.properties` with the exact keys read by
   both Android application modules:

   ```properties
   storeFile=jellyscope-android-key.jks
   storePassword=the-keystore-password
   keyAlias=jellyscope-release
   keyPassword=the-key-password
   ```

   Android mobile and Android TV use this same keystore and key alias.

   A relative `storeFile` is resolved from `~/Private/Keystores`. Complete
   values select `signingConfigs.release`; missing or blank values retain the
   debug-signing fallback.

4. Keep a secure backup of the keystore and passwords. Losing the release key
   prevents updates to an already published Android application.

5. Run the Android release verification gate when credentials are available:

   ```sh
   JELLYSCOPE_FORCE_DISABLE_DEVELOPER_PROPERTIES=true ./gradlew \
     :android-app:assembleRelease \
     :android-app:bundleRelease \
     :android-tv-app:assembleRelease \
     :android-tv-app:bundleRelease
   ```

## iOS and tvOS development signing

The Xcode projects do not commit a Development Team ID. For iOS, put
`DEVELOPMENT_TEAM = TEAMID` in ignored `ios-app/LocalSigning.xcconfig`, which
the committed signing config already includes. For tvOS, choose the team
locally or pass `DEVELOPMENT_TEAM` to `xcodebuild`. Never commit a Team ID.

Certificates and their private keys remain in the login keychain, and generated
provisioning profiles remain in Xcode's local profile storage. Neither belongs
in this repository.

Each Xcode project independently owns `MARKETING_VERSION` and
`CURRENT_PROJECT_VERSION`; align them within that Apple platform family. They
do not derive from Android or desktop versions.

## macOS Developer ID signing

1. Enroll in the [Apple Developer Program](https://developer.apple.com/programs/)
   with the distribution account; wait until membership is active.

2. Create a certificate signing request in Keychain Access (`Keychain Access` →
   `Certificate Assistant` → `Request a Certificate From a Certificate
   Authority`) and save the CSR locally.

3. In the Apple Developer portal, open `Certificates, Identifiers & Profiles`,
   create a `Developer ID Application` certificate, upload the CSR, download
   the certificate, and open it to install it in the login keychain.

4. Find the Team ID in the Apple Developer account's Membership details. It is
   also the ten-character value in the certificate identity. Confirm the full
   identity string locally:

   ```sh
   security find-identity -v -p codesigning
   ```

   Use the complete `Developer ID Application: Name (TEAMID)` identity shown by
   that command as `signingIdentity`.

5. Choose one notarytool credential method:

   - App-specific password: sign in to [appleid.apple.com](https://appleid.apple.com/),
     open `Sign-In and Security` → `App-Specific Passwords`, generate a password
     for JellyScope, and keep it private. Use the Apple ID email as `appleId`.
   - App Store Connect API key: in App Store Connect open `Users and Access` →
     `Integrations` → `Keys`, create a key, record its Key ID and Issuer ID,
     download the `.p8` private key once, and store it under
     `~/Private/Keystores`. Use its path as `notaryPrivateKeyPath`.

6. Create `~/Private/Keystores/macos-signing.properties`. The exact property
   keys read by `desktop-app/build.gradle.kts` are:

   ```properties
   signingIdentity=Developer ID Application: Certificate Name (TEAMID)
   teamId=TEAMID

   # Use these three for an Apple ID app-specific-password flow.
   appleId=account@example.com
   appleAppSpecificPassword=app-specific-password

   # Or use these three for an App Store Connect API-key flow.
   notaryKeyId=KEYID12345
   notaryIssuerId=issuer-uuid
   notaryPrivateKeyPath=AuthKey_KEYID12345.p8
   ```

   Remove the unused credential block. The equivalent environment variable
   names are `APPLE_SIGNING_IDENTITY`, `APPLE_TEAM_ID`, `APPLE_ID`,
   `APPLE_APP_SPECIFIC_PASSWORD`, `APPLE_NOTARY_KEY_ID`,
   `APPLE_NOTARY_ISSUER_ID`, and `APPLE_NOTARY_PRIVATE_KEY_PATH`. A property
   file value takes precedence over its environment variable. A relative
   private-key path is resolved from `~/Private/Keystores`.

### Manual macOS stages

The individual commands below allow local edits and are for testing or
troubleshooting one stage in isolation.

7. Verify the bundled desktop mpv runtime and resolved JVM runtime inventory
   before signing:

   ```sh
   ./gradlew :desktop-app:verifyDesktopMpvBundle
   ./gradlew :desktop-app:verifyDesktopJvmRuntimeLicenseInventory
   ```

   [BUILD.md](BUILD.md#desktop-jvm) owns acquisition, cache, and offline
   behavior, including local app-image verification with
   `-Pjellyscope.macos.signing.enabled=false`. That switch disables credential
   loading, Developer ID signing and both notarization routes for the invocation;
   native ad-hoc signing remains. [Licensing and distribution](operations/licensing-and-distribution.md#desktop)
   owns the accepted provenance and source obligations.

8. Build the signed DMG. The committed `desktop-app/entitlements.plist`
   supplies the hardened-runtime exceptions required by the JVM, JNA, and the
   bundled libmpv dylibs. `verifyDesktopMpvBundle` remains a required
   packaging dependency. To include both macOS mpv presentations, pass
   `-Pjellyscope.desktop.mpvMetalRuntimeDir=<source-built-runtime-directory>`;
   [BUILD.md](BUILD.md#desktop-jvm) owns runtime acquisition and validation.

   ```sh
   ./gradlew :desktop-app:packageReleaseDistributionForCurrentOS
   ```

   Package resources only under `common`, `<os>`, or `<os>-<arch>`; the
   `appResourcesRootDir` root is ignored. The produced app must use only bundled
   `@loader_path` mpv dependencies. DMG production normalizes and remount-checks
   the `/Applications` symlink, app, icon, and iconset before signing.

   Release-DMG production stages `JellyScope-source-<revision>.tar.gz`,
   `vlc-3.0.24.tar.xz`, `desktop-mpv-0.41.0-sources.tar.gz`,
   `vlc-contrib-3.0.24-sources.tar.gz`, and `SOURCE_MANIFEST.txt` in
   `desktop-app/build/compose/binaries/main-release/dmg/` beside the DMG. The
   maintained script validates, moves and version-renames them. The mpv archive
   retains all native source inputs, the upstream CoreAudio patch, recipe and
   actual build records. An explicitly requested Metal runtime also stages
   `desktop-mpv-metal-0.41.0-sources.tar.gz` with its matching sources, patches,
   notices and build records. VLC's source archive supplies its build rules and
   patches; the contrib archive retains the selected dependency sources and
   matching notices. Collection validates the packaged runtimes and source
   records without building another native runtime.

   Source pins live in `scripts/macos-source-bundle/manifest-macos-arm64.txt` and
   the referenced native manifests. VLC source downloads use
   `.local/native/macos-sources/`, or `JELLYSCOPE_MACOS_SOURCE_CACHE_ROOT` for a
   dedicated cache; mpv source archives come from the native build cache. Valid
   retained inputs work offline. The JellyScope archive always comes from
   `HEAD`, so a direct dirty-tree package does not prove matching project source.

   To validate a clean source-bound DMG manually, run
   `./gradlew verifyCleanSourceReleaseBinding` immediately before the packaging
   command and again after it, as separate sequential commands. Both checks
   must pass on the same candidate. The maintained release script enforces this
   sequence; local app-image and DMG tasks still permit uncommitted edits.

9. Notarize and staple the DMG. With an Apple ID app-specific password, use
   Compose Desktop's generated task:

   ```sh
   ./gradlew :desktop-app:notarizeReleaseDmg
   ```

   With an App Store Connect API key, use the repository task, which invokes
   `xcrun notarytool submit` and then `xcrun stapler staple`:

   ```sh
   ./gradlew :desktop-app:notarizeReleaseDmgWithApiKey
   ```

10. Validate the signed result before distribution. Confirm the packaged app
    contains `libmpv.2.dylib` and the complete manifest closure and succeeds
    with Homebrew, MacPorts, and other system mpv installations absent:

    ```sh
    codesign --verify --deep --strict --verbose=2 path/to/JellyScope-*.app
    spctl -a -t exec -vv path/to/JellyScope-*.app
    ```

### Maintained release script

Once the Android and macOS credentials are configured, the maintained command
for all Gradle-owned release artifacts is:

```sh
./scripts/build-release-artifacts.sh
```

It rejects a dirty tree or incomplete Android signing, runs preflight, builds and
signature-checks both Android apps, then signs, notarizes, staples, and validates
the macOS DMG and corresponding source. It rechecks source cleanliness and places
versioned output in ignored `release-artifacts/`.

To include the source-built Metal runtime through this script, pass the Gradle
project property in its environment (the script does not forward `-P` arguments):

```sh
env "ORG_GRADLE_PROJECT_jellyscope.desktop.mpvMetalRuntimeDir=$PWD/.local/native/desktop-mpv-metal/runtime" \
  ./scripts/build-release-artifacts.sh
```

The runtime must already be built and validated as described in
[BUILD.md](BUILD.md#desktop-jvm). This remains a signed release command requiring
the credentials and clean candidate above.

#### Collected artifact names

```text
JellyScope-<version>-android.apk
JellyScope-<version>-android.aab
JellyScope-<version>-android-tv.apk
JellyScope-<version>-android-tv.aab
JellyScope-<version>-macos-arm64.dmg
JellyScope-<version>-source-<revision>.tar.gz
JellyScope-<version>-vlc-3.0.24-source.tar.xz
JellyScope-<version>-desktop-mpv-sources.tar.gz
JellyScope-<version>-vlc-3.0.24-contrib-sources.tar.gz
JellyScope-<version>-source-manifest.txt
```

When Metal is requested, the collected artifacts also include
`JellyScope-<version>-desktop-mpv-metal-sources.tar.gz`.

iOS and tvOS archives remain Xcode-owned. The script does not tag or upload.
Publish only after the smoke, binary-compliance, and release gates: APKs are
direct-download artifacts, AABs go through Google Play, and the DMG ships with
its corresponding-source files from the same output directory.

## iOS release artifacts

Before archiving, fetch the pinned official framework and run the scoped
license-metadata gate from a clean release commit:

```sh
./scripts/fetch-vlckit.sh
./gradlew verifyCleanSourceReleaseBinding verifyIosBinaryLicenseMetadataReadiness
```

For a locally rebuilt VLCKit, fetch first, then replace only
`ios-app/Frameworks/VLCKit.xcframework` with a compatible framework built from
the recorded revision using VideoLAN's upstream tools.
Keep its version stamp and both device/simulator slices. The stamp proves
preparation, not provenance; review source and license records before release.
JellyScope has no separate VLCKit build script.

Archive with the `storeRelease` scheme and a generic iOS device, then use the
Organizer's **Distribute App** flow. All committed schemes select StoreRelease
for Archive; that configuration, rather than the scheme name, enforces the
distribution settings described in [BUILD.md](BUILD.md#ios).

- Use TestFlight for public beta, and the App Store or an approved alternative
  route for public distribution.
- Xcode can export a development or ad hoc `.ipa`. GitHub Releases can store
  that file, but it installs only on devices covered by its provisioning route;
  ad hoc distribution requires registered device identifiers and enabled
  Developer Mode. It is not a general public iOS download.
- Keep the raw `.xcarchive` local for re-export and crash symbolication; it is
  not installable.
- A zipped simulator `.app` runs only in a compatible simulator; label it as a
  simulator artifact.

Apple's current workflows are documented in
[Registered-device distribution](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices)
and the [TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview).

## Source and license metadata

Every release package carries `license-metadata` for its exact Git revision:
source URL/tree, project license and notice, third-party/dependency inventory,
native notice/source manifests, managed-runtime notices, and the reviewed
desktop JVM inventory. Verification compares each required record with source.

The preparer resolves output through its nearest existing parent. It accepts an
empty directory or marker-owned symlink-free rerun, and rejects files, nonempty
unmarked directories, symlinks, repository/home roots, and `/`.

Verify the generated set during ordinary development work:

```sh
./gradlew verifyReleaseLicenseMetadata
```

Release build tasks record whether the worktree is dirty but do not reject it,
so release variants remain available for local testing. The maintained
`build-release-artifacts.sh` command runs the strict source-binding check before
and after building Gradle-owned publishable artifacts. iOS and tvOS archives
are Xcode-owned, so run the equivalent check before archiving:

```sh
./gradlew verifyCleanSourceReleaseBinding
```

Clean-source binding proves provenance, not legal or inventory readiness; the
platform readiness gate remains required.

Use the scoped gate for the platform being released:

```sh
./gradlew verifyAndroidBinaryLicenseMetadataReadiness
./gradlew verifyIosBinaryLicenseMetadataReadiness
./gradlew verifyMacosArm64BinaryLicenseMetadataReadiness
```

Android release, iOS StoreRelease, and macOS DMG tasks run their scoped gate.
Ordinary local iOS Release checks consistency only. A `review-required` entry
fails the affected release; clean-source verification remains separate.
The macOS gate also validates both selected native bundles, their original
notices and matching source inputs. Generic metadata verification alone does
not build or approve a native runtime.

The all-target audit remains available:

```sh
./gradlew verifyBinaryLicenseMetadataReadiness
```

It stays blocked while tvOS or another target has an unresolved entry. This does
not block a scoped Android, iOS, or macOS arm64 release. The macOS gate also
rejects a resolved JVM group, module, or version outside its reviewed inventory.
Do not relabel an unresolved entry merely to make a task green.

## Android release checklist

An Android release is one coordinated mobile and TV release from the same
commit. Both applications use the `com.udnahc.jellyscope` application ID for
one Google Play listing and share
`jellyscope.versionName`. `jellyscope.versionCode` is the mobile code; TV uses
the next code. Advance the property by two for the next coordinated release.

### Automated gate and artifacts

- Run `./scripts/verify.sh` and require it to pass.
- Use `./scripts/build-release-artifacts.sh` for publishable artifacts so the
  clean-source gate runs before and after the build.
- Require `verifyAndroidBinaryLicenseMetadataReadiness`; release builds invoke
  it automatically.
- Require the packaged release-license metadata and Android native-bundle
  verifier to pass.
- Confirm the minified Android release artifacts in the
  [collected artifact list](#collected-artifact-names) exist.
- No hosted CI workflow is committed. Run the committed local verification gate
  and retain its output before distributing builds;
  do not assume an external pull-request check supplies release verification.
- Without a configured release keystore (above), release variants are
  debug-signed and suitable for local testing only; production signing and
  store distribution require the keystore.

### Mobile smoke test

- Fresh-install the mobile release APK.
- Log in, open Home and a video library, open Detail, and play through the end.
- Relaunch and confirm the existing session restores to logged-in Home.
- Confirm unsupported libraries show the planned unsupported-library message.
- Log out and confirm credentials, session state, and server-scoped data clear.

### TV smoke test

- Fresh-install the TV release APK on the target emulator or hardware.
- Log in and verify drawer, Home, library, Find, Detail, and playback navigation
  with D-pad SELECT and BACK.
- Verify visible focus returns to the originating item after opening and closing
  Detail or playback.
- Relaunch and confirm the existing session restores to logged-in Home.
- Confirm unsupported libraries show the planned unsupported-library message.
- Log out and confirm credentials, session state, and server-scoped data clear.

### Release handoff

- Record Added, Fixed, and Known gaps in [`../CHANGELOG.md`](../CHANGELOG.md)
  and the release notes for the tag.
- Tag the release only after the automated gate and both smoke-test sections
  pass for the same commit and shared version.
