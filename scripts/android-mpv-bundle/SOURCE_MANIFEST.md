# Android Native Corresponding-Source Manifest

The Android APKs contain the project-owned `android-libmpv` wrapper and the
native payload from `io.github.ch4ndu:libmpv-native:0.41.0-jellyscope.1`.
The bundle replaces only ARM32 `libmpv.so`; other native libraries remain from
`dev.jdtech.mpv:libmpv:1.0.0`. The original AAR `libplayer.so` and x86 payload
are excluded; the bridge is rebuilt from committed JellyScope JNI sources.
The pinned source, patch, toolchain, ABI, library and license records are in
`manifest-1.0.0.txt` (the filename is retained for existing packaged consumers).

The native bundle's exact source repository is
https://github.com/ch4ndu/jellyscope-mpv-android/tree/8a2e7e75c53c7fb818d86299d4653cbde650a434.
Its [matching source archive](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.1/libmpv-native-0.41.0-jellyscope.1-sources.tar.gz)
contains modified and upstream mpv, 13 pinned native components, eight submodules,
provider source, patches and build records. Its
[notices archive](https://github.com/ch4ndu/jellyscope-mpv-android/releases/download/v0.41.0-jellyscope.1/libmpv-native-0.41.0-jellyscope.1-notices.zip)
contains component notices. NDK prerequisites and LLVM source/patch routes are
recorded in the source archive; the NDK distribution is obtained separately.
The ARM32 changes reduce ImageReader capacity to two and add optional bounded
crop diagnostics. The inactive FFmpeg VP9 adaptive-max experiment is not applied.
The consolidated native build procedure has not yet had a clean rebuild; these
are retained Cube-tested bytes, not a claim of independent reproducibility.

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
   enables GPL and version-3 code. Its dependencies, patches and build order
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
