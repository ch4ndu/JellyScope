# Android Native Corresponding-Source Manifest

The Android APKs contain the project-owned `android-libmpv` wrapper and the
unchanged native payload extracted from the pinned libmpv-android `v1.0.0`
source/AAR input (`fcf6745703dc1265bca88f12fee8fc355ddf251e`). The original
AAR `libplayer.so` is excluded; the bridge is rebuilt from the committed JNI
sources. The pinned source, patch, toolchain, ABI, library, license, and
corresponding-source records are in `manifest-1.0.0.txt`.

For every published JellyScope Android build, publish these source routes with
the release metadata:

1. The project-owned wrapper sources under
   [`android-libmpv/`](../../android-libmpv/), including `UPSTREAM.md`, the
   committed Kotlin/JNI bridge, CMake file, ABI declaration shims, and MIT
   `LICENSE`, plus the exact libmpv-android source at
   https://github.com/jarnedemeulemeester/libmpv-android/tree/fcf6745703dc1265bca88f12fee8fc355ddf251e.
2. The pinned Maven AAR as a **build input only**. The
   extraction task copies all `jni/` libraries except `libplayer.so` and x86;
   CMake imports the extracted `libmpv.so`, `libavcodec.so`, and NDK-29
   `libc++_shared.so` while compiling only the project bridge. The native source
   tags in `ATTRIBUTION.md`, FFmpeg configuration that enables GPL and version-3
   code, Android API/NDK/CMake versions, and required package inventory are in
   the manifest.
3. Jellyfin's Media3 FFmpeg decoder source at tag `v1.9.0+1`, commit
   `af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396`, including its upstream build
   instructions: https://github.com/jellyfin/jellyfin-androidx-media/tree/af9ee4e26b2045e3ea6f2ebf4a18ac8ebfeae396.
4. VideoLAN's `libvlc-all:3.7.5` source route and its embedded VLC revision:
   https://code.videolan.org/videolan/libvlcjni/-/tree/libvlcjni-3.x and
   https://code.videolan.org/videolan/vlc/-/tree/ac1101d2c5.

JellyScope does not mirror these upstream projects or maintain their build
scripts. If a recorded source route disappears or a pinned artifact changes,
refresh the source record before publishing. A Maven POM alone is not a native
corresponding-source notice.
