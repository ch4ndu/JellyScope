# VLCKit Attribution And Corresponding Source

JellyScope's iOS application can link the unmodified official VideoLAN
VLCKit 4.0.0a23 dynamic XCFramework downloaded by
`scripts/fetch-vlckit.sh`. The tvOS application does not link this framework.

- Component: VLCKit 4.0.0a23, including its embedded libVLC and contributed
  codec/runtime graph.
- Upstream-declared license: GNU Lesser General Public License 2.1.
- Upstream copyright: VideoLAN and the individual VLCKit, VLC, and dependency
  authors.
- License text: the upstream archive includes `COPYING.txt`; the complete
  LGPL-2.1 text is also packaged from `scripts/vlc-bundle/LGPL-2.1.txt`.
- Artifact and source evidence: `manifest-4.0.0a23.txt`.

Corresponding source and build material for this binary are available from the
official VLCKit revision recorded in the manifest. That revision identifies the
libVLC base revision, carries the complete VLCKit wrapper source and build
scripts, and carries the 27 libVLC patches used by the build. A release that
distributes this framework must retain that source snapshot and the upstream
binary archive with the release records rather than relying only on a moving
branch or package-manager entry.

The XCFramework is dynamically linked by the iOS application. Dynamic linkage
is an artifact fact, not by itself a conclusion that App Store terms, signing,
DRM, source availability, replacement, or relinking obligations are satisfied.
Those conclusions remain part of the qualified legal review required by
`docs/operations/licensing-and-distribution.md`.
