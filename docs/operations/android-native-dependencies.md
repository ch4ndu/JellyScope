# Android Native Dependency and License Runbook

This runbook owns the Android mpv, Media3 FFmpeg, and LibVLC playback payloads.
It is a release-candidate checklist, not proof that a candidate has passed the
physical playback gate. Both Android applications install on API 25
(Android 7.1), including Fire OS 6; only the bundled mpv backend requires API
26 (Android 8.0) and is reported unavailable below that floor. ExoPlayer
remains the default on API-25 devices.

## Pinned inputs

The project-owned `:android-libmpv` module prepares
`io.github.ch4ndu:libmpv-native:0.41.0-jellyscope.2` as its only external mpv input.
The `.2` source commit, complete source/notice assets and publication are pending;
its proposed release routes are not ordinary public-resolution evidence. The
published `.1` remains immutable. Prepublication qualification uses an ignored
local resolver; final ordinary builds must resolve the new public AAR through
the existing exclusive artifact-only Ivy repository without that override.

Both GPU and Direct MediaCodec video-output drivers remain in the bundle. The
[TV output setting](../engineering/android-playback.md#android-mpv-backend) selects
between them without another native rebuild.

The prepared `.2` bundle replaces `libmpv.so` and `libavformat.so` for arm64-v8a,
armeabi-v7a and x86_64: six entries, preserving the other 44 provider entries.
The common AudioTrack patch preserves encoded carrier rates and FFmpeg enables
only the SPDIF muxer after the disabled-muxer option. The two-image ImageReader
limit and numeric crop diagnostics remain ARM32-only. Provider/mpv/FFmpeg pins
and the remaining provider native graph are unchanged.

The maintained builder completed a fresh three-ABI build. Static checks found
no unresolved strong imports against the retained provider libraries plus
API-26 stubs, preserved exports and dependency lists, and registered SPDIF
muxers. The new AAR/source-config records match those outputs. These checks do
not establish Android loader behavior or physical surround acceptance. Earlier
`.1` Cube and local-candidate TiVo acceptance are historical; new Shield/TiVo
qualification and Cube/x86 physical limits remain separate.

The AAR is extracted into the module build directory. All native libraries
except the original `libplayer.so` and x86 are retained. The committed project
bridge compiles against the extracted `libmpv.so`, `libavcodec.so`, and NDK-29 C++
runtime. Its upstream wrapper remains provider commit
`fcf6745703dc1265bca88f12fee8fc355ddf251e`; the real `.2` native source commit
and tag still await the explicit Git checkpoint. Both provenance layers and
proposed source/notice routes are recorded in
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

The project wrapper also owns stopped Surface retirement and its INT64 event
fence; see [wrapper provenance](../../android-libmpv/UPSTREAM.md). This rebuilds
`libplayer.so` only and does not replace the pinned mpv/FFmpeg payload.

### E-AC-3 encoded-output prerequisites

mpv E-AC-3 output requires FFmpeg's registered `spdif` muxer and an AudioTrack
backend that preserves the 192 kHz encoded carrier rate. Decoder availability
alone does not establish either prerequisite. The published `.1` bundle disables
FFmpeg muxers and retains mpv's PCM-rate cap. A missing muxer fails before the
first audio frame reaches output initialization, so that stage cannot use
mpv's later audio-output failure fallback to PCM.

The unpublished candidate enables only `--enable-muxer=spdif` at the pinned
FFmpeg revision and replaces `libavformat.so` alongside the patched `libmpv.so`
for each shipped ABI. Its build and source records must identify both changes;
all remaining provider libraries stay unchanged. Verify muxer registration,
imports against those retained libraries, and exact packaged bytes before the
physical playback gate. The prepared `.2` pin is not yet publicly resolvable
and does not extend earlier Cube acceptance to these rebuilt libraries.

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
[Android mpv diagnostic policy](../engineering/android-playback.md#android-mpv-backend) owns that
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
  native patch bundle does not replace this project-owned bridge.
