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
  ViewModels or UI. Jellyfin, Seerr and OpenSubtitles calls remain behind their API and
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
leases from [Downloads](downloads.md#downloads-and-offline).

## Account Boundary And Lifecycle

See [accounts and persistence](accounts-and-persistence.md#account-boundary-and-lifecycle).

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

<a id="presentation-mvi-coverage-and-boundaries"></a>
<a id="find-mvi-pilot"></a>
<a id="library-browse-mvi-slice"></a>
<a id="library-hub-mvi-slice"></a>
<a id="library-selection-mvi-slice"></a>
<a id="player-controls-and-presentation-mvi-slice"></a>
<a id="browse-destinations-mvi-slice"></a>
<a id="seerr-requests-mvi-slice"></a>
<a id="detail-series-and-subtitle-search-mvi-slice"></a>
<a id="downloads-mvi-slice"></a>
<a id="settings-and-account-mvi-slice"></a>
<a id="authentication-mvi-slice"></a>
<a id="kids-catalogue-mvi-slice"></a>
<a id="android-launcher-mvi-slice"></a>
<a id="native-tvos-entry-screens-mvi-slice"></a>
<a id="native-tvos-browse-mvi-slice"></a>
<a id="native-tvos-authentication-and-accounts-mvi-slice"></a>
<a id="native-tvos-settings-mvi-slice"></a>
<a id="native-tvos-downloads-mvi-slice"></a>
<a id="native-tvos-detail-and-subtitles-mvi-slice"></a>
<a id="native-tvos-offline-playback-and-session-mvi-slice"></a>
<a id="native-tvos-online-playback-mvi-slice"></a>
### Presentation ownership

Shared Compose, Android TV, launcher and native tvOS presentation accept typed
inputs and use pure reducers for state transitions. Credential-bearing inputs must
not generate string representations exposing their payloads; retain a plain-class
or explicitly redacted representation. ViewModels/presenters retain
jobs, request generations, locks, persistence, effects and cancellation; reducers
perform no I/O. Evaluate live admission facts inside atomic update retries.
Independent lifetimes remain independent even when UI projects one operation
phase: a state record cannot replace synchronization, cleanup or queued receipts.

Player asset selection returns synchronous acceptance; Kids launch admission
remains typed, suspending and cancellable through installation. Catalogue selection
and launch consumption follow acceptance, so rejection cannot advance selection.
Read-only queries, observation handles, surfaces and lifecycle disposal remain
direct. UI owns navigation, focus, scroll, animation and local modal state.

Keep coherent screen state while passing children narrow inputs and preserving
unchanged lists. Source-level skipping opportunities require runtime measurement
before claiming performance gains. Player position and diagnostics remain separate
hot observations; controller projection preserves current diagnostics.

Async completion must match its request/item/account authority. Cancellation alone
is insufficient where callbacks or atomic retries can overlap. Persist against
latest user intent; independently loaded shelves and optimistic mutations must
survive unrelated refreshes. Pagination retains consumed server offsets and retry
latches. Silent refresh preserves content during loading/failure and replaces it
on successful empty results. Feature-specific contracts belong to [UI](ui.md),
[accounts](accounts-and-persistence.md), [API/cache](api-and-caching.md),
[downloads](downloads.md), [Kids](kids-viewing.md) and [runtime](playback-runtime.md).

Find submission returns a synchronous receipt used by Android TV's request-qualified
focus handoff. Shared typing drives search and IME commits history; TV submission
executes search. Refresh preserves query/results. Library shuffle remains a
buffered one-shot; its generation/admission/cleanup is Main-confined while fetching
runs on the work dispatcher. Native tvOS retains its separate presenters and
owner-controlled observation closure. Session routing follows committed session
state rather than optimistic sign-out. Subtitle install acknowledgements retain
selection revisions even across dismissal; artwork reads retain separate view
lifetimes so bytes never enter transfer-list state.

## Source Layout Target

Dependencies point from shells/presentation toward application coordination and
lower contracts. Lower modules never depend on the app aggregate.

| Owner | Responsibility |
| --- | --- |
| `core-model` | Immutable domain values, plans and pure value/queue/quota rules; no runtime, transport or persistence |
| `diagnostics`, `platform`, `network` | Safe vocabulary, dispatcher/environment services and credential-origin policy |
| `player`, `playback-policy` | Controller/lease contracts; concrete-backend capability, selection and recovery decisions |
| `jellyfin-api`, `transport` | Wire DTOs/profile mapping; HTTP execution over injected configured clients |
| `storage`, `auth-session` | Durable stores/Room schemas; credentials, account leases and session transitions |
| `player-runtime`, `diagnostics-runtime` | Native execution/probes; bounded capture/report preparation |
| `shared-core` | Planning, repositories, reporting, account-qualified offline validation and root DI |
| `ui-focus-scroll`, `android-hero-ui` | Independent focus/ribbon and Android artwork rendering leaves; no client aggregate or playback dependency |
| `ui-foundation` | One shared resource/theme/component owner, input/focus contracts and selection memory |
| `settings-account`, `browse`, `playback`, `detail`, `auth`, `downloads` | Cohesive presentation features over foundation/core |
| `shared-ui` | App composition, navigation, DI and generated configuration |
| Android/desktop/iOS shells, `shared-tvos`/`tvos-app` | Platform lifecycle, input, packaging; native tvOS presentation/SwiftUI |

Share code in the highest valid source set (`commonMain`, `appleMain`, `iosMain`,
`tvosMain`, `androidMain`, `jvmMain`). Use narrow `expect`/`actual` only where
common code cannot own behavior. Group by cohesive ownership; the roughly
500-line review trigger does not justify splitting cohesive state or adding wrappers.

`storage` owns Room generation and committed schemas. `auth-session` sits above
storage/transport without native players; launcher can consume it independently.
App composition owns singleton construction, cleanup membership, configured
HTTP-client lifetimes and native startup. Internal engine test seams remain with
their client factories; test-only upward dependencies do not permit production cycles.
Native implementations, cinterop and inseparable suites stay with their runtime owner.
Validated values retain private invariants; Kotlin production bridges must not
widen Objective-C APIs or expose trusted artifact paths to UI or diagnostics.

The iOS facade builds one static `SharedUi` framework without re-exporting the
implementation modules; Swift receives its entry/download bridge. tvOS explicitly
exports the shared types its presenters expose. Root factories retain Koin startup,
configuration and callback providers. Keep one public Compose resource package,
`com.jellyscope.ui.generated.resources`, in foundation. Module boundaries and smaller
framework headers do not establish faster optimized native Release builds; measure
the edit loop before making that claim.

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
  [playback runtime backend policy](playback-runtime.md#backend-selection).
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
[Downloads And Offline](downloads.md#downloads-and-offline).

iOS optionally injects continued-execution permission into the shared Apple
lifecycle host. The Swift controller owns BackgroundTasks objects; a narrow
`ios-framework` bridge forwards opaque generations and numeric progress to the core
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

See [desktop playback](desktop-playback.md#desktop-libmpv-boundary).

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
- Output evidence is capability-specific. Unsupported measurements stay
  explicit, and surface/Vout attachment alone never proves displayed output.
- `AndroidPlayerSurfaceHost` serves mobile/TV and attaches Media3 or app-owned
  mpv/LibVLC surfaces with shell subtitle inset. iOS attaches AVPlayerLayer or a
  retained VLCKit drawable behind iOS capability interfaces. Desktop mpv publishes
  through the JVM-only BGRA capability. Common UI never casts native controllers.
- Optional `PlayerTimingController` keeps persistence/UI common and application
  backend-owned. Unsupported backends expose no misleading value; detailed
  offset behavior belongs to [subtitle selection](subtitles.md#quality-tracks-and-subtitles).
- `SavePlaybackSelectionAction` and `SavePlaybackTimingOffsetAction` use the
  shared latest-wins per-key `DebouncedKeyedStoreWriter` off Main.
- Android backend choice is resolved before planning and remains authoritative
  through queue items unless explicitly switched. Startup recovery and offline
  construction fallback are distinct; installed offline playback never performs
  a remote recovery replan.
- `PlayerViewModel` owns explicit remote switching: it plans off Main while the
  current controller remains installed, uses the serialized release-first
  installer, revalidates ownership/identity after suspension, preserves durable
  preference and offline resolution, and releases untransferred candidates once.
  Factory-reported backend selects the plan reaching `prepare`; switch-only
  construction fallback tries one platform default.
- Android mpv and LibVLC keep vendor APIs inside their adapters. Controllers own
  serialized native lifecycle, tracks, diagnostics, retry, and release; surface
  hosts are owner-qualified and JNI/native logging admits no raw text. Their
  capability, track, readiness, and recovery details belong to
  [Android playback](android-playback.md#android-mpv-backend).
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
  payloads. Detailed [track](subtitles.md), [recovery](playback-runtime.md), and
   [profile](playback-policy.md) mapping has separate owners.

### Other bridges

Concurrency primitives must match access discipline: atomics for indivisible
updates, monitors for compound state, and cancellation/join outside locks. Native
Objective-C calls use the typed lazy facade; features retain selectors, callbacks,
AppKit dispatch and lifecycle. `LocalSubtitleFileStore.resolvePath` stays a bounded
synchronous path/stat seam; byte movement and reconciliation use injected IO.
Generalize retries/lifecycle only when their assets, timing and ownership share a
contract.


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
[diagnostic privacy](diagnostics.md#diagnostics-logging-and-privacy).

## External Source Boundary

JellyScope is independently implemented. Third-party source, structure, and
assets require file-level license review. License compatibility and distribution
gates live in
[Licensing And Distribution](../operations/licensing-and-distribution.md).

<a id="why"></a>
<a id="platform-strategy-and-sharing"></a>
<a id="android-playback-integration"></a>
<a id="playback-contracts"></a>
<a id="diagnostics"></a>
<a id="engineering-discipline"></a>
See [state ownership](#state-and-event-modeling), [module direction](#source-layout-target)
and [platform bridges](#platform-bridge-rules) for rules and their rationale.

## Launcher configuration ownership

See [launcher configuration ownership](../launcher/engineering.md#launcher-configuration-ownership).
