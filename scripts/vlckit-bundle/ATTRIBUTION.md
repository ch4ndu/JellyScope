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

Corresponding source and build material are available from the exact official
VLCKit revision recorded in the manifest. It identifies the libVLC base
revision, wrapper build tools, and 27 applied patches.

To replace the downloaded binary, follow `docs/RELEASE.md`, section "iOS release
artifacts", in the JellyScope source revision identified by the accompanying
release metadata's `SOURCE_URL.txt`. That procedure prepares the local fetch
stamp before replacing the framework with a compatible upstream build, retaining
the required slices so the next cinterop fetch does not overwrite it. The local
Swift package and Kotlin cinterop use the prepared `ios-app/Frameworks/` path.
JellyScope does not duplicate VideoLAN's build scripts.

The recorded policy accepts VideoLAN's LGPL declaration, exact source route,
and replace-and-rebuild path for this pinned input. A new version, source revision, patch set, or linkage
mode requires a fresh review.
