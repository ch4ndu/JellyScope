# JellyScope Source And Open-Source Notices

JellyScope-owned source is licensed under MPL-2.0. The corresponding source
repository is:

https://github.com/ch4ndu/JellyScope

Each release metadata set records the exact source revision in
`SOURCE_REVISION.txt` and the complete tracked project tree in
`PROJECT_FILES.git-tree`. The repository's `LICENSE` file contains the project
license text.

This source-only license status does not legally clear any Android, Apple, or
desktop binary distribution. Each binary retains the separate obligations of
its third-party dependency and native-artifact graph.

JellyScope also uses separately licensed third-party software. The generated
release metadata includes `THIRD_PARTY_COMPONENTS.tsv`, the reviewed desktop JVM
runtime-family inventory and notices, the Gradle version catalog, and any
platform-native manifests, notices, license texts, and source routes currently
available in the repository.

The Android packages include the recorded mpv/FFmpeg and LibVLC native notice
set from `scripts/android-mpv-bundle/`. iOS packages include the exact pinned
VLCKit 4.0.0a23 artifact, LGPL-2.1 notice, build revision, patch-set, and source
routes from `scripts/vlckit-bundle/`. Apple Silicon macOS packages include the
recorded VLC 3.0.23 notice set from `scripts/vlc-bundle/` and the IINA 1.4.0
arm64 mpv dylib manifest, GPL texts, and source routes from
`scripts/desktop-mpv-bundle/`. The IINA/upstream declarations were not
independently audited; the project owner accepts them as ordinary OSS
provenance, and the combined macOS package is available under GPL-compatible
terms through MPL-2.0 Section 3.3. The desktop JVM inventory records the accepted
macOS arm64 runtime families, resolved versions, published licenses, and source
routes; release verification rejects any family or version outside that record.
macOS release output also retains the exact JellyScope commit archive and the
VLC 3.0.23 source archive beside the DMG.

This notice supplements rather than replaces any third-party license text.
Third-party licenses and notices must be preserved unchanged.
