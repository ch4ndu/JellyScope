# Releasing JellyScope

The release runbook: signing setup, release builds, and the release checklist.

Release credentials stay local. Ordinary Android `assembleRelease` builds use
the release signing config only when `.local/keystore.properties` exists and
otherwise retain the debug-signing fallback needed by credential-free CI. The
maintained release-artifact script is stricter: it requires Android release
signing plus macOS signing and notarization credentials before it builds.

Never commit `.local/`, keystores, passwords, Apple private keys, or generated
signing output.

## Android release keystore

1. Install a JDK with `keytool` and create the ignored credentials directory:

   ```sh
   mkdir -p .local
   ```

2. Generate a release key. Let `keytool` prompt for the store and key
   passwords so they do not appear in shell history:

   ```sh
   keytool -genkeypair \
     -v \
     -keystore .local/release-keystore.jks \
     -storetype JKS \
     -alias jellyscope-release \
     -keyalg RSA \
     -keysize 4096 \
     -validity 10000
   ```

3. Create `.local/keystore.properties` with the exact keys read by both
   Android application modules:

   ```properties
   storeFile=.local/release-keystore.jks
   storePassword=the-keystore-password
   keyAlias=jellyscope-release
   keyPassword=the-key-password
   ```

   A relative `storeFile` is resolved from the repository. The file's
   presence selects `signingConfigs.release`; when it is absent, both release
   build types retain their existing debug-signing fallback.

4. Keep a secure backup of the keystore and passwords. Losing the release key
   prevents updates to an already published Android application.

5. Run the Android release verification gate when credentials are available:

   ```sh
   ./gradlew :android-app:assembleRelease :android-tv-app:assembleRelease
   ```

## iOS and tvOS development signing

The Xcode projects intentionally do not commit an Apple Development Team ID.
Simulator builds disable code signing. For iOS, the committed
`ios-app/Signing.xcconfig` optionally includes the ignored
`LocalSigning.xcconfig`. Put `DEVELOPMENT_TEAM = your-team-id` in that local
file, assign `Signing.xcconfig` as the target's Debug and Release Base
Configuration in Xcode, and remove any literal Team ID from `project.pbxproj`.
For tvOS, choose the team locally or supply `DEVELOPMENT_TEAM` to `xcodebuild`;
never include a Team ID in a commit.

Certificates and their private keys remain in the login keychain, and generated
provisioning profiles remain in Xcode's local profile storage. Neither belongs
in this repository.

Apple release version ownership is separate from Android and desktop Gradle
properties. Each Xcode project owns its `MARKETING_VERSION` (the displayed
Apple version) and `CURRENT_PROJECT_VERSION` (the Apple build number); update
those values deliberately as part of an Apple release and keep them aligned
within that platform family. This runbook does not derive them from an Android
prerelease or change the current project values.

## macOS Developer ID signing

1. Enroll in the [Apple Developer Program](https://developer.apple.com/programs/)
   using the Apple organization or individual that will distribute JellyScope.
   Wait until membership is active.

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
     download the `.p8` private key once, and store it under `.local/`. Use its
     path as `notaryPrivateKeyPath`.

6. Create `.local/macos-signing.properties`. The exact property keys read by
   `desktop-app/build.gradle.kts` are:

   ```properties
   signingIdentity=Developer ID Application: Your Name (TEAMID)
   teamId=TEAMID

   # Use these three for an Apple ID app-specific-password flow.
   appleId=you@example.com
   appleAppSpecificPassword=app-specific-password

   # Or use these three for an App Store Connect API-key flow.
   notaryKeyId=KEYID12345
   notaryIssuerId=issuer-uuid
   notaryPrivateKeyPath=.local/AuthKey_KEYID12345.p8
   ```

   Remove the unused credential block. The equivalent environment variable
   names are `APPLE_SIGNING_IDENTITY`, `APPLE_TEAM_ID`, `APPLE_ID`,
   `APPLE_APP_SPECIFIC_PASSWORD`, `APPLE_NOTARY_KEY_ID`,
   `APPLE_NOTARY_ISSUER_ID`, and `APPLE_NOTARY_PRIVATE_KEY_PATH`. A property
   file value takes precedence over its environment variable.

Once the Android and macOS credentials are configured, the maintained
repository command for all Gradle-owned release artifacts is:

```sh
./scripts/build-release-artifacts.sh
```

It runs the repository preflight, builds Android mobile and Android TV with the
configured release key, then signs, notarizes, staples, and validates the macOS
Release DMG and its corresponding-source artifacts. It fails before building
when Android release signing is absent or incomplete. iOS and tvOS archives
remain Xcode-owned. The individual commands below remain useful when running or
troubleshooting one stage in isolation.

The script does not create a tag, GitHub Release, or remote upload. After the
remaining smoke-test, binary-compliance, and release checks pass, manually
publish the two APKs, the DMG, and the macOS corresponding-source files printed
by the script.

## iOS release artifacts

Archive the iOS application through Xcode using a generic iOS device, then use
the Organizer's **Distribute App** flow.

- For normal public beta distribution, upload the archive to App Store Connect
  and use TestFlight. For general public distribution, use the App Store or an
  Apple-approved alternative-distribution route where available.
- Xcode can export a development or ad hoc `.ipa`. GitHub Releases can store
  that file, but it installs only on devices covered by its provisioning route;
  ad hoc distribution requires registered device identifiers, and users must
  enable Developer Mode. It is not a general public iOS download.
- Do not publish the raw `.xcarchive` as an end-user release. Keep it as a local
  release record for re-exporting and crash-symbolication.
- A zipped simulator `.app` may help developers, but it runs only in a
  compatible simulator and must be labeled as a developer artifact.

Apple's current workflows are documented in
[Distributing your app to registered devices](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices)
and the [TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview).

7. Verify the pinned IINA 1.4.0 arm64 mpv runtime before signing. The task
   rechecks the complete 69-file manifest, stages only arm64
   dylibs targeting no newer than macOS 13, relocates their non-system closure,
   and fails instead of reading a machine-installed mpv:

   ```sh
   ./gradlew :desktop-app:verifyDesktopMpvBundle
   ```

   The exact input and GPL/source route are owned by
   `scripts/desktop-mpv-bundle/manifest-iina-1.4.0-arm64.txt` and
   `scripts/desktop-mpv-bundle/ATTRIBUTION.md`. A cold cache needs network; a
   valid warm cache is offline-capable.

   Verify the resolved desktop JVM runtime against its reviewed family/version,
   published-license, and upstream-source inventory as well:

   ```sh
   ./gradlew :desktop-app:verifyDesktopJvmRuntimeLicenseInventory
   ```

8. Build the signed DMG. The committed `desktop-app/entitlements.plist`
   supplies the hardened-runtime exceptions required by the JVM, JNA, and the
   bundled libmpv dylibs. `verifyDesktopMpvBundle` remains a required
   packaging dependency.

   ```sh
   ./gradlew :desktop-app:packageReleaseDistributionForCurrentOS
   ```

   Release-DMG production stages `JellyScope-source-<revision>.tar.gz`,
   `vlc-3.0.23.tar.xz`, and `SOURCE_MANIFEST.txt` in
   `desktop-app/build/compose/binaries/main-release/dmg/` beside the DMG. The
   JellyScope archive is generated from the exact clean release commit. The VLC
   source is structurally validated on every use against
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

The macOS Keychain secure store is validated with a live read/write/remove
round-trip and legacy migration on a signed macOS build during the release
pass.

## Source and license metadata

Every platform release package carries a `license-metadata` set
prepared from the repository candidate. It records the full Git revision and
matching source URL, tracked project tree, current top-level license, open-source
notice, third-party inventory, dependency inputs, and the available Android,
iOS, and macOS native notice/source manifests. The set also contains the
reviewed desktop JVM runtime-family inventory and its notices; each required
record is compared with its repository owner during verification.

Verify the generated set during ordinary development work:

```sh
./gradlew verifyReleaseLicenseMetadata
```

Release-producing tasks bind the source metadata to a clean Git candidate
automatically. Android `preReleaseBuild`, the desktop release distribution
and DMG tasks, and the iOS/tvOS `Package License Metadata` phase use the strict
source-binding check; debug/development paths continue to record metadata
without requiring a clean worktree. The equivalent explicit check is:

```sh
./gradlew verifyCleanSourceReleaseBinding
```

This clean-source binding is a provenance check, not legal or inventory
clearance. It records the exact source revision and metadata that a
package carries, while the separate readiness gate below remains required for
an actual binary release.

The global binary license-metadata readiness check is:

```sh
./gradlew verifyBinaryLicenseMetadataReadiness
```

That command remains blocked while any release target has a `review-required`
entry in `distribution/THIRD_PARTY_COMPONENTS.tsv`. The macOS arm64 release
path instead runs its scoped gate automatically before DMG packaging; its
equivalent explicit command is:

```sh
./gradlew verifyMacosArm64BinaryLicenseMetadataReadiness
```

The scoped command requires a clean candidate and rejects a resolved desktop
JVM group, module, or version outside the reviewed macOS arm64 inventory. It
does not claim readiness for Android, iOS, tvOS, Intel macOS, Windows, or Linux,
and neither command substitutes for the ownership and legal-review requirements
in
[`operations/licensing-and-distribution.md`](operations/licensing-and-distribution.md).
Do not remove or relabel an unresolved inventory entry merely to make the task
green.

## Android release checklist

An Android release is one coordinated mobile and TV release from the same
commit. Both applications read `jellyscope.versionCode` and
`jellyscope.versionName` from `gradle.properties`; bump those values once for
the pair.

### Automated gate and artifacts

- Run `./scripts/verify.sh` and require it to pass.
- Require the packaged release-license metadata and source binding to pass as
  part of the Android native-bundle verifier.
- Confirm the minified release artifacts exist:
  - Mobile: `android-app/build/outputs/apk/release/android-app-release.apk`
  - TV: `android-tv-app/build/outputs/apk/release/android-tv-app-release.apk`
- Hosted CI verifies pull requests but does not publish releases. Run the local
  release verification gate and retain its output before distributing builds.
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
