# Releasing JellyScope

Release credentials stay local. Ordinary Android `assembleRelease` builds use
the release signing config only when `~/Private/Keystores/keystore.properties`
contains all required values and otherwise retain the debug-signing fallback
needed by credential-free CI. The maintained release-artifact script is
stricter: it requires Android release signing plus macOS signing and
notarization credentials before it builds.

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
   ./gradlew \
     :android-app:assembleRelease \
     :android-app:bundleRelease \
     :android-tv-app:assembleRelease \
     :android-tv-app:bundleRelease
   ```

## iOS and tvOS development signing

The Xcode projects intentionally do not commit an Apple Development Team ID.
Simulator builds disable code signing. For iOS, the committed
`ios-app/Signing.xcconfig` optionally includes the ignored
`LocalSigning.xcconfig`. Put `DEVELOPMENT_TEAM = TEAMID` in that local
file. The project and target already use `Signing.xcconfig` for Debug, Release,
and StoreRelease; no Base Configuration or committed Team ID edit is needed.
For tvOS, choose the team locally or supply `DEVELOPMENT_TEAM` to `xcodebuild`;
never include a Team ID in a commit.

Certificates and their private keys remain in the login keychain, and generated
provisioning profiles remain in Xcode's local profile storage. Neither belongs
in this repository.

Apple release version ownership is separate from Android and desktop Gradle
properties. Each Xcode project owns its `MARKETING_VERSION` (the displayed
Apple version) and `CURRENT_PROJECT_VERSION` (the Apple build number). Update
and align them within each Apple platform family; neither derives from Android
prerelease versions.

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
   behavior. [Licensing and distribution](operations/licensing-and-distribution.md#desktop)
   owns the accepted provenance and source obligations.

8. Build the signed DMG. The committed `desktop-app/entitlements.plist`
   supplies the hardened-runtime exceptions required by the JVM, JNA, and the
   bundled libmpv dylibs. `verifyDesktopMpvBundle` remains a required
   packaging dependency.

   ```sh
   ./gradlew :desktop-app:packageReleaseDistributionForCurrentOS
   ```

   Compose packages resources only from `common`, `<os>`, and `<os>-<arch>`
   directories; files staged at the `appResourcesRootDir` root are dropped.
   Inspect every `@loader_path` dependency in the produced `.app`; a supported
   package cannot depend on system libmpv. DMG production normalizes the root
   `Applications` symlink to `/Applications`, then remount-verifies it, the app,
   declared icon, and complete iconset before container signing. Finder
   automation is not required. Remounting verifies the produced container rather
   than trusting packaging-task success after layout setup.

   Release-DMG production stages `JellyScope-source-<revision>.tar.gz`,
   `vlc-3.0.23.tar.xz`, and `SOURCE_MANIFEST.txt` in
   `desktop-app/build/compose/binaries/main-release/dmg/` beside the DMG. The
   maintained release script moves and version-renames those files after final
   validation. The JellyScope archive is generated from `HEAD`; the script
   requires that revision to be the exact clean release candidate. A direct
   dirty-tree package records that state and is only a local test artifact. The
   VLC source is structurally validated on every use against
   `scripts/macos-source-bundle/manifest-macos-arm64.txt` and cached at
   `~/Library/Caches/JellyScope/release-sources/vlc-3.0.23.tar.xz`; a valid warm
   cache is offline-capable, while a cold or invalid cache is downloaded and
   structurally validated before staging. The manifest retains the existing approved
   IINA/mpv source routes; it does not build another native runtime.

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

It runs the repository preflight, builds Android mobile and Android TV with the
configured release key, verifies their APK and AAB signatures, then signs,
notarizes, staples, and validates the macOS Release DMG and its
corresponding-source artifacts. It fails before building when the Git tree is
dirty or Android release signing is absent or incomplete, and rechecks the tree
after building. On success, it moves the publishable files into the ignored
`release-artifacts/` directory at the repository root and renames them with
`jellyscope.versionName` for Android and `jellyscope.desktop.version` for macOS.

#### Collected artifact names

```text
JellyScope-<version>-android.apk
JellyScope-<version>-android.aab
JellyScope-<version>-android-tv.apk
JellyScope-<version>-android-tv.aab
JellyScope-<version>-macos-arm64.dmg
JellyScope-<version>-source-<revision>.tar.gz
JellyScope-<version>-vlc-3.0.23-source.tar.xz
JellyScope-<version>-source-manifest.txt
```

iOS and tvOS archives remain Xcode-owned. The script does not create a tag,
GitHub Release, Play release, or remote upload. After the remaining smoke-test,
binary-compliance, and release checks pass, publish the APKs for direct downloads
and the AABs through Google Play. Publish the DMG and macOS corresponding-source
files from the same output directory.

## iOS release artifacts

Before archiving, fetch the pinned official framework and run the scoped
license-metadata gate from a clean release commit:

```sh
./scripts/fetch-vlckit.sh
./gradlew verifyCleanSourceReleaseBinding verifyIosBinaryLicenseMetadataReadiness
```

To use a locally rebuilt VLCKit, run the fetch step first, then replace only
`ios-app/Frameworks/VLCKit.xcframework` with a compatible framework built from
the recorded revision using VideoLAN's upstream tools. Preserve the adjacent
version stamp created by the fetch script and both required device/simulator
slices. The fetch script runs before cinterop and replaces an unprepared copy
if the stamp or slices are missing. The stamp records preparation, not proof
of the replacement's provenance; review its source and license record before
distribution. JellyScope does not maintain a separate VLCKit build script.

Archive with the `storeRelease` scheme and a generic iOS device, then use the
Organizer's **Distribute App** flow. All committed schemes select StoreRelease
for Archive; that configuration, rather than the scheme name, enforces the
distribution settings described in [BUILD.md](BUILD.md#ios).

- For normal public beta distribution, upload the archive to App Store Connect
  and use TestFlight. For general public distribution, use the App Store or an
  Apple-approved alternative-distribution route where available.
- Xcode can export a development or ad hoc `.ipa`. GitHub Releases can store
  that file, but it installs only on devices covered by its provisioning route;
  ad hoc distribution requires registered device identifiers and enabled
  Developer Mode. It is not a general public iOS download.
- Do not publish the raw `.xcarchive` as an installable release. Keep it as a local
  release record for re-exporting and crash-symbolication.
- A zipped simulator `.app` runs only in a compatible simulator; label it as a
  simulator artifact.

Apple's current workflows are documented in
[Registered-device distribution](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices)
and the [TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview).

## Source and license metadata

Every platform release package carries a `license-metadata` set
prepared from the repository candidate. It records the full Git revision and
matching source URL, tracked project tree, current top-level license, open-source
notice, third-party inventory, dependency inputs, and the available Android,
iOS, and macOS native notice/source manifests. The set also contains the
Android/iOS managed-runtime notice and the reviewed desktop JVM runtime-family
inventory; each required record is compared with its source file during
verification.

The release-metadata preparer resolves output through its nearest existing
parent. It accepts an empty unmarked directory or marker-owned symlink-free
rerun; it rejects files, nonempty unmarked directories, symlinks, repository/home
roots, and `/`. These marker and resolved-path guards prevent replacement from
reaching unrelated data.

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

This clean-source binding is a provenance check, not legal or inventory
clearance. It records the exact source revision and metadata that a
package carries, while the separate readiness gate below remains required for
an actual binary release.

Use the scoped gate for the platform being released:

```sh
./gradlew verifyAndroidBinaryLicenseMetadataReadiness
./gradlew verifyIosBinaryLicenseMetadataReadiness
./gradlew verifyMacosArm64BinaryLicenseMetadataReadiness
```

Android release builds and the iOS StoreRelease metadata phase run their
matching readiness gate automatically. Ordinary local iOS Release checks
metadata consistency without requiring distribution readiness. macOS DMG
packaging runs its scoped readiness gate. These readiness gates
fail on a `review-required` entry for that platform; clean-source verification
is the separate publication gate above.

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
commit. Both applications use the `com.jellyscope` application ID and share
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
