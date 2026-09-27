# Android Playback Backends

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Media3 audio-output recovery

- Android Media3 treats persistent audio-output failures as recoverable when
  video can keep playing: after transient AudioTrack init retries are exhausted,
  the controller disables Media3's audio track type, re-prepares the current
  plan at the saved position, and reports `audioUnavailable = true` instead of
  failing the whole player. A fresh media item re-enables audio; an explicit
  audio-track selection also re-enables audio for the current item so users can
  recover after fixing receiver/TV output.

## Android mpv backend

- [Backend selection](playback-runtime.md#backend-selection) owns Android backend policy — the
  default, `Auto` normalization, and the visible per-shell order. Within that
  policy, mpv is the TV opt-in alternate backend and the existing backend
  dialog is the explicit opt-in; the persisted server-scoped choice resolves
  initial PlaybackInfo and remains authoritative across queue items unless the
  user makes an explicit session-only switch.
- `AndroidMpvRuntimeAvailability` is a cached, cheap bundled-library check. It
  verifies the OS API level first — mpv requires Android API 26+
  (`ANDROID_MPV_MIN_OS_API`), and older devices get the same typed availability
  refusal (`OsApiBelowMinimum`) — then class-loader library paths, ABI
  agreement, the three shipped ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`), and
  the running device ABI without loading JNI or creating an engine. The ExoPlayer path does no mpv native work;
  full create/init admission is reserved for a requested mpv controller and
  runs on the work dispatcher. A successful initialized context is handed
  directly to that controller; failures destroy the candidate, and normal
  ownership ends at controller release.
- Requested Android mpv construction emits an Info-priority, sanitized
  `backend-construction` record before any ExoPlayer fallback. It distinguishes
  bundled availability (including the closed ABI), unavailable reason,
  subtitle-store resolution, network-policy creation, trust-bundle creation,
  audio-focus resolution, native-engine creation, option application,
  observer/property registration, and native initialization, plus created/
  failed/unavailable outcome and exception class. A rejected option includes
  only its fixed option key and native result code, never its value. Exception
  messages, paths, URLs, tokens, titles, and identifiers are never included.
  The record must survive release minification and be available through both
  the sanitized in-app safe-history store and the verbose platform-log path.
- The project-owned Android wrapper defaults each new native instance to the
  mpv `no` log request. The controller selects `Error` before initialization,
  or `Verbose` while **Collect diagnostic logs** is enabled, following toggles.
  `AndroidMpvEngine` reduces errors to an allowlisted severity/category. From
  verbose callbacks it accepts only fixed numerical FFmpeg output-crop and mpv
  ImageReader texture-size messages, discarding all other text. Structured
  `native-video-format` records distinguish crop-derived decoder dimensions
  from imported texture dimensions, include the current surface size, and are
  limited to 16 distinct observations per prepare with generation/collection
  guards. Texture dimensions appear only when mpv reports a size change;
  absence does not establish buffer dimensions. Capture enabled/disabled/failure
  milestones distinguish log-request status from missing observations. A failed
  verbose request falls back to `Error`; a failed initialization Error request
  retains the fixed `mpv-log-level` construction failure. The bridge never writes
  raw mpv messages or routine event names to Android logcat.
- The same format record accepts optional experimental ImageReader observations:
  buffer allocation, image dimensions, valid image crop, mapper source/destination
  dimensions, and closed `Success`/`Failed`/`Unavailable` query results. Failed or
  unavailable query values remain absent. ImageReader crop right/bottom are
  exclusive; FFmpeg `DecoderCrop` endpoints are inclusive. These observations
  require an instrumented native bundle; their absence on the stock provider
  bundle does not imply unavailable hardware or a particular image size.
- Android mpv prepares a file with mpv 0.41's five-position command shape:
  `loadfile`, opaque URL, `replace`, playlist index `-1`, then the per-file
  `start=` option. The URL never enters JellyScope diagnostics. Sanitized
  `native-lifecycle` records instead expose network-request acceptance, the
  closed load-command shape, `START_FILE`, `FILE_LOADED`, surface/readiness
  gates, unpause dispatch, terminal native events, and startup timeout. A load
  dispatch without `START_FILE` identifies command admission; `START_FILE`
  without `FILE_LOADED` identifies native open/demux work. Startup timeout uses
  the last safe native error category when available and otherwise stays
  `Unknown`; it is never relabeled as a network error without evidence.
- Android mpv retains the current engine-bound native surface across same-host
  prepares, including in-player quality replans. A replacement `loadfile` must
  not reattach the identical `Surface`: doing so can rebuild the zero-copy TV
  video output and block before command dispatch. The sanitized
  `SurfaceAlreadyAttached` milestone distinguishes the intentional reuse from
  a missing surface callback without exposing a handle.
- Android mpv seek confirmation tracks both the pending origin and the latest
  requested target. It keeps publishing the target while current-generation
  native `time-pos` remains short of that target in the seek direction; a
  250-ms tolerance covers native clock quantization, and a stale origin-plus-
  small-delta tick is not confirmation. Coalesced seeks preserve the original
  origin and replace only the target. `prepare`, `stop`, `retry`, `release`,
  and the TV surface-reload reset all cancel the coalescer and clear the full
  pending seek state. Confirmation holds are bounded: a generation- and
  target-guarded watchdog abandons an unconfirmed seek after a bounded wait
  (5 s default), records a sanitized `Timeout` seek diagnostic, and lets the
  next native `time-pos` tick republish observed truth, so a seek mpv refuses
  or clamps can never pin `seeking` or freeze the published position. Seek request/confirmation records use only the bounded,
  allowlisted origin, target, observed position, generation, and operation
  fields.
- Android mpv embedded audio and subtitle mapping treats an exact
  `track-list` row with `selected=true` as authoritative activation for the
  requested selector. It confirms that target immediately and does not wait
  for mpv's same-value `aid`/`sid` property callback, which may be omitted.
  An exact row that is not selected keeps the existing setter, exact readback,
  and non-extending three-second deadline. Mapping resolution and the
  already-selected confirmation use bounded, deduplicated structured records
  (`mappingResult`, `mappingReason`, track kind, candidate count, and
  operation); raw mpv output and track identity never enter diagnostics.
  External `sub-add` passes JellyScope's generated track title as mpv's raw
  positional title argument, then requires an exact selected external
  `track-list` title readback before activation. Prefixing that positional value
  with `title=` changes the native title itself and is invalid.
- `AndroidMpvCapabilityMatrix` is separate from Media3 and LibVLC. The pinned
  mpv/FFmpeg declaration covers its listed containers, video/audio families,
  text and bitmap subtitle delivery, and up to eight audio channels. It makes
  no HDR, Dolby Vision, audio-passthrough, or display-derived capability claim.
  MediaCodec contributes only complete bounds for the video families explicitly
  delegated to it; unknown or incomplete facts stay unknown rather than becoming
  guessed software ceilings.
- `AndroidMpvEnginePolicy` is deterministic and controller-owned: per-class
  video output (`gpu-next` for the copy/software classes, classic `gpu` by default for TV;
  the renderer rationale is owned by
  [`playback-architecture.md`](playback-architecture.md)),
  Android/OpenGL ES, no mpv config/scripts/ytdl/cookies/autoloaded resources or
  native UI/input bindings, disabled subtitle auto-selection (`sid=no`), `idle=yes`,
  `keep-open=always`, TLS verification, and cache pause. Audio starts with
  `aid=auto`; the controller separately applies the pending exact audio mapping
  after FILE_LOADED and gates activation confirmation. The initial cache policy
  is a 10-second target with 64 MiB forward and 16 MiB backward caps. Mobile
  uses `mediacodec-copy`, emulators use software decode, and TV uses zero-copy
  `mediacodec` with the `fast` profile in its explicitly selected opt-in
  path.
- Android TV exposes **Settings → Advanced playback → mpv video output** as a
  device-local preference, defaulting to **GPU**. The controller factory snapshots
  it at construction; changes apply to the next playback session. **Direct
  MediaCodec** selects `vo=mediacodec_embed` with the existing hardware-decoding
  policy. Mobile and emulator output policies are unchanged. Selection is explicit;
  subtitle or sizing requests do not silently change renderers.
  Direct output requires hardware decoding and bypasses mpv subtitle/OSD and
  Fit/Fill/Zoom rendering. Embedded or external local subtitle activation reports
  `mpv-direct-output-unavailable`, allowing the existing subtitle recovery policy
  to request server-rendered subtitles where available. Burned-in subtitles remain
  visible. Sizing changes are refused and the TV menu explains how to select GPU.
  Both drivers are already present in the pinned native bundle.
- Android mpv writes its own verbose log beneath `Context.noBackupFilesDir` at
  `mpv-logs/mpv-verbose.log` (the prior file is retained as
  `mpv-verbose.prev.log`; rotation happens per engine instance and on each
  mid-session re-enable, and a 30-second watchdog closes the file once it
  crosses 32 MiB). Writing happens only while the diagnostics-collection
  setting is on — mid-session toggles close or reopen the file live, and
  disabling collection deletes both retained files even with no player
  active. This file is the primary evidence for user-reported playback
  defects; the in-process observer seam stays reduced to sanitized
  categories. The RAW file contains secrets — mpv echoes the Authorization
  header via its `http-header-fields` property at verbose level — which is why
  it lives in no-backup storage and never enters client-log uploads. The log
  provider removes the legacy `files/mpv-logs` directory on use and when
  collection is disabled; both app shells exclude that legacy subtree from
  cloud backup and device transfer. Uploads contain only the structured
  diagnostics report and scrubber-admitted bounded history.
- `AndroidMpvNetworkPolicy` strips auth-query parameters before native loading,
  attaches only the modern token-only `Authorization: MediaBrowser
  Token="..."` header on the trusted same-origin HTTP(S) request, clears stale
  header state before a load, and never logs the URL, header, or token. Cleartext
  is allowed only for a same-origin host permitted by Android's
  `NetworkSecurityPolicy`. Remote subtitle sidecars must be same-origin; a
  cross-origin sidecar fails closed into the existing unavailable/Encode path.
  Project-owned local subtitle files are resolved through
  `LocalSubtitleFileStore` and never receive credentials.
- mpv/FFmpeg does not inherit Android network-security-config domain pins or
  per-domain rules from the exported CA roots, and the native stack cannot
  intercept credential forwarding across an HTTP redirect. This is the
  documented trusted-initial-authority residual; HTTPS verification remains
  enabled with an atomically replaced PEM bundle built from Android's default
  trust managers. Android has no `tls-verify=no` fallback or user exception;
  the explicit [account/server desktop exception](accounts-and-persistence.md#resource-authentication) is desktop-only.
- The vendor adapter converts callbacks to project-owned events. The
  `AndroidMpvPlayerController` owns serialized native calls, generation-scoped
  state, play/pause/seek/retry/release, exact track and subtitle activation,
  timing/style, audio focus, and sanitized diagnostics. Shared UI's Android-only
  `AndroidPlayerSurfaceHost` owns the common mobile/TV
  Compose hierarchy and maps Fit/Fill/Zoom plus the shell-supplied subtitle
  inset into project-owned presentation. `AndroidMpvSurfaceView` plus
  `AndroidPlayerSurfaceBridge` owns native SurfaceView callbacks. The shared
  host gives each exact controller/platform-player binding a process-monotonic
  owner token: a replacement detaches before it attaches, and delayed attach,
  release, or presentation work from an outgoing token is ignored. Factory
  creation is the only attach path; resize, style, and inset updates never
  attach. The Media3 path likewise owns one cue consumer per binding. A valid
  attach enables mpv's native window, writes the current
  `android-surface-size`, and reapplies dimensions after rotation; detach clears
  that window without tearing down the VO pipeline. Shared
  UI and TV UI never import mpv types. A current `FILE_LOADED` establishes load
  readiness even with a null duration; observed `eof-reached` is ignored until
  that generation is loaded, and `keep-open=always` keeps the property readable
  after natural EOF. An in-player embedded-subtitle switch also updates the
  controller-owned activation target, so Retry and TV surface-loss reloads do
  not restore the earlier Subtitle Off state. Native startup recovery follows
  the qualified [backend fallback contract](playback-runtime.md#backend-selection); it never
  changes a live controller in place.
- Android mpv treats a loaded, video-expected plan as `UnsupportedMedia` when
  a native decoder/output initialization or video-conversion failure is followed
  by confirmed `vid=no` in a non-idle, non-EOF session. Both native error and
  video-selection changes trigger confirmation, independent of diagnostic
  collection. It pauses native playback before publishing failure, stops that
  prepare, and enters the existing bounded backend recovery at the retained
  position. Audio-only plans, successful internal decoder fallback, unavailable
  properties, natural EOF, and stopped/replaced/released prepares do not trigger
  this path. A structured failure records the closed native reason and disabled
  video track. Existing quality policy and offline recovery exclusions remain
  authoritative; this does not enable direct-surface presentation.
- Android mpv sustained slow-software recovery is mandatory player UI, independent
  of playback-health diagnostics and warning settings. A current prepare must
  report Software decoding, explicit play intent, Playing and at least 3 seconds
  buffered. After a 10-second prepare grace, consecutive observation windows of
  at least 5 seconds must accumulate 20 seconds below half the selected speed.
  Pause/seek/resume/replan, speed/PiP changes, insufficient buffer, decoder changes,
  discontinuous position and observation gaps over 10 seconds discard partial
  evidence. Existing state observations drive detection; no polling job is added.
  Online Android mpv only opts in; offline/PiP playback does not trigger it.
  Detection enters the [mandatory recovery decision](playback-runtime.md#mandatory-mpv-recovery).
  The confirmed position is preserved through the explicit switch path. The
  detector initiates no automatic backend change or quality replan.
- The shared Android player-surface host sets `keepScreenOn` on every mobile
  and TV playback view, whether the backend supplies an
  `AndroidPlayerSurfaceBridge` view or uses the Media3 wrapper. The view flag
  belongs to that view's composition lifetime and is cleared on release. On
  Android TV, `TvPlayerScreen` additionally owns
  `FLAG_KEEP_SCREEN_ON` on its resolved Activity window for the whole mounted
  player route — including Loading, Buffering, Playing, Paused, controls,
  pickers, and dialogs — so backend-view replacement cannot create a
  wakefulness gap. Route disposal clears the window flag; a missing Activity is
  a safe no-op and retains the playback-view safeguard. Android mobile keeps
  the view-lifetime policy. Neither shell uses a process singleton, service,
  `PowerManager.WakeLock`, `WAKE_LOCK` permission, polling, or device-specific
  branch.
- Android mpv reports buffering, dropped-frame, decoder, cache, and bounded
  runtime facts through the existing diagnostic contract. The first load arms
  hot samples immediately. Replacement prepare and TV surface reload keep
  clock/cache/diagnostic samples disarmed through late outgoing callbacks and
  any retiring `START_FILE`; after the expected retiring `END_FILE` is consumed,
  only the following matching `START_FILE` for the awaited replacement
  generation arms them. Disarmed hot callbacks are consumed rather than
  retagged, so they cannot mutate replacement state or satisfy replacement
  seek/resume confirmation. Once armed, ordinary
  current-generation `time-pos`/buffer samples are conflated before Main and
  publish the latest clock at an approximately 250 ms cadence; cache speed,
  frame rate, dropped-frame health, and buffered-ahead diagnostics publish at
  most once per second. Seek/resume confirmation and lifecycle, error, track,
  activation, completion, and recovery edges remain immediate. Prepare, TV
  surface reload, stop, and release invalidate both retained samples and their
  cadence so an outgoing generation cannot delay or overwrite its replacement.
  The backend currently declares first-video-output measurement unsupported, so
  surface attachment or native configuration is not presented as displayed
  output. Native logs are reduced to allowlisted stage/event, exception, and
  bounded numeric fields. Surface lifecycle records include attachment state,
  bounded width/height, size-change and detach milestones, and ignored stale
  callbacks/releases; runtime native option rejection includes only the fixed
  key and numeric code. Those records must make an audio-with-black-video report
  distinguish missing geometry, native-window rejection, current detach, and a
  late stale detach from logs alone.
- Android mpv uses the project-owned `android-libmpv` module. Its source/build
  provenance, native inventory, license/source obligations, package verification,
  and separate runtime gate are owned by the
  [Android native dependency runbook](../operations/android-native-dependencies.md).
  Desktop libmpv remains a separate integration.

## Audio delay, focus, and buffering

- Android Media3 audio delay runs through a PCM audio processor (positive values
  insert silence; negative values drop leading frames) that is **inert at zero
  offset** (full passthrough/offload retained). It deliberately applies **no
  media-clock compensation**: the master audio clock counts the inserted silence
  and lets dropped frames advance the playout position, because that is exactly
  what shifts the audible audio relative to the clock-slaved video. Consequently
  **reported position and Jellyfin progress track the video**, and the resulting
  ≤20 s cosmetic offset versus the audible audio is an **accepted** trade — do
  NOT reintroduce clock compensation (it re-syncs video to the shifted audio and
  cancels the correction). A non-zero offset forces a decoder for encoded
  passthrough (so the PCM processor sees samples) and every offset change is
  applied by a single coalesced (~400 ms), position/speed/style-preserving
  re-prepare rather than mutating the processor from the main thread.
- Media3 subtitle timing is **PositiveOnly**: the decoder callback is the
  earliest observable point for a cue, so a negative (earlier) shift is clamped
  to zero and the UI hides the negative controls; a both-signs implementation
  would require replacing Media3's final TextRenderer (deferred). LibVLC
  supports both signs via native delay. Cues render through a project-owned
  overlay `SubtitleView` (PlayerView's built-in one is hidden) so output does
  not depend on listener registration order; the renderer keeps a bounded,
  source-time cue-event timeline (including cue-end events), schedules a single
  speed-aware transition callback, seeds from the current cues on attach, and
  clears on seek/discontinuity/release. Downloaded OpenSubtitles assets use the
  same offset controls as server tracks.
- All Android player controllers use the shared audio-focus/noisy policy. Focus loss
  pauses or ducks without resurrecting a stopped generation, focus gain resumes
  only an item that still has play intent, and a becoming-noisy/headphone
  disconnect pauses. The sole-owner rule and its three controller consumers are
  defined in [`architecture.md`](architecture.md#platform-bridge-rules).
  Media3 delayed audio recovery is generation/job-bound and checks liveness,
  source identity, and play intent before any re-prepare or `play()` call.
- A ducked volume survives a LibVLC media replan. During a native prepare the
  transition queue owns the player, so a focus handler must not call
  `setVolume` itself; the completion replay restores the cached pre-duck level
  alongside the playback rate. `setVolume` reports `-1` until a native audio
  output exists, and none exists yet at replay time — so a rejected write
  **retains** the cached level instead of discarding it, letting a later focus
  event restore it. Discarding on rejection strands playback at the ducked
  level until the next full duck/gain cycle. `prepare()` abandons audio focus,
  so a duck cached before the transition is stale by definition; the system
  re-issues Duck against the new focus request if it still wants one.
- Media3 streaming keeps fixed 8–20 second buffer durations, 1 second initial
  startup, and 2 seconds after a rebuffer. It explicitly prioritizes streaming
  time thresholds, so reaching the byte target cannot stop loading below the
  8-second floor or start playback below the applicable startup duration.
  `ActivityManager.isLowRamDevice` is resolved once when the controller is
  created; missing or failed classification is conservatively low-RAM. Low-RAM
  and regular devices currently use the 16 MiB control target. A regular-device
  `min(Runtime.maxMemory()/3, 384 MiB)` target exists only as an A/B candidate.
  It must not become the default until a controlled comparison uses the same
  high-bitrate DirectPlay, HLS DirectStream, and server Transcode fixtures for
  five cold-process runs per policy with server, route, quality, start position,
  and duration fixed. Retain the candidate only if it eliminates a reproducible
  rebuffer or improves median post-start rebuffer duration by at least 20%, while
  median launch-to-first-frame regresses by no more than the larger of 500 ms or
  20%, peak total PSS grows by no more than the larger of 64 MiB or 20%, and no
  OOM, process kill, critical trim, or sustained GC thrash occurs. An API-26+
  low-RAM TV baseline must also keep the 1-second initial, 2-second rebuffer, and
  8-second loading floors despite reaching 16 MiB. Otherwise retain 16 MiB and
  do not weaken the time floors.
- The Media3 byte value is a **load-decision target**, not a Java-heap or
  process-memory ceiling. Time priority may allocate beyond it while reaching
  the duration floor, and codec, decoder, audio, graphics, and other native
  allocations are outside Media3's allocator. The existing one-second position
  ticker samples buffered-ahead and allocator bytes while Playing, Buffering,
  and Paused, because loading can continue with a stationary playhead. Idle,
  completion, failure, stop, and release stop that sampling. Android mpv instead
  publishes buffer diagnostics after applying its observed clock/cache sample,
  so a final cache update while paused cannot race ahead of the values it reads.
  Neither path invents buffer growth after the backend stops loading.
  Media3 callbacks aggregate first
  frame, true post-start rebuffers, and audio underruns once per prepare epoch.
  Process PSS and memory-pressure evidence are sampled externally.

## LibVLC and platform transport

- Android LibVLC attaches its `VLCVideoLayout` only after the Compose view is
  attached to a window. The vout callback re-enables the video track and
  re-issues a pending play request, which prevents an initial attach from
  leaving audio running with no video surface. Android uses LibVLC's
  `SurfaceView` output path as the backend presentation contract.
- Android LibVLC **coalesces** seek requests through `SeekCoalescer` (250 ms
  window, newest target wins) before `setTime()` flushes the decoder. `pause()`
  flushes a pending target; `prepare()`, `stop()`, and `release()` cancel it.
  From request until native arrival, the published position remains the requested
  target rather than the stale pre-seek clock, so the seek bar moves once.
- Android LibVLC seeks explicitly enter `Buffering` while the requested target
  is being resolved. High-frequency native buffering callbacks are coalesced;
  the initial clock jump to the requested timestamp remains `Buffering`, and the
  state returns to `Playing` only after that clock advances again to prove
  playback resumed. A delayed fallback probe may confirm that same progress, but
  it must not force `Playing` while a slow stream remains stalled. This
  preserves the TV spinner without allowing Fire OS callback storms to
  monopolize the Main dispatcher.
- Android LibVLC treats `TimeChanged` and `PositionChanged` as one native
  progress source. Every accepted native sample still feeds seek and
  end-of-stream correctness, while one approximately 250 ms publication policy
  deduplicates the exact effective position, duration, and status. Seek
  completion and status/duration edges publish immediately. The one-second
  ticker always refreshes diagnostics but publishes progress only when native
  callbacks are missing or stale; pause, terminal, prepare, stop, and release
  reset freshness so the first sample of a new active interval is admitted.
- Android LibVLC initial playback remains `Buffering` until its native clock
  advances. A non-zero resume position uses the same arrival-then-advance rule
  as an explicit seek, so an early native `Playing` event or immediate
  `setTime()` readback cannot expose a black frame as ready playback.
- Android LibVLC quality replans detach the retained VLC views before replacing
  native media, then run the potentially blocking `MediaPlayer.stop()` and media
  assignment serially on the platform IO dispatcher. Prepare completion is
  generation-gated; a newer request coalesces the pending native transition, and
  the current generation reapplies the retained surface, latest track/speed
  selections, seek target, play/pause intent, and the existing bounded resume
  output re-lock. This is a private Android controller seam; it does not widen
  `PlayerController` or change other backends.
- The safe capped H.264 resume reproduction also separates clock arrival from
  displayed-picture readiness. After the deferred start position is marked
  applied, Android LibVLC keeps the generation in `Buffering` and arms at most
  one bounded output/decoder re-lock after the existing
  `STARTUP_RESYNC_MIN_LAG_MS` when no displayed picture has arrived. One
  displayed-picture sample at resume arrival establishes the fresh-media
  baseline and a second sample after that delay can prove healthy output
  without waiting for two 1-second ticker intervals.
  First displayed-picture evidence cancels it and can publish `Playing` only
  after position advance; user seek, pause, stop, prepare, surface replacement,
  release, and stale generations cancel or consume it. This is a controller
  safeguard for the confirmed safe black-start condition, not a repeated seek
  loop or a visual-pass claim.

- Android LibVLC's media listener belongs to the assigned native media
  generation. `prepare()` detaches the old listener before generation advance
  and old-media teardown, assigns and publishes the replacement, installs a
  listener capturing the replacement generation, and only then attaches any
  subtitle slave. `stop()` and `release()` likewise detach before generation
  advance and teardown. Events are handled directly on LibVLC's documented
  main-thread callback; stale captured generations are dropped. A genuine error
  while paused becomes `Failed`, while post-stop/detached-generation errors
  cannot overwrite `Idle` or a replacement session. Periodic runtime/progress
  reads execute off Main under the native-transition mutex, checking the exact
  native media before reading and the Main-owned generation before publication.
  Pending transitions publish the requested or cached position and cached
  duration; they cannot restart polling merely by publishing `Buffering`. Snapshot
  cancellation does not imply that an in-flight JNI call was interrupted. Native
  events from old media delivered to a newly installed listener remain a
  device-validation residual.
- Android LibVLC publishes native runtime diagnostics at a low rate: codec
  label, dimensions, frame rate, and lost-picture count. Before media assignment
  it also emits one Info-priority `backend-readiness` record with the closed
  bounded-dav1d policy and frame-thread value. Production diagnostics can prove
  the applied option and stream codec, but libvlc-android 3.7.5 exposes no public
  API for the native decoder module that actually opened; they must not claim
  `dav1d` as an observed decoder. LibVLC bandwidth is intentionally left unknown
  because the API does not document the unit of `inputBitrate`; the debug overlay
  must not show a false bits/sec value. Audio and subtitle track commands resolve
  native IDs through the shared stable-index/metadata/ordinal resolver, while
  the native subtitle Disable entry remains mapped to `-1`. Embedded activation is driven by
  `ESAdded`/`ESSelected`, not time/position ticks. Empty candidates before
  native Playing do not count; Playing or non-empty candidates arm one
  non-extending three-second timeout. Ambiguous/unsupported mapping fails
  immediately, not-found waits for another ES event or timeout, and a rejected
  setter retries every 250 ms for at most ten attempts. Setter acceptance still
  requires exact native ID readback or matching `ESSelected` before `Active`.
  External/local subtitle attachment snapshots existing IDs immediately before
  `addSlave(select=true)` and confirms only a post-attachment text ID that is
  both selected and read back from `spuTrack`; ambiguous evidence remains
  Pending until timeout. Off, replacement, stop, and release cancel the target,
  retry, and timeout state.
- Android mobile PiP actions and MediaSession commands call
  `PlayerPlatformCommandCallbacks` owned by the active `PlayerViewModel`.
  `BackendMediaSessionPlayer` is a backend-neutral `SimpleBasePlayer` adapter
  used by AndroidX MediaSession for ExoPlayer, LibVLC, and mpv; no raw native
  player object or controller is passed to MediaSession. Only a successfully
  installed `BoundedVod` plan is seekable. After either initial or same-item
  `prepare`, a normal return whose synchronous state is already `Failed` is not
  installed truth: the installed plan remains absent, seekability stays revoked,
  and no selection or play follows. Default-position, in-item,
  media-item-position, back, and forward seek commands are exposed and accepted
  only for a successfully installed bounded plan; queue next/previous,
  queue-boundary variants, and the restart-or-previous rule remain separate. A
  monotonic publication owner stops an outgoing composition from restoring or
  clearing a newer active player, and adapter replacement revokes the retiring
  callbacks before release.
  Positive planned video
  dimensions own PiP's stable, normalized coded aspect, with 16:9 as the
  missing/invalid-metadata fallback. Aspect/action/auto-enter signatures are
  recorded and deduplicated before Android is called. Changing Compose bounds
  never enter that signature: they are deduplicated separately and sent only
  as an aspect-free partial `sourceRectHint` update so transition guidance can
  follow the rendered surface without consuming Android's aspect-change quota.
- Android TV's root player key bridge handles dedicated Play, Pause, Play/Pause,
  Next, Previous, and Stop keys in the controls, queue/Up Next, picker, loading,
  buffering, and error states. Play only starts, Pause only pauses, Toggle
  inverts, Next advances through the ViewModel queue, Previous seeks to item
  start after five seconds or moves to the prior queue item, and Stop follows
  the existing stop-and-back contract. Space remains the TV toggle/skip
  affordance where the current state already owns it.
- The Jellyfin Media3 FFmpeg extension is consumed as a published Maven artifact
  (`org.jellyfin.media3:media3-ffmpeg-decoder`, version-matched to Media3
  1.9.0), depended on unconditionally (fail-closed); no in-repo native build is
  required. Adopting a newer decoder is a `libs.versions.toml` version bump.
  Device/runtime FFmpeg-decode validation remains
  a release gate. Capability probing calls the shipped runtime's availability
  and E-AC-3 support checks behind a non-throwing process cache. An unavailable,
  unsupported, linkage-failing, or throwing probe leaves Media3 on its prior
  platform-only audio profile.
- Android LibVLC 3.7.5 receives only `CredentialOriginGuard.authorizedUrl`
  output: same-origin HTTP(S), no userinfo, inbound auth-query stripping, and
  exactly one current `ApiKey`. Legacy inbound spellings such as `api_key` are
  still stripped before the current value is appended. Local subtitle files
  never receive a token.
  LibVLC's native same-origin redirect cannot be intercepted reliably; this
  documented redirect residual is limited to a trusted initial authority and is
  not a reason to attach headers or add external-player intents.

## Android TV display refresh-rate matching

- Android TV owns display refresh-rate matching through
  `TvDisplayModeController`; Jellyfin and the shared planner own what is
  streamed. The controller consumes `PlaybackPlan.videoPresentation` after
  planning and must never feed display results back into device profiles,
  PlaybackInfo, or stream-mode decisions.
- The persisted `matchDisplayRefreshRate` preference defaults to false. When it
  is enabled with a usable content frame rate, matching considers only modes at
  the current display resolution, preferring exact refresh, then an integer
  multiple, then the reported 2.5× fallback tier. While it owns a mode, the
  controller disables Media3 frame-rate switching so there is exactly one mode
  owner.
- `TvDisplayModeTelemetry` remains TV-owned runtime state for active and
  requested modes, match tier, result, and switch duration. `Off` means the
  setting is disabled; `Unavailable` means no usable target or display
  capability is available; `Applied`, `Switched`, and `TimedOut` retain the
  observed request outcome. A 2.5× decision is a fallback tier, not an exact
  cadence match; these display facts are not rows in the shared overlay.
- Releasing display control restores preferred display mode id `0` and Media3's
  normal frame-rate strategy. It is required when the setting turns off, plan
  metadata is absent or invalid, a fatal playback error occurs, the player
  ends/stops or routes back, lifecycle `ON_STOP` fires, and the player
  composition is disposed. This cleanup must happen before leaving the player;
  the default must remain off until physical Android TV validation confirms
  exact, integer-multiple, and 2.5× fallback choices plus restoration on every
  listed exit, with no stranded display-mode request or Media3 strategy.

## Android mobile

The concrete Android choices, default, normalization, and visible order come from
the [Android backend policy](playback-runtime.md#backend-selection), which both
Settings shells consume.
Media3 derives video decoder facts from MediaCodec and the active display path.
For each codec it keeps resolution, throughput, profiles, levels, bit depth,
acceleration class, and ordinary/secure eligibility on one candidate until it
selects the preferred ordinary decoder: hardware before unclassified before
software, excluding secure-required candidates. Finite limits and constraints
are projected from that same candidate. Its audio decode list combines the platform
probe with the process-cached, fail-closed E-AC-3 result from JellyScope's bundled
Media3 FFmpeg extension. Decode-input channel ceilings remain per codec, while
the active route separately supplies PCM-output and encoded-passthrough facts.
LibVLC and mpv retain separately pinned codec/audio/subtitle declarations.
For intersecting declared and probed video codecs whose declared resolution
tuple is entirely unknown, LibVLC projects available MediaCodec width, height,
frame-area, and throughput bounds, including partial bounds; mpv requires
complete probed bounds. Existing declared bounds remain unchanged. This
projection does not import Media3's
audio support, profiles, or full capability set into either engine. All three
attach the source that owns each decode and finite-limit claim; pre-29 name
classification and unclassified platform results retain those weaker identities
instead of becoming hardware-probe evidence. All three map the same immutable
plan, share policy/recovery logic, and retain their own native lifecycle,
audio, subtitle, surface, and error mappings. Alternate-backend startup recovery
and the distinct offline construction-fallback boundary are owned by
[shared playback runtime](playback-runtime.md#backend-selection). Media3 emits native first-frame
evidence, LibVLC emits a positive displayed-picture-counter observation, and
Android mpv currently declares first-video-output measurement unsupported;
surface attachment is never treated as displayed output. Android LibVLC
additionally applies its pinned dav1d resource policy at the native-media
boundary. That policy is self-scoped to dav1d by module ownership and remains
separate from source compatibility and quality decisions.

Android mpv deliberately advertises conservative pinned-engine capabilities:
up to eight audio channels and supported text/bitmap subtitles, but no HDR,
Dolby Vision, passthrough, or display-derived ceiling. Mobile uses copy-back,
emulators use software decode, and TV defaults to classic GPU plus zero-copy
MediaCodec because supported TV drivers reject the `gpu-next` external-sampler
path and copy-back cannot guarantee real-time TV presentation. Direct MediaCodec
is an explicit setting, never an automatic fallback, and gives up mpv subtitles
and sizing. Exact cache, output, surface-lifetime, and diagnostic rules belong to
the [Android mpv backend](#android-mpv-backend).

## Android TV

Android TV uses the Android planners/controllers but owns its D-pad, native
surface, and focus shell; unlike Android mobile, it does not own a MediaSession.
Its server-scoped backend dialog and session-only live switch consume the
[shared Android policy projection](playback-runtime.md#backend-selection) directly.
ExoPlayer, LibVLC, and mpv retain
their backend-specific capability/evidence rules from Android mobile. The TV
notice is a persistent, dismissible bottom action surface above visible
controls; it never steals focus on appearance. Its explicit actions are
D-pad-reachable, and dismissal restores a safe player focus owner. It may
defer an automatic replan while PiP prevents safe interaction. Its app root
uses the shared `Actionable` guidance policy, so qualified Auto health evidence
can take the existing one-shot common recovery path; desktop and tvOS retain
their own Advisory bindings.

## Health and audio evidence

- **[Android]** Media3, LibVLC and mpv declare
  `PlaybackHealthMeasurementCapabilities.BufferingAndDroppedFrames` and emit
  `DroppedFrameMeasurement` values on `PlayerController.droppedFrameMeasurements`.
  A measurement is an **occurrence with a known duration** — dropped frames, the
  interval they were measured over, and the resulting rate — not a state sample.
  It is built only through `DroppedFrameMeasurement.create`, which reuses the
  shared `droppedFrameRatePerSecond` kernel and returns null for a non-positive
  interval, a negative count, or a non-finite rate, so a half-formed measurement
  is never emitted at all. Media3 emits one per `onDroppedVideoFrames` callback
  using the `elapsedMs` it carries; LibVLC emits one per poll that yields a real
  `lostPictures` delta over a real interval, sampling only while Playing. mpv
  derives occurrences from its native dropped-frame counters and poll interval.
  The primitive is `Channel(Channel.BUFFERED).receiveAsFlow()` per
  [`architecture.md`](architecture.md): occurrences need reliable
  single-consumer handoff, and because an event cannot be re-delivered, the
  consumer needs **no novelty inference** of any kind.

- **[Android]** A ready Media3 item held by the initial audio gate remains
  `Buffering`, not `Paused`, while play intent is active. This prevents the
  DirectPlay-disabled recovery from mistaking the gate for a user pause; an
  explicit user pause remains authoritative across the re-plan.

- **[Android]** Media3 degrades to video-only only after AudioTrack retry
  exhaustion. New items retry audio; explicit audio selection re-enables it.

- Mandatory software-playback recovery emits bounded `software-progress` records
  with closed admission/window outcomes, prepare/session identity, active decoding,
  dimensions, buffer and elapsed/actual/expected progress. `software-recovery`
  records identify Prompted, SwitchRequested, SwitchPrepared, SwitchFailed,
  ContinueRequested, Stopped and Superseded. They use release platform logging
  and retained diagnostic capture, but collection never gates detection or UI.
  No health-signal or optional health-guidance state owns this recovery.

- With diagnostic collection enabled, Android mpv records allowlisted
  `native-snapshot` events at file load, pause/resume requests and observations,
  seeks, video-state changes, and coalesced drop-counter changes (at most once
  per second for counter samples). Snapshots include a controller sequence,
  prepare sequence, monotonic sample time, requested/active output driver,
  active audio output, signed A/V difference and cumulative A/V correction,
  native pause/position, decoder/format availability, both native drop counters,
  and buffered-ahead time. These are retained by the same capture filter used by
  Send logs; the overlay is not the only source of transition measurements.
  Native error categories and closed failure reasons are recorded immediately,
  once per category/reason per prepare, even if audio continues. Raw native log
  text remains excluded from uploads. Decoder/format availability and Playing
  state do not prove visible output; unsupported first-frame evidence stays
  explicit. Diagnostic sampling does not change backend or transcode policy.

- LibVLC uses the [shared terminal evidence policy](playback-runtime.md#vlc-terminal-policy).

## Why

- **Android TV wakefulness follows the mounted player route.** Activity-window
  ownership spans backend replacement and loading/picker states without a
  singleton, service, CPU wake lock, vendor branch, or screensaver mutation.
  Physical acceptance remains pending.

- **Active decoding and presentation are different facts.** A configured hardware
  preference can fall back to software, and a GPU renderer can present either.
  Only mpv's active decoder property supplies the mode; generic codec names and
  screen dimensions cannot establish hardware decoding or full-resolution output.
  Numerical native format records distinguish decoder crop from texture import
  without exporting credential-bearing verbose messages or treating missing
  size-change logs as proof of a buffer's dimensions.
  A video track explicitly removed after native failure is actionable even when
  first-frame measurement is unsupported; continuing audio is not successful
  video playback. Initialization errors alone can still recover within mpv.
  Conversely, active software video can remain unusably slow without disabling
  its track or accumulating enough dropped-frame reports. Sustained buffered
  playback-progress evidence can identify that condition without changing codec
  envelopes. It requires a user decision because progress evidence can be wrong;
  Continue preserves user control and cannot create a repeated-dialog loop.
  Accepting Switch completes the user's decision immediately; preparation belongs
  to the player's loading state, with a fresh decision only if switching fails.

- **Android mpv native option and command shapes are versioned API.**
  Omit the singular `script` option instead of passing an empty value, preserve
  the defined `loadfile` and `sub-add` argument positions, and treat rejected
  return values or failed readback as failures.

- **Android platform playback uses installed-plan truth.** MediaSession
  seekability, PiP aspect, and command callbacks follow the current successful
  plan and monotonic owner, never a returned prepare call or transient layout.
  Coded dimensions own aspect; composed bounds supply only the source rectangle.

- **Android TV mpv uses the selected supported rendering baseline.** Zero-copy
  `mediacodec` avoids the copy path's audio/video drift, and classic `gpu`
  avoids the supported-TV external-sampler failure. GPU remains default; direct
  output is an explicit choice with subtitle/sizing limits. Surface teardown
  synchronizes with mpv because asynchronous detach can race Surface destruction.

- **Media3 prioritizes time thresholds over a claimed memory ceiling.** The
  allocator target does not cover codec, graphics, audio, or other native
  allocations, and time priority can exceed it. A larger regular-device target
  therefore remains an evidence-gated candidate rather than a silent default.
