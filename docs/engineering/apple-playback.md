# Apple Playback Backends

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## iOS recovery

- AVPlayer dispatches the first ready-item seek immediately and retains only
  the newest queued target while one native seek is active. Targets clamp to
  a known positive duration; iOS Now Playing clamps before its ViewModel
  callback so immediate progress reporting uses the same bounded target.
  Explicit seeks disable automatic wait-to-minimize-stalling and use a small
  tolerance; `prepare()` restores the native waiting default.
- Initial and explicit seek work belongs to the installed prepare generation.
  Completion, interruption, or a ten-second dispatch deadline releases the
  target hold to the native clock; the existing state poll observes the
  monotonic deadline, so scheduling latency still applies. Sidecar loading
  does not consume that deadline. Replacement, stop, release, and terminal
  handling invalidate queued and active work before native cancellation.
- Play, audio activation, polling, and seek completion share one native
  playback-release guard: current play intent, matching ready item, open audio
  gate, and no unresolved initial, queued, or active seek. Pause keeps the
  latest seek target but prevents resume. Paused seeks publish `Paused`,
  including their immediate progress report. Native `Playing` takes precedence
  over predictive buffer flags and stale stall notifications; native waiting
  or stalled zero-rate playback under play intent remains buffering/recovery.
  Shared Play/Pause toggle uses durable intent during Loading/Buffering;
  Playing pauses, and Idle/Paused/Failed/Completed plays. iOS Now Playing uses
  that same callback.
- The iOS player keeps `UIApplication.idleTimerDisabled = true` while player
  content is composed (parity with Android's `keepScreenOn`), restoring it on
  dispose. Without it the device auto-locks mid-playback, which backgrounds the
  app and closes the non-PiP player.
- `PlaybackAudioSession` (appleMain, shared by the AVPlayer and VLCKit
  controllers) owns the Apple audio-session lifecycle: category `.playback` +
  movie mode, off-main activation on every `prepare()`, and a short delayed
  `setActive(false, NotifyOthersOnDeactivation)` on release gated to the
  controller that actually activated (the eager AVPlayer controller released by
  a backend swap never activated and must not deactivate). All session mutations
  run on one serialized executor so a stale delayed deactivation can never land
  after a newer activation. System interruptions (call/Siri/alarm) capture the
  current play intent once and use an internal pause that preserves that capture
  while clearing active play intent. A later public Pause or output-loss pause
  revokes the capture. Repeated begin callbacks cannot restore revoked intent;
  interruption end consumes it once and resumes only when iOS also reports
  `shouldResume`. Replacement and release reset it. Its interruption and route
  observers exist only while a current activation owner exists and deliver to
  that captured owner on the main queue. An `oldDeviceUnavailable` route change
  pauses through the normal controller path without recording interruption
  intent; wired/USB/Bluetooth reconnection therefore never auto-resumes it.
- Unified VLCKit 4 drives buffering from its typed progress callback and maps
  audio/text tracks from typed ordered lists with stable native IDs. Jellyfin
  identity remains ordinal/language/readback based; external subtitle IDs are
  excluded from embedded candidates, and requested activation is confirmed only
  by selected-track readback.
- The VLCKit controller resolves terminal events through the **shared**
  `VlcEndOfStreamEvidence`/`resolveVlcTerminalStatus` kernel, the same one the
  Android and desktop VLC backends use (see [VLC terminal policy](playback-runtime.md#vlc-terminal-policy)). Unified
  VLCKit 4 reports both natural completion and interruption as sticky `Stopped`.
  The iOS controller classifies that edge as completion only when play intent is
  still active, playback **provably progressed** beyond the prepare/resume/seek
  baseline, and the max of native and last-published position is within 2 s of a
  known duration. Otherwise an active session becomes `Paused` at its honest
  last position; an explicit stop publishes no replacement status. Unknown
  duration is deliberately insufficient completion evidence, so a stopped live
  or unknown-length stream never auto-advances or marks watched. The
  `VlcTerminalEventLatch` admits the sticky stopped transition once and is
  re-seeded to the player's initial stopped level on every teardown. Failure
  classification retains its separate, looser native-output latch.
- VLCKit tears its native session down **off the main thread**. `stop()` joins
  libvlc's input/decoder threads and destroys the vout through the main queue, so
  running it on Main — with the drawable still attached — can stall for seconds
  on a live HLS/transcode input or deadlock against the vout's own main-queue
  work. The drawable is detached first, then the stop is handed to the IO
  dispatcher; the generation bump, delegate detach, and field clearing stay
  synchronous so late callbacks from the old session are already rejected. This
  matches the Android LibVLC controller's teardown policy.
- VLCKit publishes the requested target until transport settles: arrival near
  an explicit target (or at/beyond a fresh-session resume target), then a later
  clock advance under play intent; paused seeks settle on arrival. The
  synthetic buffering hold then ends and the timeline follows native time,
  even when displayed-picture statistics are unavailable. Genuine native
  buffering remains authoritative. Arrival itself never proves playback
  progress for natural-end classification; later native progression can.
  Transport settlement also occurs on the existing twenty-second timeout and
  survives Pause without re-pinning the old target.
  Presentation readiness remains separate: `Ready`/`Presented` requires the
  existing clock and picture evidence, and the bounded picture timeout retains
  its diagnostic and PiP-admission rules. Transport settlement alone never
  claims a displayed frame. Each transition has a generation-
  local sequence and one timeout observation, so stale callbacks cannot clear a
  newer hold or trigger its recovery. Without the pending hold the next native
  time callback republishes the pre-seek clock and the bar travels target → old
  → target. Ordinary in-player seeks remain uncoalesced; decoder work and
  coalescing remain separate concerns. Transition boundaries emit bounded,
  scrubber-allowlisted facts for the native clock/state, buffering boundary,
  video-output presence, decoded/displayed/lost picture counters, published
  timeline, progress latch, and terminal classification. They use the existing
  Kermit writers: diagnostic collection controls in-app retention/upload and
  verbose system logging controls the raw release platform sink. The internal
  PiP content latch is deliberately separate from shared health capability: a
  Ready transition admits it normally, and a counter-zero timeout admits it
  only when VLCKit reports an active native video output. Neither case promotes
  the unreliable counter into shared first-output health evidence. PiP does not report this
  transition-pinned UI position to AVKit: its playback-time contract reads a
  separately cached native VLC clock so the advertised time range describes the
  sample-buffer stream that AVKit is actually presenting. VLCKit PiP skips bound
  the relative request against that native clock and the canonical content
  duration, then use VLCKit's completion-capable `jumpWithOffset`. An accepted
  jump publishes a prepare-scoped host-seek snapshot before the resulting state
  update for [shared health exclusion](playback-runtime.md#playback-health-and-guidance).
  Generation-qualified PiP Play/Pause also publish a separate command snapshot
  before dispatch when they start a playback attempt or clear play intent;
  shared health consumes it under that same lifecycle contract. It is owner
  admission, not proof of native success.
  A generation-tagged single-flight gate consumes the system completion exactly
  once from the native seek-finished callback; after matching the request token and native
  session, it refreshes the cached native clock before invoking AVKit's
  completion. The shared transition independently owns the target-pinned
  timeline, buffering state, and picture-readiness evidence, and never blocks a
  later native PiP skip once the previous native command has finished. Rejection,
  bounded deadline, stop/release, Play replacement, and source replacement still
  release the system completion safely on the iOS Main owner lane.
- VLCKit failures are classified `Network` only when the media parse failed or
  timed out before playback ever progressed; everything ambiguous stays
  `Unknown` (fallback-ladder eligible). Never guess Decoder/UnsupportedMedia.
- Apple controllers supply buffering/startup state and only the first-output
  evidence their measurement capabilities support. Neither supplies shared
  dropped-frame health measurements. VLCKit's native cumulative lost-picture
  total combines output losses with some pre-queue picture failures. It remains
  a generic best-effort count; the output-only count is unknown. Sampling occurs
  every fourth state update at a variable cadence. Assigning media to each fresh
  native player creates the zeroed statistics snapshot before app reads. The
  wrapper exposes no availability result, and native allocation-failure handling
  is not qualified. Initial zeros and repeated totals establish neither output,
  freshness nor a drop rate. First-output measurement remains unreliable.
  Shared actionable guidance follows [quality policy](playback-policy.md#quality-semantics)
  and [runtime health](playback-runtime.md#playback-health-and-guidance), including
  budget, identity and output-reliability gates. Controllers never change quality
  themselves; host checks do not establish device acceptance.
- iOS and macOS share the Now Playing snapshot publication policy:
  title/series/duration/status and configured playback-speed changes push full
  metadata, while ordinary position ticks remain suppressed. A speed change in
  either direction republishes even while paused, where the exported playback
  rate remains zero. On iOS, `PlayerNowPlayingEffects` also installs
  play/pause/toggle/skip±15s/change-position commands; they hop to the main queue
  and route through ViewModel callbacks so remote seeks honor transcode-window
  restart and reporting rules. Android and non-macOS desktop actuals are no-ops.
- AVPlayer advertises AV1 direct play only with hardware decode support;
  VLCKit uses its separate VLC-family codec declarations. Unsupported extreme
  sources should fail cleanly rather than stall.
- The [VLCKit 4.0.0a25 pin](../../scripts/vlckit-bundle/manifest-4.0.0a25.txt)
  has a compiled avcodec route to registered libavcodec decoders for ALAC and
  the declared `pcm_*` names except `pcm_dvd`, across the four required iOS/tvOS
  frameworks (six architectures). This establishes an available route, not the
  module VLC selects. It does not qualify container/demuxer routing, sample
  format, channel count or audible playback; the server profile still imposes
  no channel-count ceiling for these VLCKit inputs. Other codec entries and the
  native `pcm_dvd` route remain unqualified for this pin. On pin changes,
  requalify the module list, audio fourcc table, codec list and descriptors on
  every required slice; watchOS and visionOS were not checked.
- On iOS, AVPlayer is the recommended fresh-install default. Auto remains an
  explicit user choice and may route a source to VLCKit when AVPlayer cannot
  direct play it. VLCKit is labeled beta in Settings; its Picture in Picture
  integration is experimental and seeking may close PiP or interrupt playback.
  This disclosed limitation does not change foreground playback planning or
  silently disable the user's PiP preference.
- Session restore uses the persistent Apple plaintext store. AVPlayer retains its
  player-layer PiP controller. VLCKit uses its public drawable, PiP drawable,
  media-controller, and window-controller protocols on the retained native
  surface; it does not discover private layers or own an AVKit sample-buffer
  controller. The surface binds the current prepare generation even when Compose
  creates it after native prepare. Only a confirmed native started callback
  suppresses background shutdown; enabled/supported/controller availability and
  failed or denied starts are not active PiP. Both paths share the identity-bound
  coordinator: restore keeps the route open, close-without-restore closes it,
  and source replacement invalidates stale window callbacks. VLCKit exposes
  native play/pause, completion-safe relative skip through the canonical
  absolute seek transition, cached duration/time, and linear playback for
  non-seekable media through that public contract.
  Direct-play subtitle subpictures remain a separate UIKit overlay and do not
  appear in PiP; burned-in transcode subtitles remain visible as video pixels.
  The AVPlayer layer observes distinct prepare epochs and replaces native
  player/PiP ownership only when the exact controller, AVPlayer, or prepare
  epoch changes. Gravity, PiP-enabled and linear-playback policy changes do not rebind;
  an immutable delegate retains each retired binding's epoch for any already
  queued native callback. AVPlayer PiP requires linear playback when the installed
  plan is Transcode and the controller restarts streams for seeking. Set that
  policy before automatic PiP registration, update it on the retained binding,
  and clear it on disposal. Native transcode PiP seeking is disabled; direct-play
  seeking and play/pause retain their existing controls. VLCKit's public-protocol
  integration and disclosed PiP limitations remain separate.
- Diagnostics include sanitized player/wait/buffer/error/access state and never
  include URLs, auth headers, or tokens.

## tvOS native player

- The app-global playback-info-at-start setting is snapshotted when either an
  online or offline presenter is created. The first installed/Active native
  player opportunity opens diagnostics once when no panel, sheet, error, or
  higher-priority prompt owns interaction. A blocked opportunity is consumed;
  dismissal, retry, reprepare, and queue advance never reopen it automatically.
- Native device settings expose Audio Auto/Stereo PCM and HDR Auto/Prefer SDR
  through the existing shared device policy for the next online play/replan.
  Effective formats/channels and fallback reasons describe the cached Apple
  capability snapshot; unsupported saved passthrough is shown as Auto without
  rewriting it. No capability re-probe is offered. Serialized writes preserve
  unexposed fields from the latest observed settings; the shared store's
  asynchronous startup/default-state semantics remain unchanged. These controls
  do not modify offline VLC's saved plan or an already prepared player.
- Online tvOS playback resolves AVPlayer or VLCKit through the shared backend
  policy and uses the Apple-family controller behind `PlayerController`
  contract; `TvPlaybackSessionPresenter` (shared-tvos) owns plan -> prepare ->
  play, executes `PlaybackSessionRecoveryPolicy` decisions (including the
  one-shot runtime network retry and typed activation fallbacks), owns
  transcode-seek restart, and retains the [ordered reporting contract](playback-runtime.md#progress-reporting).
- `TvPlaybackSessionPresenter` keeps native position and buffer samples for
  reporting and recovery. Its outward state displays the requested position
  during a replacement-seek hold. The Swift watch projection suppresses changes
  limited to clock fields; semantic boundaries emit the complete current state.
- AVPlayer playback uses `AVPlayerViewController`. Its transport drives AVPlayer
  directly. The native host forwards explicit rate-change notifications through
  the current player/item/plan identity and a captured transport epoch to update
  controller play intent without
  repeating the native command. System interruption/background reasons are
  excluded; unchanged intent is a no-op. Internal transport changes advance the
  epoch before native commands, rejecting queued notifications from the old
  observer. During replacement-seek loading, AVKit interaction is blocked while
  the retained host and spinner remain visible; the physical Play/Pause command
  updates durable intent, which also drives the transport label. An explicit pause publishes `Paused`
  and preserves it through startup or stalls until play is requested again.
  The controller retains readiness/stall recovery for active play intent and
  pause-qualified seek completion. Free scrubbing remains disabled
  for transcode plans via
  `requiresLinearPlayback` — a system-UI seek outside the produced window would
  bypass the Kotlin transcode-restart path and wedge AVPlayer.
- Track/quality selection rides `transportBarCustomMenuItems` over the shared
  option builders (`audioOptions`/`subtitleOptions`/`qualityOptions`) with the
  PlayerViewModel local-switch-vs-replan rules and durable subtitle intent
  (stored intent validated against options, invalid entries deleted — the intent
  store is device-local — then preferred language, default track, Off). Menus
  render confirmed activation truth, never pending requests; a separate local
  selection intent keeps Off available while local activation is pending. They
  rebuild only on a menu-configuration key (plan epoch + track lists + confirmed selections +
  bitrate), never per position tick. Settings and the in-player menu share the
  same quality ladder so defaults always match a rung.
- Healthy remote changes use the proposal contract in
  [playback policy](playback-policy.md#quality-semantics), retaining
  installed playback on eligible planner rejection. A passive typed notice
  explains the rejected operation and stays until dismissed or superseded.
  It does not block interaction or unmount AVPlayer, and waits behind higher
  priority surfaces. Retry, item start and close clear it; a local-subtitle
  unavailable notice created by startup or repair survives its own install.
- Chapters render as `AVNavigationMarkersGroup` on the player item; segment
  skipping honors the per-type `SegmentSkipPolicy` (`contextualActions` for Ask,
  presenter-driven once-per-item auto-skip while Playing, ordered after the
  Start report). A verified natural completion starts the normalized shared
  0–60 second next-up delay (default 10); a pre-end suggestion never starts it.
  While the chronological queue is pending, verified completion retains the host
  until that identity-qualified request settles. A next item starts next-up;
  an empty or failed result ends playback. Item, queue-order and launch-generation
  identity qualify countdown/dismissal.
  Manual selection, queue changes, close, or background cancel it. The autoplay
  preference is read at each item start; a disabled preference prevents that
  item's countdown. Dismiss suppresses automatic advance for that completion while
  preserving manual Next. The player remains mounted while countdown or
  still-watching owns completion. The shared `PlayerStillWatchingState` gates
  three consecutive automatic advances; manual navigation resets it.
  Item-scoped AVKit decorations re-apply on plan-epoch changes. Queue changes
  invalidate in-flight loads/replans and clear outgoing track menus. Committed
  manual navigation stops the outgoing engine, retaining its final position for
  ordered Stop reporting. A failed replacement stays authoritative until a valid
  new prepare; stale controller updates cannot replace it.
- A failed detail fetch surfaces `PlaybackError.Network`; `UnsupportedMedia` is
  reserved for a successful detail response with no usable media version.
- Backgrounding closes the playback presenter (final Stop report + release) via
  SwiftUI scene phase; route dismissal does the same via `onDisappear`.
- tvOS shares `PlaybackAudioSession` through the appleMain
  `AppleAVPlayerController`: playback session activation/deactivation and
  interruption pause/conditional-resume now apply on tvOS too. The tvOS
  presenter consumes the typed Auto/Original/Fixed policy,
  current-playback choices, runtime Auto recovery state, and first-output bridge.
  SwiftUI renders a persistent, dismissible action overlay above AVKit controls;
  it does not choose a stream or mutate native transport directly. Now Playing
  remains system-player owned.
- tvOS executes the same shared exact-stream one-shot Encode decision as shared
  UI, including initial missing-response and runtime activation recovery,
  position and play/pause preservation, ordered Stop reporting, and stale/stop
  invalidation. Final failure stays nonfatal with no subtitle selected.
- Offline playback uses `TvOfflinePlaybackPresenter`, the same core offline
  plan construction as shared UI, and the required Apple VLC controller. Only
  persisted snapshot metadata, local tracks, chapters, and local progress enter
  this path; no detail, PlaybackInfo, image, segment, queue, or reporting calls
  occur and no online fallback is attempted. The presenter applies saved audio
  and subtitle activation targets before playback, reasserts embedded selections
  and Off, and retains artifact-qualified identity for downloaded sidecars. VLC
  attaches sidecars only through the validated offline lease; a trusted leased
  sidecar starts pending activation without requiring a remote subtitle asset.
  Menu checkmarks follow confirmed activation. The native VLC host provides
  play/pause, seek, progress and supported controls over the retained drawable.
- Advanced controls expose confirmed speed, supported text subtitle style,
  AVKit surface sizing, and sanitized diagnostics. Timing and VLC surface sizing
  are unavailable. Apple VLC timing and sizing are unimplemented adapter
  integrations: VLCKit exposes native delay and sizing APIs, so these are not
  native API limitations. Its live font-scale API alone does not establish the
  full shared text-style contract; `appliesSubtitleStyle=false` remains truthful.
  Local
  subtitle selection carries asset identity through detail and online playback;
  missing/purged files fail nonfatally without remote Encode fallback.
- Custom trickplay scrubber thumbnails remain deferred; online transcodes retain
  the system HLS I-frame preview path.

## iOS

Apple capability reads use a launch-frozen, Main-captured
`UIScreen.mainScreen.potentialEDRHeadroom > 1.0` snapshot (failed/unavailable is
false). A true snapshot permits HDR10/HLG only for Apple-claimed HEVC and
hardware-decodable AV1; H.264 stays SDR and Dolby Vision stays false. Later
reference-mode, mirroring or external-display changes do not refresh it.
AVPlayer rejects interlacing for every codec and requires HEVC `hvc1|dvh1` at
no more than 60 fps. H.264/AV1 do not inherit those HEVC conditions. VLCKit's
separate engine profile accepts `hev1`/`dvhe` and deinterlacing without importing
AVPlayer's probe or conditions; it claims no HDR. Multichannel declarations do
not establish Apple hardware output acceptance. Subtitle delivery belongs to
[selection and delivery](subtitles.md#selection-and-delivery).

iOS Compose selects AVPlayer or VLCKit before initial planning and also exposes
the shared explicit remote session switch. AVPlayer uses the narrow
ready-for-display bridge from its native presentation surface. VLCKit uses
best-effort transition counters but does not advertise reliable shared
first-output measurement; see the [iOS recovery contract](#ios-recovery).
AVPlayer/VideoToolbox capability facts and
VLCKit engine facts are separate: one backend's hardware probe cannot erase a
codec from the other. Capability provenance likewise keeps the AV1 hardware
probe, static codec declarations, documented finite ceilings, and unknown
VLCKit limits distinct. Both receive the shared policy/notice model, while
Apple audio session, PiP, native track mapping, and authenticated URL handling
stay in Apple-owned code.

Standard compatibility applies the same app-owned 4K30 frame-area/throughput
input envelope to both AVPlayer and VLCKit. Only finite width/height conditions
reach the server profile; frame area and proportional throughput remain local
source-copy limits. Unrestricted removes only this marked envelope from the
profile and preflight, preserving backend codec, container, range, explicit
quality, and runtime-health constraints. This is product policy, not shared
decoder evidence.

## tvOS native playback

tvOS uses native SwiftUI player hosts over shared Kotlin presenters.
`TvPlaybackSessionPresenter` resolves the same typed policy, keeps player
choices session-only, and consumes the same session-recovery and Auto-recovery
kernels as the Compose player. The
AVPlayerViewController host qualifies layer availability and ready-for-display
observations against player, item, plan and native prepare identity before
changing observer state. An observed layer enables reliable first-output health
evidence; availability alone does not mean a frame was presented. Missing or
retired layers remain unavailable. The transport callback epoch is separate.
SwiftUI
renders persistent dismissible actions above the AVKit controls without
rebuilding transport menus on playback ticks or stealing Siri Remote focus;
the presenter controls any replan. Simulator success is not Apple TV hardware
decode, PiP, remote, or presentation evidence.

tvOS publishes `playerInstalled` only after Main installs the concrete
controller, creates reporting from that controller state flow, and attaches
output observation. Swift re-reads `platformPlayer` from that readiness update
instead of retaining an initialization snapshot. A close before installation
reports nothing and leaves any late candidate to startup ownership for one
release; a close after installation retains final reporting settlement and one
native release.

Online sessions resolve the intended audio and source before concrete backend
selection, then use that backend for default quality and every planning request.
Playback Controls exposes a session-only Player menu for available AVPlayer/VLC
choices. Switching retains the old owner through candidate/planning validation,
then transfers native host, reporting and health ownership under the installation
guard; see [backend switching](playback-policy.md).
`TvOfflinePlaybackPresenter` loads the
account-qualified local download and shared `buildOfflinePlaybackPlan`, requires
VLC, and updates local progress without online planning or reporting. The
MainActor native model watches exactly one presenter and mounts the host for its
installed backend: AVKit for AVPlayer, or a controller-owned VLC drawable with
native transport for online and offline VLC. The Apple engine owns leases and native teardown; Swift never
resolves artifact paths. Queue/countdown and native panel behavior are owned by
[native tvOS playback](#tvos-native-player) and
[ui.md](tvos-ui.md#native-tvos-screens).

<a id="why"></a>
See native lifecycle, transport and evidence rules above.
