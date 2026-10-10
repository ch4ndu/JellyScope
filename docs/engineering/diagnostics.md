# Diagnostic Collection And Privacy

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Diagnostics, logging, and privacy

- Logging uses the Kermit facade (`Logger.withTag(...)`) in EVERY module,
  including Android app modules — never `android.util.Log` or `println`. Never
  log tokens, passwords, auth headers, or credentialed URLs. Ktor HTTP logging
  is debug-build-only (via `CoreConfig.enableHttpLogging`) and must keep
  `sanitizeHeader` covering `Authorization`. Failure logs never pass a
  `Throwable` to Kermit: use `formatSafeFailureDiagnostic` for general failures
  and `formatPlaybackDiagnostic` for playback failures so only fixed stage/event
  labels and an allowlisted exception class are emitted. Never log exception
  messages, causes, stack traces, or raw payloads.
- Safe client diagnostics capture only a bounded, sanitized, app-global
  `LogBufferStore` history in the isolated disposable `DiagnosticDatabase`.
  A line must have both an audited tag and the fixed structured
  `stage=`/`event=` diagnostic grammar; all other Kermit output, including
  HTTP and free-form messages, is dropped before a defense-in-depth
  URL/token/path/address scrubber runs. Collection defaults off when the
  preference key is absent or invalid; an explicit persisted value is honored.
  The history is
  capped at 4,000 entries/500,000 UTF-8 bytes with a 4,096-byte line limit,
  is deleted when collection is turned off, and leaves the app only after an
  explicit save or send action. Android, Android TV,
  desktop, iOS, and tvOS register the writer and expose bounded collection
  controls through their native settings surfaces. This allowlist-first capture
  boundary remains defense in depth; console-safe failure formatting is required
  independently for every Kermit writer.
- Playback diagnostics preserve a closed causal chain without media identity:
  planner resolution records the concrete backend, request policy, capability
  conclusion, quality mode/cap origin, and request cap separately from the
  effective transcode cap; player events record backend fallback, process-local
  diagnostic session/prepare sequences (including request before the synchronous prepare
  boundary), first-output availability/observation, qualified health evidence,
  bounded Auto recovery decisions/budgets, persistence read/write outcomes,
  and terminal native outcomes. A cancelled asynchronous persistence write is
  `Cancelled`, not `Failed`, so teardown races do not masquerade as storage
  failures. The
  first-output and health-summary records include bounded runtime resolution,
  frame rate, decoder token, active decoding mode where available, source bitrate,
  bandwidth estimate, and available
  presentation/drop counters so a report can distinguish planning, decode,
  delivery, and rendering symptoms without media identity. Media3 may include
  its safe exception class and numeric error code;
  LibVLC `EncounteredError` remains `Unknown` because its callback exposes no
  safe structured cause. LibVLC has no Media3-equivalent periodic aggregate, so
  shared health summaries are the authoritative common evidence.
- A user-visible player fallback or fatal error is never allowed to be the first
  evidence of its cause. The catching boundary emits enough closed breadcrumbs
  for a first diagnosis from logs alone: requested/active backend, availability
  or policy gate, construction/runtime stage, result, and safe exception class
  where one exists. Raw messages and stacks remain forbidden; diagnostic
  completeness does not weaken the scrubber or permit media/account identity.
- On Android 11+ and only while diagnostic collection is enabled, app startup
  adds the newest previously unreported historical process exit to the safe
  diagnostic history. The record contains only closed reason/importance, clamped peak PSS
  and RSS in MiB, and an age bucket; descriptions, traces, signals, status,
  process/media/account identifiers, and exact timestamps are never logged.
  One exact exit timestamp is retained in app-private storage solely as the
  local deduplication marker; it is never added to the safe diagnostic history or
  uploaded. Older Android and Fire OS implementations safely provide no record,
  so a filtered external logcat capture remains necessary to prove a legacy
  low-memory kill.
- App-owned uncaught Kotlin/JVM and Kotlin/Native failures may leave one
  restart marker containing only a sanitized exception-class token and a closed
  platform token. On the next startup, while collection is enabled, the marker
  is consumed into safe history before the process hook is installed; disabling
  collection clears it. This does not claim coverage for Swift/Objective-C
  signals, player-process crashes, or operating-system kills.
- A Settings-triggered diagnostic upload snapshots the currently loaded player
  backend preference before dispatch. Platform backend policy normalizes that
  value: an explicit concrete preference is authoritative, while `Auto` may use
  a recent concrete failure backend that the platform supports and otherwise
  falls back to the policy default. A recent failure snapshot contributes
  capabilities and source fields only when its backend matches the resolved
  report backend; stale cross-backend metadata is omitted and capabilities are
  probed for the resolved backend instead.
- The in-memory holder behind that failure snapshot is written only when
  playback fails. Two failure classes have separate owners: `PlaybackInfoPlanner`
  records its capability fallbacks (it still returns a successful plan), while
  the active presentation shell records controller failures through
  `PlayerViewModel` or `TvPlaybackSessionPresenter`. Successful playback must
  never write to it: recording current state on success would overwrite the
  failure the report exists to explain.
- Release app entries retain the sanitized `LogBufferStore` writer and install
  a bounded platform writer that forwards only allowlisted structured
  diagnostics after the same scrubber accepts them. It drops ordinary messages
  and throwable payloads, so causal playback fields remain available in Android
  logcat, Apple unified logging, and desktop stdout without enabling raw release
  logging. Android uses `BuildConfig.DEBUG`; desktop uses its debug JVM flag;
  iOS derives the flag from the Xcode `CONFIGURATION`. Debug builds or the
  explicit verbose-log preference install the raw platform writer instead. This
  gate does not alter the bounded safe-history store or its scrubber.
- `adb shell setprop log.tag.<TAG> <LEVEL>` cannot enable raw logging in a release
  build. A log-level system property only filters writers that already exist;
  sanitized release records remain available, but adb cannot restore raw lines
  excluded by the logging configuration. Verbose on-device logging is an in-app,
  persisted, off-by-default preference that re-installs the writer at runtime;
  there is no adb-side substitute.

## Platform logging

- Because Fire OS runtimes have been observed to omit Debug/Verbose-priority
  logcat output, diagnostics that must be readable on TV use Info or higher;
  keep them low-volume (once per item/session, not per frame).

## Capture timing and playback timelines

`LogBufferStore` adds this envelope only after a line passes the scrubber:

```text
[jst1 diagEpoch=<32 lower-case hex digits> diagSegment=<nonnegative Long> diagElapsedMs=<nonnegative Long>] <severity> <tag> <safe message>
```

The epoch is random per process, elapsed time is monotonic, and the segment
changes at collection clear/disable boundaries. Producers cannot supply these
reserved fields. Persisted lines keep their original envelope; legacy lines
remain untimed. Store entry IDs, upload acknowledgements and hydration history
epochs retain their independent meanings. Diagnostic session allocation is
process-global and separate from launch/recovery counters used by playback.

Regular text uploads retain the envelope. Its epoch is a process correlation
token, not an account, device or installation identifier; reports containing
the same retained lines can carry the same token. Exported timeline JSON replaces
it with file-local numeric lanes and a capture-local zero. Neither format adds
wall-clock time, URLs, paths, media/account identity or raw native values.

`PreparePlaybackTimelineAction` encodes only the already-sanitized snapshot,
off the main thread, into Chrome trace-event JSON. Stages, events and arguments
use closed allowlists. The 2 MiB limit includes JSON framing; truncation drops
whole events and preserves valid JSON. Missing, evicted, malformed, legacy or
uncorrelated records produce explicit coverage limitations. No timed playback
events produces a visible no-data result, not an empty saved file.

Intervals require explicit matching capture, session, backend and prepare
identity at both ends. Lost or ambiguous boundaries remain instants. Prepare
dispatch does not measure startup; seek remains an instant; mpv first-frame
timing is unavailable; tvOS output without proven prepare identity remains an
instant. A timeline is bounded diagnostic evidence, not a complete performance
trace. Preparing, saving and sending it never clear or acknowledge safe history.

### Explicit delivery

| Platform | Settings → Diagnostics action and destination |
| --- | --- |
| Android mobile, iOS, desktop | Save playback timeline through the native document/save picker |
| Android TV | Save through an available document provider, or separately choose Send playback timeline to server |
| tvOS | Send playback timeline to server; no document picker |

Android uses `CreateDocument(application/json)` without broad storage
permission. Desktop uses the JDK save dialog entirely on the AWT event thread,
with file writing on IO. iOS retains its document-picker delegate and serializes
staging/presentation/cleanup across exporter instances in one bounded private
directory. Cleanup runs on terminal paths and next entry after interruption;
a cleanup failure is logged without its path, blocks dirty staging reuse, and
surfaces a failure even if a user-owned copy was already exported.

TV sending uses the existing `MediaRepository.uploadClientLogs` permission and
authenticated `/ClientLog/Document` boundary. The body is JSON sent as
`text/plain`; a server-assigned `.log` filename does not change its contents.
There is no converter, automatic upload, or automatic network fallback after
save fails. Missing Android TV document providers produce a visible result.

Only one operation is active per Settings owner. Payloads stay in memory, never
saved state; collection disable and screen disposal invalidate pending work.
Live collection/segment checks precede native write or upload handoff. An Android
picker result restored without its live payload reports interruption and writes
no bytes; it never regenerates a report or deletes the selected document. A
provider may already have created an empty file. A failed write may leave an
incomplete file. Stale callbacks cannot complete a newer operation.

Bytes already handed to the OS or server cannot be recalled. Disabling collection
deletes retained app history, not user-owned copies, and later cancellation is
best effort. Outcome diagnostics contain closed stage/event/result tokens only,
never JSON, destinations, filenames or native error text.

## PlaybackInfo diagnostic fields

- `PlaybackPlan` also carries `container` and the server's `TranscodeReasons`.
  Prefer the standalone PlaybackInfo list when present; otherwise URL-decode the
  `TranscodeReasons` query value from `TranscodingUrl`. Missing or malformed
  fallback data remains an empty list and must not fail playback or cause a
  credentialed URL to be logged. `PlayerViewModel` exposes the result through
  `PlayerDebugInfo` (play method, transcode reasons, codecs/bitrates, source/max
  bitrate, session, stream URL, and planned video presentation). The in-player
  overlay consumes only the compact privacy-safe projection defined below;
  identity-bearing and richer diagnostic fields remain available to their
  existing diagnostic or policy consumers without becoming visible rows.

## Validation artifacts

- Retained validation artifacts are sanitized like release diagnostics: they
  never contain credentialed URLs, tokens, item/source/session IDs, server
  identity, titles, or device serials. Release-equivalent validation builds
  emit only scrubber-accepted structured diagnostics by default.

## Crash Report Scrubbing

- Never send auth tokens, request headers, server URLs, media paths, media
  titles, usernames, local file paths, or raw Jellyfin responses to crash
  reporting.
- No crash or playback diagnostic data is ever uploaded automatically or sent
  to a third-party service by JellyScope. Users explicitly choose local timeline
  export or sending diagnostics to their connected Jellyfin server.
- The Send Client Logs action posts the structured diagnostics report plus bounded,
  `LogScrubber`-admitted
  `LogBufferStore` history to the connected Jellyfin server's
  `/ClientLog/Document` endpoint. Raw native-player files and free-form excerpts
  never enter this payload; a server that disallows
  client logs surfaces `UploadDisallowed` rather than failing silently. An
  upload proceeds even with an empty safe history, carrying the capability and
  failure snapshot alone. The separate timeline actions above export only
  admitted timeline events and require timed history.
- Playback/API crash context must be reduced to sanitized feature area, stream
  mode, capability flags, HTTP status class, exception type, and app/platform
  version.
- Playback repository/planner, native-player, PiP, mapping, subtitle activation,
  OpenSubtitles, and Jellyfin subtitle-sync failures use structured diagnostics.
  General repository and presentation failures use the shared safe-failure
  formatter and expose only fixed stage/event tokens plus exception class.
  Playback diagnostics admit only closed stage, event, platform, backend,
  request-policy, trigger, quality-cap, transcode-reason, video-range,
  delivery, mapping, reporting-result, recovery, and terminal values; numeric
  native/HTTP, stream, buffer, cache, output, health, and TV-display facts; and
  identity-free requested-versus-confirmed track and subtitle-render state.
  Session and prepare correlation joins planning, dispatch, backend, reporting,
  health, recovery, controller-failure, and terminal records into one causal
  chain, including the tvOS presenter path. iOS and tvOS use distinct fixed
  platform values. Logs must omit
  item/source/stream identifiers, media titles, local paths, remote URLs,
  headers, API keys, tokens, raw native fields, and raw throwable text.
- `LogScrubber.allowedFields` must remain a superset of every field
  `formatPlaybackDiagnostic` can emit; a common test enforces this contract. New
  diagnostic fields must carry closed enum-like or numeric values, never free
  strings.

## Runtime playback diagnostics

- `PlayerController.runtimeDiagnostics` is a dedicated
  `StateFlow<PlaybackRuntimeDiagnostics>`, separate from `PlaybackState` so
  normal playback updates do not churn for debug-only data. Debug overlays
  collect it only while their visible panel is composed.
- Decoder name, runtime width/height/frame rate, cumulative dropped video
  frames, and bandwidth estimate are nullable observational values. Optional
  debug-only attribution also includes a nullable presentation-path label,
  cumulative decoder drops, cumulative output drops, recent video-render p95,
  recent presented-frame rate, and cumulative app-presentation gaps. The
  dropped-frame rate for the most recent reported interval is a diagnostic
  observation but is not part of the compact in-player overlay. The quality
  detector never reads the snapshot; it consumes `droppedFrameMeasurements`
  instead, because a snapshot persists until replaced and so cannot express
  "a new measurement happened". Null means the
  active platform or stream cannot provide that value; invalid native sentinels
  are also null, never zero. A non-null dropped-frame count is cumulative for
  the prepared item, so zero means diagnostics are available and no frames have
  dropped. Bandwidth is always bits per second.
- Controllers reset diagnostics to `EMPTY` on every `prepare()` and `release()`
  so values never cross item boundaries. Android reacts to Media3 analytics, iOS
  uses access-log/presentation information on its existing observer cadence, and
  desktop reads mpv properties on its existing poll.
- Track mapping uses one locked `TrackResolutionDiagnosticGate` whose per-kind
  keys prevent mpv's poll and command paths from admitting the same outcome.
  `reset()` is not an ordering barrier: single-threaded Media3 and Apple
  controllers reset at prepare, while mpv retains bounded outcomes for its
  lifetime. Monotonic activation `requestId`s keep later sessions distinct. If
  targets are ever reused, add epoch-aware admission rather than a reset. The
  target participates only in equality and must never be formatted or logged.
- Desktop keeps `frame-drop-count` as the compatible `droppedVideoFrames`
  value and also exposes it as output drops; `decoder-frame-drop-count` is the
  separate decoder value. Native macOS presentation labels the active path
  (`OpenGL Render API` or `LibVLC native NSView`) and leaves software render
  p95, app-published rate, and app-presentation gaps unavailable. The software
  path labels itself `Software` and merges a
  bounded presentation snapshot into the same low-rate poll. Render p95 is calculated from a fixed
  120-sample duration ring only on that poll. Presented rate uses publications
  from the latest two-second window. A presentation gap requires more than two
  nominal frame intervals plus 8 ms of scheduling tolerance (at least 24 ms);
  when no positive finite installed frame rate is available, the conservative
  fallback is 75 ms. Startup, pause, buffering, resize, stop, release, and the
  first publication after a cadence reset do not count as gaps.
- These additional fields are observations only. They must not feed
  `droppedFrameMeasurements`, the shared health evaluator's native facts,
  stream planning, device profiles, backend selection, or transcode decisions.
- Runtime diagnostics are internal diagnostic data. Only the compact allowlist
  below is projected on screen; a separate bounded subset of current
  allocation, buffered-ahead, cache, output, and health facts is copied into
  correlated `PlaybackDiagnostic` health and terminal records. Related logging
  remains low-volume, sanitized fixed-label numeric data and must never include
  URLs, headers, tokens, or other identity-bearing values.
- The diagnostics snapshot renders each normalized codec's closed decode and
  finite-limit evidence. A source-copy rejection also records the source codec,
  dimensions/rate, effective bounds, finite-limit provenance, and first binding
  reason at the planner boundary, before a generic failure can discard them.
  Original rejection and compatibility recovery preserve that detail. Missing
  facts remain unknown; raw decoder names and unrecognized codec values stay
  outside the report. These records explain enforced policy, not measured native
  decoder performance.
- Media3 emits one allowlisted audio-decoder initialization lifecycle record
  only when the callback's media-item prepare epoch matches the active prepare.
  Its closed reason is `BundledFfmpeg`, `Platform`, or `Unknown`; the documented
  `ffmpeg<version>-eac3` name maps to allowlisted codec `eac3`, while the raw
  decoder name is never logged. `prepareSequence` and `sessionSequence`
  correlate the record to the immutable plan used for device acceptance.
- Health decision/session-summary diagnostics use an allowlist rather than raw
  native errors: backend identity, stream mode, sanitized server reason,
  signal kind, threshold, duration/count, effective cap, and cap origin are
  sufficient. They never retain URLs, tokens, server/user IDs, titles, local
  paths, or native exception text, and they do not persist learned server
  capacity. The first admissible post-start decision is retained for guidance;
  later evidence may remain in the bounded session summary without opening a
  second surface.
## Debug overlay

- The bug-report control toggles a persistent non-modal playback-info panel.
  [`ui.md`](ui.md#player-playback-notices) owns the exact compact visible-row,
  ordering, emphasis, unavailable-state, and privacy contract shared by every
  player shell. This diagnostics guide owns the distinction between the compact
  projection and the richer modeled runtime, policy, health, recovery, and
  identity-bearing facts that remain available to their existing non-overlay
  consumers.

## Renderer color evidence

`PlaybackRuntimeDiagnostics.outputColor` keeps closed source transfer/primaries,
renderer-target transfer/primaries, renderer range, evidence provenance and
conversion observation separate. Physical-display confirmation is always
Unknown. Android and desktop mpv observe `video-params` source properties and
`video-target-params/gamma` plus primaries through their existing native queue or
background poll. `video-out-params` alone is not renderer-target evidence.

PQ/HLG targets indicate HDR; recognized SDR curves indicate SDR. Linear,
unrecognized or absent targets remain Unknown, as do audio-only playback and
backends without equivalent observations. HDR-input-to-SDR-target is reported
only when both sides are known in the same valid sample; it identifies a
conversion relationship, not a tone-mapping algorithm or quality guarantee.
An unrecognized native target can carry NativeTarget provenance while its
range remains Unknown. Desktop Render API/Metal property availability remains
unverified; unavailable evidence must stay Unknown without renderer repair.

Prepare, release, backend changes, terminal failure and native reconfiguration
clear observations; publication checks current prepare/reconfiguration identity
and failure state. Failed attempts cannot restore positive color evidence before
a valid prepare begins. Collection being
off does not prevent the compact shared Playback info row from updating. Closed
state changes and selected health/terminal records enter sanitized diagnostics
and the timeline. Native tvOS diagnostics retain Unknown; there is no new native
tvOS playback-info screen. These observations never change planning, profiles,
quality, health, recovery, backend selection or display policy. Compilation and
mapper tests do not prove native property availability or physical HDR output.

## Native and execution observations

- Android and desktop mpv runtime diagnostics derive active video decoding from
  `hwdec-current`: `no` means Software, a `-copy` driver means HardwareCopyBack,
  another active driver means Hardware, and unavailable means Unknown. This is
  separate from the configured `hwdec` preference and presentation renderer.
  Android property-unavailable callbacks clear decoding and size observations;
  a new prepare resets them. The shared/TV mpv overlay labels `video-codec` as
  Codec description and native width/height as Decoded size. It does not claim
  that decoded size is the TV output resolution. Other backends retain their
  existing rows until they supply equivalent active-mode evidence.

- Native controller diagnostics identify the backend and prepare attempt; Apple
  and desktop records also carry the shared diagnostic session sequence when
  available. Terminal failures preserve the typed playback error and native
  code or exception class where the backend exposes them. Desktop VLC failure
  events use the ordinary sanitized logger independently of optional probe
  collection. Android mpv records terminal lifecycle failures even when native
  initialization never reaches a playable file.

- Download diagnostics cover durable attempt claims, state transitions and
  finalization, recovery, app-active wake completion/cancellation, lifecycle
  checkpoints, and Android scheduler failures. Records contain only generation,
  closed state/failure/outcome values and exception class; a rejected durable
  transition is recorded separately from successful settlement. Generations
  identify revisions of an attempt, not globally unique download identities;
  the serialized execution breadcrumbs establish ordering. Progress writes do
  not emit per-chunk records. Native process termination may prevent the last
  queued log from being persisted; managed previous-run failure markers do not
  replace native crash reports.

- Resolved playback diagnostics keep Jellyfin `Transcode reasons` separate from
  `App trigger`. The compact desktop/TV overlay shows only the server's
  `Transcode reasons`; app triggers remain in structured diagnostics, including
  client recovery and Android TV LibVLC capability preflight. Requested start
  position is retained in structured planning diagnostics so track or fallback
  replans can be checked for accidental restart.

## Why

### Diagnostic privacy rationale

- **Trust and credential attachment are one project-owned decision.** A
  credential-free decision lets native transports attach only current
  same-origin authorization. Per-adapter checks can authorize one URL and load
  another; redirect residuals stay explicit.
- **Native authorization uses one backward-compatible form.** Verified modern
  Jellyfin releases accept the comma-free token or guarded `ApiKey` form, while
  legacy fallbacks preserve insecure or disabled routes. Broad inbound query
  stripping remains only to sanitize stale URLs.
- **Diagnostics never leave automatically.** Bounded, sanitized history and
  typed snapshots leave only through explicit save or server-send actions. Raw native
  files stay local because free-form text cannot be proven identity-free.
- **Timing preserves uncertainty.** Capture/session identities correlate safe
  events without media identity. Missing boundaries cannot justify durations;
  local lane remapping and coverage markers make partial history interpretable.
- **Native export keeps delivery accessible.** Users need no offline converter.
  TV offers explicit server delivery where a native destination picker may be
  absent; failed local saving never silently grants network consent.
- **Renderer evidence has a defined limit.** Source HDR does not establish
  renderer HDR, and a renderer target does not establish the physical display's
  output. Separate observations preserve these distinctions without changing
  playback policy.
- **Failure boundaries preserve causal evidence.** Native errors need backend
  and attempt correlation before generic state or recovery hides their origin.
  Download execution can finish after its caller returns, so retained wake,
  recovery, and durable settlement outcomes must survive in client reports.
- **Formatting and admission share one closed schema.** The scrubber allowlist
  covers structured formatter fields, preventing silent loss. Playback,
  download, persistence, and native-stage records preserve causal decisions
  before generic projection without raw requests or external data.
- **Platform logs supplement the safe upload path.** Desktop probes use typed
  records; iOS uses Kermit-to-OSLog plus bounded in-app history rather than a
  second logger. Unified logs remain available through Console, `log collect
  --device`, or sysdiagnose.

- Android no-backup logs limit exposure of raw native diagnostics; they do not
  change the accepted Apple credential-storage policy.
