# JellyScope Source And Open-Source Notices

JellyScope-owned source is licensed under MPL-2.0. The corresponding source
repository is:

https://github.com/ch4ndu/JellyScope

Each release metadata set records the exact source revision in
`SOURCE_REVISION.txt` and the complete tracked project tree in
`PROJECT_FILES.git-tree`. The repository's `LICENSE` file contains the project
license text.

Each binary also carries the licenses and obligations of its third-party
dependency and native-artifact graph.

JellyScope also uses separately licensed third-party software. The generated
release metadata includes `THIRD_PARTY_COMPONENTS.tsv`, the Android/iOS managed
runtime notices, the reviewed desktop JVM inventory, the Gradle version catalog,
and the platform-native manifests, license texts, and source routes.

Android packages include the recorded mpv/FFmpeg, Media3 FFmpeg, and LibVLC
notice/source set from `scripts/android-mpv-bundle/`. The combined Android
application is distributed under GPL-3.0 terms. JellyScope-owned files remain
available under MPL-2.0 and are additionally distributed under GPL-3.0 for this
Larger Work through MPL-2.0 Section 3.3.

iOS and tvOS packages include the exact pinned VLCKit 4.0.0a23 artifact, LGPL-2.1
notice, build revision, patch set, and source routes from
`scripts/vlckit-bundle/`. A compatible framework built with VideoLAN's upstream
tools can replace the downloaded framework at the documented local path.

Apple Silicon macOS packages include the
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

Android and iOS managed runtime families, their published licenses, and their
upstream source routes are recorded in `MOBILE_RUNTIME_NOTICES.md`. Exact direct
versions are recorded in the packaged Gradle version catalog.

This notice supplements rather than replaces any third-party license text.
JellyScope is provided without warranty. Third-party licenses and notices must
be preserved unchanged. The Android GPL-3.0 text is available at
[`scripts/android-mpv-bundle/licenses/GPL-3.0-only.txt`](../scripts/android-mpv-bundle/licenses/GPL-3.0-only.txt).
