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
- Android decoder, extension and profile qualifications belong to
  [Android playback](android-playback.md) and [wire policy](playback-policy.md).
  FFmpeg is an ExoPlayer decoder extension, not another player backend.
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
- Android LibVLC's MediaCodec-bound projection and dav1d resource policy are
  owned by [Android playback](android-playback.md#android-mobile). A projected
  bound is a conservative source-copy safety input, not measured software-decoder
  capacity or visual-output acceptance. The planner owns compatibility before
  prepare; Auto/Original remain uncapped quality policies even when a decoder
  bound rejects a source.

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
- An accepted seek requiring a replacement server stream immediately pauses
  old playback and displays Loading at the requested absolute position. Both
  shells retain Play/Pause intent separately from this internal pause; a newer
  Pause wins over automatic resume. Native progress and outgoing Stopped reports
  retain the actual played position, and the hold suspends health evaluation.
  Before outgoing reporting Stop, a newer seek can cancel planning and return
  to the old stream window. After Stop admission, the installer completes while
  retaining only the newest requested target; intermediate playback stays paused
  until that target is handled against the installed plan. Fatal failure remains
  visible with the requested target retained. Stop, close and owner replacement
  revoke the hold. Shared controls, desktop shortcuts, Kids controls and TV
  transport labels use the retained intent while loading, so Pause remains
  available. A failed local subtitle lookup, immediate subtitle Off or accepted
  backend switch settles the abandoned seek only while the installed owner is
  still healthy; otherwise the requested target remains available for Retry.
  Terminal reporting captures the actual outgoing position before asynchronous
  diagnostics. Server preparation and native seek arrival can still take time.
- Lifecycle outcomes are shared: explicit Pause revokes automatic resume;
  Stop/close settles reporting and releases ownership; retired callbacks cannot
  restart playback. Android Activity Stop, iOS background/PiP admission, and
  tvOS scene background reach those outcomes through their native lifecycle
  owners. Desktop window deactivation retains playback for multitasking and is
  not a mobile background-close event. iOS keeps the screen awake for the
  player-screen lifetime because auto-lock previously interrupted playback;
  desktop holds display sleep only during Playing/Buffering. Revisit the iOS
  exception only with paused/playing/background/PiP device evidence that avoids
  that failure; host compilation does not qualify a replacement policy.
- AVPlayer derives buffered position from the loaded range covering its native
  playhead. Ready-item state preserves explicit pause intent and native Playing
  ahead of predictive buffer flags or stale stall notifications. Waiting,
  unresolved seeks/audio activation, and genuine zero-rate stall recovery under
  play intent remain Buffering. Seek lifecycle and deadlines are owned by
  [iOS recovery](apple-playback.md#ios-recovery).
- VLCKit publishes `bufferedPositionMs` equal to its published position, so
  buffer-ahead is unavailable, not a measured empty buffer. Its Buffering
  combines native buffering progress under play intent with the transport hold
  owned by [iOS recovery](apple-playback.md#ios-recovery). Once target arrival
  and clock advancement settle transport, UI and health use ordinary native
  status and the timeline follows native time while best-effort picture
  readiness may remain pending. Native buffering still takes precedence.
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
- PlaybackInfo uses the concrete backend's capability snapshot. The
  [policy guide](playback-policy.md#3-wire-and-persistence-boundaries) owns bitrate
  encoding, the Jellyfin 10.11 8/40 Mbps defaults, per-codec channel ceilings,
  source-copy preflight and server authority. [Apple playback](apple-playback.md#ios)
  owns its launch HDR snapshot and distinct AVPlayer/VLCKit conditions;
  [subtitle delivery](subtitles.md#selection-and-delivery) owns text/bitmap policy.
  Capability provenance is diagnostic evidence, never a DTO field or output proof.

## Playback health and guidance

The pure shared `PlaybackHealthEvaluator` receives native controller facts but
owns the meaning of those facts. Slow startup is eligible after 10 seconds
before the first `Playing` and clears at the first `Playing`; it has a separate
message-only startup budget. VLCKit's public transport status can end that
accounting before picture readiness: it represents native transport plus an
earlier clock advance at the target, not first-video-output proof. Post-start guidance uses one
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

Installation records capture each owner's admitted operation and identity; their
presence preserves the existing install guards. Locks, queued Stop, backend-switch
state and health suspension remain independently owned. Shared and tvOS installer
paths retain their distinct health boundaries. Replacement-seek target, outgoing
Stop position and queued target can clear independently. Displaced recovery retains
independent audio and subtitle roots even when its displacement flag alone clears;
proposal cancellation does not undo an already-admitted reporting Stop.

## Progress Reporting

- Report playback start, periodic progress, pause, seek, stop, completion, and
  failure through the shared progress-reporting boundary.
- A per-owner `PlaybackReportingCoordinator` owns Start/pending state and
  ready-state retry, pause/unpause edges, periodic sampling, progress/Stop
  eligibility, stale Start rejection, duplicate Stop suppression, and final
  disposal for both `PlayerViewModel` and the tvOS playback presenter. Compose
  supplies its stable shared playback-state projection across controller
  replacement; the coordinator never retains a replaceable controller flow.
  Its lifecycle distinguishes installed, starting, started, Stop-admitted and
  disposed phases. Stop admission precedes synchronous enqueue; the independent
  receipt attaches afterward and remains awaitable after disposal. Reentrant
  calls before attachment see admission without a receipt. Pending Start still
  admits progress, and stale Start completion cannot reopen a stopped session.
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
  starts one when absent, for ordinary failure handling. A replan or accepted
  backend switch can instead publish Failed from a later command after prepare.
  Once its lease releases, the existing planned activation handoff runs first.
  If the request or switch, actual installed controller and prepared-plan
  identity still own playback, admission is open, and the current Failed has
  not already been handled, observation refreshes to deliver it through ordinary
  recovery. This later failure keeps
  prepared authority; it does not use the immediate-prepare revocation path.
  A switch that rejects preparation after transferring its replacement instead
  retains that controller with a null installed plan. After software-switch
  outcome reporting, current switch/reporting ownership, replacement logical
  item/source and concrete controller ownership qualify an unhandled Failed
  observation refresh. Prepared authority remains revoked. Retained outgoing,
  aborted, stale, already-handled and already-published Error cases keep their
  existing paths.
  An owned fast Retry
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
  VLCKit's Retry reporting handoff captures the bounded current position before
  native preparation publishes its temporary paused zero. Immediate activation
  recovery uses the newly admitted controller-retained target when qualified,
  otherwise that captured position, and follows ViewModel play intent rather
  than the transitional pause. Later ViewModel pause/play revisions still win;
  a prior pause made only through native PiP, an interruption or route loss is
  not preserved by this recovery replacement. All backends use the common current-position Retry snapshot.
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
  installed plan's start position for startup/replan; same-plan Retry instead
  captures the bounded current position before native retry. A changed native
  prepare epoch and current owner qualify any retained Retry target. This does
  not confirm native seek arrival. Selected external audio and ambiguous response Audio identities
  lack an embedded descriptor; this fallback establishes no native codec
  incompatibility. Retry resets the
  policy allowance; Retry fallback carries captured pause intent through both
  audio and subtitle replans, while a newer explicit command wins.
  Startup and replan failures use a private copy of Compose's planner-error
  classifier; check both helpers when changing its immediate-cause mapping.
  Unlike Compose's Audio-fallback catch, tvOS preserves typed classification
  even though each Audio fallback now captures its own request target.
  Failed retains its first cause, including a missing native cause, across
  later failure and state projections while other native fields keep updating.
  Accepted start/item or recovery Loading clears it; a still-owned replacement
  installation can also clear a failure that arrived after planning began.
  Track and quality proposals keep the installed presentation while planning;
  replacement seeks use the shared pause/Loading contract above. AVPlayer may
  remain Audio-gated, and failure presentation alone implies no native Stop.

  A healthy, current user quality, Audio, Subtitle, local-subtitle or
  out-of-window seek proposal commits requested fields and persistence only
  when its replacement is accepted. A planner exception keeps installed playback
  and shows a typed passive rejection notice only if the proposal displaced no
  admitted recovery, and only while outgoing reporting has not been stopped for
  a replacement. If a prior proposal queued Stop before supersession, a later
  planner or local-read failure retains fatal-with-Retry behavior rather than
  claiming intact playback. Displacement is inherited by replacement proposals, so
  rapid choices cannot hide an unresolved activation gap behind a still-Playing
  native status. An accepted immediate native change settles only the exact
  recovery it retires; exact current-plan Audio confirmation also settles the
  matching Audio provenance. Unrelated recovery history remains.
  Native preparation failure and recovery-displacing planner failure remain fatal.

  Same-plan Error Retry cancels pending planning and any pre-planning local
  asset read before resetting recovery allowances and reporting. Cancelled
  results cannot replace that attempt, and uncommitted choices were never saved.
  Retry reloads a snapshot of the current absolute position, speed and style,
  retaining admitted track intent. It starts a new reporting session; Close/Back exits, and remote
  Play/Pause may control a stream beneath a fatal surface. UnsupportedMedia
  retains Retry. Successful owned installation resetting an earlier failure is
  an existing boundary, not a claim that every request is isolated from it.

  Installed-local-subtitle activation failure or deletion remains nonfatal.
  If a user proposal or exact Audio recovery owns planning, a qualified repair
  fact waits for that owner to settle instead of absorbing its pending inputs.
  Repair uses installed choices, turns subtitles Off, persists that intent and
  shows the unavailable notice; a repair failure republishes current native
  state without manufacturing Failed. A requested local asset that cannot be
  read is different: a healthy non-displacing proposal is rejected without
  mutating installed intent. Exceptional reads settle at the same current-owner
  boundary with sanitized diagnostics, and cancellation or stale ownership
  cannot publish a newer attempt's outcome. No local-file path requests
  ForceEncode. Completion during repair can still project Loading, and native
  ordering and output remain unqualified.

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
  not part of this handoff. A proposal dropped after Stop, such as a plan-epoch
  change, or an immediate native change cancelling it can retain the same
  stopped-reporting boundary until install, Retry or item change. Neither the
  proposal drop nor the immediate native change restarts reporting. A Failed
  sample for the exact installed Audio
  target is deferred while its launched recovery request owns the slot; it
  cannot start competing network/automatic recovery or an Original prompt.
  A silent exit releases that ownership and reconsiders current failure once
  when no replacement owns it. Other health/recovery triggers and native
  callback ownership retain their separate policies; this is not general
  serialization of recovery. Retry cancels pending replans.
- **[all]** Policy-gated decoder, unsupported-media, health, and recovery
  behavior is owned by [quality policy](playback-policy.md#quality-semantics).
  A backend-qualified replacement re-plans from its own capability facts and
  keeps the selected policy; a same-plan network retry remains a separate
  one-shot retry.
- **[all]** Same-session explicit `retry()` snapshots the current absolute
  position, speed and subtitle style before prepare. Position is nonnegative
  and bounded by a known positive duration. Every adapter retains admitted
  local subtitle intent, including explicit Off, and re-applies retained audio.
  Concrete subtitle restoration still requires the complete activation target;
  an obsolete remote asset is discarded without changing server delivery or
  offline sidecar ownership. Native restart order and play/pause intent remain
  controller-owned. Automatic network recovery uses its separate replan path.
- **[Android LibVLC]** Retry retains timing offsets and applies them once at the
  current generation's native Playing event, when an input thread exists.
  A paused Retry waits for eventual playback. Timing commands are unavailable
  during prepare or pending restoration and never write the outgoing player.
  Failed native application clears only that channel's displayed offset;
  replacement, Stop, release and failure invalidate pending restoration.
  Other timing-capable adapters retain their existing timing application.
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
- **[Compose]** Automatic Compatibility, quality and Android alternate-backend
  recovery credit is armed only after a replacement is prepared and installation
  ownership is released. The request, controller and installed plan must still
  be current, recovery admission open, and native state non-Failed. Quality
  credit uses the installed Auto session limit; controller replacement alone
  grants no credit. A current Playing snapshot can complete that request's
  presentation credit after the activation handoff, without replaying playback
  failure, reporting or completion handling. Native output remains unverified.
  Credit is not cleared at ordinary replan entry: a rejected user quality
  proposal preserves the installed recovery, and credit can survive later
  ordinary installs or activation continuations. Accepted quality commit,
  same-item Retry, full start and Stop clear pending credit. PromptUser clears
  already-armed quality credit but does not cancel an in-flight LowerTo request;
  a later successful install and Playing can replace that prompt with an applied
  notice. A retained Auto cap in a Compatibility request grants Compatibility
  credit only, without a quality-applied notice.
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

- **[all]** Dropped-frame evidence is accumulated qualifying measured duration,
  never report count or wall-clock polling. Each occurrence spans
  `[arrival - intervalMs, arrival]`; its average rate must be at least 2 fps while
  Playing, and its whole span must postdate reset and the two-second exclusion.
  Clip accepted spans to the rolling 60-second window; 20 seconds qualifies.
  Clear on leaving Playing, session/retry/replan/queue/controller replacement.
  This avoids treating Media3's 50-frame batches as equivalent to LibVLC's
  roughly one-second polls or attributing pre-seek drops to later playback.
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

### Installation admission

An existing installation attempt rejects another mutation even for the same
launch owner; matching identity is no reentrancy grant. Jobs, the install mutex,
deferred Stop, backend-switch state, queued receipts and health suspension have
independent lifetimes. A current launch waits for the serialized installer and
rechecks generation/item authority after acquisition; dropping it merely because
the lock was busy could strand the current item.

Compose suspends health for timing restoration, construction, switch commit and
same-controller replacement, including final planning under installation ownership;
initial switch preflight remains outside. tvOS suspends health for a replacement-seek hold and backend-switch
commit; an ordinary replan installation record does not itself start suspension.
Do not infer either shell's health boundary merely from a generic Installing phase.

## 4. Runtime evidence and recovery ownership

Native observations share teardown ownership: serialize potentially blocking
getters with native transitions off Main, publish cached state and reject retired
media/generations. Reduce hot clocks at their source without delaying lifecycle,
seek confirmation, reporting or recovery edges.

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

The [reporting owner](#progress-reporting) consumes Compose's stable shared state
projection across replacements. tvOS creates reporting only after concrete
installation and observes that installed controller flow. Both shells retain
completion, queue, navigation and native-command ownership.

For Compose and tvOS same-item VLCKit Retry, reporting temporarily retains the bounded
current position captured before Retry and the intended play/pause state. The transition belongs to
one reporting installation and its prior native prepare epoch. A new known
epoch's Buffering or Playing sample, or an explicit user seek/play/pause,
settles it. Transitional Paused does not report a Pause edge when play was
intended. Failed and Completed retain the target until Stop, stopNow or dispose
reports and consumes it; installation resets it. Real zero positions after
settlement remain valid. This is a reporting contract, not evidence of native
seek arrival. Both shells forward the installed controller's native prepare
epoch on admitted reporting samples; a null or unchanged epoch cannot settle it.

An exact pending Audio fallback defers a later Failed sample from the same
installed target before network or automatic recovery. The pure policy returns
`Deferred`, distinct from terminal `NoAction`, without spending another budget.
Each shell supplies the target only while its exact launched job or request
generation owns the replan slot. A silent exit releases that ownership and
reconsiders the still-owned native failure once when no replacement owns it.
A retained unresolved target alone is not pending-request authority.

Diagnostics are allowlisted and backend-qualified. They state policy,
constraint/client limiter, wire representation, capability result/reason,
effective transcode cap, configured VLC default state, recovery intent/result, and
first-video-output evidence/observation. A backend-readiness record may state
the closed resource policy and bounded value that were applied, but must not
claim a native decoder module the backend API cannot expose. Disabled and
no-cap states are rendered explicitly; a protocol sentinel is never presented
as a user quality. Diagnostics never include stream URLs, headers, tokens,
server/user IDs, titles, paths, or raw native errors.

Native cache and decoder budgets remain execution policy, separate from stream
quality and capability claims. Media3's time/byte load-control bounds and Android
mpv's forward/backward cache bounds limit different native allocations; desktop
mpv retains engine defaults. Their objective is bounded memory with usable
startup/rebuffer behavior, not numerical equality. They remain configuration
choices without fresh cross-engine performance acceptance. Android VLC's dav1d
frame-thread bound addresses a recorded resource-failure candidate; Android TV
mpv's GPU route addresses a recorded shader-link failure. Their owning
[Android contracts](android-playback.md) retain
the affected routes. Change or retire those exceptions only after the same
route demonstrates memory, startup, rebuffer and output behavior without the
original failure. A shared source plan alone is not that evidence.

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

<a id="why"></a>
See the rationale beside admission, activation, health and reporting rules above.
