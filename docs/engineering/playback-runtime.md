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
  runtime availability. For shared online Apple Auto launches, the descriptor
  uses the container, first video's codec/range, and effective launch audio
  selection. Audio lookup uses actual nonnegative indexes occurring exactly once
  across all Audio entries, including external audio. It falls back to the first
  default then first eligible stream; the chosen codec stays unknown if missing,
  and no eligible stream also leaves it unknown. That durable initial backend remains authoritative for
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
  fails, never the Android automatic-health fallback. Installed audio fallback
  runs after installation releases ownership and any software-decoding switch
  result is recorded; a follow-up replan cannot turn that completed switch into
  a failed switch report.
- Android's ExoPlayer path is Media3 1.9.0 with decoder fallback and
  the Jellyfin FFmpeg extension (`org.jellyfin.media3:media3-ffmpeg-decoder`
  from Maven Central); FFmpeg is an ExoPlayer decoder extension, not a third
  backend. Media3 is pinned to 1.9.0 because the published decoder's version
  tracks Media3 and 1.9.0 is the newest published pairing — so no local NDK
  build is required. Media3's server-facing audio capability is the union of
  its MediaCodec probe and a process-cached, fail-closed check that this shipped
  extension is available and supports E-AC-3. Only that E-AC-3 result is added;
  the extension's broader decoder list is not enumerated.
  The `audio/raw` probe gates presence and supplies the channel ceiling; it does
  not prove PCM decoding. Its eight exact input names are qualified against the
  Media3 1.9.0 Matroska extractor and PCM output path and admitted only in MKV
  profiles. Requalify these paths when changing the Media3 pin.
- Android's LibVLC path is the in-process libvlc 3.7.7 controller/surface bridge
  and is used only when selected and available. An eligible startup/prepare
  failure in mpv or LibVLC
  while streaming from Jellyfin can end that controller session and request a
  fresh ExoPlayer-qualified plan at the last confirmed position. Installed
  offline playback never enters that recovery. Where offline policy permits,
  controller construction may fall back before preparation using the existing
  offline plan; a required offline backend cannot fall back.
- Android phone and TV startup warm LibVLC’s fontconfig disk cache once per
  process on `Dispatchers.IO`. First LibVLC prepare joins that same attempt
  before opening media. A dedicated temporary engine stays alive until the
  blocking `buildFontCache()` call returns and is released afterward; controller
  teardown cannot race that engine. Failure or an unwritable cache falls back
  to normal text-renderer initialization without retrying for every item.
  Release diagnostics record Started and Ready/NotWritten/Failed, with only a
  sanitized exception class. This does not establish a measured startup gain.
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
  describes the selected Android probe candidate, not the component opened by
  LibVLC or measured limits of its software decoder. Unprobed AV1 retains the
  pinned engine's unknown bounds. The shared mpv/LibVLC projection also records
  closed diagnostic reasons for retained declarations, missing probe facts,
  incomplete tuples not projected, and complete or permitted partial tuples
  projected. Probe classification is retained separately from engine decode
  evidence; neither diagnostic field changes admission or recovery. mpv still
  requires a complete tuple, while LibVLC keeps its existing partial-limit rule.
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
  is inert when another decoder module opens, so this is a pinned libvlc 3.7.7
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
  after audio output fails. Its optional `healthStatusOverride` supplies health
  status only while the public status is `Buffering`; null uses the public
  status. UI, reporting, failure and terminal handling retain the public status.
- The AVPlayer controller derives buffered position from
  `AVPlayerItem.loadedTimeRanges` by reporting the end of the loaded range
  covering the current playhead. It derives `Buffering` from AVFoundation's
  stall notification, buffer-empty and likely-to-keep-up flags, and
  waiting-to-play time-control status.
- The VLCKit controller publishes `bufferedPositionMs` equal to its published
  position, so its buffer-ahead is unavailable, not a measured empty buffer.
  Its `Buffering` combines native buffering progress under play intent with the
  prepare/resume/seek transition hold owned by
  [iOS recovery](apple-playback.md#ios-recovery). Once the transition clock has
  reached the target and advanced, health uses the ordinary native status
  mapping during the remaining picture-readiness hold. The spinner and pinned
  target remain unchanged. Before that clock advance, health uses public
  `Buffering`; genuine native buffering after the advance remains measurable.
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
message-only startup budget. VLCKit's health status can end that accounting
before picture readiness: it represents native transport plus an earlier clock
advance at the target, not first-video-output proof. Post-start guidance uses one
shared budget and the first admissible signal wins: one buffering interval of at
least 5 seconds,
10 seconds of buffering in a rolling 60-second window, three post-start
`Playing`→`Buffering` stalls in 60 seconds, or sustained dropped-frame evidence
of at least 2 frames/second for 20 seconds within 60 seconds. Existing dropped-
frame measurements remain duration spans, not report counts; stale batch spans
and spans crossing the 2-second exclusion after seek, prepare, re-plan, resume,
or backend replacement are rejected or clipped before they qualify.

`PlaybackHealthSessionCoordinator` owns generation-scoped timer evaluation.
Failure, completion, stop, item switch and disposal cancel its jobs; a stale
callback cannot mutate a new item session. During the Compose ViewModel's
controller installations, health evaluation is suspended and all three timers
are canceled. This covers timing restoration, controller construction,
serialized backend switching and same-controller replans, including final
planning while the outgoing controller still exists. Initial switch preflight
remains outside that suspension. Matching prepare identity, positive output and
Started/Presented transition facts remain admitted; they do not spend warning
or signal slots and may have no replay.

Settlement restarts rolling buffering, stall, dropped-frame and no-output
waiting evidence before lifting suspension. It preserves previously spent
signal/guidance slots, Auto recovery budgets and current-prepare positive facts.
Health receives the current owned, prepared controller's state only when the
installing launch still owns it. An ended session stays ended. Without a
qualified snapshot, the next prepare admission or observed state can rearm
timers. Slow-startup timing still counts from launch: an unstarted session may
show that message immediately after settlement, without automatic recovery.

A typed TimedOut delivered during suspension is discarded before its transition
or no-output slot is spent. A fact delivered afterward still follows existing
prepare/transition identity checks, including a retained transition whose
fact was produced while busy. Native observation timestamps do not share the
coordinator's clock and are not compared to an installation cutoff. The current
VLCKit producer lacks reliable first-output capability, so it cannot enter this
health signal path; a future reliable producer needs an explicit clock or
identity contract before promising stronger stale-fact rejection.

First-video-output is evaluated only when video is expected, playback is actively Playing or
progressing, a reliable current-prepare observation has been armed, and the
session is outside seek/replan/resume/surface/backend/background/PiP-transition
exclusions. A positive Media3 first-frame fact, mpv software-frame publication,
VLC displayed-picture counter, or AVPlayer ready-for-display bridge is
sufficient; mpv OpenGL, Vout, and surface
attachment is not. The deadline begins at active playback, not request launch.
The ViewModel reads both capability sets from the same installed concrete
controller on each evaluation. It reports no measurement capabilities while
pending or between release and replacement. Capability is checked when the
deadline is armed and again when it fires; capability loss or an unsupported
backend cancels the signal rather than manufacturing a no-video recovery.

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

App-owned seek and play/pause commands **restart the evidence window**:
accumulated buffering intervals, stall events, and dropped-frame spans are cleared
so evidence gathered under previous conditions cannot reach a threshold.
Restarting during an active buffering episode reopens the interval at the boundary — the retained status is
unchanged, so the ongoing wait remains measurable. Once-per-session emission
latches survive a restart, so a signal kind reports at most once in an item
session. Auto's compatibility and quality budgets also survive and remain
one-per-item-session.

VLCKit PiP supplies separate snapshots for accepted seeks and owner-admitted
play/pause commands. Play publishes before the verb when it starts a playback
attempt, including from Failed or Completed with retained play intent; Pause
publishes when it clears play intent. These command facts do not prove native
acceptance or output. Repeated Play while intent is true and status is Playing
or Buffering, and Pause while intent is false, publish nothing. Another Play
before VLC leaves Stopped can qualify again, matching ordinary app Play.

Before evaluating a status sample or deadline, the coordinator independently
acknowledges each snapshot with a matching prepare epoch and increasing
sequence, then restarts evidence once if either is new. Pending commands may
coalesce; the existing two-second exclusion begins at consumption. Reading the
same snapshot again does not extend it. An app-owned restart also acknowledges
both pending facts. Session start resets both watermarks; within that session,
only an actual new prepare or controller admission resets them. Native teardown
clears published snapshots without resetting their lifetime sequence counters.

This restart contract covers VLCKit PiP. AVPlayer native PiP play/pause/skip and
Apple audio interruption/route-loss intent changes remain unassessed here.
Snapshot admission, rejection and consumption are not separately logged.

The bounded health summary additionally records which gate suppressed a would-be
warning (`postStartGuidanceShown`, `noVideoOutputGuidanceShown`, the resolved guidance
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
  preserve the incoming backend and its capability data. PlaybackInfo request
  failures propagate for every backend and quality mode, including uncapped
  AVPlayer Auto. A failed request never installs an unverified local direct-play
  plan or initiates the source-capability forced-transcode retry. Successful
  responses still undergo source-copy preflight and its policy-bounded recovery.

## Audio activation and native mapping

- Source audio choices, initial codec lookup and response-derived embedded Audio
  descriptors require actual nonnegative indexes occurring once across all
  case-insensitive Audio streams, including external entries. Missing, negative
  and repeated identities are omitted. Choice ordinals remain relative to all
  Audio; native descriptor ordinals and cohort sizes remain relative to embedded
  Audio before omissions. External entries can disqualify a duplicate identity
  without adding an embedded slot. Both descriptor overloads retain the same
  identities and positions; a false DirectPlay-switch admissibility stamp does
  not remove an eligible descriptor for the server-approved initial track.
  A nonnegative response-selected audio index remains authoritative even without
  a descriptor or Audio metadata, including sparse DirectStream/Transcode
  responses. An unmappable DirectPlay target uses the existing bounded Audio
  recovery, which may convert delivery or fail on a constrained server; an absent
  target creates no synthetic recovery obligation. Download drafts and offline
  descriptors retain their separate rules.
- Requested and installed audio are separate. Detail/Series accept a remembered
  default only when its Audio index is offered by the current source; otherwise
  they use preferred language, Jellyfin default, then first track. This does not
  change remembered records or memory-tier precedence. Their track state records
  explicit audio provenance at the UI boundary: an untouched resolved default
  routes `null`, while touch or D-pad selection routes the chosen non-null
  stream index even when it equals the displayed default. The state is keyed by
  item plus media source, and a source replacement or invalid selection clears
  that explicit provenance. A fresh launch resolves valid explicit route value,
  remembered explicit value, preferred language, Jellyfin default, then first
  track. Durable writes contain only an explicit route value, remembered
  explicit value, or an in-player audio action; response/native substitution is
  never persisted. The latest explicit/default request still drives PlaybackInfo
  and process-local memory, while picker selection and debug metadata use only a
  response-selected DirectStream/Transcode track or an exact native DirectPlay
  activation. A pending DirectPlay switch leaves the previous installed row
  selected. A response that authoritatively selects another audio stream
  replaces both requested memory and installed truth. Same-target Kids
  Retry/Replay can retain playback independently of explicit intent under the
  [recovery contract](#error-and-audio-recovery).
- Native embedded mapping filters to the correct type and embedded/external
  class, prefers exact stable source identity, then tries the filtered ordinal
  in deterministic native order and validates available metadata. Conflicting
  ordinal metadata may fall back only to one unique codec/language/label match;
  zero matches are not found and multiple matches are ambiguous. Neither result
  permits a closest-language or closest-title guess. Media3 sorts fully numeric
  colon-delimited ids numerically and excludes JellyScope sidecars/external
  groups. Those extractor IDs are ordering hints, not Jellyfin stream indexes;
  Media3 resolves audio and subtitles by ordinal with metadata validation, then
  unique metadata fallback. AVPlayer uses ready media-selection-group order and
  confirms the exact option identity; mpv prefers `ff-index` and sets `aid`/`sid` to the resolved
  `track-list/N/id`, never `ordinal + 1`. Subtitle codec comparison uses the
  canonical alias families. Audio comparison lowercases and trims, strips an
  `audio/` MIME prefix, then maps `eac3-joc`/`ec-3`/`ec+3` to `eac3`, `ac-3` to
  `ac3`, `dca`/`vnd.dts` to `dts`, `vnd.dts.hd` to `dtshd`,
  `mp4a.40.*`/`mp4a-latm` to `aac`, and `mpeg` to `mp3`; `dts`, `dtshd`, and
  `truehd` remain distinct. Before this comparison, Media3 maps raw audio's
  PCM encoding to its exact qualified `pcm_*` name, preserving `codecs` or the
  MIME fallback for other formats and unknown encodings. Unsupported Media3 text groups retain ordinal slots
  and are rejected only after exact resolution; **Media3** audio preserves
  supported-only ordering. Mapping outcomes carry a fixed, allowlisted reason for
  release-safe diagnostics. Native elementary-stream selection is **not proof of
  decoding**: the libvlc-family and mpv backends accept a `set*Track` and read the
  index back for any stream present in the container, whether or not they own a
  decoder for it, so they have no decode-capability signal at selection time.
  Desktop LibVLC Audio uses the original response cohort size or complete saved
  offline Audio count for its positional shortcut. DirectPlay and Offline with
  an equal native count keep the original ordinal without waiting for Playing;
  a known count mismatch in either direction permits only a unique comparable
  label match. Unknown cohorts retain descriptor-list count shortcuts and the
  shared resolver.
  One matching sole planned track with an effective source count of one may
  confirm after current-generation Playing when VLC exposes at most one track,
  without a setter or readback; a known cohort also requires DirectPlay or Offline.
  Other Audio confirmation requires exact native selector readback.
  Offline Audio count mismatch never uses positional fallback, even for complete valid
  downloads. Outside the true-singleton exception above, lack of a unique
  comparable label makes bounded attempts end in Audio Unavailable with no
  automatic session recovery; an already-playing backend can continue its
  default audio. Saved labels prefer display titles while VLC
  supplies its own track names, so label matching cannot be relied on. Unavailable
  does not force a stop or guarantee a visible error.
  Desktop embedded subtitles retain a separate shortcut: DirectPlay in the
  current native Playing generation with an equal known response cohort may use
  the original ordinal without comparable labels. Their unchanged shared fallback
  can also accept an ordinal when no available metadata conflicts. A successful
  subtitle setter alone is not activation; Active requires exact native readback.
- DirectPlay audio switches use the negotiated effective policy, stamped by
  `admitsDirectPlayAudioSwitch` on response-derived descriptors. A known codec
  must occur in a matching container profile after effective codec filtering;
  comma-separated container names match by token. Known positive channel counts
  must fit the effective ceiling, using the exact codec spelling first and the
  strictest family-alias ceiling otherwise. A rejected descriptor becomes
  `Unavailable` without a native attempt and takes the existing
  [single DirectPlay-disabled recovery](#error-and-audio-recovery).
  Unknown codec metadata and missing or non-positive channel counts retain
  compatibility in every audio mode; absent policy/profiles also remain
  admissible, and unknown containers do not narrow profiles. This deliberate
  unknown-metadata behavior does not prove native downmix or mirror required
  server channel conditions. `allowAudioStreamCopy=false` alone adds no local
  rejection: compatible AAC/MP3 stereo tracks can still switch natively in
  Stereo PCM mode. The server-approved initial track remains exempt.
  Recovery may end in terminal `UnsupportedMedia` if transcoding is denied or
  the response remains DirectPlay. For example, AVPlayer's six-channel ceiling
  now sends known 7.1 switches through recovery; it cannot guarantee playback
  on a server that disallows the required conversion.
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
- **[Compose]** `SourceVideoCopyUnsupported` with no cause or with immediate
  cause `SourceVideoCopyRejected`/`NoSupportedStream` is deterministic Unsupported
  Media with `retryable = false`, so the dialog offers Close. A wrapper carrying
  a forced-transcode `RemoteRequestFailed` with `isNetworkFailure = true`
  remains retryable Network; other unknown/non-planning causes map to Unknown.
  Bare `NoSupportedStream`/`SourceVideoCopyRejected` also remain Unknown. The
  wrapper is classified by its immediate cause rather than treated as one
  universal network failure.
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
- **[Compose]** The installed-activation handoff uses one current controller
  snapshot, overlaying eligible missing audio mapping and unavailable subtitle
  facts together. One policy decision preserves audio-first priority and leaves
  the other allowance available for a later current plan or state. Startup and
  post-replan paths can supply both facts; current explicit-switch intent
  validation excludes a planned-unavailable subtitle replacement, so that
  switch handoff supplies installed audio fallback. The handoff requires the
  captured controller and prepared identity, current caller authority, and
  released installation ownership, including an unlocked mutex. Same-item
  Error Retry supplies current logical activation facts separately from its
  captured, possibly null installed identity; ViewModel-side diagnostic,
  speed and style copies alone do not become prepared authority. A controller's
  own prepared retry copy can qualify through the admission checks below.
  Ordinary Error Retry also reconsiders a present switch-inadmissible audio
  descriptor when its exact target still records an executed, unresolved audio
  fallback. An exact current native Audio confirmation resolves that record,
  including when the installed index is unchanged or the collector is pending.
  The switch stamp alone does not invalidate server-approved initial audio. Startup and post-install handoffs keep their existing admission rules.
  A launch-owned marker blocks recovery during synchronous startup `prepare()`
  and same-plan native `retry()`. Failed snapshots wait until return; non-Failed
  status, health and reporting observations continue. The marker clears in
  `finally`, then the caller rechecks ownership. A reentrant Stop cannot install
  or play an abandoned startup result. Retry additionally checks the logical
  plan, selection generation and replan request before health binding and again
  before its handoff; a newer owner wins. A still-current synchronous Failed
  clears installed authority and restarts the existing state collector, or
  starts one when absent, for ordinary failure handling. An owned fast Retry
  can restore missing installed authority from the controller's `retainedPlan`
  only after a changed non-null prepare epoch, a non-Failed snapshot, and a
  matching item and nonblank source. The actual controller snapshot, rather
  than the logical plan copy, restores seekability, backend selection and
  quality-change preservation. The captured expected identity advances with
  that assignment, the screen refreshes immediately, and ownership is checked
  again before activation fallback. Existing non-null authority is unchanged.
  Missing/unchanged epochs, unknown retained plans, failed preparation and
  item/source mismatches do not grant missing authority. This establishes a
  newly admitted preparation, not native readiness or output. A native Retry
  that publishes nothing can still rehandle the
  old Failed snapshot with the reset budgets.
  Startup captures its request token after the plan becomes authoritative.
  Startup and accepted same-item Error Retry reconsider current non-Failed
  native unavailability alongside planned gaps, after binding the returned
  prepare epoch and arming health observation. Matching native selections are
  retained by the backend; Retry does not replay every selection.
  Only VLCKit's Retry handoff substitutes the logical plan's start position for
  its temporary paused native zero and follows ViewModel play intent rather
  than that transitional pause. Later ViewModel pause/play revisions still win;
  a prior pause made only through native PiP, an interruption or route loss is
  not preserved by this recovery replacement. Other backends retain their
  existing snapshot behavior, including Android mpv's current retry position.
  Ordinary VLCKit Start/Pause reports at zero and post-replan zero snapshots
  remain separate from this handoff's corrected Stopped position.
  A shown Error retains its first cause across later native failures and
  Content projections. Retry, an accepted queue change and existing explicit
  Loading transitions reopen the screen. Non-forced replans clear Error only
  at the guarded replacement install; reused-controller startup clears it at
  the final owned prepare after timing restoration. A non-forced replan that
  never installs keeps the first Error. A full Retry that fails before prepare can
  retain an earlier native cause and its Retry eligibility. Stop or ownership
  loss after reset does not restore that Error; Loading can remain until the
  next native update. During restoration, Error can remain visible with Retry
  refused and Close using deferred Stop. Native observation, reporting and
  recovery continue; forced-play recovery keeps its existing early Loading.
  Error retains TV display-mode reset, pins the Kids watch page and hides
  Up Next; final-item completion can still close the player. Software recovery
  can overlay Error and refuse Retry. Content projections are still built
  before the reducer discards them. This handoff does not replay the full state
  handler or serialize later collector recovery. Like other immediate handoffs,
  it omits the full
  collector's terminal RetryScheduled diagnostic; a later collector delivery
  may report the already-consumed fact normally.
- **[tvOS]** Startup, post-replan preparation and same-plan Retry hand missing
  DirectPlay audio descriptors and eligible planned-unavailable subtitles to
  one audio-first policy decision. The handoff requires the exact installed
  plan, current item, controller and selected source, with startup admission
  open; recovery checks admission before spending an allowance. It uses the
  plan's start position because paused VLCKit preparation can report native
  time zero. Selected external audio and ambiguous response Audio identities
  lack an embedded descriptor; this fallback establishes no native codec
  incompatibility. Retry resets the
  policy allowance; paused AVPlayer Retry can resume through Loading.
  Startup and replan failures use a private copy of Compose's planner-error
  classifier; check both helpers when changing its immediate-cause mapping.
  Unlike Compose's Audio-fallback catch, tvOS preserves typed classification
  even though each Audio fallback now captures its own request target.
  Failed retains its first cause, including a missing native cause, across
  later failure and state projections while other native fields keep updating.
  Accepted start/item/replan Loading clears it; replacement installation also
  clears a failure that arrived after planning began. Existing early replan
  Loading can clear an earlier failure even if planning never installs.
  AVPlayer may remain Audio-gated; VLCKit may continue unconfirmed default
  audio with Pending activation. No native Stop is implied.

  A failed or pending user-requested quality, track or local-subtitle change,
  or an out-of-window seek, can leave Failed over the continuing previous stream.
  Same-plan Error Retry cancels the earlier replan and invalidates its pending
  subtitle fallback before resetting recovery allowances and reporting. A late
  result or error from that cancelled job cannot replace the Retry attempt.
  The cancelled replan's change is abandoned, but its selected menu value and
  already-saved track choice remain. A local-subtitle check not yet replanning
  can still finish and submit its choice after Retry. Retry reloads the installed plan from its own start
  position, possibly rewinding, and starts a new reporting session; Close/Back exits. Remote
  Play/Pause can still control the hidden stream. tvOS retains Retry for
  UnsupportedMedia. It has a dismissible automatic-recovery prompt, which can
  appear above Failed, but lacks Compose's dismissible change-rejection
  notice. Preserving healthy playback after a rejected change remains open.
  Failed can block completion auto-close while autoplay still advances.

  App-local subtitle activation failure, deletion or a missing picked file
  remains nonfatal: the producer disables the file, saves Off and sets its
  passive notice. Its replan failure or rejected DirectPlay result republishes
  actual native state rather than manufacturing Failed; a real native Failed
  remains visible. Both planner failure catches check cancellation after their
  diagnostic and before any publication, so a cancelled exceptional result
  cannot acquire a newer attempt's Failed state. The local-file branch reads
  the controller only after that check. It never requests ForceEncode.
  This request can subsume pending quality/track choices or spent Audio
  recovery; it adds no recovery arbitration. Completion during that request
  can project Loading, and a restored native Failed lacks the full terminal
  diagnostic. Native/manual acceptance remains outstanding.

  The Audio fallback request disables DirectPlay. Any later
  DirectPlay replan is rejected while its Audio recovery target remains
  recorded, during the fallback or after it fails, until a new item, an explicit
  Audio selection, a non-DirectPlay install, or exact native Audio confirmation
  for the recorded current-plan target and item. The collector retires that
  record; Retry also samples confirmation before native re-prepare can replace
  it with Pending. Ordinary Error Retry additionally treats a present
  switch-inadmissible descriptor as a gap only for that exact unresolved
  fallback target. Server-approved initial Audio stays exempt without a record;
  startup and post-install handoffs keep their existing admission rules.

  Each Audio fallback captures its own target. Exact resolution, a changed
  target or item, or closed/start admission rejects its late planner error,
  DirectPlay result, and replacement before and after reporting Stop. A current
  native confirmation also rejects the request before collector delivery.
  If resolution occurs while reporting Stop is suspended, dropping installation
  leaves native playback continuing but progress, pause/unpause and final
  disposal Stop unreported until another install, explicit Retry or item change.
  Resume can remain at the fallback's captured position. Reporting restart is
  not part of this handoff. Collector recovery can preempt the fallback, and an
  Original failure prompt can coexist with its replacement; this handoff does
  not serialize those paths. Retry cancels pending replans, while broader
  recovery arbitration and native callback ownership remain separate.
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
- **[Compose]** Ordinary Retry refuses an active launch while queue identity is
  unsettled, before changing recovery admission, loading, generations, budgets
  or native playback. This includes repeated Retry during a full rebuild. A
  cancelled child request can briefly keep the launch active after Error is
  published; Retry during that interval is ignored until the job ends. Kids
  delegates to its own selector before this check. Disposed owners, pending Stop
  and installation ownership retain their existing refusals.
  Once the launch ends, unsettled identity forces full startup even when the
  retained plan names the current item. A failure before the stabilizing plan
  install keeps its typed Error and unsettled identity. Native preparation can
  fail after queue identity settles; the existing null-installed-plan recovery
  path remains unchanged. Settled matching-item Retry keeps the native fast path.
  Full Retry replaces the existing launch job, so Stop, disposal and newer
  launches can cancel it. Ordinary online startup records its raw item, source,
  audio, subtitle and start-position request before resolution. Full Retry
  snapshots that item and request after admission and reuses them, preserving
  explicit route choices through failure without turning resolved runtime
  values into new explicit choices. Every queue handoff, including the current
  row, records its own omitted source/audio/subtitle choices and zero position;
  returning to the initial item does not restore the constructor's old choices.
  Fresh detail and source-qualified stored selections still resolve omitted or
  invalid inputs under the [selection contract](subtitles.md#selection-and-delivery).
  Offline full Retry keeps its artifact-track retention. Kids online Retry/Replay
  carries current playback choices in an attempt-local hint, separate from the
  target's explicit launch and later accepted in-player track choices. A hint
  applies only when the same source still resolves; its audio and subtitle values
  are validated against fresh options or local-asset context. It steers playback,
  not explicit audio constraints or durable preference writes. Unlike a fresh
  launch, Retry/Replay can retain a subtitle fallback even when a raw explicit
  choice becomes valid after metadata changes; only that validated explicit
  choice is saved. Missing local assets do not turn fallback Off into explicit
  intent. A changed source discards the hint and uses existing explicit, stored
  and default resolution with track-membership validation; matching indices do
  not establish semantic identity across versions. Source selection, played-position
  proof, Replay zero and offline restart retain their existing owners.
  Queue settlement does not prove successful native preparation or preserve an
  outgoing source merely because the item ID matches.
  Normal detail and planning failures, and offline record, context, backend and
  install results, recheck cancellation and launch authority before publication.
  An obsolete install failure after deferred Stop leaves the UI projection
  intact; current failures retain their errors and diagnostics. These checks
  qualify named boundaries, not every mutable launch-state write.
- **[Compose]** Audio, subtitle (including Off), local-subtitle, audio/subtitle
  offset adjust/reset, quality changes and seeks refuse while queue identity is
  unsettled, before command switch invalidation, selection/proposal changes,
  health/progress work, replanning, native commands, reporting or persistence. The
  existing queue boundary follows the [item-identity contract](player-interactions.md):
  admission clears stable identity synchronously; only a stabilizing launch
  restores it. Installation ownership alone does not cover reporting and
  planning. The local-asset continuation also checks this boundary beside its
  installation check; entry refusal and existing job cancellation prevent a
  new outgoing-context read from surviving the queue handoff. Pickers can remain
  visible while commands are refused. Opening the quality picker and dismissing
  notices remain presentation actions. Auto-skip refuses before recording its
  once-per-segment key, leaving it eligible for a later admitted observation.
  Launches that fail before identity settles keep these commands refused until
  Retry or another admitted queue launch installs a stabilizing plan. Previous
  at or past five seconds remains a seek-to-zero and is refused while unsettled;
  it does not fall through to queue navigation. Queue ownership is never cleared by arbitrary job cleanup or by
  replaying a retained plan.
- **[all]** A manual Auto-recovery notice offers **Choose lower quality**, which
  opens the in-player picker, plus the remaining stream actions and dismissal.
  A successful automatic quality recovery offers **Keep current quality**, **Try
  higher quality**, **Choose lower quality**, and **Dismiss**. Neither notice
  routes to Playback Settings. Any chosen row is current-playback only and a
  fresh playback resolves its defaults again.
- **[tvOS]** Compatibility credit and the automatic quality recovery notice are
  armed only after the recovery request installs and prepares its replacement,
  then consumed by a later Playing observation. The request's original recovery
  intent controls credit; a planner-internal forced retry does not turn an
  ordinary change into automatic recovery. Quality credit uses the installed
  plan's Auto session limit. A failed, cancelled or rejected replacement cannot
  credit the still-playing old stream. An admitted ordinary replan clears
  unconsumed credit, while a subtitle encode continuation and native Retry of
  the installed recovery preserve it. Same-plan Retry clears a stored action
  notice before its next publication; it does not dismiss a visible overlay.
  This changes no Auto budget, cap or quality intent. A retained session cap can
  constrain a later non-Quality replan without a new recovery-applied notice.
  Preparation followed by Playing establishes neither audible output nor
  native video readiness.
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
- `retainedPlan` is an optional owner-lane snapshot of the project plan kept
  for Retry, including controller-made position, speed, style or subtitle-target
  copies. Stop/release do not clear it, so it is neither current ownership nor
  readiness proof. Read it immediately after an owned retry with a changed
  non-null prepare epoch; unknown implementations default to no snapshot;
- dropped frames and first-video-output observations are event flows scoped to
  the controller prepare generation;
- iOS VLCKit prepare/resume/seek observations additionally carry a transition
  sequence within that generation, so Started, Presented, and TimedOut settle
  only the exact target transition;
- the latest accepted native-host seek is an owner-lane snapshot with prepare
  epoch and sequence, independent of presentation readiness; other controllers
  default to no snapshot;
- VLCKit PiP additionally publishes an owner-lane playback-intent snapshot with
  prepare epoch and sequence for qualifying Play/Pause commands, without native
  acceptance or output proof; ordinary app verbs and `recordHostPlaybackIntent`
  do not publish it, and other controllers default to no snapshot;
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

The `playback-policy` module owns these recovery/health coordinators and their
request-policy values; presentation owners supply scope, clock and callbacks.

`AutoPlaybackRecoveryCoordinator` is pure common-domain logic: it has no
coroutines, controller references, persistence calls, navigation, or strings.
It owns the two one-shot budgets and produces `CompatibilityReplan`, `LowerTo`,
a user prompt, a recovered notice, or no action. `LowerTo` requires the typed
explicit-session-Auto authorization; inherited Auto receives the typed manual
choice prompt without spending the quality budget. The ViewModel and
`TvPlaybackSessionPresenter` own execution, position preservation, policy
persistence, notice state, and stale-work cancellation. While the Compose
ViewModel owns a controller installation or queue item identity is unsettled,
automatic triggers are ignored before recovery decisions, budget, quality,
notice or PiP-slot changes, including manual prompts. Session recovery also
requires settled queue identity. Health signals, first-output state and their
diagnostics may already have been recorded before the trigger gate. A replan
rejected during installation leaves backend-switch authority intact. Initial
switch preflight retains its existing recovery arbitration.

The Compose owner closes automatic and session recovery at the first Stop
request, even when native Stop must wait for installation, and at disposal.
Stop also clears retained PiP recovery. A `Failed` snapshot first handled while
closed is discarded before publication or suspended diagnostic capture; it is
not automatically redelivered when recovery reopens. Existing generation and
controller checks still reject captures invalidated by Stop. This is an
admission-time boundary, not proof of when a delayed native event was emitted.
After session recovery returns, a `Failed` result is also discarded if queue
identity is unsettled, Stop/disposal closed ownership, or the captured launch or
selection generation changed. Queue refusal must not publish an outgoing Error;
an awaited backend recovery must not publish a displaced result. This check
precedes terminal outcome, Error, Stop and last-status publication, but does not
isolate earlier metadata, health or diagnostic work. Queue identity settles when
a stabilizing plan is installed, before native prepare; current synchronous
failure and settled Retry can still recover with a null installed plan.

Android alternate-backend fallback rechecks its captured launch/item and
queue/Stop/disposal ownership after controller installation, before replanning,
Applied diagnostics or recovered guidance. Merely handing the lock to a waiter
is not displacement: a waiter that has not run or declines leaves current
fallback able to replan. Successful installation may intentionally cancel the
fallback's collector and replace its controller, so neither collector activity
nor old-controller identity determines this continuation's authority.

Only an accepted explicit Play, Retry, Kids selection/replay, manual queue
selection, or Still Watching confirmation reopens recovery. Starts refuse
disposed, installing, locked-installation, or pending-Stop ownership before
mutating their caller state; valid software Continue delegates to Play and keeps
its prompt when admission refuses. Invalid selection, automatic Next, Pause,
and seek-only Previous never reopen it. Previous retains its existing seek
behavior and applies the fuller start guard only to actual queue selection.
Ordinary Retry also refuses an active unsettled launch before reopening recovery.
Pending Stop must drain first. The internal obsolete-replan abort can call Stop
without leaving the route: its existing projection remains, with recovery and
`Failed` publication closed until an accepted explicit action. Direct settings,
track, backend, and transcode-seek replans can still prepare without reopening
recovery; this contract does not add a retained-screen restart or state replay.

While the ViewModel is in PiP, with installation released and queue identity
settled, its recovery slot retains the strongest trigger
in this locked order: DecoderFailure, UnsupportedMedia, NoVideoOutput,
CumulativeBuffering, RepeatedStalls, then DroppedFrames. Equal severity keeps
the first trigger. Leaving PiP consumes one retained trigger; if installation is
busy or queue identity is unsettled, it is discarded without execution or a
later retry. New item or queue
launch, retry, actual controller release and session end clear retained work.
Rejected or cancelled switch planning that keeps the outgoing controller also
keeps its pending evidence, unless PiP exit has already consumed it. Installation
settlement preserves evidence retained for the replacement. PiP presentation
deferral alone keeps health measurement active; controller installation suspends evaluation as
[described above](#playback-health-and-guidance). An admitted retained trigger enters
`AutoPlaybackRecoveryCoordinator`; arbitration grants no new
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

- **Desktop Audio mapping needs the original cohort.** Omitting descriptors
  does not remove their native ordinal slots, so saved offline Audio counts must
  precede identity omission. A mismatched native count cannot justify a positional
  guess; unique labels provide separate evidence. Equal counts need no Playing
  gate: TracksChanged can arrive first, and pausing during opening must not turn
  a valid mapping into a selection timeout. Unknown-cohort mapping and the
  existing no-setter true-singleton route remain separate exceptions.

- **Initial routing describes the selected audio.** The first listed track can
  have different decoder requirements from the effective launch choice. Resolve
  that choice before choosing the backend, then commit selection fields only
  after backend success and a fresh launch-authority check. The descriptor's
  fallback lookup itself writes no selection preferences. An ordinal cannot
  replace missing server identity, and repeated Audio indexes cannot identify
  one selected codec or native track. Omitting source choices can change the
  fallback track and Apple Auto routing for malformed metadata. Response
  descriptors apply the same identity qualification without renumbering the
  original embedded cohort: retaining the first duplicate would invent a choice,
  while closing its ordinal slot would shift later native mappings. The selected
  response index remains authoritative; an absent descriptor follows existing
  activation recovery. Detail/Series check remembered defaults against offered
  indexes, as playback and native tvOS do, because reconciliation can otherwise
  keep reinstating a stale default. Backend-specific quality ordering follows
  [playback policy](playback-policy.md).

- **A failed request provides no compatibility approval.** Local source-copy
  bounds cannot establish the container, codec-tag, audio, and progressive-video
  requirements normally evaluated during negotiation. Propagating the failure
  avoids handing an unverified source to AVPlayer or forcing a video re-encode
  without a successful response establishing the need.

- **Font scanning has process and engine ownership.** The native API blocks and
  may scan for several seconds. Startup and prepare share one IO attempt, while
  a temporary engine isolates that work from player release and leaves native
  font loading available when the disk cache cannot be written.

- **Media3 IDs are container-specific.** Matroska TrackNumber can numerically
  collide with a different Jellyfin stream index. Treating that collision as
  exact identity can map two picker choices to one native track and falsely
  confirm both. Numeric ordering remains useful without asserting identity.

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
  Installation admission must precede switch invalidation: a rejected recovery
  must not abandon paused or partly replaced playback. Installed activation
  recovery follows release of that ownership and software-switch reporting,
  because its replan invalidates switch authority. Combining installed audio
  and subtitle facts prevents two helper calls from spending both allowances
  and letting the second replan cancel the first.

- **Recovery and reporting decisions are shared without moving lifecycle
  ownership.** Common immutable coordinators keep precedence, PiP arbitration,
  budgets, reporting order, and settlement consistent. Jobs, notices, native
  commands, navigation, and stale cancellation stay with each presentation
  owner; a universal stateful player would couple unrelated lifecycles.
  Startup guards its unfinished prepare interval rather than rejecting every
  null installed plan, because recovery after a synchronous failure can still
  own that null identity. Refreshing the failure collector and reconsidering
  only activation facts avoid duplicating full failure handling.
  Queue admission must precede recovery policy changes, and its refusal must
  not turn an outgoing failure into Error through the no-action fallthrough.
  Backend recovery can suspend across ownership handoff, so its continuation
  and the caller's late Failed result both check actual launch/queue/Stop/disposal
  ownership. Lock possession alone could abandon a valid fallback whose waiter
  later declines; collector cancellation is also intentional during replacement.
  Clearing retained PiP evidence at controller release and session end prevents
  retained outgoing failures from spending a replacement's recovery budget. Clearing at
  switch request would discard evidence even when planning keeps the old owner.
  Stop needs persistent recovery closure because a later failure can capture
  the post-Stop generation. Rejecting it before suspended diagnostics prevents
  an explicit Play from admitting that old capture when recovery reopens.
  Explicit starts share ownership checks so they cannot reopen around a pending
  native Stop; ordinary Pause remains eligible for recovery.

- **Controller installation is serialized.** A current launch waits for an
  earlier installer and rechecks generation and item authority. Dropping a
  launch while installation is busy can leave the current item without a
  controller.

- **Retry follows existing launch and controller ownership.** The common
  policy validates exact audio/subtitle targets and explicit Off only from the
  vocabulary each controller already owns. Assets, timing, play intent,
  reinitialization, and native command order remain controller-specific.
  Ordinary Retry preserves an active unsettled launch before changing its
  generation. After that launch ends, full startup rebuilds and stabilizes
  identity; replaying a retained same-item plan cannot establish authority over
  partially replaced source and track fields. Ordinary online full Retry reuses
  the raw accepted request because resolved runtime fields may belong to a
  cancelled launch or represent defaults rather than explicit user intent. Kids
  keeps later deliberate track choices in the same target-owned intent record;
  source-qualified hints retain playback defaults or substitutions without
  making them new explicit preferences. This separates the two meanings without
  another durable owner or observable state. The existing launch job owns its
  lifetime. Cancellation alone cannot prevent a
  synchronous failure publication after an installer drains Stop, so result
  boundaries also check launch authority.

- **Playback commands need settled item identity.** Queue planning can
  retain a visible picker and outgoing controller while item, source and track
  fields change. Installation guards alone allow an outgoing choice or offset
  to be applied and saved under the incoming identity. Quality commit checks
  also miss earlier planning failures that can publish Error. Without entry
  admission, an out-of-window transcode seek can replan through absent installed
  authority, install and restart reporting during the handoff, or stop a newer
  queue launch when its obsolete continuation finishes. Direct progress refusal
  does not guard that replan path. These are source-supported, timing-dependent
  paths, not native reproductions. Reusing the queue boundary rejects commands
  before these side effects without introducing another owner.

- **Health policy is selected by the application surface.** Android TV uses the
  shared Actionable policy, while desktop and tvOS remain Advisory; placing that
  choice on `PlayerController` would conflate measurement support with
  presentation behavior. Android LibVLC's one-frame dav1d delay bound is a
  backend resource safeguard, not a source, quality, or device-profile rule.

- **Initial audio activation has one shared decision table.** DirectPlay waits
  for exact native mapping while other modes begin active, with two deliberate
  renderings for controller state differences. Centralizing the table prevents
  controller copies from drifting without forcing unrelated lifecycle state
  into a common base class. An absent descriptor never starts the native
  selection deadline, so preparation and accepted same-plan Retry hand the
  gap to recovery. One combined decision preserves Audio-first precedence.
  Compose Retry separates logical activation facts from captured installed
  authority, since diagnostic copies are not new preparations. VLCKit's
  synchronous Retry snapshot precedes deferred play; using its intended plan
  position and ViewModel play intent avoids a new zero-position fallback and
  false pause without changing Android mpv's current-position Retry. Native-only
  play intent remains separate. The Compose reducer retains terminal Error
  until an accepted attempt resets it, so unrelated native updates cannot
  remove Retry/Close. Resets at guarded install/prepare boundaries admit a new
  attempt without treating its planning as a successful replacement.
  Fast Retry recovers revoked authority from the controller's retained plan
  only when a new prepare epoch and the current owner admit it. Native and
  logical plans can differ, so treating a ViewModel copy as the prepared plan
  would invent timeline or selection facts. Advancing the captured identity
  with the assignment preserves the following activation handoff's ownership
  check; a new epoch still does not establish native readiness.
  An inadmissible switch can fail server fallback while native audio retains the
  previous choice. Retry uses the unresolved fallback target to repeat that
  request; requiring this lineage preserves the initial audio exemption.
  tvOS likewise retains Failed phase/cause while projecting current native
  facts; its existing early replan Loading remains a separate reset boundary.
  tvOS samples exact Audio confirmation before native Retry can reset mapping
  to Pending. Capturing each fallback target also rejects obsolete errors and
  installs after resolution; the global DirectPlay refusal remains necessary
  while Audio is unresolved. A drop after reporting Stop can leave reporting
  quiet while native playback continues, matching the existing cancelled
  subtitle replacement limit; another install reopens reporting.
  Same-plan Error Retry cancels earlier planning before resetting reporting, so
  its late result or failure cannot claim the new attempt. The existing
  cancellation checks cover planner delivery and the suspended reporting Stop;
  no extra generation is needed for those jobs.
  Recovery credit is armed at preparation rather than at the decision because
  the previous stream can keep emitting Playing while replacement planning
  fails. Original request intent prevents a planner's internal fallback from
  taking credit for an ordinary change. Subtitle encode continuation and native
  Retry keep the installed recovery's credit; a superseding request clears it.
  Local-file subtitle replans carry invocation-local nonfatal provenance so
  durable fatal publication does not turn an unavailable file into a blocking
  failure. Both planner catches reject cancelled work before publication,
  because exceptional coroutine delivery can win over cancellation and must
  not give an abandoned request durable ownership of a newer screen state.

- **Health evidence is qualified rather than globally tuned.** Capabilities follow
  the installed controller: a retained placeholder suppresses valid signals,
  while a released controller can admit obsolete evidence during replacement.
  Backend-specific first-output thresholds, advancing-clock checks and generation
  exclusions keep slow start and surface work from becoming false no-output recovery.
  Installation suspension prevents long planning or replacement gaps from
  spending health slots before recovery admission. Settlement restarts rolling
  evidence and reads the qualified controller snapshot because a state update
  skipped during installation need not be emitted again. Positive facts stay
  admitted so a non-replaying frame event is not lost. Startup timing remains
  launch-based. Evidence-window restarts keep evidence from before app-owned
  controls or qualifying VLCKit PiP seeks/play/pause from contaminating later
  stall decisions. Reading separate seek and command snapshots before status
  and deadline evaluation avoids independent event-flow ordering without
  treating play intent as native success. AVPlayer PiP and interruption/route-loss
  restart coverage remain unassessed. Restarting the window retains
  one-per-session signal and recovery budgets.
