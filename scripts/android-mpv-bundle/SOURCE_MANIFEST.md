# Android Native Corresponding-Source Manifest

The Android APKs contain the project-owned `android-libmpv` wrapper and the
native payload from `io.github.ch4ndu:libmpv-native:0.41.0-jellyscope.2`.
The bundle replaces six entries, `libmpv.so` and `libavformat.so` for
`armeabi-v7a`, `arm64-v8a` and `x86_64`; all other native libraries, including
the other FFmpeg libraries, remain from `dev.jdtech.mpv:libmpv:1.0.0`. The
original AAR `libplayer.so` and x86 payload are excluded; the bridge is rebuilt
from committed JellyScope JNI sources. The pinned source, patch, toolchain, ABI,
library and license records are in `manifest-1.0.0.txt` (the filename is
retained for existing packaged consumers).

**Pending binding — blocks release.** `0.41.0-jellyscope.2` is a locally
prepared candidate. Its native source commit does not exist yet
(`source-commit=pending-native-source-commit`), and its tag and release assets
are not published. The routes below are proposed and are not claimed to be
accessible:

- source tree: https://github.com/ch4ndu/jellyscope-mpv-android/tree/v0.41.0-jellyscope.2
- [source archive](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.2/libmpv-native-0.41.0-jellyscope.2-sources.tar.gz)
- [notices archive](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.2/libmpv-native-0.41.0-jellyscope.2-notices.zip)

These records must be bound to the real native commit and checked against
public assets before any JellyScope build using this pin is published. The
source archive is to carry the inherited upstream and modified mpv, pinned
native component, submodule and provider sources, each ABI's patched mpv source
and build records, and a repository snapshot matching that commit. The notices
archive is to carry the inherited component notices. NDK prerequisites and LLVM
source/patch routes are recorded in the source archive; the NDK distribution is
obtained separately.

All three ABIs apply a common AudioTrack patch that exempts encoded formats from
the PCM sample-rate cap, preserving the encoded-audio carrier rate. ARM32
additionally carries the upstream three-image ImageReader backport, reduces
ImageReader capacity to two and adds optional bounded crop diagnostics. The
pinned FFmpeg source is unchanged; its build recipe adds only
`--enable-muxer=spdif` after `--disable-muxers`, and only the resulting
`libavformat.so` is packaged. The inactive FFmpeg VP9 adaptive-max experiment
is not applied.

A fresh native build of all three ABIs in new build and output directories
produced these six libraries, and static ELF checks against the retained
provider graph and API 26 passed. That is build evidence for a local candidate,
not physical qualification or public availability: Shield and TiVo
qualification, source/notice asset verification and publication remain pending.

The published `0.41.0-jellyscope.1` release, which replaced only ARM32
`libmpv.so`, is immutable and historical. Its Cube acceptance covers only those
ARM32 bytes and does not qualify the `.2` libraries.

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
   for its rebuilt `libavformat.so`. Its dependencies, patches and build order
   are described by the [same revision's build instructions](https://github.com/jarnedemeulemeester/libmpv-android/blob/fcf6745703dc1265bca88f12fee8fc355ddf251e/buildscripts/README.md).
3. Jellyfin's Media3 FFmpeg decoder source at tag `v1.9.0+1`, commit
   `af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396`, including its upstream build
   instructions: https://github.com/jellyfin/jellyfin-androidx-media/tree/af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396.
4. VideoLAN's `libvlc-all:3.7.5` source route and its embedded VLC revision:
   https://code.videolan.org/videolan/libvlcjni/-/tree/libvlcjni-3.x and
   https://code.videolan.org/videolan/vlc/-/tree/ac1101d2c5.

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
