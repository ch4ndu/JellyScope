# Playback Policy And Wire Contracts

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## 1. Product policy and invariants

JellyScope is DirectPlay-first. A request is governed by two independent
inputs, which must never be relabelled as one another:

1. **Concrete-backend compatibility facts** describe what the selected decoder
   can truthfully accept: codec, container, profile, bit depth, range,
   dimensions, throughput, subtitle delivery, and audio/output facts.
2. **The user's resolved quality policy** is `Auto`, `Original`, or
   `Fixed(exact bitrate)`. Resolution order is an explicit choice in the current
   playback, then the per-server VLC-family default when a VLC backend is active,
   then the general per-server playback default.

The following rules are non-negotiable:

- Decoder capability comes from an active decoder/platform probe, a pinned
  engine declaration, or platform-vendor documentation. A deliberately
  conservative product input envelope may intersect more than one backend when
  it is owned and labelled as app policy rather than decoder evidence. A screen,
  window, monitor, and JellyScope's limited device/server/content measurements
  never create a platform-wide decoder claim.
- Every advertised codec carries closed decode and finite-limit provenance.
  Composed claims may retain multiple sources; absent or weaker knowledge stays
  explicit as `Unknown`, and static fallbacks are never relabelled as probe
  results. Provenance explains an existing capability but does not alter it or
  become a Jellyfin wire field.
- A shared capability object is permitted for more than one backend only when
  every claim is accurate for each of those backends. Backend-specific objects
  are an accuracy choice, not an architectural goal.
- Unknown resolution capability means unknown, not unsupported. A finite low
  bound remains a usable bound; only absence of a decoder makes a codec
  unusable. Source-relative preflight decides whether the particular source can
  be copied.
- DirectPlay and DirectStream both undergo source-copy preflight. DirectStream
  remains preferred to full Transcode when a server-side remux is sufficient.
- Display geometry is presentation state only. Fullscreen, resize, external
  display, and monitor changes never initiate a quality replan.
- The player controller receives an immutable `PlaybackPlan`; it maps that plan
  to native APIs and reports native facts. It never fetches Jellyfin metadata or
  selects a stream strategy itself.
- Backend readiness is a controller-owned execution concern applied only after
  the immutable plan is installed. Native resource configuration may make an
  already-selected backend safer to execute, but it cannot change DirectPlay
  eligibility, the user quality policy, a device profile, or the wire request.
- A remote current playback may take one explicit, session-local concrete-backend
  switch. The ViewModel projects `PlayerBackendPolicy` in policy order, excludes
  `Auto`, leaves known unavailable choices visible but disabled, and never writes
  the persisted backend preference. It first builds and validates a clean
  target-qualified plan while the healthy controller remains installed. The
  switch captures explicit source/audio/subtitle/quality intent, play/pause,
  speed, subtitle style, resize mode, queue identity, and installed-plan/
  reporting authority; it never carries outgoing decoder, health, dropped-frame,
  recovery, or runtime bitrate facts into the target request. A preflight
  planning or explicit-intent failure closes the picker and presents a persistent,
  dismissible notice with the rejection reason and confirmation that current
  playback was kept, without a Stop or teardown. For the final
  commit, the serialized installer revalidates authority, pauses an originally
  playing outgoing controller, refreshes confirmed position, and builds a final
  target-qualified plan while the controller and reporting session remain
  installed; a final planning or explicit-intent failure restores its prior
  play/pause intent and keeps that playback. After the final plan succeeds, the
  release-first installation revalidates authority after its committed
  suspensions and before reporting, prepare, and play/pause. Construction may fall back once to the platform
  concrete default only for that explicit switch; the factory-reported backend
  is authoritative and receives a clean matching plan before prepare. Offline
  sessions, queue changes, stop, disposal, unavailable/current choices, later
  plan/reporting installation, and stale switch generations cannot commit.

## Quality semantics

Diagnostics record the effective policy and origin, never a fake nullable-bitrate interpretation.

| Policy | Initial wire request | Automatic stream-changing recovery | Persistence |
| --- | --- | --- | --- |
| `Original` | No client quality/performance limit; DirectPlay/DirectStream enabled | Never. Source-copy incompatibility fails closed; a runtime decoder failure or reliable missing-video-output fact shows a persistent action notice. | Server default, or current playback only when selected in the player. |
| `Auto` | The same no-client-limit, DirectPlay-first request as Original | Auto may take one compatibility replan. Only an explicit in-player Auto choice may take one strictly lower canonical quality recovery; an inherited Playback-default Auto policy instead presents manual in-player choices. | Server default; a runtime recovery cap and player choice never persist. |
| `Fixed` | The exact selected bitrate, plus the canonical rung resolution when applicable | Compatibility recovery may stay inside the selected cap; it never lowers the user's fixed choice. | Server default, VLC-family default, or current playback only. |

`Original` does not hide genuine decoder incompatibility. The device profile may
still cause Jellyfin to choose a compatible stream when authoritative facts
require that. It means that JellyScope adds no finite quality/performance
limiter and does not silently choose a different stream after runtime failure.

In shared Compose playback, a user quality choice is a proposal until a current
replacement plan succeeds. A rejected proposal preserves the installed stream,
last applied quality and its explicit/inherited origin, current pause/play
intent, reporting, tracks, and health/recovery state. It shows the same typed,
dismissible playback-change notice used for rejected backend switches. A later
backend switch inherits the last applied quality, never a rejected proposal.
Planning remains cancellable; the existing installation guard owns outgoing
reporting Stop through replacement installation.
This preservation applies only while the same installed session remains active;
startup, terminal recovery, and native prepare failures retain their normal
failure semantics. A new relevant action or explicit dismissal clears the notice.

`Original` recovery is a single prompt contract. Every trigger below returns
`OriginalPlaybackFailed` with exactly `AcceptAuto`, `OpenPlaybackSettings`,
`Dismiss`, and `Close`; it creates no replan, recovery budget use, or runtime
quality cap.

| Trigger | Evidence class |
| --- | --- |
| `DecoderFailure` | Typed decoder failure |
| `UnsupportedMedia` | Typed unsupported-media failure |
| `NoVideoOutput` | Reliable missing-video-output observation |
| `CumulativeBuffering` | Cumulative buffering evidence |
| `RepeatedStalls` | Repeated-stall evidence |
| `DroppedFrames` | Sustained dropped-frame evidence |

`Auto` starts identically to Original at the quality layer. Its additional
quality-recovery permission is granted only by an explicit in-player Auto
selection for this playback; an inherited Playback-default Auto policy has the
same initial request and collects the same health evidence, but it does not
automatically change streams. Auto may take one compatibility recovery for a
decoder/unsupported failure or trustworthy no-video-output observation. An
explicit in-player Auto may also take one quality recovery for
repeated/cumulative buffering or sustained dropped-frame evidence; inherited
Auto instead presents manual in-player choices. Slow start is guidance only.
Every automatic quality step is strictly downward on the shared canonical
ladder. There is no automatic upshift: stable playback after a lower choice is
not evidence that a higher choice is safe. **Try higher quality** clears the
session runtime cap, records explicit session Auto authority, and replans
uncapped; it preserves the spent automatic budgets so an immediate loop cannot
occur. **Keep current quality** converts
the recovered value to Fixed only for the current playback. Stopping and
reopening the item resolves the defaults again.

A Fixed-policy failure never lowers quality automatically. Its persistent
notice offers the in-player lower-quality picker, an uncapped higher/Original
retry, and dismissal; it does not route to Settings.

Network retry which repeats the same plan, and an already-qualified backend
replacement which preserves the selected policy, remain separate from a
stream-changing quality recovery. A replacement must re-plan from the new
backend's own facts and discard observations belonging to the old controller.

## 3. Wire and persistence boundaries

`PlaybackBitrateConstraint` is internal planner/wire intent, not UI policy:

| Constraint | Meaning and encoding |
| --- | --- |
| `NoClientLimit` | Encode `Int.MAX_VALUE.toLong()` in PlaybackInfo and the DeviceProfile streaming/static fields. Omit `TranscodingProfile.MaxBitrate` and codec `VideoBitrate` conditions. The sentinel is a protocol maximum, never a displayed quality rung. |
| `ExactUserLimit` | The Fixed exact value in request/profile output fields and the required `VideoBitrate` condition. A canonical rung also supplies its resolution box; Custom is bitrate-only. |
| `AutoSessionLimit` | A temporary Auto recovery value encoded like an exact quality output limit. It is held only for the active session and is never silently written to preferences. |

The no-client-limit sentinel exists because omitting these Jellyfin fields lets
the server assume its own finite default. It is not a claim that every decoder
can play every source. A forced-transcode sentinel probe must be recorded in
the server-behavior evidence before release promotion; an unsafe result blocks
promotion rather than authorizing a hidden finite replacement.

Quality has exactly two durable settings and one ephemeral scope:

- `PlaybackPreferences.defaultQualityPolicy` is a local installation's default
  for a Jellyfin server (therefore server + device/app-profile in practice).
- `PlaybackPreferences.vlcTranscodeMaxBitrateBps` is the legacy storage name for
  an optional VLC-family Fixed default on that server. It uses the same
  bitrate-plus-canonical-resolution semantics as any other Fixed quality.
- An in-player choice is held only by the active presenter/ViewModel. It is not
  written to `PlaybackSelection`, process memory, or preferences.

Settings offers Auto, Original, the canonical fixed rungs, and Custom. Player
pickers additionally offer **Use playback default**, whose second line names the
effective inherited policy and whether it comes from the VLC-family or general
Playback setting. It clears only the current-playback choice and must preserve
audio/subtitle choices. Legacy per-source quality columns remain only for Room
schema compatibility and are ignored on read and cleared on write; durable audio
selection remains source keyed. There is no account-wide quality fallback,
per-asset quality memory, or persisted learned capacity.

- **[all]** A user-selected Fixed quality rung is an **absolute resolution cap**, not
  just a bitrate cap. Rungs carry real dimensions; the planner derives the bound
  centrally by mapping the request's bitrate back to its rung
  (`qualityRungForBitrate`), so no caller can opt out and no signature carries
  dimensions. The bound travels on `PlaybackInfoRequestPolicy` into the device
  profile's per-codec `Width`/`Height` conditions and the transcode-URL rewrite.
  The effective bound everywhere is the **tighter** of the rung cap and the
  device decode ceiling, reconciled in one place
  (`reconcileVideoResolutionBounds`). Auto and Original carry no quality-rung
  bound and remain limited only by authoritative decoder/user-device facts.
  An Auto-session recovery uses the next lower canonical rung and therefore
  carries that rung's dimensions. The optional VLC-family setting is not an
  automatic cap or second request: it resolves as the corresponding Fixed
  default before the initial request and uses the same canonical-rung semantics.
  A source whose bitrate
  fits a Fixed rung but whose resolution exceeds it may transcode; uncapped
  Auto/Original do not add that quality restriction.

- **[all]** Every path that hands source video to a decoder unchanged —
  server-approved DirectPlay, server-approved DirectStream, and the local
  PlaybackInfo-failure fallback — passes one planner preflight against that
  reconciled bound (box, frame area, and throughput). Refusal recovers through a
  bounded sequence according to policy: a transcode already in the response,
  else at most one backend-preserving re-request with DirectPlay and
  DirectStream disabled for Auto or eligible Fixed, else a real error/action
  notice. Original never authorizes that automatic stream-changing request. An
  unknown source codec or missing dimensions refuse only the
  copy (the transcode outputs a declared, condition-bounded codec); a missing
  frame rate under a finite throughput ceiling fails closed even after recovery,
  because no profile condition can express throughput — the recovery re-request
  pins `MaxFramerate`, but that pin is not yet probe-verified against the
  server (see [server verification](playback-policy.md#verifying-server-behavior)); until a probe confirms the
  server honours `MaxFramerate`, the fail-closed refusal stands.

- **[all]** Missing stream metadata is borrowed from item detail only within
  the same media-source version. The planner falls back to the response's
  first media source when the requested id is absent, and detail streams
  describe the requested version — an unconditional borrow could fill a 60fps
  alternate's missing frame rate with 24fps and let an unsafe copy through the
  preflight. A mismatched source keeps its own metadata and fails closed on
  what it lacks.

- **[all]** Player-device settings expose Audio Auto/Stereo/Passthrough and HDR
  Auto/Prefer SDR separately from per-item pickers. Unsupported values remain
  visible with a reason, stored unchanged, and resolve to safe effective policy.
  Android capability refresh re-probes the active route/display.

## VLC-family PlaybackInfo policy

The optional per-server VLC setting is a backend-specific default quality.
Disabled means inherit the general Playback default. An enabled value resolves
to Fixed before the initial PlaybackInfo request for LibVLC or VLCKit, and uses
the same exact bitrate and canonical resolution box as the matching Fixed rung.
It does not change decoder capabilities and never applies to Media3/ExoPlayer,
AVPlayer, or mpv. A player-picker choice has higher priority but lasts only for
that playback. No compatibility failure activates or lowers this value, and no
combination-test value, including 8 Mbps, is a built-in default, maximum, or
decoder rule.

Android seeds this per-server VLC value to the conservative 8 Mbps rung as a
stored value through a one-time gated data migration (NULL rows are seeded and
the no-row default supplies the same value), because VLC-family playback at
high bitrates can crash the app or device on Android. Four asymmetries are
accepted: the one-time seed is clearable back to inherit; NULL ambiguity
overwrites an explicit inherit; logout re-applies the seed; and a
downgrade/upgrade cycle replays data-only migrations once. Desktop and iOS are
not seeded because their VLC paths have not shown the crash class.

## Device profiles and server stream-copy policy

- PlaybackInfo requests include the effective player-device policy. Stereo PCM
  limits requested audio channels to 2, narrows profile audio codecs to AAC/MP3,
  and disables audio stream copy. Passthrough may advertise route-supported
  encoded audio codecs only when the active output route reports them. A
  per-codec local-decode ceiling takes precedence over the positive legacy
  global fallback; the route ceiling participates only for a codec on the
  passthrough list. Media3's bundled-FFmpeg E-AC-3 path therefore accepts up to
  eight encoded input channels even when the current PCM route is stereo, while
  it makes no E-AC-3 passthrough claim. Prefer
  SDR uses the range-type derivation with an SDR-only capability set but keeps
  video stream copy ALLOWED: the per-codec `EqualsAny VideoRangeType` conditions
  already deny copying non-SDR sources (the server tone-maps them), while SDR
  sources remux when only audio needs transcoding (probe-verified on Jellyfin
  10.11). `AllowVideoStreamCopy=false` is never sent as a routine safety flag:
  the server honors it by silently re-encoding video at the same codec and
  bitrate without adding any `TranscodeReasons` entry, so the quality loss is
  invisible in the PlaybackInfo response (the reasons list still shows only the
  original trigger, e.g. `AudioCodecNotSupported`) and surfaces only as
  `IsVideoDirect=false` in `/Sessions` TranscodingInfo (probe-verified on
  Jellyfin 10.11). Disabling stream copy is reserved for the explicit runtime
  [runtime fallback ladder](playback-runtime.md#runtime-request-fallback), where a re-encode is the intended outcome. Auto HDR
  falls back effectively to SDR when display/HDR support is not detected.
- Android caches decoder enumeration but re-probes the anticipated media audio
  route and primary app display for every capability read. API 33+ uses media
  `AudioAttributes`; older Android/Fire OS uses the current HDMI plug state.
  Unknown routes fall back to stereo with no passthrough, and duplicated active
  routes advertise only common encoded formats and the conservative
  passthrough-channel limit. MediaCodec input-channel counts use Media3 1.9's
  effective adjustment rather than uncorrected framework values; bundled
  FFmpeg E-AC-3 keeps its separate eight-channel decode-input ceiling.
- Device profiles model video range capabilities per advertised codec. The
  mapper emits exactly one required `EqualsAny VideoRangeType` condition for
  each codec, using a pipe-joined supported allowlist; unknown or newly added
  server range strings therefore transcode rather than leaking through an
  exclusion rule. `NotEquals` range conditions are never used: the server's
  condition processor treats subtype ranges as satisfying their base type
  (`HDR10Plus` satisfies an `HDR10` condition), so a `NotEquals` denial list
  leaks ranges it was meant to exclude — only a pipe-joined `EqualsAny`
  allowlist admits and denies exactly the listed ranges (probe-verified on
  Jellyfin 10.11). `SDR` and `DOVIWithSDR` are always allowed. The remaining
  entries are derived as follows:

  | Capability | Additional advertised range types |
  | --- | --- |
  | HDR10 | `HDR10`, `HDR10Plus`, `DOVIWithHDR10`, `DOVIWithHDR10Plus` |
  | HDR10+ | `HDR10Plus`, `DOVIWithHDR10Plus` |
  | HLG | `HLG`, `DOVIWithHLG` |
  | Dolby Vision base | `DOVI`, `DOVIWithHDR10`, `DOVIWithHDR10Plus`, `DOVIWithHLG` (a DV decoder renders the DV layer regardless of fallback-range display flags) |
  | Dolby Vision enhancement layer | `DOVIWithEL`, `DOVIWithELHDR10Plus` |

  `DOVIInvalid` is never advertised. Base Dolby Vision and its enhancement layer
  are independent capabilities: the latter is reported only when Android detects
  profile 7 and multi-instance HEVC decode. Android maps profiles 5 and 8 to
  HEVC base Dolby Vision and profile 10 to AV1 base Dolby Vision; a Dolby Vision
  MIME with no enumerable profiles falls back only to HEVC base. Non-HDR codecs
  therefore advertise exactly `SDR|DOVIWithSDR`, and Prefer SDR uses that same
  two-member allowlist for every codec.
- Explicit per-codec range exclusions are stronger than that derived positive
  set and are subtracted after canonical, case-insensitive normalization. The
  Android provider attaches only the HEVC exclusions
  `DOVIWithHDR10Plus` and `DOVIWithELHDR10Plus` for exact device models
  `AFTKRT`, `AFTKA`, `AFTKM`, and `AFTMM`; blank, case-different, near-match,
  and other model IDs infer nothing. The DTO still emits one positive
  `EqualsAny` allowlist. Local source-copy preflight applies the same explicit
  exclusion before DirectPlay, DirectStream, or PlaybackInfo-failure fallback,
  so local copy cannot bypass the wire subtraction. Auto and eligible Fixed may
  use the existing bounded forced-re-encode request; Original fails closed, and
  Unrestricted never removes a hardware exclusion. An empty exclusion map is
  behavior-preserving for every other provider and model.
- Android's named `VideoProfile` list must be **ordered best-first** (high,
  main, main 10, high 10, baseline, constrained baseline; unknown names last):
  Jellyfin uses the first listed profile as its transcode **encode target**, so
  an enumeration-ordered `baseline|main|high` list can select a systematically
  dropped-frame-prone constrained-baseline transcode. `EqualsAny` direct-play
  eligibility is order-insensitive, so keep the transcode encode-target order
  explicit rather than inheriting decoder-enumeration order.
- Device profiles must equally not **under-claim** from unreliable decoder
  enumerations. Android's named `VideoProfile` and `VideoLevel` conditions are
  derived only from the selected decoder and advertised only when its mapped
  `profileLevels` contain the codec's trust-anchor set (`h264: main|high`,
  `hevc: main|main 10`, `av1: main|main 10`) — defense-in-depth for vendor
  decoders that under-report profiles. When the selected decoder gives
  different maximum levels to its advertised profiles, JellyScope emits the
  minimum of those per-profile maxima because Jellyfin applies one level
  condition to the entire profile set. A
  degenerate enumeration drops the profile and level claims but retains the
  conservative `VideoBitDepth <= 8` condition, so the server keeps its default
  encode profile (High) while 10-bit sources still transcode instead of direct
  playing. Accepted residual risk: degenerate-reporting hardware that truly
  cannot decode Main/High transcodes fails fatally, because transcode failures
  deliberately bypass the runtime fallback ladder. The omission is logged once
  per decoder enumeration as sanitized fixed-label Info
  (`codec-constraints-untrusted codec=<name> mappedProfiles=<count>`).
- Advertised `VideoProfile` values use Jellyfin's lowercase space-form
  spellings ("main 10", "constrained baseline"), never MediaCodec underscore
  forms — the server compares them case-insensitively but not
  punctuation-insensitively. `VideoLevel` conditions use Jellyfin's numeric
  encodings: H.264 levels ×10 (4.1 → 41), HEVC levels ×30 (5.1 → 153), and
  AV1's raw level index (e.g. 15, 19). Wrong spellings or scales silently fail
  the condition and change direct-play/transcode decisions (probe-verified on
  Jellyfin 10.11; the spellings and scales are an ecosystem-wide convention).
- Transcode plans must cap output resolution to the target codec's decoder box.
  Jellyfin's transcode scaler is width-tier based and does not enforce the
  profile's `Height` condition on portrait sources, so a response can exceed a
  height-limited decoder and produce black video or stuck buffering. When the
  response video stream cannot fit the transcode target codec's detected
  resolution limits, the planner rewrites the transcoding URL with an
  aspect-preserving integer-math fit on **both** parameter sets the server
  consults:
  `MaxWidth`/`MaxHeight` are replaced (the bitrate-driven resolution normalizer,
  active whenever the output bitrate is below the source bitrate, takes
  `min(tier, MaxWidth)` and ignores explicit dimensions) and explicit
  `Width`/`Height` are appended (honored on the non-normalized path). Log only
  the dimensions — the capped URL carries the ApiKey. The planner may rewrite
  the returned `TranscodingUrl`'s query parameters (resolution caps, subtitle
  fields) because those parameters are the transcode job spec itself: the
  server builds the ffmpeg job from the URL's query values at segment-fetch
  time and no signature covers them, so an edited parameter changes the job
  (probe-verified on Jellyfin 10.11). Both this resolution-cap rewrite and the
  [server-attached subtitle stripping](subtitles.md#selection-and-delivery) depend on that contract; if a future
  server release signs or ignores these parameters, both features break
  together and must be re-verified.

## Server response and container authority

- PlaybackInfo decisions are server-authoritative. Select the requested media
  source when returned, otherwise the first source. Prefer `SupportsDirectPlay`
  with the static URL, then `SupportsDirectStream` with a usable response
  container and a `/Videos/{itemId}/stream.{container}` URL carrying the media
  source, device, play-session, response audio/subtitle indices, and subtitle
  method. Use a nonblank `TranscodingUrl` only when transcoding is supported. A
  malformed direct-stream response falls through to valid transcode before a
  typed planning failure. Response container/subprotocol determines stream MIME;
  HLS always uses the HLS MIME. Runtime recovery follows the separate bounded
  policy above; response preference order is not a decoder-retry ladder.
- Device profiles are platform/container-qualified, not one global container
  list. They intersect platform containers/codecs with the effective audio/HDR
  policy and mirror a non-null bitrate cap into request, device-profile, and
  transcoding-profile bitrate fields. Android also maps available H.264, HEVC,
  and AV1 codec profile/level/bit-depth data. Named video profiles are
  advertised as Jellyfin `EqualsAny` sets (for example `main|main 10`), never
  ordered comparisons; keep detected resolution limits and do not invent
  reference-frame constraints. The HLS-TS transcoding profile advertises the
  best-first intersection of `aac,mp3,ac3,eac3` and effective audio codecs
  (falling back to `aac` when empty) so aac remains the re-encode target while
  compatible ac3/eac3 sources can copy; Stereo PCM retains its narrower policy
  codecs.

## Playback metadata

- Playback metadata extensions stay shared: item detail maps Jellyfin
  `Chapters`, `Trickplay`, and `RemoteTrailers`; media segments come from `GET
  /MediaSegments/{itemId}` with segment types `Intro`, `Outro`, `Recap`,
  `Preview`, and `Commercial`. Legacy Intro-Skipper endpoints are not part of
  the shared contract unless a later feature adds an explicit fallback.
- Jellyfin trickplay metadata is retained as media-source ID → nullable
  resolution map. After playback planning selects a source, Player resolves the
  exact outer entry, falling back only when the server supplied exactly one
  outer entry, and chooses the largest valid inner resolution for that source.
  The selected source ID is part of both tile URLs and tile-cache identity, so a
  source switch cannot reuse another version's thumbnails.
- `PlaybackPlan` carries chapters, trickplay info, media segments, playback
  speed, and subtitle style so platform players can consume them at prepare
  time. `PlayerViewModel` enriches `PlaybackState` with the current segment and
  exposes playback-speed/subtitle-style updates through `PlayerController`.
- `PlaybackPlan.videoPresentation` is passive response-derived video metadata
  (width, height, frame rate, and range). It supports debug presentation,
  Android TV panel presentation, and Android mobile's stable coded PiP
  aspect. The dimensions remain rotation- and pixel-aspect-blind; they must
  never change a device profile, PlaybackInfo request, or the selected stream
  mode.

## Verifying server behavior

Server-behavior facts in this guide carry a "(probe-verified on Jellyfin
10.11)" stamp; a new server major requires re-probing every stamped fact
rather than porting code. When probing a live server:

- A transcode job only starts when a SEGMENT is fetched: GET the master
  playlist, then the child `main.m3u8`, then at least one `.ts` segment.
  Fetching only the master playlist never starts an encode, so `/Sessions`
  will show no `TranscodingInfo` and the verification silently observes
  nothing.
- The PlaybackInfo response's standalone `TranscodeReasons` list is often
  empty; when it is, URL-decode the `TranscodeReasons=` query value from the
  returned `TranscodingUrl` instead. The authoritative record of what the
  server actually did for a session is `/Sessions` — `PlayState.PlayMethod`
  plus `TranscodingInfo` — not the PlaybackInfo response the client planned
  from.
- Any PlaybackInfo replay used to verify direct-play decisions MUST include
  the client's real `SubtitleProfiles`. A profile without them makes the
  server plan burn-in for a default-flagged or auto-selected subtitle and deny
  direct play for the whole item, producing false direct-play denials that do
  not reproduce the app's actual behavior.

## Request and effective caps

- `PlaybackPlan.maxStreamingBitrate` is the exact PlaybackInfo **request cap**
  for every final stream mode. Its typed origin survives planning. An
  effective-transcode cap is populated only when the installed result is
  `Transcode`; DirectPlay and DirectStream may retain a request cap without
  claiming that it limited the delivered stream. App policy never modifies the
  server's `transcodeReasons`.

## Representative direct-play declarations

These are representative formats each player requests directly. Device
decoders, output capabilities, media profiles, and stream details can still
cause conversion or an unsupported result.

| Player | Containers | Video | Audio |
| --- | --- | --- | --- |
| ExoPlayer (Android) | MP4, M4V, MOV, MKV, WebM, TS | H.264, HEVC, VP9, AV1 | AAC, MP3, AC-3, E-AC-3, DTS, FLAC, Opus, Vorbis, PCM |
| mpv (Android) | AVI, MKV, M2TS, MOV, MP4, TS, WebM | H.264, HEVC, VP8, VP9, AV1, MPEG-2, MPEG-4 | AAC, AC-3, E-AC-3, DTS, FLAC, MP3, Opus, PCM, TrueHD, Vorbis |
| LibVLC (Android) | MP4, MOV, MKV, WebM, TS, AVI | H.264, HEVC, MPEG-1, MPEG-2, VP8, VP9, AV1 | AAC, AC-3, E-AC-3, MP3, FLAC, Opus, Vorbis |
| AVPlayer (iOS, Apple TV) | MP4, M4V, MOV, TS | H.264, HEVC, AV1 | AAC, MP3, AC-3, E-AC-3, FLAC, ALAC |
| VLCKit (iOS) | MP4, M4V, MOV, MKV, WebM, TS, M2TS, AVI, FLV, 3GP, OGV, ASF, WMV | H.264, HEVC, VP8, VP9, MPEG-1/2/4, VC-1, WMV, ProRes, Theora, MJPEG, AV1, and more | AAC, AC-3, E-AC-3, DTS, MP1–MP3, FLAC, ALAC, Opus, Vorbis, PCM, WMA, and more |
| macOS (both players) | MP4, M4V, MOV, MKV, WebM, TS, AVI | H.264, HEVC, VP9, AV1 | AAC, MP2, MP3, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis, PCM |

On Android, support depends on the selected player and device decoders. On
Apple devices, the hardware AV1 check applies to AVPlayer. VLCKit declares AV1
without that hardware gate and does not claim HDR support.

## Why

### Server contract and capability modeling

- **Trickplay is source- and delivery-qualified.** Resolution alone can pair a
  tile sheet with the wrong media version, and advertised metadata does not
  prove a tile can be fetched and decoded. Source identity therefore participates
  in URL/cache keys, while preview chrome appears only after image delivery.

- **DTS claims remain backend-specific.** Media3 advertises DTS only with a
  confirmed decoder or active passthrough route, while Android mpv retains its
  pinned dca/DTS declaration. The bundled FFmpeg E-AC-3 result augments only
  Media3, and an active route's channel count remains separate from local
  decode/downmix capability.

- **Profile and URL rewrites preserve intent.** Wire spellings stay exact while
  local mapping canonicalizes aliases. Both dimension parameter sets are capped,
  and conflicting server-attached subtitle parameters are stripped because
  `SubtitleStreamIndex=-1` alone is insufficient.

### Quality policy

- **A named quality rung carries an absolute resolution cap.** Bitrate-only
  enforcement can produce source-resolution video that contradicts the visible
  rung and exceeds the decoder. Canonical dimensions are derived centrally;
  custom and unrelated automatic bitrate limits remain bitrate-only.

- **Quality has one precedence chain and no learned capacity.** A current-player
  choice outranks the active VLC-family Fixed default, which outranks the
  general server default. Session scope prevents stale experiments from
  overriding current policy and keeps inherited Auto distinct from explicit
  Auto. A choice commits only after planning succeeds, so rejection or
  cancellation cannot alter the installed stream or its reporting. The
  no-client-limit sentinel is required because both omission and a finite
  stand-in can impose a server cap.

- **Original has one manual failure contract.** Decoder, unsupported-media,
  missing-output, buffering, stall, and dropped-frame triggers all preserve the
  source and present the same actions. Trigger-specific hidden replans would
  make Original's no-stream-change promise depend on implementation detail.

- **Capability facts remain concrete-backend owned.** Combining unrelated
  decoder maxima, platform probes, static declarations, or extension support
  can describe a nonexistent path. Closed provenance preserves what was measured
  or declared without creating a capability or sharing Media3 FFmpeg support.

- **Desktop and iOS product envelopes are scoped policy.** The macOS LibVLC
  4K60-equivalent and iOS 4K30-equivalent Standard envelopes prevent known
  unsafe inputs without claiming a universal hardware ceiling. Unrestricted
  removes only that envelope; backend facts remain active. Screen geometry,
  guessed machine tables, and sparse model catalogs are not decoder evidence.
