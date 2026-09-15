# JellyScope Android mpv wrapper provenance

This module is a project-owned build of the small Kotlin/JNI wrapper from
`libmpv-android` `v1.0.0` at commit
`fcf6745703dc1265bca88f12fee8fc355ddf251e`. The pinned JellyScope native AAR is a
build input: `extractPinnedMpvNative` copies its `jni/` payload into
the module build directory, excludes the upstream `libplayer.so` and `x86`,
and CMake links the remaining native libraries without rebuilding them.

The committed wrapper contains exactly three deliberate changes to the pinned
wrapper:

1. The Kotlin API adds typed `LogRequestLevel` values, defaults new instances
   to mpv's `no` log request, and lets JellyScope request `error` before
   initialization. JNI returns mpv's bounded request result.
2. The bridge removes Android logcat writes for `MPV_EVENT_LOG_MESSAGE` text
   and routine event names. Log messages are delivered only to registered
   observers, where `AndroidMpvEngine` reduces them to the allowlisted
   diagnostic category before they enter app logs.
3. The Kotlin `destroy()` implementation carries upstream main's `bf5e0d8`
   idempotence correction: a missing native instance is already destroyed and
   a second call is a no-op.

The native input is `io.github.ch4ndu:libmpv-native:0.41.0-jellyscope.1`,
[published separately](https://github.com/ch4ndu/jellyscope-mpv-android/releases/tag/v0.41.0-jellyscope.1). It replaces only ARM32
`libmpv.so` with the two-image ImageReader patch and crop diagnostics. Other
provider libraries/ABIs remain unchanged. This module rebuilds only the JNI
bridge; it does not build mpv or its native dependencies. Native patch sources,
notices and the clean-native-rebuild limitation are recorded in
[`SOURCE_MANIFEST.md`](../scripts/android-mpv-bundle/SOURCE_MANIFEST.md).

The small headers under `src/main/cpp/include/` are the ABI declarations used
by the pinned bridge for mpv 0.41.0 and FFmpeg 8.1. They do not add native
code; the corresponding implementations are the extracted AAR libraries.

## Refresh procedure

Do not float this module to upstream main. To refresh it deliberately:

1. Pin the native bundle version in the version catalog and review its release
   source/notice assets and ABI scope. Keep the provider and bundle commits
   distinct in the manifest. If the wrapper changes, pin its tag/commit and
   compare its source with the current bridge.
2. Rebase the three wrapper patches onto the exact upstream Kotlin/JNI source.
   Confirm that default log requests remain disabled, only the requested Error
   level is selected by JellyScope, raw message/event logcat writes remain
   absent, and `destroy()` remains idempotent.
3. Rebuild `arm64-v8a`, `armeabi-v7a`, and `x86_64` with Android API 36, NDK
   `29.0.14206865`, CMake `4.1.2`, and the repository's pinned ABI filters.
   Keep `x86` excluded and keep the extracted NDK-29 `libc++_shared.so` as the
   only packaged STL runtime.
4. Refresh `scripts/android-mpv-bundle/manifest-1.0.0.txt` with the reviewed
   source, patch, toolchain, ABI, library, license, and corresponding-source
   records. Re-run the APK verifier.
5. Update this file, `SOURCE_MANIFEST.md`, `ATTRIBUTION.md`, and the
   [native dependency guide](../docs/operations/android-native-dependencies.md)
   in the same change. Do not commit
   generated extraction directories, AARs, APKs, or native build output.
