# Android native corresponding-source manifest

The Android APKs contain the project-owned `android-libmpv` wrapper and the
unchanged native payload extracted from the pinned libmpv-android `v1.0.0`
source/AAR input (`fcf6745703dc1265bca88f12fee8fc355ddf251e`). The original
AAR `libplayer.so` is excluded; the bridge is rebuilt from the committed JNI
sources. The pinned source, patch, toolchain, ABI, library, license, and
corresponding-source records are in `manifest-1.0.0.txt`.

For every published JellyScope Android build, retain the following source
inputs and build metadata with the release candidate:

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
3. The source archive or an offer for the corresponding sources, extraction and
   CMake build scripts, patches, native configuration, and build instructions
   sufficient to reproduce the distributed native libraries. Keep that archive
   available for the GPL/LGPL notice period and
   publish the same route with the release source bundle.

The repository's pinned source references and this manifest are the canonical
route for an audit. The AAR POM's MIT wrapper declaration alone is not a native
license or corresponding-source notice.
