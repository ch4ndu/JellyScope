# Licensing And Store Distribution

This document owns JellyScope's active project-source license, the boundary
between project and third-party licenses, platform-distribution obligations,
source availability, trademark direction, and the remaining binary-release
compliance gates.
Exact Android native inputs and corresponding-source packaging remain owned by
[`android-native-dependencies.md`](android-native-dependencies.md).

This is an owner-approved engineering and release policy, not legal advice. It
uses the practical FOSS approach followed by comparable media clients: pin the
distributed inputs, preserve their notices, record upstream source routes, and
inspect the final package. The recorded Android, iOS, and macOS arm64 inputs use
their publishers' license and source declarations without a separate legal-
review blocker. A changed artifact or dependency graph requires a new review.

## Current Source-License Status

JellyScope-owned source and non-brand assets are licensed under `MPL-2.0`. The
root [`LICENSE`](../../LICENSE) contains the exact unmodified MPL-2.0 text, and
eligible JellyScope-owned source files carry `SPDX-License-Identifier:
MPL-2.0`. This source-only cutover does not change any imported or third-party
license, notice, or obligation.

The active MPL coverage is limited to material whose copyright is owned by, or
validly relicensable by, Murali Vipparla as the JellyScope project owner.
Third-party code, generated wrappers derived from third-party sources, fonts,
media, native binaries, and imported assets keep their existing terms. Official
identity assets remain subject to the separate trademark gate.

No per-file copyright line was added as part of the cutover. Copyright notices,
when present, must remain accurate for generated, imported, adapted, and
third-party files and must not be added or rewritten mechanically.

The source license alone does not establish binary readiness. Android, Apple,
and desktop applications combine JellyScope source with separately licensed
dependencies and native artifacts, so each release target has its own gate.

Recipients of earlier GPL-3.0-only copies retain the rights already conveyed to
them. The current source-license cutover does not revoke those rights.

The repository prepares release-license metadata for each release target.
[`distribution/OPEN_SOURCE_NOTICES.md`](../../distribution/OPEN_SOURCE_NOTICES.md)
and [`distribution/THIRD_PARTY_COMPONENTS.tsv`](../../distribution/THIRD_PARTY_COMPONENTS.tsv)
are packaged with release artifacts together with the exact source revision,
project tree, dependency inputs, native notices, source manifests, the Android
and iOS runtime notice in
[`distribution/MOBILE_RUNTIME_NOTICES.md`](../../distribution/MOBILE_RUNTIME_NOTICES.md),
and the macOS arm64 JVM inventory and notices in
[`distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv`](../../distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv)
and [`distribution/DESKTOP_JVM_RUNTIME_NOTICES.md`](../../distribution/DESKTOP_JVM_RUNTIME_NOTICES.md).
Existing settings surfaces identify the source revision and route users to the
matching repository tree. A `review-required` entry blocks only its named
platform. Passing a scoped gate records technical readiness under this policy;
it is not a legal opinion.

MPL-2.0 provides file-level reciprocity rather than whole-program copyleft. A
distributor must make MPL-covered source available as the license requires;
independent files may use other terms in a larger work. Paid use and compliant
commercial redistribution remain permitted. Product-identity protection must
therefore come from trademark and store policy, not a noncommercial restriction
added to MPL.

## Current Native And Packaging Inputs

These are source-checked boundaries, not compliance conclusions. A release must
inspect the final artifacts because a dependency name or top-level project
license does not establish the packaged native graph.

### Android

The Android apps package the project-owned `android-libmpv` bridge over a
pinned AAR input, Jellyfin's GPL-3.0 Media3 FFmpeg decoder, and VideoLAN's
LGPL-2.1 LibVLC runtime. Exact versions, ABIs, libraries, license texts, patches,
and source routes are owned by
[`android-native-dependencies.md`](android-native-dependencies.md) and its
linked manifests.

The combined Android application is distributed under GPL-3.0 terms.
JellyScope-owned files remain available under MPL-2.0 and are additionally
distributed under GPL-3.0 for this Larger Work through MPL-2.0 Section 3.3.
The project owner accepts the pinned upstream GPL/LGPL declarations and source
routes. The scoped gate checks the inventory, and the Android bundle verifier
checks the final APKs for the recorded native libraries and release metadata.

### iOS And tvOS

The iOS build currently uses the pinned VLCKit 4 pre-release
XCFramework fetched by [`scripts/fetch-vlckit.sh`](../../scripts/fetch-vlckit.sh)
and linked through the local binary Swift package in
[`ios-app/VLCKitLocal/Package.swift`](../../ios-app/VLCKitLocal/Package.swift).
The device slice is a dynamically linked framework. The exact archive,
upstream LGPL-2.1 declaration and license text, official build revision,
libVLC base revision, patch set, and source routes are recorded in
[`scripts/vlckit-bundle/manifest-4.0.0a23.txt`](../../scripts/vlckit-bundle/manifest-4.0.0a23.txt).
The project owner accepts that published provenance for this pinned input.

A developer may build the recorded VLCKit revision with VideoLAN's upstream
tools and place the resulting compatible `VLCKit.xcframework` at
`ios-app/Frameworks/`. The existing local Swift package and Kotlin cinterop use
that path. JellyScope does not mirror VLCKit or maintain a duplicate build
script.

The current tvOS target uses the Apple player path and does not consume the iOS
VLCKit cinterop. Its managed dependency graph is still `review-required`. If a
native player is later added to tvOS, that graph must be reviewed separately.

The iOS release gate requires the exact source/notices record and a clean source
revision. App signing, App Store metadata, privacy declarations, and final
archive inspection remain ordinary release tasks in [`../RELEASE.md`](../RELEASE.md).
Distribution terms must not restrict rights granted by the included FOSS
licenses.

### Desktop

The macOS arm64 desktop JVM inventory groups every resolved external
`desktop-app` runtime component by exact Gradle group, module, and resolved
version. `verifyDesktopJvmRuntimeLicenseInventory` resolves the runtime
classpath and rejects an absent or unreviewed coordinate; the scoped
`verifyMacosArm64BinaryLicenseMetadataReadiness` gate and DMG packaging depend
on that task. The inventory records each family's published license and
upstream source route, while its companion notice preserves the scope and JNA's
published dual-license choice. A dependency or version change requires
inventory review rather than an automatic catalog-derived approval. Other
release targets remain blocked in the global inventory until their own runtime
graphs are reviewed.

The macOS arm64 mpv package consumes IINA's versioned 1.4.0 dylib set. The
69-file inventory, IINA bundle route, disclosed mpv revision `c0dd2b3`,
GPL texts, and source routes are owned by
[`scripts/desktop-mpv-bundle/`](../../scripts/desktop-mpv-bundle/). JellyScope
validates the complete inventory on every cache use and changes only staged install
names so non-system dependencies resolve through sibling `@loader_path`
references, then re-signs the staged files. It does not read Homebrew, MacPorts,
`/usr/local`, or another machine-installed mpv.

IINA and each upstream component retain their own licenses. Their published
licensing and source declarations are relied upon but were not independently
audited. JellyScope-owned files remain MPL-2.0; under MPL-2.0 Section 3.3, the
combined macOS distribution is made available under GPL-compatible terms while
preserving the MPL terms for covered files. The project owner accepts this
provenance and licensing route without a separate legal-review blocker for the
pinned mpv runtime. A different IINA release, architecture, file inventory, or
native graph requires a new recorded decision.

Apple Silicon macOS packages additionally carry a pinned VLC 3.0.23 arm64
subset with GPL/LGPL texts and a corresponding-source route. Its exact files
and exclusions are owned by
[`scripts/vlc-bundle/manifest-3.0.23-arm64.txt`](../../scripts/vlc-bundle/manifest-3.0.23-arm64.txt)
and [`scripts/vlc-bundle/ATTRIBUTION.md`](../../scripts/vlc-bundle/ATTRIBUTION.md).
Release-DMG production also creates the exact clean JellyScope commit archive
and retains the VLC 3.0.23 source archive beside the DMG. The
source manifest preserves the existing IINA 1.4.0, IINA mpv-formula, and mpv
`c0dd2b3` routes without introducing another native build.
Intel macOS packages are unsupported. Windows and Linux packages, and any future
architecture, require their own artifact-level audit rather than an inference
from the macOS manifest.

## Contributors, Provenance, And Product Identity

Murali Vipparla is the sole human contributor to the JellyScope-owned material
currently in this repository. The project owner has confirmed that he owns, or
has written relicensing permission for, all JellyScope-authored source and
non-brand assets, including work produced in an employment or client context
and material adapted from another source. Git author names or aliases do not
represent additional human contributors.

That confirmation is the ownership and provenance record relied on for this
source-only cutover. It does not claim ownership of imported code, generated
third-party wrappers, fonts, media, native binaries, or other third-party
material, and it does not change their existing licenses or notices.

Adopt a contributor policy before accepting outside contributions. A future
dual-license or relicensing plan may need a contributor agreement; a provenance
attestation is not a copyright assignment.

The code license does not grant a fork the right to impersonate the official
JellyScope product. A future trademark policy may cover the name, logo, app
icon, store artwork, bundle identifiers, domains, and endorsement claims, but
it must not restrict MPL rights. JellyScope must also follow Jellyfin's current
third-party branding rules and avoid implying official Jellyfin status.

Trademark and store enforcement can address confusing impersonation; they
cannot prohibit a properly rebranded commercial fork that complies with MPL and
all dependency licenses. That limitation is accepted.

## Binary Distribution Technical Gate

Before publishing a platform artifact:

1. Keep the exact source revision, dependency inputs, native manifests, license
   texts, notices, and upstream source routes in its release metadata.
2. Run the platform-scoped readiness gate. Android and iOS may pass while tvOS
   or unsupported desktop targets remain blocked.
3. Verify the final APK, app archive, or desktop package rather than relying on
   dependency names alone.
4. Repeat the review when a pinned artifact, version, license, source route,
   linkage mode, runtime family, platform, or architecture changes.

Contributor and trademark policies are separate governance work. Signing,
notarization, store terms, privacy declarations, and device smoke tests remain
release operations; they are not third-party license-inventory entries.
The owner-approved Apple application credential-persistence policy is owned by
the [data and playback guide](../guides/data-playback.md#persistence). Its
accepted plaintext-at-rest tradeoff is not a project licensing or binary-
inventory blocker and must not be reopened by these gates; a newly applicable
external store requirement remains a separate release review.

## Rejected Alternatives

- **Remain GPL-3.0-only for JellyScope-owned source:** preserves stronger
  whole-program copyleft and aligns naturally with the current Android mpv
  graph, but leaves the Apple store-distribution conflict to a custom legal
  path and does not prevent paid forks.
- **MIT or Apache-2.0:** lowers integration friction but permits proprietary
  modifications without the desired source reciprocity.
- **LGPL as the application license:** protects covered library changes rather
  than providing the selected file-level application reciprocity.
- **GPL or AGPL with a custom store exception:** creates a nonstandard legal
  surface that still permits paid forks and must track changing store terms.
- **MPL plus a no-sale clause:** is no longer standard MPL or OSI open source,
  adds compatibility and scanning risk, and can conflict with dependency terms.
- **Noncommercial or proprietary source-available terms:** may reserve future
  commercial distribution but are not open source and cannot retroactively
  remove rights already conveyed with GPL copies.

## Why

- **MPL-2.0 is the approved balance between distribution flexibility and
  reciprocal source.** Its standardized file-level source obligations and
  Secondary Licenses mechanism fit the selected direction better than a custom
  store exception. Permissive terms were rejected because they abandon the
  reciprocity goal; noncommercial terms were rejected because they abandon
  open-source status.
- **Brand protection is separate from source licensing.** Trademark, bundle
  identity, and store copycat rules address impersonation without taking away
  MPL rights.
- **Source licensing and binary readiness are separate decisions.** MPL-2.0
  governs JellyScope-owned files. Each release gate records the additional
  platform graph and its licenses.
- **Pinned native graphs use accepted upstream FOSS provenance.** The project
  records official artifacts, license texts, source routes, and final package
  contents. Mirroring third-party projects, duplicating their build systems, or
  maintaining a second checksum database was rejected as unnecessary upkeep.
  A changed graph still requires a new recorded decision.
- **Desktop runtime approval is exact and source artifacts stay beside the
  binary.** A generated dependency report was rejected because it records what
  resolved without recording which families, versions, licenses, and source
  routes were reviewed. The checked-in family inventory gates the resolved
  classpath, while the clean-commit JellyScope archive and pinned VLC source are
  retained beside the DMG so source availability does not depend on rebuilding a
  historical release workstation.
