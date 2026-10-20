# Android Native Corresponding-Source Manifest

The Android APKs use the project-owned `android-libmpv` wrapper
and `io.github.ch4ndu:libmpv-native:0.41.0-jellyscope.3`. The bundle replaces
libmpv and all seven FFmpeg 8.1.3 shared libraries for armeabi-v7a, arm64-v8a
and x86_64: 24 entries. The other 26 provider entries stay byte-identical to
`dev.jdtech.mpv:libmpv:1.0.0`. The provider bridge and all x86 payload are
excluded by the app, which rebuilds its committed JNI bridge.

Native commit `94dd29ce82399112cb161ffa23f9e55eb5913adc` supplies the exact
source tree. `manifest-1.0.0.txt` records the source file-inventory digest.
The public tag resolves to this commit. Anonymous AAR/source/notice downloads
matched the prepared SHA-256 digests on 2026-10-20, and ordinary Gradle
resolution and extraction passed through the public release repository:

- source tree: https://github.com/ch4ndu/jellyscope-mpv-android/tree/v0.41.0-jellyscope.3
- [source archive](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.3/libmpv-native-0.41.0-jellyscope.3-sources.tar.gz)
- [notices archive](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.3/libmpv-native-0.41.0-jellyscope.3-notices.zip)

Fresh three-ABI build, static ELF/package checks and the isolated app full
host/minified-package gate pass. The source offer
retains exact component/submodule/provider archives, each ABI's builder-produced
modified mpv archive and configuration records, and the committed repository
snapshot. Source and notices include the replacement FFmpeg 8.1.3 materials.
NDK prerequisites and LLVM source/build routes are supplied separately from
the NDK distribution itself. The historical `.1`/`.2` releases remain immutable;
their device acceptance does not qualify these rebuilt `.3` bytes.

Every ABI preserves the encoded AudioTrack carrier-rate patch and registered
SPDIF muxer. ARM32 retains the three-image backport, two-image limit and bounded
crop diagnostics. No FFmpeg source patch or archived VP9 experiment is applied.
Host/package checks cannot establish loader/playback, output or performance
acceptance. The physical device matrix remains unrun for these rebuilt bytes.

For every published JellyScope Android build, publish these source routes with
the release metadata. In an Android APK, `assets/license-metadata/SOURCE_URL.txt`
identifies the exact JellyScope source tree; in a standalone metadata bundle,
use its root `SOURCE_URL.txt`. Resolve the project paths below within that
source tree, not relative to this packaged notice:

1. The project-owned wrapper sources under
   `android-libmpv/`, including `UPSTREAM.md`, the
   committed Kotlin/JNI bridge, CMake file, ABI declaration shims, and MIT
   `LICENSE`, plus the exact libmpv-android source at
   https://github.com/jarnedemeulemeester/libmpv-android/tree/fcf6745703dc1265bca88f12fee8fc355ddf251e.
2. The pinned GitHub Release AAR as a **build input only**. The
   extraction task copies all `jni/` libraries except `libplayer.so` and x86;
   CMake imports the extracted `libmpv.so`, `libavcodec.so`, and NDK-29
   `libc++_shared.so` while compiling only the project bridge. The native source
   tags are in [ATTRIBUTION.md](ATTRIBUTION.md); Android API/NDK/CMake versions
   and package inventory are in `manifest-1.0.0.txt`. The exact upstream
   [FFmpeg build configuration](https://github.com/jarnedemeulemeester/libmpv-android/blob/fcf6745703dc1265bca88f12fee8fc355ddf251e/buildscripts/scripts/ffmpeg.sh)
   enables GPL and version-3 code; the native bundle's controlling copy,
   `vendor/provider/buildscripts/scripts/ffmpeg.sh`, adds only the SPDIF muxer
   for its complete rebuilt FFmpeg library set. Its dependencies, patches and build order
   are described by the [same revision's build instructions](https://github.com/jarnedemeulemeester/libmpv-android/blob/fcf6745703dc1265bca88f12fee8fc355ddf251e/buildscripts/README.md).
3. Jellyfin's Media3 FFmpeg decoder source at tag `v1.9.0+1`, commit
   `af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396`, including its upstream build
   instructions: https://github.com/jellyfin/jellyfin-androidx-media/tree/af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396.
4. VideoLAN's `libvlc-all:3.7.7`: JNI source and its `libvlc/patches` at
   https://code.videolan.org/videolan/libvlcjni/-/tree/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6,
   applied to VLC base
   https://code.videolan.org/videolan/vlc/-/tree/84177b2273abc5c4300234e6e18b55a4d2dd5a03.
   Preserve that revision's build instructions and the VLC `contrib` recipes.
   The published AAR embeds VLC `3.0.24-35` with distinct patched revision
   suffixes for each ABI; its artifact hash, exact shipped-ABI version strings,
   and separate Android resource revision are recorded in `manifest-1.0.0.txt`.

The native bundle repository maintains its mpv patches and build entry point;
its release archives preserve the dependency source inputs. Other upstream
projects retain their own build instructions. If a recorded source route
disappears or a pinned artifact changes,
refresh the source record before publishing. A Maven POM alone is not a native
corresponding-source notice.

The project wrapper additionally carries `stopped-surface-detach`: native
status-preserving Surface retirement, output-destruction confirmation and an
INT64 event-drain marker. Its corresponding source is the project's
`android-libmpv/src/main` tree at the candidate revision. It does not change
the native bundle libraries; upstream mpv/FFmpeg source revisions are unchanged.
