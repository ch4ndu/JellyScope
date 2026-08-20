# Android Native Dependency and License Runbook

This runbook owns the Android native payload introduced by the Android mpv
backend. It is a release-candidate checklist, not proof that a candidate has
passed the physical playback gate. Both Android applications install on API 25
(Android 7.1), including Fire OS 6; only the bundled mpv backend requires API
26 (Android 8.0) and is reported unavailable below that floor. ExoPlayer
remains the default on API-25 devices.

## Pinned inputs

The Android mpv runtime is the project-owned module:

```text
:android-libmpv
```

The module's only external mpv input is the pinned
`dev.jdtech.mpv:libmpv:1.0.0` AAR. It is extracted into the module build
directory; all native libraries except the original `libplayer.so` and x86
are retained, while the committed project bridge is compiled
against the extracted `libmpv.so`, `libavcodec.so`, and NDK-29 C++ runtime.
The reviewed native source is `libmpv-android` tag `v1.0.0`, commit
`fcf6745703dc1265bca88f12fee8fc355ddf251e`. The native component versions are recorded in
[`scripts/android-mpv-bundle/manifest-1.0.0.txt`](../../scripts/android-mpv-bundle/manifest-1.0.0.txt).
The manifest currently records mpv 0.41.0, FFmpeg 8.1, NDK 29, dav1d 1.5.3,
libplacebo 7.360.1, libass 0.17.4, and the other native inputs in the audited
bill of materials.

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
libass, font/subtitle libraries, mbedTLS, Lua, and the NDK C++ runtime. The
exact component, version, license, source URL, wrapper patch, and
corresponding-source route are listed in
[`ATTRIBUTION.md`](../../scripts/android-mpv-bundle/ATTRIBUTION.md) and
[`android-libmpv/UPSTREAM.md`](../../android-libmpv/UPSTREAM.md).
The audited license texts are under
[`scripts/android-mpv-bundle/licenses/`](../../scripts/android-mpv-bundle/licenses/).

For every release candidate, retain or publish the exact corresponding source
inputs, build scripts, patches, native configuration, and build instructions
described by [`SOURCE_MANIFEST.md`](../../scripts/android-mpv-bundle/SOURCE_MANIFEST.md).
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

The verifier must pass for both APKs and must prove all of the following:

- API-26-compatible packaging retains exactly the three shipped mpv ABIs and
  no x86 entry.
- Every required mpv/FFmpeg and LibVLC library is present for every shipped
  ABI.
- The APK identifies `android-libmpv` and contains the patched `libplayer.so`;
  the original AAR bridge remains excluded.
- `libc++_shared.so` is present for each shipped ABI.
- `ATTRIBUTION.md`, `SOURCE_MANIFEST.md`, the native manifest, and every
  manifest-listed license asset are present in both APKs.
- The packaged native manifest is byte-for-byte equal to the reviewed source
  manifest.
- The release-license metadata set is present, and its project license, source
  revision/URL, dependency inputs,
  native notices, and source manifests agree with the repository candidate.

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
sidecars, and never logs URLs, headers, tokens, paths, titles, or raw native
errors.

The remaining native residuals are explicit: mpv/FFmpeg does not inherit
Android network-security-config domain pins/rules from the exported CA roots,
and native redirect handling cannot intercept credential forwarding across an
HTTP redirect. This is a trusted-initial-authority residual, not permission to
disable TLS verification or use a tokenized URL.

## Why

- **The Android mpv backend ships a project-owned wrapper over the pinned
  native AAR input, not the upstream artifact directly.** A raw-log finding
  showed the upstream bridge requested verbose mpv messages and echoed
  credential-bearing request details to logcat, outside the app's scrubber
  boundary. The wrapper retains the stable wrapper source and the unchanged
  extracted native graph, compiles only the small bridge library, defaults
  native logging off, and removes raw message writes; the AAR stays a pinned
  build input. Rejected: silencing only app loggers or
  release builds (the native writer would remain), publishing a
  coordination-heavy fork, and rebuilding the full native graph.
