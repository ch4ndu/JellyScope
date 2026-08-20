# Licensing And Store Distribution

This document owns JellyScope's active project-source license, the boundary
between project and third-party licenses, platform-distribution obligations,
source availability, trademark direction, and the remaining binary-release
compliance gates.
Exact Android native inputs and corresponding-source packaging remain owned by
[`android-native-dependencies.md`](android-native-dependencies.md).

This is an engineering and release policy, not legal advice. The source-only
MPL-2.0 cutover was explicitly authorized by the project owner without legal
review. Android and Apple binary-compliance conclusions still require qualified
review against the exact artifacts being distributed. The desktop technical
inventory and source-availability gates below are recorded engineering evidence,
not legal advice or a general binary-clearance claim. The exact IINA-built macOS
mpv runtime below is the owner-approved exception: its published GPL/source
declarations are accepted as ordinary OSS provenance without an additional
legal-review release blocker.

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

The source-license change is not a binary-distribution clearance. Android,
Apple, and desktop applications combine JellyScope source with separately
licensed dependencies and native artifacts. No APK, app bundle, Apple app, or
desktop package may be described as legally reviewed or cleared merely because
JellyScope-owned source now uses MPL-2.0.

Recipients of earlier GPL-3.0-only copies retain the rights already conveyed to
them. The current source-license cutover does not revoke those rights.

The repository prepares release-license metadata independently of
binary legal clearance. [`distribution/OPEN_SOURCE_NOTICES.md`](../../distribution/OPEN_SOURCE_NOTICES.md)
and [`distribution/THIRD_PARTY_COMPONENTS.tsv`](../../distribution/THIRD_PARTY_COMPONENTS.tsv)
are packaged with release artifacts together with the exact source revision,
project tree, dependency inputs, native notices, source manifests, and the
reviewed macOS arm64 JVM runtime-family inventory and notices in
[`distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv`](../../distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv)
and [`distribution/DESKTOP_JVM_RUNTIME_NOTICES.md`](../../distribution/DESKTOP_JVM_RUNTIME_NOTICES.md).
Existing settings surfaces identify the source revision and route users to the
matching repository tree. Entries marked `review-required`, and any desktop JVM
group, module, or version outside the reviewed inventory, remain explicit
blockers for relevant binary-distribution claims. Passing the metadata checks
is technical evidence, not legal clearance.

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
pinned AAR input. Its current audited graph includes GPL-2.0-or-later
mpv and FFmpeg with GPL and version-3 code enabled. The apps also package the
separate LibVLC runtime. Exact component versions, ABIs, required libraries, license texts,
patches, and source routes are owned by
[`android-native-dependencies.md`](android-native-dependencies.md) and its
linked manifests.

MPL-2.0 covers eligible JellyScope-owned files, but it does not replace the
GPL/LGPL obligations of the packaged graph. Android binary compliance remains
unreviewed and is not legally cleared. Before distribution, qualified review
must determine the applicable combined-work treatment, MPL Secondary Licenses
handling, and the notices and corresponding source required by the final APKs.
JellyScope files must not be marked `Incompatible With Secondary Licenses`
unless counsel and the final dependency design establish that doing so is
valid.

### iOS And tvOS

The iOS build currently uses the pinned VLCKit 4 pre-release
XCFramework fetched by [`scripts/fetch-vlckit.sh`](../../scripts/fetch-vlckit.sh)
and linked through the local binary Swift package in
[`ios-app/VLCKitLocal/Package.swift`](../../ios-app/VLCKitLocal/Package.swift).
The current device slice is a dynamically linked framework. The exact archive,
upstream LGPL-2.1 declaration and license text, official build revision,
libVLC base revision, patch set, and source routes are recorded in
[`scripts/vlckit-bundle/manifest-4.0.0a23.txt`](../../scripts/vlckit-bundle/manifest-4.0.0a23.txt).
This closes the technical-provenance inventory for the pinned input; it does not
resolve the legal review of the framework's complete contributed-code graph or
Apple distribution terms. Do not infer those obligations from historical
MobileVLCKit packaging or from the VLCKit name alone.

Apple binary compliance remains unreviewed and is not legally cleared for iOS
or tvOS.

The current tvOS target uses the Apple player path and does not consume the iOS
VLCKit cinterop. It still needs a final application and dependency audit before
distribution. If VLCKit or another native player is later added to tvOS, that
new graph must be reviewed independently.

Before an Apple App Store submission, the release must also provide an in-app
source and open-source-notices route tied to the exact released revision and
native artifacts; preserve all required license rights in the EULA; verify the
effect of signing, DRM, and store terms; and retain any required source, offer,
build, replacement, or relinking material for the applicable period. Dynamic
linkage is a fact to audit, not proof that every LGPL or other obligation is met.

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

Adopt an explicit contributor policy for work accepted after this cutover. If
the project wants authority to dual-license or relicense later, use a counsel-
reviewed contributor agreement that actually grants that authority; a provenance
attestation must not be described as a copyright assignment.

The code license does not grant a fork the right to impersonate the official
JellyScope product. Before claiming official distribution clearance, add a
separately reviewed trademark policy covering the name, logo, app icon,
official screenshots and store artwork, bundle identifiers, domains, and
endorsement claims. It may require public forks to use distinct branding while
allowing accurate compatibility statements, but it must not condition MPL
rights on displaying official marks
or claim registration that has not been confirmed. JellyScope must also follow
Jellyfin's then-current third-party branding rules and avoid implying official
Jellyfin status.

Trademark and store enforcement can address confusing impersonation; they
cannot prohibit a properly rebranded commercial fork that complies with MPL and
all dependency licenses. That limitation is accepted.

## Binary Distribution Legal And Compliance Gate

The source-only MPL-2.0 cutover is active. It was deliberately completed without
legal review and does not satisfy the remaining binary gate. Before claiming an
Android, Apple, or desktop artifact is compliant or store-ready:

1. Inventory every dependency, native binary, plugin, codec, linkage mode, and
   transitive license in each final platform and architecture artifact.
2. Obtain qualified legal review of the Android GPL graph and MPL Secondary
   Licenses treatment, Apple EULA/store terms, exact VLCKit obligations,
   replacement or relinking requirements, contributor policy, and trademark
   policy. The pinned IINA macOS mpv runtime uses the owner-approved
   GPL-compatible route above and is not itself a remaining qualified-review
   blocker; the desktop JVM inventory and retained source archives are technical
   evidence rather than a substitute for artifact-specific advice.
3. Add reviewed contributor and trademark policies without embedding trademark
   restrictions in the MPL-covered source license.
4. Verify that each released binary's in-app source/notices route identifies
   the exact source revision and native inputs used by that binary.
5. Make release verification fail when required license texts, notices,
   corresponding source, replacement or relinking material, and exact artifact
   manifests are absent. A clean candidate and reviewed technical inventory are
   evidence inputs; neither is legal clearance.
6. Inspect the final Android, iOS, tvOS, and desktop artifacts. Do not infer
   compliance from source declarations, build files, or metadata checks.

The active root license and SPDX identifiers establish only the license for
eligible JellyScope-owned source. They must not be cited as proof that any
distributed binary has passed this gate.

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
- **Source licensing and binary clearance are separate decisions.** The owner
  authorized the source-only MPL-2.0 cutover based on the confirmed ownership
  and provenance record. Exact native-artifact, platform-distribution, legal,
  contributor-policy, and trademark work remains open, so no released product
  is represented as legally cleared by the source change.
- **The pinned IINA macOS mpv graph uses accepted upstream GPL provenance.**
  Rebuilding an LGPL-targeted graph or requiring separate legal review for this
  exact runtime was rejected after the owner accepted IINA's published binary,
  source, and license route. That acceptance is narrow: changed bytes or a new
  graph require a new inventory and decision.
- **Desktop runtime approval is exact and source artifacts stay beside the
  binary.** A generated dependency report was rejected because it records what
  resolved without recording which families, versions, licenses, and source
  routes were reviewed. The checked-in family inventory gates the resolved
  classpath, while the clean-commit JellyScope archive and pinned VLC source are
  retained beside the DMG so source availability does not depend on rebuilding a
  historical release workstation.
