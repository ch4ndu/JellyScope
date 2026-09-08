# VLCKit Attribution And Corresponding Source

JellyScope's iOS and tvOS applications link the unmodified official VideoLAN
VLCKit 4.0.0a23 dynamic XCFramework downloaded by
`scripts/fetch-vlckit.sh`.

- Component: VLCKit 4.0.0a23, including its embedded libVLC and contributed
  codec/runtime graph.
- Upstream-declared license: GNU Lesser General Public License 2.1.
- Upstream copyright: VideoLAN and the individual VLCKit, VLC, and dependency
  authors.
- License text: the upstream archive includes `COPYING.txt`; the complete
  LGPL-2.1 text is also packaged from `scripts/vlc-bundle/LGPL-2.1.txt`.
- Artifact and source evidence: `manifest-4.0.0a23.txt`.

Corresponding source and build material are available from the exact official
VLCKit revision recorded in the manifest. It identifies the libVLC base
revision, wrapper build tools, and 27 applied patches.

To replace the downloaded binary, build that revision with VideoLAN's upstream
tools and place a compatible `VLCKit.xcframework` in `ios-app/Frameworks/`.
The local Swift package and Kotlin cinterop use that path. JellyScope does not
duplicate VideoLAN's build scripts.

The XCFramework is dynamically linked by both Apple applications. The project owner
accepts VideoLAN's LGPL declaration, exact source route, and replace-and-rebuild
path for this pinned input. A new version, source revision, patch set, or linkage
mode requires a fresh review.
