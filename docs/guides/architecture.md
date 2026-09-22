# JellyScope Architecture

## Stack Target

- Kotlin Multiplatform Compose targets Android TV, Android mobile, iOS, and JVM
  desktop. tvOS is a native SwiftUI shell over shared Kotlin because Compose
  Multiplatform publishes no tvOS UI target. Android TV and mobile are primary;
  iOS/desktop reuse the shared app root, and tvOS uses `shared-tvos` presenters.
- Shared UI uses Compose Multiplatform where stable. Koin provides DI, Ktor sits
  behind the app API facade, kotlinx.serialization maps JSON, Coil 3 loads
  images, Kotlin time APIs own time logic, and `kotlin.test` serves shared tests.
- Playback stays platform-native: Android Media3/ExoPlayer with approved decoder
  extensions, project-owned Android mpv and LibVLC bridges, desktop libmpv and
  LibVLC bridges, and native Apple playback. Android and desktop mpv are separate
  integrations. Exact native inputs and provenance belong to
  [Android Native Dependencies](../operations/android-native-dependencies.md)
  and [Licensing And Distribution](../operations/licensing-and-distribution.md).

## Layering

- Data owns API clients/DTOs, transport queries, caches, settings stores,
  repositories, and platform data adapters. Remote models map to domain before
  ViewModels or UI. Jellyfin and OpenSubtitles calls remain behind their API and
  repository facades.
- Domain owns models, mapping, read UseCases, write/side-effect Actions, search
  projection, playback planning, and decision protocols consumed by presentation.
  Session-removal authorization, download outcomes, subtitle-selection identity,
  visible Related projection, and mapped playback failures are domain contracts.
- Settings values such as `AppColorThemeId` and `PlaybackPreferences` are domain
  models persisted by data stores. Raw mutable bytes and wire DTOs remain data-owned.
- Each screen ViewModel injects UseCases/Actions, never repositories or settings
  stores. It owns screen state, pagination, selection, and screen-specific rows.
  It does not construct transport queries such as `ItemsQuery`.
- UI renders supplied state and sends events. Filtering, sorting, grouping,
  mapping, row packing, and playback decisions stay in domain/ViewModels.
  State-holder composables may collect state/effects; plain content takes
  immutable state and callbacks.
- Platform players consume `PlaybackPlan` and publish app-owned control, state,
  progress, diagnostics, and failures. They do not choose Jellyfin stream policy
  or expose native APIs to UI/ViewModels.
- Platform code stays behind app interfaces or narrow `expect`/`actual` seams.

Refetchable cache data may use a documented destructive alpha migration/reset.
Durable user-authored state requires an explicit migration. Server/account stores
must register for the correct cleanup boundary. New repositories, UseCases,
Actions, ViewModels, and platform implementations must be registered in DI; add a
focused fake only when justified by [the test policy](workflow.md#5-selective-tests).

Execution follows one dispatcher contract:

- Network, database, and file work uses the injected project IO dispatcher,
  which maps to `Dispatchers.IO` on Android/JVM and currently to
  `Dispatchers.Default` on Apple. The pinned coroutines library also exposes
  Native `Dispatchers.IO`; that does not change this app policy.
- CPU-heavy decode, mapping, sorting, grouping, ranking, and playback planning
  uses the injected Default dispatcher.
- `viewModelScope.launch {}` starts on `Main.immediate`. tvOS presenters use
  injected `Dispatchers.Main`, which is not the immediate dispatcher. Keep
  `_state.update` and cheap bounded transforms on Main; calculate expensive
  values before the update. Repositories guarantee their own off-Main work, so
  callers do not wrap repository calls again.
- Suspending work uses `runCatchingCancellable` or explicitly rethrows
  `CancellationException`; ordinary `runCatching` is limited to synchronous
  parsing and best-effort platform guards.

Local subtitles use `LocalSubtitleFileStore`. Room stores metadata and selection;
one app-scoped coordinator is the sole writer of subtitle files, asset rows, and
selection and owns account cleanup. Players receive sealed remote/local
`SubtitleAsset`; offline packages use trusted `PlannedSubtitle.OfflineSidecar`
leases from [Downloads](data-playback.md#downloads-and-offline).

## Account Boundary And Lifecycle

- `AccountIdentity(serverId, userId)` keys account-owned caches, activity,
  playback memory, and provider state. Replaceable connection URLs are session
  metadata, not identity.
- `SessionTransitionCoordinator` serializes restore, login/add, switch, removal,
  and logout through `ServerScopedStoreRegistry`. Authentication runs outside
  the mutation gate; its opaque token must still be current at commit.
- Cold restore publishes LoggedIn only after installing exact identity and
  boundary epoch under the gate. Background work captures one immutable snapshot
  and acquires an account lease; stale leases cannot begin or commit work.
- Shared, Android TV, and native tvOS logged-in roots are keyed by identity plus
  epoch, so same-account token rotation receives a fresh lifecycle. `accounts`
  and `SessionState` derive from one committed snapshot, including active markers.
- Login, switch, and reauthentication validate and prepare while cancellable,
  then explicitly check cancellation before one `NonCancellable` commit tail:
  envelope persistence/load, required outgoing subtitle barrier, registry/epoch
  transition, and exact snapshot publication. Cancellation is surfaced only
  after durable and observable state agree. Removal carries its committed
  snapshot; no transition rereads secure storage after commit.
- Parental-rating refresh uses a narrow same-boundary update under the existing
  mutation gate. Both the full live Session/epoch and the persisted active
  account/row must match the captured request, with no pending logout. Changed
  ratings persist then publish in one non-cancellable tail; unchanged ratings
  skip publication. This neither changes the epoch nor invalidates login
  attempts or runs account/cache cleanup. The existing idempotent subtitle-sync
  `collectLatest` consumer may restart when the changed Session is published.
  Refresh triggers and offline behavior belong to
  [Kids account playback](data-playback.md#kids-account-playback).
- Removal computes values needed after mutation before the first write, runs
  cleanup outside caller cancellation, and aggregates phase failures so one
  failing store does not abandon the rest.
- `PersistentAccountStoreCleaner` directly owns persistent full/server/account
  cleanup; correctness does not depend on lazy feature resolution. App-global
  theme, tile, PiP, logging, OpenSubtitles key, device policy, and subtitle
  file/asset policies remain outside it.
- Registry filters do not overlap: switch calls only `RefetchableServerCache`,
  removal calls only `AccountScopedClearableStore`. A runtime store that must
  clear at both boundaries implements both and has a focused check for each.
  `PlaybackDiagnosticsContext`, `DiscoveryCache`, and `DetailRelatedCache` do so.

## UseCases And Actions

- Use one UseCase per read and one Action per write/side effect; Actions normally
  expose `suspend operator fun invoke(...)`. Add/reuse these contracts before
  wiring a feature into a ViewModel.
- Shared UI may import domain identifiers such as `MediaRibbon`, never DTOs or
  repository enums. Values in the compiler-declared stable domain-model package
  are structurally immutable; opaque protocols live in their action/playback
  package.
- App-global stores are not registered with `ServerScopedStoreRegistry`.
  Settings are observed and changed through existing UseCase/Action wrappers,
  including theme, PiP, playback preferences, grid/library sort, and recent search.
- Desktop volume/mute persistence is the documented exception:
  `MpvPlayerController` sends player-internal restore state to the injected,
  serialized `JvmFileVolumeStore`. It is not a general settings pattern.

## State And Event Modeling

Choose Flow by semantics:

- `StateFlow` has a current value; expose it read-only and keep mutation private.
- Cold `Flow` executes per collector.
- `SharedFlow` is an explicitly configured hot broadcast.
- `Channel(BUFFERED).receiveAsFlow()` provides buffered single-consumer handoff;
  multiple collectors divide events rather than broadcast them.

State rules:

- Use `_state.update { it.copy(...) }` for atomic read/modify/write. Keep the
  block pure and fast. Model loading, absence, and failure explicitly.
- Persistence commits against latest user intent, not a shared load/sort
  generation; a reload does not invalidate intent.
- Define `stateIn` once as a stable property. Use `WhileSubscribed` only when
  stale/uninitialized synchronous `.value` reads are acceptable.
- Navigation, snackbar, toast, and similar one-shot effects stay outside durable
  state unless intentionally persistent. Default `MutableSharedFlow()` can lose
  emissions without a collector.
- UI-local scroll, focus, animation, and interaction state may stay in the
  composable that renders it.
- Player picker visibility and selected values are durable player state.
  Requested and installed audio/subtitles are distinct: planning and memory may
  reflect request intent, while selected/debug truth changes only after server
  response or exact platform confirmation. Replanning retains installed-plan
  truth until replacement. Stale confirmation remains pending; explicit failure
  or no confirmation three seconds after readiness becomes Unavailable, and a
  later exact confirmation may recover.
- The six-second subtitle-unavailable banner is UI-local presentation derived
  from state. Fatal error and non-fatal `audioUnavailable` are durable player
  state. OpenSubtitles search/progress/quota are dialog state; successful install
  is a buffered one-shot, while assets and reconciliation remain durable Room data.

Review replay, fan-out, buffering, `.value`, atomicity, stable sharing, and event
loss. Plain content must remain independent of ViewModels, repositories, and
navigation controllers.

## Source Layout Target

```text
shared-core/src/commonMain/kotlin/.../
+-- data/                 # remote, local, repository
+-- domain/               # model, usecase, action, playback
+-- di/

shared-ui/src/commonMain/kotlin/.../
+-- component/
+-- screen/
+-- theme/
+-- navigation/

android-libmpv/           # project-owned Kotlin/JNI bridge over pinned AAR input
android-app/              # shared UI shell and selectable Android playback
android-tv-app/           # D-pad shell, TV screens/player, Watch Next
desktop-app/              # JVM Compose shell and libmpv/LibVLC bridges
ios-app/                  # shared Compose shell and Apple playback
shared-tvos/              # Kotlin presenters, Swift bridge, static SharedTv
tvos-app/                 # native SwiftUI shell with AVKit/VLC hosts
```

Platform source sets include `androidMain`, `appleMain`, `iosMain`, `tvosMain`,
and `jvmMain`. Share in the highest valid set; use `expect`/`actual` only when
common code cannot own the behavior. Prefer feature grouping after a feature
owns enough data, domain, presentation, and DI to justify it.

Split large files by cohesive ownership near a 500-line review trigger. Keep
public contracts in named files and helpers/chrome/fixtures in same-package
siblings. Do not split cohesive state or behavior to meet a number.

## Platform Bridge Rules

Keep platform code at the edge and in the highest valid source set. Use
`appleMain`/`iosMain` where appropriate instead of duplicate target actuals.

Persisted wire format and tolerant decode belong in `commonMain`. Unknown enum
names from newer builds decode to null so downgrade falls back safely; changing
an existing on-disk shape requires migration. Platform stores retain storage
mechanics, keys/prefixes, and cleanup. Extract only identical decoding; keep
platform/default policy at call sites. Composite formats such as
`SavedLibrarySortCodec` remain dedicated codecs with malformed-input coverage.

Use an injected app interface for meaningful behavior, fakes, lifecycle/setup,
multiple implementations, players, stores, downloaders, and OS integrations.
Use a narrow `expect`/`actual` function/property/factory for tiny single-platform
facts. Avoid expected classes where simpler seams work. Entry points construct
implementations and provide app identity through DI; common code never locates
native services. Native callbacks and failures cross as app-owned state/events.

Entry-point responsibilities:

- Android mobile wires Media3/mpv/LibVLC, secure storage, filesystem,
  notifications, callback-owned MediaSession, and PiP. TV provides its own
  shell but reuses Android player/surface/audio-focus/backend bridges. Android
  backend order and mpv's API-26 availability follow the
  [data/playback backend policy](data-playback.md#backend-selection).
- iOS wires AVPlayer/VLCKit, settings/session storage, and Apple lifecycle. App
  credentials/preferences use app-owned `NSUserDefaults`; designated data stays
  in Room. Application credentials do not use Keychain.
- Desktop wires Compose, Koin, window capabilities, plaintext JSON credentials
  under `~/.jellyscope`, and native players. Application credentials do not use
  Keychain; signing credentials are separate.
- tvOS exposes only `TvosEntry` to Swift for Koin, presenter factories, and dev
  prefill. Apple download storage/lifecycle and `AppleVlcKitPlayerController`
  live in `appleMain`. Directory actuals, DI entry modules, the iOS PiP wrapper,
  and the retained tvOS VLC drawable remain target-specific. SwiftUI files mirror
  feature owners under `screen/`, with shared `component/media/`, `theme/`, and
  `navigation/` directories. Hardware playback/display/remote acceptance cannot
  be inferred from simulator.

### Downloads module boundary

Android mobile/TV, iOS, tvOS, and desktop opt into `downloadsModule` and supply
their database, artifact store, and lifecycle/execution host. iOS and tvOS share
Apple adapters with target-specific directory choices. Repositories/adapters
feed domain UseCases/Actions; ViewModels and native presenters consume those
surfaces, and UI never owns queue/filesystem. The
isolated Download database keeps durable transfer/removal state outside
refetchable cache cleanup. See
[Downloads And Offline](data-playback.md#downloads-and-offline).

iOS optionally injects continued-execution permission into the shared Apple
lifecycle host. The Swift controller owns BackgroundTasks objects; a narrow
shared-ui bridge forwards opaque generations and numeric progress to the core
adapter. Shared coordinators remain the byte writers and quota/account owners.
tvOS uses the default app-active host without that adapter.

### Android TV focus ownership

`TvFocusCoordinator` owns route focus. Each back-stack entry has a monotonic ID,
saveable `TvFocusPath`, and `TvFocusMemoryState`; stable scope/target keys define
identity, never requesters or lazy indices. A pop creates a tokenized transaction
consumed only by confirmed focus. Settled-route hardware input may cancel it.
Every `SaveableStateProvider` key includes route ID and presentation epoch because
outgoing/incoming entries may compose together.

Detail ViewModels retain per route ID while active or in history. Render leases
keep outgoing owners through disposal; owners clear when neither retained nor
rendered, and all clear at session change. Render keys include Detail item ID.

Kernel groups use `TvFocusScopeNode`/`TvFocusResolver`, not `focusRestorer`.
Static non-owner chrome and shared Detail-local traversal may keep
`focusRestorer`. `DetailFocusBridge` carries only stable strings and semantic
positions; TV supplies it per rendered entry so inactive content cannot consume
active restoration. Kernels own one off-screen reveal and confirm both key and
semantic position. Restore-aware bring-into-view suppresses automatic scrolling
only during that handoff. Silent Detail refresh atomically preserves current
Related groups/loading and does not own that stream.

### Desktop libmpv boundary

macOS arm64 development and packaging use the pinned IINA 1.4.0 69-file dylib
inventory. Fetch/prepare scripts validate inventory, copy resources, rewrite
non-system paths to `@loader_path`, restore signatures, and verify architecture,
deployment floor, and dependency closure. `LibMpv` loads only bundled
`libmpv.2.dylib` on macOS and never searches machine package paths; Linux uses
system lookup, and Windows packages must provide `mpv-2.dll`. `LC_NUMERIC=C` is set before use.
Release builds preserve runtime-loaded providers through the desktop ProGuard file.

Apple Silicon packages also prepare the audited VLC 3.0.23 arm64 runtime and
licenses. Intel macOS packaging is unsupported. Packaged VLC is preferred;
development may use `/Applications/VLC.app`. Availability is a cached file check,
while engine creation validates version. Signing covers prepared native dylibs,
and variant-specific tasks verify packaged VLC inventory. Package acceptance also
requires the [release checks](../RELEASE.md#manual-macos-stages), while runtime
acceptance remains separate and requires real macOS playback through both mpv
and LibVLC.

Desktop mpv has a JVM-only presentation capability. Portable software rendering
uses two UI-owned Skia BGRA bitmaps; core renders off Main and publishes only a
complete front buffer on Main with one-frame ownership acknowledgement.

On Apple Silicon, an app-owned IOSurface path is primary: UI owns a generation-
scoped `NSView`, private CGL context, three-buffer swapchain, AppKit/Retina resize,
and detach. Core owns libmpv callbacks/context and one render executor. Detach is
acknowledged after callbacks stop and context frees. Initialization/release are
serialized under `engineLock`; joins occur outside the monitor. IOSurface failure
falls back to app-owned `NSOpenGLView`, then software before item load. No path
supplies `wid` or lets mpv create/focus a window.

Compose alone owns desktop controls. A `BlendMode.Clear` cutout reveals video;
a 1 dp `SwingPanel` supplies the peer while Compose bounds size the native view.
Native views sit below Compose and reject hit testing, keeping input on the normal
AWT-Compose path. The OpenGL fallback uses swap interval zero. There is no popup
controls window, synchronization observer, or synthetic native-pointer path.

libmpv's deprecated OpenGL output can drop very-high-resolution presentation
under sustained pointer motion. LibVLC avoids that path but can exceed its own
output capacity. Probe render/present times measure CPU callback/presentation
work only, not GPU/compositor/end-to-end latency. The rejected `wid` path opened
a separate window and is not production behavior.

Desktop LibVLC is an independent `LibVLC (beta)` controller/AppKit surface and
does not emulate mpv render targets. Settings chooses mpv or LibVLC for the next
session; unavailable LibVLC normalizes to mpv with a fallback notice. Native
handles, addresses, Skia types, callbacks, and generations never enter common
player contracts.

### Playback bridge rules

[Playback Architecture](playback-architecture.md) owns the ordered decision
pipeline and backend comparison. These source boundaries enforce it:

- Shared domain owns plans, quality/bitrate policy, capabilities, launch context,
  bounded recovery/retry kernels, and stream strategy. Launch returns normalized
  inputs and closed outcomes; screen/presenter owners retain process memory,
  explicit intent, option validation, local reconciliation, stale gates, and
  diagnostics. `DeviceProfileProvider` reports capabilities; data maps DTOs.
- `PlaybackPreferencesStore` keeps account-scoped quality and optional VLC Fixed
  default in Room across Android/Apple/JVM. `PlayerDeviceSettingsStore` keeps
  device audio/HDR policy in Android SharedPreferences and Apple/JVM Room.
  Session quality is not persisted; `PlaybackSelection` keeps source-keyed audio.
- Controllers publish `PlaybackState`, diagnostics, measurement capabilities,
  and generation-scoped output. `prepare()` installs without starting. Initial
  DirectPlay audio mapping may delay play; subtitle activation may not.
- Controllers resolve verified local subtitle identities only through
  `LocalSubtitleFileStore`; UI paths and Jellyfin headers never reach local files.
- `PlaybackHealthSessionCoordinator` owns generation-safe measurements.
  `AutoPlaybackRecoveryCoordinator` has one compatibility and one quality budget;
  `PlaybackSessionRecoveryPolicy` owns activation precedence, target validation,
  typed causes, and attempt facts. Inherited Auto gathers evidence but only an
  explicit in-player Auto choice authorizes auto quality reduction. ViewModel/
  presenter executes verbs, selections, navigation, notices, and safe PiP deferral.
- Policy decisions and cheap state writes remain on Main; repository/PlaybackInfo,
  persistence, DTO mapping, and native I/O use injected dispatchers. A prepare
  generation cancels old observations before new facts can act.
- Output evidence is capability-specific: Media3 first-frame, mpv software
  published frame, LibVLC displayed pictures, Apple ready-for-display. mpv OpenGL
  and iOS VLCKit declare reliable shared first-output unsupported; VLCKit may use
  best-effort counters for readiness. Surface/Vout attachment is diagnostic only.
- `AndroidPlayerSurfaceHost` serves mobile/TV and attaches Media3 or app-owned
  mpv/LibVLC surfaces with shell subtitle inset. iOS attaches AVPlayerLayer or a
  retained VLCKit drawable behind iOS capability interfaces. Desktop mpv publishes
  through the JVM-only BGRA capability. Common UI never casts native controllers.
- Android LibVLC waits for window attachment before vout attach, re-enables video
  from the vout callback, and replays play intent. Backend replacement retains
  the owning ViewModel.
- Optional `PlayerTimingController` keeps persistence/UI in common and application
  in backends. Media3 audio delay uses PCM insertion/drop with video-clock progress
  and coalesced re-prepare; zero keeps passthrough/offload. Its separate subtitle
  overlay is positive-only and stores post-clamp values. Android LibVLC and
  Android/desktop mpv use native timing; Apple and desktop LibVLC expose none.
- `SavePlaybackSelectionAction` and `SavePlaybackTimingOffsetAction` use the
  shared latest-wins per-key `DebouncedKeyedStoreWriter` off Main.
- Android backend choice is resolved before planning and remains authoritative
  through queue items unless explicitly switched for the session. Backend
  defaults/order are owned by playback architecture. Eligible Jellyfin-streamed
  alternate-backend startup recovery and the separate pre-prepare offline
  construction fallback follow
  [Settings And Credentials](data-playback.md#settings-and-credentials); installed
  offline playback never performs a remote recovery replan.
- `PlayerViewModel` owns explicit remote switching: it plans off Main while the
  current controller remains installed, uses the serialized release-first
  installer, revalidates ownership/identity after suspension, preserves durable
  preference and offline resolution, and releases untransferred candidates once.
  Factory-reported backend selects the plan reaching `prepare`; switch-only
  construction fallback tries one platform default.
- Android mpv keeps vendor API inside its engine adapter. Its controller owns
  serialized commands, generation lifecycle, tracks/timing/diagnostics, audio
  focus, retry, and release; `AndroidPlayerSurfaceHost` maps resize/subtitle inset
  into the app surface bridge. Hosts are owner-qualified, and same-host replans
  retain the engine surface. JNI logging excludes raw text and routine event names.
- Android LibVLC projects only its own capabilities. Its optional Fixed default
  affects initial VLC-family PlaybackInfo and is not a decoder fact or platform
  8 Mbps rule. Diagnostics report codec/size/frame rate/lost pictures; bandwidth
  stays absent until the API unit is verified. Track IDs use
  `NativeTrackResolution`, with subtitle Disable at `-1`.
- `AndroidAudioFocusCoordinator` alone owns focus/noisy behavior for all Android
  backends; Media3 internal focus/noisy handling is off. Media3 uses bounded
  streaming load control. LibVLC seek/resume stays Buffering until native clock
  advances beyond its jump; callbacks are coalesced.
- PiP/MediaSession call ViewModel-owned `PlayerPlatformCommandCallbacks`.
  `BackendMediaSessionPlayer` projects backend-neutral state; raw players do not
  cross the platform boundary.
- Each user-visible failure, blocked action, degraded path, or fallback records
  closed decision inputs, stage, outcome, and safe exception class/result code,
  with enough bounded transitions and surface milestones to reconstruct cause.
  Never log URLs, headers, tokens, paths, identities, messages, stacks, or raw
  payloads. Detailed track/recovery/profile mapping belongs to data playback.

### Other bridges

Shared Detail uses `AmbientColorExtractor` over Coil `Image`. Android may use
Palette internally; other platforms may return null. Shared UI imports no Android
graphics/resources. Model capabilities explicitly instead of inferring platform.

Bridge review checks: highest valid source set, narrow seam, contained vendor
types, centralized setup, and a common-fake path without native bootstrapping.

## Platform Strategy

Share models, contracts, and UI at the highest valid source set. Keep shells
separate where navigation, focus, lifecycle, player, or packaging differs.
Android TV/mobile share playback without phone navigation assumptions; Apple and
desktop keep native integration behind app-owned bridges. Current platform claims
live in `README.md`; owning guides state stable limitations and required
validation criteria.

## Known Duplication

Recheck these source relationships before changing them:

- Shared/TV notice visibility is semantically aligned, but audio notice lifetime
  differs (shared keep-alive versus TV fixed delay); any shared helper must first
  define one contract.
- `TvPickerRow` adds focus semantics absent from shared `PickerRow`; labels and
  resources also differ. Keep them separate.
- Series/episode metadata formatting overlaps, but segment labels intentionally
  differ. Back-action kernels differ because TV adds menus, Up Next, and queue
  paging.
- iOS VLCKit lacks Android LibVLC's 250 ms newest-wins seek coalescing.
- Duplicate IOSurface helpers and similar mpv/LibVLC AppKit host canvases are
  accepted until a demonstrated presentation defect justifies consolidation.

## Testing Boundary

Pure domain rules, mapping, planning, search/filter, and repository contracts fit
`commonTest`; platform bridges should expose common fakes before runtime tests.
Follow [workflow](workflow.md#5-selective-tests) for proportional
verification.

## Crash And Telemetry

Diagnostics cross platforms only through sanitized app models. A causal report
distinguishes planning, admission, construction, prepare, runtime,
fallback/recovery, and outcome. A boundary that catches a failure records its
safe operation/stage/result before recovery. Upload/scrubbing policy belongs to
[data playback](data-playback.md#diagnostics-logging-and-privacy).

## External Source Boundary

JellyScope is independently implemented. Third-party source, structure, and
assets require file-level license review. License compatibility and distribution
gates live in
[Licensing And Distribution](../operations/licensing-and-distribution.md).

## Why

### Platform strategy and sharing

- Native tvOS preserves Siri Remote focus and keeps SwiftUI/AVKit outside common
  code while sharing Kotlin presentation/domain. Platform-family implementations
  stay in Apple source sets; directory choices, DI entry, iOS PiP and tvOS drawable
  adapters preserve the native boundaries.
- Downloads is installed only through platform graphs that supply real storage
  and lifecycle contracts; cache cleanup must not own downloaded media.
- Duplicate iOS/JVM Skia ambient-color actuals remain because an intermediate
  source set would disable the default hierarchy and restructure every target for
  a bounded narrow seam.
- Platform-only player capabilities use nullable side interfaces instead of
  widening `PlayerController`.
- Persisted enum parsing is shared but defaults stay local, preserving on-disk
  compatibility without silently equalizing platform policy.

### Accounts and lifecycle

- Server-plus-user identity and one immutable session snapshot prevent requests,
  caches, and publication from crossing account generations.
- Presentation-policy refresh must not reauthenticate or remount an active
  account. Comparing the durable active row as well as live state prevents a
  stale refresh from reactivating an account or clearing a pending logout.
- Direct persistent-store cleanup avoids correctness depending on lazy DI order.
- Removal and committed transition tails are atomic/non-cancellable because
  partial durable mutation cannot be represented safely as an unchanged session.
- Presentation-visible results live in domain so ViewModels never depend on
  repository or transport implementation types.

### Desktop playback embedding

- macOS mpv uses IOSurface, app-owned OpenGL, then software fallback; `wid` is
  rejected because it opens a separate player window. Compose remains the sole
  controls/input scene.
- Native teardown uses the engine lock and deferred lifetime/lease discipline;
  render paths retain their separate lock/executor ordering to avoid per-frame
  lease traffic.
- VLC availability is cached file inspection with version validation at engine
  creation. Packages bundle the pinned audited runtime and attribution.
- Development and packaging use one pinned mpv inventory so machine-installed
  libraries cannot silently change the native graph.

### Android playback integration

Android mpv wrapper provenance belongs to
[Android Native Dependencies](../operations/android-native-dependencies.md).

### Playback contracts

- Live backend switching stays in `PlayerViewModel` because it shares launch
  identity, controller ownership, reporting, and installer authority.
- Launch reading is shared, while owner-specific memory, explicit intent,
  validation, reconciliation, and stale-result handling remain with each caller.
- `LocalSubtitleFileStore.resolvePath` remains a bounded synchronous stat/path
  seam; byte movement and reconciliation use injected IO owners. No common
  staging lifecycle exists to justify a wider suspend abstraction.

### Diagnostics

- Closed sanitized causal records support first-pass diagnosis without exposing
  external data; a generic unavailable flag cannot identify the failing boundary.

### Engineering discipline

- Remove obsolete internal compatibility fields so the compiler exposes every
  consumer instead of preserving silent null fallbacks.
- Extract only behavior that is actually common. Controller retries and mutable
  session fields remain local where assets, timing, order, and lifecycle differ.
- Native Objective-C dispatch uses one typed lazy `libobjc` facade, while each
  feature owns selectors, callbacks, AppKit dispatch, and lifecycle.
- Concurrency fixes match access discipline: volatile for independent reads,
  atomics for indivisible updates, monitors for confined compound state, and
  cancellation/join outside locks.
