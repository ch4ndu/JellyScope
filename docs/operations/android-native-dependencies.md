# Android Native Dependency and License Runbook

This runbook owns the Android mpv, Media3 FFmpeg, and LibVLC playback payloads.
It is a release-candidate checklist, not proof that a candidate has passed the
physical playback gate. Both Android applications install on API 25
(Android 7.1), including Fire OS 6; only the bundled mpv backend requires API
26 (Android 8.0) and is reported unavailable below that floor. ExoPlayer
remains the default on API-25 devices.

## Pinned inputs

The project-owned `:android-libmpv` module uses
`io.github.ch4ndu:libmpv-native:0.41.0-jellyscope.1` as its only external mpv input.
Gradle resolves the AAR from the public
[JellyScope native release](https://github.com/ch4ndu/jellyscope-mpv-android/releases/tag/v0.41.0-jellyscope.1) through an
artifact-only Ivy repository restricted to that module. No GitHub credentials,
local AAR override or native source build is needed for a normal clone/build.

The existing bundle includes both GPU and Direct MediaCodec video-output drivers.
The [TV output setting](../guides/data-playback.md#android-mpv-backend) selects
between them without a native rebuild or an additional release.

The bundle replaces only `jni/armeabi-v7a/libmpv.so` from the original
`dev.jdtech.mpv:libmpv:1.0.0` payload. It applies the two-image ImageReader limit
and bounded crop diagnostics; FFmpeg and other ABIs remain unchanged. ARM64 and
x86_64 do not receive the patch. The retained ARM32 bytes were manually exercised
on a Cube with a 1080p display; this does not establish 8K or general device
support. The consolidated native builder completed a clean ARM32 source build
and AAR packaging verification without native-source or build-script changes.
The rebuilt verification artifact is separate from the published, device-tested
bytes; no app dependency replacement or additional native release was made.

The AAR is extracted into the module build directory. All native libraries
except the original `libplayer.so` and x86 are retained. The committed project
bridge compiles against the extracted `libmpv.so`, `libavcodec.so`, and NDK-29 C++
runtime. Its upstream wrapper remains provider commit
`fcf6745703dc1265bca88f12fee8fc355ddf251e`; the native bundle is tagged at commit
`8a2e7e75c53c7fb818d86299d4653cbde650a434`. Both provenance layers and the published
source/notice archives are recorded in
[`scripts/android-mpv-bundle/manifest-1.0.0.txt`](../../scripts/android-mpv-bundle/manifest-1.0.0.txt).
The stable manifest filename remains unchanged for packaged consumers.

Both Android applications consume the dependency through `androidMain` and
ship only these native ABIs:

```text
arm64-v8a, armeabi-v7a, x86_64
```

The upstream x86 payload is intentionally excluded. The module's CMake
configuration uses `ANDROID_STL=none` and imports the extracted NDK-29
`libc++_shared.so`, so the module does not generate a second STL runtime. Both
apps still declare their existing `pickFirst` rule for coexistence with
LibVLC; that packaging rule is not allowed to mask a duplicate inside the
project module. The manifest records the selected C++ runtime, every required
mpv/FFmpeg and LibVLC library, and the project bridge.

## License and corresponding source

The wrapper base is MIT-licensed, but the project-owned bridge and the native
graph still require the complete license record. The distributed graph
includes mpv, FFmpeg with GPL and version-3 code enabled, dav1d, libplacebo,
libass, font/subtitle libraries, mbedTLS, Lua, and the NDK C++ runtime. The apps
also package the GPL-3.0 Jellyfin Media3 FFmpeg decoder `1.9.0+1` and LGPL-2.1
LibVLC `3.7.5`. Their records stay in the same Android manifest so the release
has one native notice set. The exact component, version, license, source URL, wrapper patch, and
corresponding-source route are listed in
[`ATTRIBUTION.md`](../../scripts/android-mpv-bundle/ATTRIBUTION.md) and
[`android-libmpv/UPSTREAM.md`](../../android-libmpv/UPSTREAM.md).
The [license-file coverage section](../../scripts/android-mpv-bundle/ATTRIBUTION.md#license-file-coverage)
describes which license texts and component notices the packaged directory
contains. Review the applicable component notices and license choices for the
candidate; the directory is not a complete component-owner inventory.

For every release candidate, publish the exact corresponding-source routes,
patches, native configuration, and upstream build instructions described by
[`SOURCE_MANIFEST.md`](../../scripts/android-mpv-bundle/SOURCE_MANIFEST.md).
Do not treat the Maven POM, a library name, or the top-level project
license as a substitute for the native bill of materials and source offer.

## Candidate verification

From the repository root, after both minified Android release APKs have been
assembled, run:

```bash
bash scripts/verify-android-native-bundle.sh \
  android-app/build/outputs/apk/release/android-app-release.apk \
  android-tv-app/build/outputs/apk/release/android-tv-app-release.apk
```

The verifier must pass for both APKs. It checks the package inventory:

- API-26-compatible packaging retains exactly the three shipped mpv ABIs and
  no x86 entry.
- Every required mpv/FFmpeg, Media3 FFmpeg, and LibVLC library is present for
  every shipped ABI.
- The packaged manifest identifies `android-libmpv`, and `libplayer.so` is
  present for each shipped ABI.
- `libc++_shared.so` is present for each shipped ABI.
- `ATTRIBUTION.md`, `SOURCE_MANIFEST.md`, the native manifest, and every
  manifest-listed license asset are present in both APKs.
- The packaged native manifest is byte-for-byte equal to the reviewed source
  manifest.
- The release-license metadata set is present, and its project license, source
  revision/URL, dependency inputs,
  native notices, and source manifests agree with the repository candidate.

Package filenames alone do not prove which bridge implementation was compiled.
Before native packaging, run
`bash scripts/check-android-mpv-wrapper-source.sh` to check the committed source
boundary. Review the extraction exclusions and CMake build route in
[`android-libmpv/build.gradle.kts`](../../android-libmpv/build.gradle.kts) to
confirm the upstream bridge is excluded and the project bridge is built.
Together these provide source/build provenance and package-inventory evidence;
the APK verifier does not inspect the binary's patch implementation.
Its success message about complete license metadata refers to required files
and record consistency, not component-by-component notice completeness or a
legal-compliance determination. Apply the separate notice review above.

The same verifier is invoked by [`scripts/verify.sh`](../../scripts/verify.sh)
after the release assemblies. When the AAR, POM, native payload, source tag,
ABI set, or C++ runtime changes, update the manifest, attribution/source
records, and license audit together. Never hide
a coexistence failure by changing `pickFirst` order; stop for an audited
rebuilt/forked dependency or an explicit ABI/backend scope decision.

## Runtime and security gate

Package inspection is necessary but does not prove native coexistence. The
physical gate must start and stop both mpv and LibVLC on every exercised shipped
ABI without a crash, ANR, unbounded native growth, or a stale callback after
release. Android mpv uses `tls-verify=yes` with a private PEM bundle generated
from Android's default trust managers; invalid certificates outside that trust
policy must fail safely. The controller attaches the modern token-only header
only for the trusted same-origin request, rejects cross-origin remote subtitle
sidecars, and keeps URLs, headers, tokens, paths, titles, and raw native errors
out of shared/exported diagnostics and native message forwarding to logcat.
When **Collect diagnostic logs** is enabled, mpv can retain a sensitive raw log
in app-private storage; it is excluded from crash upload and bounded client-log export. The
[Android mpv diagnostic policy](../guides/data-playback.md#android-mpv-backend) owns that
exception and its collection controls.

The remaining native residuals are explicit: mpv/FFmpeg does not inherit
Android network-security-config domain pins/rules from the exported CA roots,
and native redirect handling cannot intercept credential forwarding across an
HTTP redirect. This is a trusted-initial-authority residual, not permission to
disable TLS verification or use a tokenized URL.

## Why

- **A project-built bridge preserves the native logging boundary:** the upstream
  bridge forwarded credential-bearing verbose messages outside the scrubber.
  Compiling the small patched bridge preserves the extracted native graph;
  silencing app loggers would leave the native writer active. The separate
  ARM32 native patch bundle does not replace this project-owned bridge.
