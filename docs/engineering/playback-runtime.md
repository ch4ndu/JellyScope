# Playback Runtime And Recovery

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Backend selection

- Player backend is user-selectable per platform. iOS retains `Auto`/`AVPlayer`/
  `VlcKit` with AVPlayer as its default. Desktop exposes `mpv` and
  `LibVLC` and defaults legacy or foreign values to mpv. Android exposes the
  concrete `ExoPlayer`, `Mpv`, and `LibVlc` choices in that order on mobile;
  `Auto`, null, and unknown legacy values normalize to ExoPlayer, and ExoPlayer
  is the initial/default selection. Android TV retains the same mapping and
  exposes mpv as its explicit opt-in alternate backend with a plain label.
  The shared LibVLC label is `LibVLC (beta)` on Android and desktop; iOS
  `VLCKit` is unchanged. Desktop mpv and tvOS retain their current native
  defaults. `PlayerBackendPolicy` is the single owner of each platform's
  visible order, default, and persisted-value normalization.
- Settings receives that immutable backend policy and takes runtime
  *availability* separately from `DeviceProfileProvider.availableBackends` —
  the same source playback falls back through — via
  `GetAvailablePlayerBackendsUseCase`.
  `SettingsViewModel` resolves it on its work dispatcher (the Android mpv read
  is a cheap bundled-ABI check; native create/init probes remain on requested
  controller construction and must never run during composition) and publishes
  policy-ordered choices and a safe effective selection; Settings shells render
  that state only. A backend the device cannot run stays listed but disabled
  with an unavailable reason, so a stored-but-unsupported choice remains stored
  and resolves to a safe effective selection instead of being silently
  rewritten.
  While the probe is still resolving, and if it fails, the picker keeps the full
  policy list rather than showing nothing; a probe that reports no backends
  falls back to the policy default backend.
- `resolvePlayerBackend` picks one concrete backend once per initial playback session
  from the server setting, any compatible item override, source descriptor, and
  runtime availability. That durable initial backend remains authoritative for
  queue switches unless the user makes an explicit session-only switch, and
  flows into `PlaybackInfoRequestPolicy.backend` so PlaybackInfo uses that
  backend's device profile. An explicit remote in-player switch is a separate
  session-local replacement: it projects the same concrete policy/availability
  truth, filters `Auto`, keeps unavailable choices disabled, and preserves the stored
  preference. A target preflight plan is built with explicit source/audio/
  subtitle and quality intent before teardown; failure preserves the healthy
  controller, plan, item/reporting identity, and emits no Stop. For the final
  commit, the serialized installer pauses an originally playing controller,
  refreshes confirmed position, and builds a final target-qualified plan before
  release; a pre-teardown failure restores the prior play/pause intent and
  keeps that playback. The factory's actual backend requires a matching clean
  plan; only an explicit target switch
  may construct the concrete platform default once after target construction
  fails, never the Android automatic-health fallback.
- Android's ExoPlayer path is Media3 1.9.0 with decoder fallback and
  the Jellyfin FFmpeg extension (`org.jellyfin.media3:media3-ffmpeg-decoder`
  from Maven Central); FFmpeg is an ExoPlayer decoder extension, not a third
  backend. Media3 is pinned to 1.9.0 because the published decoder's version
  tracks Media3 and 1.9.0 is the newest published pairing — so no local NDK
  build is required. Media3's server-facing audio capability is the union of
  its MediaCodec probe and a process-cached, fail-closed check that this shipped
  extension is available and supports E-AC-3. Only that E-AC-3 result is added;
  the extension's broader decoder list is not enumerated.
- Android's LibVLC path is the in-process libvlc 3.7.5 controller/surface bridge
  and is used only when selected and available. An eligible startup/prepare
  failure in mpv or LibVLC
  while streaming from Jellyfin can end that controller session and request a
  fresh ExoPlayer-qualified plan at the last confirmed position. Installed
  offline playback never enters that recovery. Where offline policy permits,
  controller construction may fall back before preparation using the existing
  offline plan; a required offline backend cannot fall back.
- Android mpv uses the project-owned controller and SurfaceView bridge described
  in the [Android mpv contract](android-playback.md#android-mpv-backend). Its source/native input and package/runtime
  gates are owned by the
  [Android native dependency runbook](../operations/android-native-dependencies.md).
- Android LibVLC keeps its pinned-engine declaration as the base profile. The
  Android provider preserves each finite width, height, padded frame-area, or
  frame-area-per-second fact from the cached active MediaCodec probe only when
  the codec is declared by both LibVLC and the platform mapping and LibVLC's
  declared tuple is all Unknown. Missing fields stay Unknown; no maxima are
  rebuilt or synthesized. The projection changes no LibVLC container, codec,
  subtitle, HDR, audio, passthrough, or software-decoder declaration. This is a
  conservative platform-decoder safety input: hardware, software, unclassified,
  and legacy-classified probe bounds all remain enforced. Their provenance
  describes Android's selected decoder, not measured limits of LibVLC's own
  software decoder. Unprobed AV1 retains the pinned engine's unknown bounds.
- When a projected bound rejects a source, the shared planner owns the normal
  compatibility path before native prepare: it records the capability-driven
  retry and `SourceCopyRejected`/`VerifiedDeviceCap` rather than handing the
  source to LibVLC. Auto and Original retain `NoClientLimit`; this restriction
  is not a finite quality cap. Fixed remains the user's exact quality policy,
  and the server-scoped VLC default remains a separate Fixed policy. The
  decoder enumeration is cached for both Android backends and explicit refresh
  re-enumerates it before applying the LibVLC projection.
- A projected probe limit is not visual-output acceptance. A native backend can
  select a track without producing a picture or can accumulate output drops;
  sources subject to an unresolved decoder-safety gate stay outside acceptance.
  LibVLC's positive displayed-picture counter supplies first-video-output
  evidence for the black-video case; it is neither a decoder ceiling nor a
  bitrate measurement.
  ExoPlayer independently advertises its probed MediaCodec decoder path and
  never inherits the VLC-specific default.
- Android LibVLC installs `:dav1d-thread-frames=1` on every fresh native
  `Media` before assigning it to `MediaPlayer`. The option belongs to dav1d and
  is inert when another decoder module opens, so this is a pinned libvlc 3.7.5
  execution-resource policy—not a codec claim, device gate, resolution limit,
  bitrate cap, quality preference, or reason to transcode. The same option
  applies consistently on Android phone and TV, while iOS VLCKit and desktop
  LibVLC remain unchanged.

## Player Control And State

- Platform players must implement project-owned player contracts instead of
  exposing native player APIs to UI.
- `PlayerController` exposes `StateFlow<PlaybackState>`, `prepare(plan)`, player
  commands, track and style selection, and a nullable `platformPlayer` escape
  hatch used only by platform video surfaces.
- `prepare(plan)` installs media and always leaves it paused. It never starts
  playback. `play()` records play intent and controllers honor it only after the
  initial DirectPlay audio mapping attempt has completed; `pause()` clears that
  intent. Subtitle activation never holds video startup.
- `PlaybackState` exposes idle, loading, playing, paused, buffering, failed,
  completed, position, duration, buffered position, audio and subtitle
  activation, an optional `PlaybackError` cause only when failed, and a
  non-fatal `audioUnavailable` flag only while playback continues video-only
  after audio output fails.
- Apple player bridges derive buffered position from
  `AVPlayerItem.loadedTimeRanges` by reporting the end of the loaded range
  covering the current playhead, and report buffering whenever AVFoundation
  stalls or the current item is not likely to keep up.
- `PlaybackProgressReporter` owns Jellyfin progress reporting actions for start,
  pause, seek, stop, completion, and failure.
- Direct-play planning uses `/Videos/{itemId}/stream?static=true` with
  `mediaSourceId` and `deviceId` query parameters. Auth tokens stay out of
  stream URLs for header-capable players. Android and desktop mpv use the
  modern token-only Authorization line and strip auth query parameters before
  loading media or same-server external subtitles; guarded VLC-family URLs use
  the modern `ApiKey` query spelling because those transports cannot inject the
  header.
- Playback planning and track/subtitle URL paths use the shared playback URL
  helpers for direct-play stream URL construction and server-relative URL
  resolution. Do not reconstruct a server base URL by parsing an already-built
  stream URL.
- Playback Expansion planning asks Jellyfin for `POST
  /Items/{itemId}/PlaybackInfo` through the shared API facade and media
  repository before playback. The injected `DeviceProfileProvider` supplies a
  domain capability model: Android enumerates decoder capabilities, while the
  Apple AVPlayer branch probes AV1 hardware decode
  (`VTIsHardwareDecodeSupported(AV1)`) and advertises AV1 direct play only where
  the device can decode it; VLCKit retains its separately owned static engine
  declaration. On iOS and tvOS, the UIKit entry captures one
  immutable launch snapshot: `UIScreen.mainScreen.potentialEDRHeadroom > 1.0`
  means the display can present HDR. An unavailable or failed read defaults to
  `false`; a true snapshot infers HDR10 and HLG for Apple-claimed HEVC and
  hardware-decodable AV1, while H.264 remains SDR and all Dolby Vision claims
  remain false. The snapshot is read on the main thread before Koin starts,
  passed through both Apple DI entries, and only read thereafter by the
  provider, so capability reads may run off-main. Reference-mode, mirroring, and
  external-display changes after launch do not refresh this v1 snapshot. The
  data/remote layer maps those capabilities to the Jellyfin
  `PlaybackDeviceProfileDto`, advertising direct play only for the resulting
  codecs. The mapper ignores the capability-evidence maps: provenance is
  rendered only in the sanitized diagnostics snapshot and never becomes a
  `PlaybackDeviceProfileDto` field. Capability baselines advertise what the playback engine can actually
  decode or downmix, never a smaller convenience value; user-selected policy
  (such as Stereo PCM) applies any intentional channel or codec clamp. Audio
  capability keeps three facts separate: the encoded-input ceiling of each
  local decoder path, the active route's encoded-passthrough ceiling, and the
  physical PCM-output shape. The effective policy resolves a ceiling per codec,
  combines passthrough only for codecs the active route actually accepts, and
  uses the largest effective codec ceiling only for the aggregate PlaybackInfo
  request field. The DTO mapper emits separate codec-profile groups when those
  per-codec ceilings differ.
  PlaybackInfo request policies carry the concrete player backend and default to
  AVPlayer on Apple. Apple
  capability reads select the cached AVPlayer profile or the cached VLCKit
  profile; Android selects a Media3/ExoPlayer, mpv, or LibVLC profile from the
  concrete session backend, while desktop selects the separately owned mpv or
  LibVLC snapshot for the concrete session backend. The
  AVPlayer profile rejects interlaced video for every advertised codec. Its HEVC
  entry additionally requires an `hvc1`/`dvh1` sample-entry tag and limits video
  to 60 fps; H.264 and AV1 receive neither the tag nor frame-rate restriction.
  VLCKit profile is common pure data: it advertises its broad demuxable
  container/video/audio set, Embed+Encode text subtitles (NOT External: VLC does
  not render an external subtitle slave over an HLS/transcode stream, so text
  burns in on transcode like AVPlayer), Embed+Encode bitmap subtitles, and no
  HDR/Dolby Vision claim. Its AV1 entry remains a static VLCKit declaration and
  does not inherit AVPlayer's VideoToolbox hardware-probe provenance. Apple multichannel direct-play
  capability is common-test/compile verified but remains runtime-unverified;
  automated evidence does not establish hardware behavior. Providers may also attach
  per-codec maximum video dimensions; the remote mapper emits Jellyfin
  `CodecProfiles` width/height conditions so larger files transcode instead of
  being offered for direct play. PlaybackInfo requests and their device profiles
  deliberately carry a bitrate constraint. `NoClientLimit` sends
  `Int.MAX_VALUE.toLong()` in `MaxStreamingBitrate` and `MaxStaticBitrate`,
  omits `TranscodingProfile.MaxBitrate`, and adds no per-codec `VideoBitrate`
  condition. This protocol sentinel prevents Jellyfin's omitted-field default
  from becoming an app-selected cap; it is never a quality rung or decoder
  claim. Every PlaybackInfo request sends explicit `MaxStreamingBitrate` and
  `MaxStaticBitrate` values because an omitted field is not "unlimited" to
  Jellyfin: the server deserializes missing bitrate fields to 8 Mbps, which
  silently denies direct play for higher-bitrate sources
  (`ContainerBitrateExceedsLimit`) and caps transcode output bitrate
  (probe-verified on Jellyfin 10.11). Fixed and Auto-session values send their
  exact numeric values and the
  required `VideoBitrate` condition; canonical Fixed and Auto-recovery rungs
  additionally supply a resolution box while Custom values remain bitrate-only.
  The VLC-family default resolves to the same Fixed constraint before the first
  request; it is not a decoder capability or a separate retry mechanism. HLS
  H.264/AAC transcoding remains the fallback when direct play is unsupported.
- Apple profile conditions are per-player and are never shared between the
  AVPlayer and VLCKit profiles: AVPlayer uses progressive-only conditions for
  every codec and HEVC-only `hvc1|dvh1` and <=60 fps conditions. VLCKit decodes
  `hev1`/`dvhe` and deinterlaces, so its profile remains unrestricted by those
  AVPlayer conditions.
- Never assume an item with unknown container bitrate direct-plays under a
  numeric cap: Jellyfin's bitrate-limit check substitutes 40 Mbps when
  `item.Bitrate` is null, so any Fixed/Auto cap below 40 Mbps forces such items
  to transcode even when their true bitrate would fit (probe-verified on
  Jellyfin 10.11). Quality-cap diagnostics and expectations must account for
  this server-side substitute, not treat the resulting transcode as a planning
  bug.

## Playback health and guidance

The pure shared `PlaybackHealthEvaluator` receives native controller facts but
owns the meaning of those facts. Slow startup is eligible after 10 seconds
before the first `Playing` and clears at the first `Playing`; it has a separate
message-only startup budget. Post-start guidance uses one shared budget and
the first admissible signal wins: one buffering interval of at least 5 seconds,
10 seconds of buffering in a rolling 60-second window, three post-start
`Playing`→`Buffering` stalls in 60 seconds, or sustained dropped-frame evidence
of at least 2 frames/second for 20 seconds within 60 seconds. Existing dropped-
frame measurements remain duration spans, not report counts; stale batch spans
and spans crossing the 2-second exclusion after seek, prepare, re-plan, resume,
or backend replacement are rejected or clipped before they qualify.

`PlaybackHealthSessionCoordinator` owns generation-scoped timer evaluation. Startup/buffering/output jobs are
canceled on failure, completion, stop, item switch, controller replacement, and
dispose; a stale callback cannot mutate a new item session. First-video-output
is evaluated only when video is expected, playback is actively Playing or
progressing, a reliable current-prepare observation has been armed, and the
session is outside seek/replan/resume/surface/backend/background/PiP-transition
exclusions. A positive Media3 first-frame fact, mpv software-frame publication,
VLC displayed-picture counter, or AVPlayer ready-for-display bridge is
sufficient; mpv OpenGL, Vout, and surface
attachment is not. The deadline begins at active playback, not request launch.
Capability is checked when the deadline is armed. The coordinator rechecks that reliable first-output measurement is still
supported when the deadline fires; capability loss or an unsupported backend
cancels the signal rather than manufacturing a no-video recovery.

The no-video-output deadline additionally requires an **advancing playback
clock**: a clock progressing with no displayed picture is the black-video
symptom, while a frozen clock is ordinary slow startup and is already reported as
`SlowStartup`. The position at deadline arm is compared against the position at
fire; if it has not moved, no signal is emitted. The threshold is
backend-qualified — 20 seconds for VLC-family backends, 5 seconds elsewhere —
because LibVLC reports `Playing` well before its first displayed picture. The
diagnostic threshold class records the bound that actually applied. These
qualifications prevent a slow or frozen startup from triggering a false
`NoVideoOutput` compatibility replan.

A seek or a play/pause **restarts the evidence window**: accumulated buffering
intervals, stall events, and dropped-frame spans are cleared so evidence gathered
under previous conditions cannot reach a threshold. Restarting during an active
buffering episode reopens the interval at the boundary — the retained status is
unchanged, so the ongoing wait remains measurable. Once-per-session emission
latches survive a restart, so a signal kind reports at most once in an item
session. Auto's compatibility and quality budgets also survive and remain
one-per-item-session. The bounded
health summary additionally records which gate suppressed a would-be warning
(`postStartGuidanceShown`, `noVideoOutputGuidanceShown`, the resolved guidance
policy, `guidancePublishable`, and whether a buffering interval opened since the
last restart), because gates that emit no signal are invisible to signal-scoped
logging.

The shared health evaluator supplies evidence and
`AutoPlaybackRecoveryCoordinator` supplies the bounded decision. The policy,
including every Original trigger and prompt action, is owned by
[quality policy](playback-policy.md#quality-semantics). PiP
suppresses interactive recovery until it is safe to show the consequence.

## Runtime request fallback

- Runtime player failures may retry PlaybackInfo with stricter request policy
  before surfacing a fatal error. `AutoPlaybackRecoveryCoordinator` owns one
  policy-gated compatibility attempt for eligible decoder, unsupported-media,
  or no-output evidence on a direct-play/direct-stream plan. It disables direct
  play, direct stream and video stream copy together, retaining the audio-copy
  policy. Original prompts instead of automatically replanning; transcode and
  offline plans cannot enter this compatibility path. The current quality and
  recovery policy is owned by
  [Error and audio recovery](#error-and-audio-recovery). Fallback requests
  preserve the incoming backend and its capability data. A
  request whose backend is not AVPlayer is non-default even when all stream
  flags retain their defaults; its PlaybackInfo failure propagates rather than
  silently reinstalling the local direct-play plan.

## Audio activation and native mapping

- Requested and installed audio are separate. Detail/Series track state records
  explicit audio provenance at the UI boundary: an untouched resolved default
  routes `null`, while touch or D-pad selection routes the chosen non-null
  stream index even when it equals the displayed default. The state is keyed by
  item plus media source, and a source replacement or invalid selection clears
  that explicit provenance. Player resolves valid explicit route value,
  remembered explicit value, preferred language, Jellyfin default, then first
  track. Durable writes contain only an explicit route value, remembered
  explicit value, or an in-player audio action; response/native substitution is
  never persisted. The latest explicit/default request still drives PlaybackInfo
  and process-local memory, while picker selection and debug metadata use only a
  response-selected DirectStream/Transcode track or an exact native DirectPlay
  activation. A pending DirectPlay switch leaves the previous installed row
  selected. A response that authoritatively selects another audio stream
  replaces both requested memory and installed truth.
- Native embedded mapping filters to the correct type and embedded/external
  class, prefers exact stable source identity, then tries the filtered ordinal
  in deterministic native order and validates available metadata. Conflicting
  ordinal metadata may fall back only to one unique codec/language/label match;
  zero matches are not found and multiple matches are ambiguous. Neither result
  permits a closest-language or closest-title guess. Media3 sorts fully numeric
  colon-delimited ids numerically and excludes JellyScope sidecars/external
  groups; AVPlayer uses ready media-selection-group order and confirms the exact
  option identity; mpv prefers `ff-index` and sets `aid`/`sid` to the resolved
  `track-list/N/id`, never `ordinal + 1`. Subtitle codec comparison uses the
  canonical alias families. Audio comparison lowercases and trims, strips an
  `audio/` MIME prefix, then maps `eac3-joc`/`ec-3`/`ec+3` to `eac3`, `ac-3` to
  `ac3`, `dca`/`vnd.dts` to `dts`, `vnd.dts.hd` to `dtshd`,
  `mp4a.40.*`/`mp4a-latm` to `aac`, and `mpeg` to `mp3`; `dts`, `dtshd`, and
  `truehd` remain distinct. Unsupported Media3 text groups retain ordinal slots
  and are rejected only after exact resolution; **Media3** audio preserves
  supported-only ordering. Mapping outcomes carry a fixed, allowlisted reason for
  release-safe diagnostics. Native elementary-stream selection is **not proof of
  decoding**: the libvlc-family and mpv backends accept a `set*Track` and read the
  index back for any stream present in the container, whether or not they own a
  decoder for it, so they have no decode-capability signal at selection time.
  Desktop LibVLC has one additional fail-closed guard for response-derived
  embedded subtitles: only a DirectPlay plan in the current native Playing
  generation with a known response cohort whose size exactly matches the native
  candidate list may use the filtered ordinal without comparable labels. All
  other cases use the shared resolver. A successful setter call is not activation;
  desktop embedded-subtitle state becomes Active only after exact native selector
  readback.
- A DirectPlay audio track whose codec is not admitted by the active backend's
  DirectPlayProfile is treated as `Unavailable` **at selection time, with no
  native attempt**, taking the same [single DirectPlay-disabled recovery](#error-and-audio-recovery).
  Without this, an in-player switch to an undecodable codec selects successfully
  and then plays silence with video still running — the server validates the
  default track against the supplied profile, but never validates a mid-session
  switch. The predicate (`admitsDirectPlayAudioCodec`) is stamped per descriptor
  by the planner from backend-keyed capabilities, so it covers every backend at
  once; it fails open on absent capabilities/profiles and missing codec metadata,
  and an unlisted codec is inadmissible because a needless transcode still plays
  audio while a wrong admission plays none. The installed/default track is
  exempt — the server already validated it.
- Initial and in-player-switched DirectPlay audio publishes `Pending`. Its single non-extending
  three-second deadline begins only after Ready or a non-empty native track
  list, not during network preparation. Android LibVLC is the startup exception:
  it may begin playback while initial audio remains Pending because elementary
  stream discovery requires playback. Exact activation publishes `Active`.
  `Unavailable` triggers one independent re-plan at the captured position with
  DirectPlay disabled, preserving play/pause and ordered Stop then Start
  reporting. A DirectPlay response is rejected for that recovery, stale targets
  are ignored, and decoder/subtitle fallback budgets are unchanged. DirectStream
  and Transcode publish their response-selected audio as Active without native
  mapping.

## Progress Reporting

- Report playback start, periodic progress, pause, seek, stop, completion, and
  failure through the shared progress-reporting boundary.
- A per-owner `PlaybackReportingCoordinator` owns Start/pending state and
  ready-state retry, pause/unpause edges, periodic sampling, progress/Stop
  eligibility, stale Start rejection, duplicate Stop suppression, and final
  disposal for both `PlayerViewModel` and the tvOS playback presenter. Compose
  supplies its stable shared playback-state projection across controller
  replacement; the coordinator never retains a replaceable controller flow.
- `PlaybackReportingQueue` owns ordered ingress and local settlement, plus a
  separate ordered remote drain. Offline Start/Progress/Stopped acknowledge the
  durable local write without waiting for optional Jellyfin reporting. Online
  reports retain the existing primary-reporter result semantics. Each guarded
  remote call rechecks the
  current matching account, and local rejection never forwards a remote report.
- Start must succeed at the applicable local or primary-reporter boundary before that
  route accepts Progress or Stopped. A failed Start may retry on a later ready
  sample. Periodic TimeUpdate coalesces within a session/control segment; Start,
  Pause, Unpause and Stop preserve their order and the captured Stop position.
  The front drain settles an old Offline Stop before forwarding a new Online
  Start. Idempotent Close rejects new ingress, finishes accepted local work,
  then closes the remote drain after its forwarded work; owner cancellation
  cannot strand the final Stop.
- Offline Stop publishes an app-scoped settlement only after local persistence
  succeeds. Online Stop retains settlement after a successful or terminally failed
  primary-report attempt. The replayable settlement registry is
  keyed by `(serverId, userId, itemId)` and assigns its own app-global sequence
  (never a player-local reporting generation). Shared detail screens consume it
  through `ObservePlaybackStopSettlementUseCase` and silently refresh relevant
  data with sequence-deduped, trailing-edge coalescing; Series re-evaluates
  retained settlements as its displayed episode IDs arrive.
- Progress reporting posts JSON to `/Sessions/Playing`,
  `/Sessions/Playing/Progress`, and `/Sessions/Playing/Stopped` through the
  shared `JellyfinApi`; Jellyfin timestamps use 10,000,000 ticks per second, so
  positions use the shared `ticks / 10_000 = milliseconds` conversion.
- Playback progress `PlayMethod` comes from the active `PlaybackPlan` stream
  mode: online DirectPlay, DirectStream, and Transcode retain their matching
  values; the optional matching-account Offline side effect reports DirectPlay
  because Jellyfin knows the original item, not JellyScope's private artifact.
  PlaybackInfo `PlaySessionId` is used when the server returns one.
- Online playback restores Jellyfin progress. Explicit offline playback restores
  and updates the local download record under the contract in
  [Downloads And Offline](downloads.md#downloads-and-offline); it does not treat server
  progress as authoritative for that local session.
- Offline reporting to a matching currently online account is best effort only.
  There is no offline reporting outbox, durable delayed-sync queue, remote
  acknowledgement guarantee, server overlay, cross-device conflict resolution,
  or eventual-delivery guarantee.

## Error and audio recovery

- **[all]** Fatal errors use typed `PlayerUiState.Error` causes and retain the
  existing Retry/Close actions.
- **[all]** A cause-less `SourceVideoCopyUnsupported` refusal (the Original
  path), or an exhausted recovery whose cause is the planner's
  `SourceVideoCopyRejected`/`NoSupportedStream`, is deterministic Unsupported
  Media with `retryable = false`, so the dialog offers Close. A wrapper carrying
  the forced-transcode repository failure remains retryable Network, and an
  unknown/non-planning cause stays on that conservative Network path. The
  wrapper is classified by cause rather than treated as one universal network
  failure.
- **[all]** `PlaybackSessionRecoveryPolicy` is the single semantic owner for
  activation-first precedence, exact current-target validation, typed replan
  causes, and the independent network/audio/subtitle one-shot facts. Compose
  and tvOS execute its decisions locally so controller commands, notices,
  diagnostics, and stale replan cancellation remain shell-owned. A new item,
  playback-session start, or explicit Retry resets the policy; installing a
  recovery plan for the same session does not. Every replan carries its closed
  `PlaybackClientTrigger`. Immutable generation/item state rejects stale items
  and targets while keeping network, audio-target and subtitle-request budgets
  armed across replans. Activation checks precede decoder/unsupported failure;
  the separate network retry preserves the quality policy.
- **[all]** Policy-gated decoder, unsupported-media, health, and recovery
  behavior is owned by [quality policy](playback-policy.md#quality-semantics).
  A backend-qualified replacement re-plans from its own capability facts and
  keeps the selected policy; a same-plan network retry remains a separate
  one-shot retry.
- **[all]** A same-plan `retry()` re-prepares the controller's `lastPlan` with
  that plan's controller-specific retained subtitle asset, then consumes the
  pure `RetrySelectionPolicy`: retained audio is re-applied, and a concrete
  subtitle is re-applied only while its complete activation target still matches
  the plan. Android mpv, Android LibVLC, and desktop LibVLC reassert their
  already-distinct explicit Off state. Media3, AVPlayer, VLCKit, and desktop mpv
  keep ambiguous null unspecified, so prepare restores the plan subtitle rather
  than treating null as Off. Media3 deliberately keeps a live `lastPlan`
  subtitle target, so its in-player subtitle changes survive retry. Each
  controller retains its released checks, play intent, native preparation/order,
  and backend-specific position, timing, asset, and engine behavior.
- **[all]** A manual Auto-recovery notice offers **Choose lower quality**, which
  opens the in-player picker, plus the remaining stream actions and dismissal.
  A successful automatic quality recovery offers **Keep current quality**, **Try
  higher quality**, **Choose lower quality**, and **Dismiss**. Neither notice
  routes to Playback Settings. Any chosen row is current-playback only and a
  fresh playback resolves its defaults again.
- **[all]** A failed Fixed policy remains user-controlled: it offers **Choose
  lower quality**, **Try higher quality**, **Try Original**, and **Dismiss**.
  It never lowers automatically or sends the user to Playback Settings.

- **[all]** The policy is **accumulated qualifying measured duration**, which
  is what makes it backend-neutral. Evidence is tracked as the SPAN each
  measurement covers — `[arrival - intervalMs, arrival]` — because a measurement
  describes an interval that *ended* when it arrived. A measurement qualifies when
  its rate is at or above 2.0 dropped frames per second, status is Playing, and its
  **whole span** postdates both the last reset and the stall exclusion window: a
  Media3 batch can span a seek and still arrive after the 2 s exclusion has
  elapsed, and those drops describe playback already discarded. Spans are clipped
  to the rolling window, so an interval straddling the edge contributes only the
  part still inside it. Shared player logic passes qualifying spans to the pure
  evaluator, which sums their `intervalMs` inside a 60 s window and applies the
  shared post-start guidance budget once the 20 s sustained threshold is
  reached. Counting raw *reports* is invalid because Media3 reports 50-frame
  batches while LibVLC is polled: at 2 fps one Media3 report covers roughly
  25 seconds while one LibVLC report covers roughly one second, so equal report
  counts encode materially different playback durations and confidence.
  Duration is also
  burst-resistant: a long interval qualifies only if its *average* rate is bad, so
  a calm stretch with a late burst cannot claim its whole duration as evidence.
  Accumulation clears on leaving Playing, on session start/retry/replan/queue
  switch, and in `installController` so a backend swap starts fresh. Wall-clock
  sampling cannot work here: Media3 notifies only per 50-frame batch or on
  `onStopped()`, so at 2 fps a report arrives roughly every 25 seconds and a
  fixed short window mostly observes no change. Such a window therefore fires
  only for higher sustained rates than its documented threshold. Runtime
  diagnostics can expose `droppedVideoFramesPerSecond`; structured health logs
  retain bounded duration/sample evidence rather than every raw rate sample.
- **[all]** A `Network` playback failure gets exactly one automatic
  position-preserving replan with the default request policy before becoming
  fatal. Offline plans are excluded. The one-shot flag is per item playback
  session, reset at playback start and explicit retry, so replacing the
  installed plan cannot create a loop. Network failures never consume the
  compatibility-attempt budget; a still-down network surfaces through the
  replan's planning failure.
- **[all]** Initial DirectPlay audio mapping alone gates startup. Pending
  mapping starts its deadline only after native readiness; failure gets one
  independent DirectPlay-disabled recovery and never consumes decoder or
  subtitle fallback.

- **[all]** Non-fatal `audioUnavailable` keeps video playing, shows a transient
  explanation, and retains an audio-off glyph until recovery.

## 4. Runtime evidence and recovery ownership

`PlayerController` reports native facts through narrow contracts:

- state and runtime diagnostics are `StateFlow` snapshots;
- dropped frames and first-video-output observations are event flows scoped to
  the controller prepare generation;
- iOS VLCKit prepare/resume/seek observations additionally carry a transition
  sequence within that generation, so Started, Presented, and TimedOut settle
  only the exact target transition;
- measurement capabilities state whether a fact is reliable and name its
  evidence class (`NativeFirstOutput`, `DisplayedPictureCounter`,
  `ReadyForDisplayBridge`, or unsupported).

`PlaybackHealthSessionCoordinator` owns the [health evidence lifecycle](#playback-health-and-guidance),
including monotonic timers, current generation, first-output deadline and
sanitized health summaries.

### Mandatory mpv recovery

Android mpv sustained slow-software recovery is separate from optional playback
health signals and warnings. Its dedicated ViewModel state pauses playback and
requires an explicit choice: Switch to ExoPlayer, Keep playing with mpv, or Stop
playback. Back/outside taps cannot dismiss it, and neither warning preferences
nor diagnostic collection can suppress it. Continue consumes the prompt for the
current prepare; a fresh prepare can collect fresh evidence. A confirmed switch
uses the existing explicit backend-switch planner/installer, preserving current
quality policy, origin and runtime cap; Original is not silently relaxed.
Selecting Switch immediately hides the dialog and presents the player loading
spinner while planning/preparing. Planning failure keeps playback paused and
restores the dialog with a failure explanation and explicit retry/continue/stop
choices. Stop/disposal/new playback invalidate
old dialog actions. Thresholds and capture fields live in
[Android mpv](android-playback.md#android-mpv-backend).

### Recovery coordinator ownership

`AutoPlaybackRecoveryCoordinator` is pure common-domain logic: it has no
coroutines, controller references, persistence calls, navigation, or strings.
It owns the two one-shot budgets and produces `CompatibilityReplan`, `LowerTo`,
a user prompt, a recovered notice, or no action. `LowerTo` requires the typed
explicit-session-Auto authorization; inherited Auto receives the typed manual
choice prompt without spending the quality budget. The ViewModel and
`TvPlaybackSessionPresenter` own execution, position preservation, policy
persistence, notice state, and stale-work cancellation. While the Compose
ViewModel is in PiP, its one pending-recovery slot retains the strongest trigger
in this locked order: DecoderFailure, UnsupportedMedia, NoVideoOutput,
CumulativeBuffering, RepeatedStalls, then DroppedFrames. Equal severity keeps
the first trigger. Leaving PiP clears and handles one retained trigger, while a
new item or queue launch and retry clear stale pending work. Measurement remains
lifecycle-safe while recovery presentation is deferred. The one retained trigger
still enters `AutoPlaybackRecoveryCoordinator`; arbitration grants no new
stream-change authority, so Original and inherited Auto keep their existing
manual prompt contracts.

The [session recovery policy](#error-and-audio-recovery) owns exact-target
precedence and the independent retry budgets; shells retain execution ownership.

`PlaybackReportingCoordinator` is the per-session-owner common coordinator for
Start readiness and retry, pause/unpause edges, periodic progress sampling, and
Stop/disposal settlement. It composes the existing `PlaybackReportingQueue`,
which owns a serial ingress/local-settlement drain and a serial remote drain.
The domain reporting capability separates local durability from guarded remote
forwarding without exposing the data router to presentation. The Compose
owner supplies its stable shared playback-state projection so controller
replacement cannot strand reporting on an outgoing controller flow. tvOS creates
its reporting coordinator only after concrete installation and uses that
installed controller flow for the session. Both shells retain their general
previous-status, completion, queue, navigation, and native-command semantics.

Diagnostics are allowlisted and backend-qualified. They state policy,
constraint/client limiter, wire representation, capability result/reason,
effective transcode cap, configured VLC default state, recovery intent/result, and
first-video-output evidence/observation. A backend-readiness record may state
the closed resource policy and bounded value that were applied, but must not
claim a native decoder module the backend API cannot expose. Disabled and
no-cap states are rendered explicitly; a protocol sentinel is never presented
as a user quality. Diagnostics never include stream URLs, headers, tokens,
server/user IDs, titles, paths, or raw native errors.

## VLC terminal policy

- VLC-family end-of-stream decisions are owned by the pure commonMain kernel
  `resolveVlcTerminalStatus` (+ `hasVlcPlaybackProgressed`), never by live
  native reads: at `EndReached` libvlc's clock is already stopped and reports
  -1.
  - libvlc emits `Stopped` immediately after `EndReached`. That trailing
    `Stopped` must **preserve** a `Completed` status; mapping it to `Buffering`
    cancels the Up Next countdown keyed on `Completed`.
  - The sample that **resolves** a seek is discarded via
    `VlcEndOfStreamEvidence.ignoreNextSample()`, because a resume seek can
    overshoot its target by seconds and that position is seek-derived, not proof
    of playback. Every path that clears the pending-seek state must call it —
    the controller resolves seeks both in the position-event handler and in the
    fallback probe, and only a later, purely playback-driven sample may arm the
    latch.
  - `EndReached` is accepted as completion only when playback **provably
    progressed** past the prepared/resume baseline (`VlcEndOfStreamEvidence`
    tracks a durable `progressBaselineMs`, and progress requires the player to
    be playing with no seek in flight). `isVlcEndedNearCompletion` alone is not
    enough — it accepts an unknown duration, and a resume seeded near the end
    satisfies any "position > 0" check before a frame renders.
  - A rejected `EndReached` publishes `Paused` at the preserved position (which
    also stops the progress ticker so it cannot overwrite that position) with no
    automatic recovery, and a one-shot flag makes the immediately following
    `Stopped` preserve that state while any later, unrelated `Stopped` still
    maps normally.
  - A genuine completion publishes the duration as its position. Reporting a
    finished item as stopped at zero would clear its watched state on the
    server.

## Why

- **Native observations share teardown ownership.** A diagnostic getter can
  block like stop. Serialize snapshots with native transitions off Main, publish
  cached state, and gate by media/generation so observation cannot freeze input
  or mutate a replacement.

- **Capability rejection retains its evidence.** A generic unsupported result
  cannot explain which source comparison failed. Closed reasons, numeric bounds,
  and provenance distinguish app-enforced platform evidence from independent
  native decoder performance without exposing media identity.

- **Hot native clocks are reduced at their source.** Generation-bound latest
  samples for mpv, native freshness for LibVLC, and a clock-only tvOS projection
  bound work without delaying lifecycle or reporting. Source-side gates reject
  late outgoing callbacks; buffer diagnostics use applied clock/cache facts, and
  Media3 samples during pause because a still playhead does not mean a full buffer.

- **Backend switching is a guarded controller replacement.** Planning and
  intent validation happen while current playback and reporting remain
  authoritative, then one release-first installation commits. Persisting the
  switch, importing outgoing health facts, or retaining two live controllers
  would blur session and ownership boundaries.

- **Recovery and reporting decisions are shared without moving lifecycle
  ownership.** Common immutable coordinators keep precedence, PiP arbitration,
  budgets, reporting order, and settlement consistent. Jobs, notices, native
  commands, navigation, and stale cancellation stay with each presentation
  owner; a universal stateful player would couple unrelated lifecycles.

- **Controller installation is serialized.** A current launch waits for an
  earlier installer and rechecks generation and item authority. Dropping a
  launch while installation is busy can leave the current item without a
  controller.

- **Same-plan Retry shares validation, not mutable replay state.** The common
  policy validates exact audio/subtitle targets and explicit Off only from the
  vocabulary each controller already owns. Assets, timing, play intent,
  reinitialization, and native command order remain controller-specific.

- **Health policy is selected by the application surface.** Android TV uses the
  shared Actionable policy, while desktop and tvOS remain Advisory; placing that
  choice on `PlayerController` would conflate measurement support with
  presentation behavior. Android LibVLC's one-frame dav1d delay bound is a
  backend resource safeguard, not a source, quality, or device-profile rule.

- **Initial audio activation has one shared decision table.** DirectPlay waits
  for exact native mapping while other modes begin active, with two deliberate
  renderings for controller state differences. Centralizing the table prevents
  controller copies from drifting without forcing unrelated lifecycle state
  into a common base class.

- **Health evidence is qualified rather than globally tuned.** Backend-specific
  first-output thresholds, advancing-clock checks, generation exclusions, and
  evidence-window restarts prevent slow start, seek, resume, or surface work
  from becoming false no-output or stall recovery. Restarting the window retains
  one-per-session signal and recovery budgets.
