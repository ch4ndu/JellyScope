# JellyScope Source And Open-Source Notices

JellyScope-owned source is licensed under MPL-2.0. The corresponding source
repository is:

https://github.com/ch4ndu/JellyScope

Each release metadata set records the exact source revision in
`SOURCE_REVISION.txt` and the complete tracked project tree in
`PROJECT_FILES.git-tree`. The repository's `LICENSE` file contains the project
license text. Open `SOURCE_URL.txt` from the metadata set to browse the exact
JellyScope revision; repository paths below are relative to that source tree.

Each binary carries its third-party licenses and obligations. Release metadata
includes `THIRD_PARTY_COMPONENTS.tsv`, the Android/iOS managed
runtime notices, the reviewed desktop JVM inventory, the Gradle version catalog,
and the platform-native manifests, license texts, and source routes.

Android packages include the recorded mpv/FFmpeg, Media3 FFmpeg, and LibVLC
notice/source set from `scripts/android-mpv-bundle/`. The combined Android
application is distributed under GPL-3.0 terms. JellyScope-owned files remain
available under MPL-2.0 and are additionally distributed under GPL-3.0 for this
Larger Work through MPL-2.0 Section 3.3.

iOS and tvOS packages include the exact pinned VLCKit 4.0.0a25 artifact, LGPL-2.1
notice, build revision, patch set, and source routes from
`scripts/vlckit-bundle/`. A compatible framework built with VideoLAN's upstream
tools can replace the downloaded framework at the documented local path.

Apple Silicon macOS 15+ packages include VLC 3.0.24 and its selected contrib
notice/source set from `scripts/vlc-bundle/`, plus project-built mpv 0.41.0,
FFmpeg 9.0.2, dav1d 1.5.4 and the complete pinned source/notice inventory from
`scripts/desktop-mpv-bundle/`. Original transitive, embedded/header and
build-input licenses accompany those inventories. The combined macOS package
uses GPL-compatible terms through MPL-2.0 Section 3.3. Upstream published
provenance remains the basis for the official VLC binary's source record.

When the optional Metal graph is included, its source-built mpv/FFmpeg graph
also uses pinned MoltenVK and a static glslang compiler. Its additional source
and original license records are in `scripts/desktop-mpv-metal/`, including
MoltenVK's declared embedded/header inputs and glslang's generated-parser
notice with the Bison exception. The MPVKit caller-owned-layer patch and local
resize patch retain mpv's upstream license terms. The explicit Metal source
sidecar retains the merged source inputs, these patches, the build recipes and
actual build records. Retained prebuilt experimental candidates do not supply
this source-built graph's distribution evidence.

The desktop JVM inventory records accepted runtime families, resolved versions,
published licenses and source routes; release verification rejects components
outside that record. macOS release output retains the exact clean JellyScope
commit archive, every mpv input and actual native build record, VLC's 3.0.24
source tar, and the matching selected VLC contrib archives beside the DMG.
Dirty local packages record their dirty state and do not establish clean
release-source correspondence.

Android and iOS managed runtime families, their published licenses, and their
upstream source routes are recorded in `MOBILE_RUNTIME_NOTICES.md`. Exact direct
versions are recorded in the packaged Gradle version catalog.

This notice supplements rather than replaces any third-party license text.
JellyScope is provided without warranty. Third-party licenses and notices must
be preserved unchanged. In the distributed `license-metadata` directory, the
Android GPL-3.0 text is at
`platform-notices/android/licenses/GPL-3.0-only.txt`. In a source checkout, it
is at `scripts/android-mpv-bundle/licenses/GPL-3.0-only.txt`.
