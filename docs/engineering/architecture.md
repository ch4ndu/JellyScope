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

## Source Layout Target

```text
core-model/src/commonMain/kotlin/.../
+-- domain/model/        # stable values, preferences and pure invariants
+-- domain/playback/     # playback plans and value vocabulary
+-- data/local/          # validated opaque artifact part names (package retained)

diagnostics/src/commonMain/kotlin/.../
+-- util/                 # safe logging and capture validation
+-- domain/playback/      # closed diagnostic field and platform vocabulary
+-- data/local/           # log-collection preference contract

platform/src/commonMain/kotlin/.../
+-- coroutines/           # shared IO dispatcher contract with platform actuals
+-- domain/platform/      # device/environment services and native-log cleanup port
+-- util/                 # cancellation-safe result handling

platform/src/jvmMain/kotlin/.../
+-- data/local/           # existing app-directory and serialized-writer primitives

network/src/commonMain/kotlin/.../
+-- data/remote/          # auth-header contracts/encoding and server-URL normalization
+-- security/             # credential-origin policy

player/src/commonMain/kotlin/.../
+-- domain/playback/      # controller contracts, state and runtime observations
+-- data/local/           # existing subtitle-file port
+-- playback/             # offline artifact leases and deletion guards

player/src/jvmMain/kotlin/.../
+-- data/local/           # existing desktop-volume port
+-- util/                 # positive process-local generation helper

playback-policy/src/commonMain/kotlin/.../
+-- domain/playback/      # capabilities, selection, diagnostics and session policy
+-- data/local/           # device-setting port over policy-owned values

diagnostics-runtime/src/commonMain/kotlin/.../
+-- util/                 # capture actor, timeline encoder, reports and failure hooks
+-- data/local/           # breadcrumb/previous-run contracts and shared bounds
+-- domain/action/        # collection toggle and explicit timeline preparation
+-- domain/model/         # prepared timeline and delivery result values
+-- playback/             # diagnostic snapshot values and source projection

jellyfin-api/src/commonMain/kotlin/.../
+-- data/remote/          # Jellyfin wire DTOs, profile mapping and typed API failures
+-- domain/model/         # Jellyfin-to-domain media projections

player-runtime/src/*Main/kotlin/.../
+-- playback/             # native controllers, surfaces and inseparable helpers
+-- domain/playback/      # native probes, audio routing and runtime diagnostics

player-runtime/src/nativeInterop/cinterop/
+-- vlckit.def            # existing VLCKit foreign package and matching Apple slices

shared-core/src/commonMain/kotlin/.../
+-- data/                 # remote, local, repository
+-- domain/               # adapters, usecase, action, planning and runtime
+-- di/

ui-foundation/src/commonMain/kotlin/.../
+-- component/
+-- adaptive/
+-- image/
+-- theme/
+-- focus/                # shared restoration and bring-into-view policies
+-- platform/             # shell-provided input/permission callbacks
+-- screen/               # shared selection, formatting and backdrop contracts

settings-account/src/commonMain/kotlin/.../
+-- screen/settings/
+-- screen/account/

browse/src/commonMain/kotlin/.../
+-- screen/               # home, discover, find, library, grid, collection, person
+-- paging/

playback/src/commonMain/kotlin/.../
+-- screen/player/        # player state and presentation

detail/src/commonMain/kotlin/.../
+-- screen/detail/        # media/series/season state and presentation

auth/src/commonMain/kotlin/.../
+-- screen/login/
+-- screen/serverentry/

downloads/src/commonMain/kotlin/.../
+-- screen/downloads/

shared-ui/src/commonMain/kotlin/.../
+-- screen/               # app-shell scaffolding
+-- navigation/
+-- di/

android-libmpv/           # project-owned Kotlin/JNI bridge over pinned AAR input
android-app/              # shared UI shell and selectable Android playback
android-tv-app/           # D-pad shell, TV screens/player, Watch Next
desktop-app/              # JVM Compose shell and libmpv/LibVLC bridges
ios-framework/            # narrow Swift API and static SharedUi framework
ios-app/                  # shared Compose shell and Apple playback
shared-tvos/              # Kotlin presenters, Swift bridge, static SharedTv
tvos-app/                 # native SwiftUI shell with AVKit/VLC hosts
```

Platform source sets include `androidMain`, `appleMain`, `iosMain`, `tvosMain`,
and `jvmMain`. Share in the highest valid set; use `expect`/`actual` only when
common code cannot own the behavior. Prefer feature grouping after a feature
owns enough data, domain, presentation, and DI to justify it.

`ios-framework` depends on `shared-ui` and `shared-core` without framework
exports. Swift consumes its `MainViewController` entry point and download
bridge. The entry calls the iOS factory in `shared-ui`, where Koin startup
and generated configuration remain. The fullscreen callback composition local
is shared through foundation; its provider remains in the root factory.
Keep one static `SharedUi` binary; Kotlin consumers continue using `shared-ui`.

`core-model` owns domain values and playback-plan vocabulary, including raw
launch inputs and subtitle intents, without runtime, persistence, transport or
presentation dependencies. `shared-core` exposes it
through an API dependency and retains application orchestration. Existing Kotlin
packages stay stable.
Pure value validation, download transition, quota, estimate and queue decisions,
and offline detail/artwork projections stay with their model values and private
helpers. The saturating-add and offline projection helpers are Kotlin production
bridges hidden from Objective-C for existing database and admission callers;
persistence and queue execution stay above. Mixed admission tests stay upper.
Consumers snapshot model properties into local values when a null or type check
needs a smart cast across module boundaries.
`shared-tvos` depends directly on and explicitly exports `core-model` from both
framework targets because Swift names these values. The narrow iOS facade does
not export it.

`diagnostics` owns safe producer tags, operation tokens, wire-field schemas,
platform vocabulary, formatting, capture validation and log-writer configuration.
It depends only on Kermit and coroutines. Collection preferences remain an injected
interface;
capture actors and reports belong to `diagnostics-runtime`, playback policy to
`playback-policy`, and persistence and uploads to `shared-core`. Internal scrubber policy and its existing tests stay together.
The wire-field enum and sanitized-marker formatter bridge are public to Kotlin
consumers and hidden from Objective-C; field-name sets remain internal.
`shared-core` exposes the module through API, and both tvOS frameworks explicitly
export it to preserve the existing Swift surface. The narrow iOS facade does not.

`platform` owns reusable IO dispatching, device/environment services, the native-log
cleanup contract and cancellation-safe result handling. Its existing actuals stay
in Android, JVM and Apple source sets; it depends on coroutines and the diagnostic
platform vocabulary. The Apple dispatcher remains `Default`; Android and JVM use
`IO`. Application DI and private Apple/Desktop device providers stay in
`shared-core`; the Android mpv native-log source belongs to `player-runtime`.
The existing public enum keeps its Kotlin package
and Objective-C exposure in `diagnostics`; no visibility is expanded. Core exposes
platform through API, and both tvOS frameworks explicitly export it to preserve
existing Swift declarations. The narrow iOS facade keeps no exports.

`network` owns authorization-header contracts and encoding, server-URL
normalization and credential-origin policy. It exposes the existing `platform`
interface dependency and uses Ktor HTTP internally, without a client engine.
The encoder remains internal; the credential-query constant is a Kotlin bridge
hidden from Objective-C for the existing Apple player. Session-backed device-ID
reads, HTTP clients, repositories and DI stay in `shared-core`. The session
provider retains its public class in a separate upper file. Core exposes network
through API, and both tvOS frameworks explicitly export it; the iOS facade keeps
no exports.

`player` owns controller/state, timing/volume and subtitle-file
ports, offline artifact leases and deletion guards, runtime observations and
progress contracts over `core-model` and coroutines. Atomicfu synchronizes lease
bookkeeping. The existing JVM volume port and generation helper also live here.
The validated opaque artifact part name, its private validation and two canonical
member values belong to `core-model`, retaining their Kotlin package. Concrete
file stores, account-qualified artifact validation and download deletion actions
remain upper; the lease registry coordinates their existing shared exclusion.
Fourteen necessary production declarations cross Kotlin module boundaries hidden
from Objective-C; trusted paths remain excluded from UI models, persistence and
diagnostics. Lease construction and private validation retain their restrictions.
Defaults and sealed families stay with their declarations. Native execution and
polling live in `player-runtime`; split reporting routes, planning and
account-qualified offline validation remain in `shared-core`. The output-color factory and shared frame-rate helper are
Kotlin bridges hidden from Objective-C; private constructors, private copy
visibility and validation remain intact. Core exposes player through API, and
both tvOS frameworks explicitly export it to retain the existing public surface.
The narrow iOS facade keeps no exports.

`playback-policy` owns capability declarations and pure capability factories,
backend/device policy, stream/track/URL decisions, playback diagnostic vocabulary
and formatting, typed planning failures, and recovery/health/session policy.
Its device-setting port uses the policy-owned settings values. The module
depends on `core-model`, `diagnostics`, `player` and coroutines. Native probes,
planner/repository execution, diagnostic capture/upload, persistence, navigation
and presentation lifetime stay above. Private helpers, sealed families, defaults
and public file facades stay with their declarations. Necessary Kotlin bridges
for existing upper production callers use `HiddenFromObjC`; other internals stay
internal. Core exposes the module through API, and both tvOS frameworks export
it; the iOS facade keeps no exports. Pure capability cases follow policy;
wire-profile mapping tests follow `jellyfin-api`, without a test-only API.
The [playback policy guide](playback-policy.md) covers the broader quality and wire contracts.

`player-runtime` owns native controllers, surfaces, device probes and their
inseparable timing, activation, routing, network and diagnostic helpers. It
exposes existing network and playback-policy contracts; native libraries remain
implementation dependencies. The existing Android backend-specific Koin factory
moves with its construction policies; root composition and account-scoped
resolver injection retain their application lifecycle. Apple expect/actual
factories and the existing `org.videolan.vlckit` interop package stay together.
Core exposes runtime through API and both tvOS frameworks explicitly export it;
the narrow iOS facade keeps no exports. JVM startup and upper DI use necessary
production bridges; Apple-only bridges remain hidden from Objective-C. Other
controller constructors, native internals and test fixtures remain internal.

The existing JVM app-directory function and serialized latest-value writer belong
to `platform`, retaining their package and behavior. Concrete Room/preferences
stores stay core-owned and use those same primitives. Whole native suites move
with runtime; mixed account/reporting and concrete-store suites stay with core.
No test-only production API or friend relationship is required.

`jellyfin-api` owns Jellyfin wire DTOs, metadata-to-domain projections,
device-profile mapping and the typed API failure family. It exposes existing playback-policy inputs and the serialization
runtime; Ktor HTTP is an implementation dependency for parsing transcode reasons.
JSON encoding and metadata-mapping tests follow the wire owner. Public mapper
packages and filenames retain their existing DTO extension identities.
HTTP execution, client factories,
sessions, repositories and DI remain in `shared-core`. Six necessary request and
response-mapping Kotlin bridges are hidden from Objective-C. Core exposes the
module through API, and both tvOS frameworks explicitly export it to preserve
existing Swift declarations; the narrow iOS facade keeps no exports.

`diagnostics-runtime` owns the bounded capture actor, report rendering, timeline
encoder and preparation, collection toggle, previous-run contracts and process
failure hooks. It exposes existing `platform` and `playback-policy` contracts;
atomicfu and JSON remain implementation dependencies. The two shared capture
bounds, three marker helpers and three delivery checks are production Kotlin
bridges hidden from Objective-C. Timeline construction, actor bookkeeping,
encoding internals and platform installer hooks remain internal. Existing whole
suites follow these owners. Account-scoped failure context, concrete Room/file
stores, upload execution and DI stay in `shared-core`; its retained class file
has no top-level facade, while the lower public values and source extension keep
the original package and filename. Core exposes runtime through API and both
tvOS frameworks explicitly export it; the narrow iOS facade keeps no exports.

`ui-foundation` owns shared resources, theme, adaptive layout, components, and
image infrastructure over `shared-core`. `settings-account`,
`browse`, `playback`, `detail`, `auth`, and
`downloads` depend on foundation and core;
`shared-ui` composes the UI modules and retains navigation, DI, generated configuration, and `AppInfo`.
Lower modules never depend on the
app aggregate. Settings receives version and revision strings from root DI.
Keep the resource package `com.jellyscope.ui.generated.resources` public and
defined only in foundation; all platforms consume that one asset set. Existing
Kotlin packages remain stable across these module boundaries, including the
shared Detail interaction locals and download byte formatter in foundation.

Browsing screens, ViewModels, pagination, and their existing tests stay together
in `browse`. Root navigation, DI, and mixed Android previews consume its
public presentation API; preview fixtures remain with app composition.

Player presentation, ViewModels, and platform UI hosts stay together in
`playback`, including their common, Android-host, iOS, and JVM friend
tests. Native controllers and Android backend factory bindings belong to
`player-runtime`; backend and capability decisions belong to `playback-policy`.
Root DI retains application composition, resolver injection and pending-controller
startup. Neutral key-command, safe-focus,
and iOS fullscreen callbacks live in foundation so player code has no dependency
on app composition or Detail. Android shells and mixed app previews consume the existing public player
presentation contracts.

Media, series and season Detail screens and their ambient-color platform
adapters share `detail` with their existing friend tests. Root navigation,
DI, TV focus providers and mixed previews consume its public API. Shared focus
restoration, permission callbacks and backdrop geometry live in foundation for
Detail, Downloads and TV. The existing account-scoped playback-selection memory,
recall precedence and duration formatter also live there; Detail has no dependency
on the playback presentation module. Root DI still owns the single memory
registration and its account/server cleanup boundary. The mixed focus-restoration
suite stays with Detail because it also exercises the internal container
constructor; foundation’s focus policies remain public.

Server entry, discovery UI, credential login and Quick Connect share
`auth` with their existing ViewModel tests. Root navigation and DI retain
session startup and constructor parameters; core retains discovery, authentication,
credentials and persistence. TV keeps its UI and consumes the same public state
and ViewModels through app composition.

Downloads list/detail state, presentation and their existing ViewModel tests
share `downloads`. Core retains admission, execution, storage and account-qualified offline
validation; Detail retains enqueue entry points. Root navigation and TV consume the
existing public API, and shared backdrop/formatting contracts remain in foundation.

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

## Why

### Platform strategy and sharing

- Safe diagnostic production and capture schemas form one lower boundary, so
  transport, persistence and native implementations can use the privacy gate
  without depending on application orchestration. Capture and upload lifecycle
  remain above that boundary; the existing wire format and admission rules stay
  unchanged.
- Domain values, playback plans and launch/selection contracts form a stable
  lower dependency closure.
  Keeping their pure invariants, offline projections and download quota/queue arithmetic together
  separates value ownership from execution changes without moving controllers or
  persistence contracts. Transport estimates reuse the same saturating arithmetic
  without importing repositories or storage.
  Existing self-contained value tests follow that owner; mixed planning tests
  stay with their implementation. Explicit tvOS exports preserve direct Swift
  use while the iOS facade keeps its narrow surface.
- Authorization syntax and credential-origin policy apply across API, image and
  native playback requests without owning a session or transport lifetime. A
  lower network owner keeps encoding and trust helpers together; session-backed
  persistence and HTTP execution retain their upper owners.
- Jellyfin wire models, metadata projection and profile encoding form a reusable
  contract below HTTP execution. Keeping their serialization defaults, mappers and existing tests
  together separates wire changes from client and repository lifetimes.
- UI foundation, Settings/Account, browsing, playback presentation, Detail,
  authentication and Downloads have cohesive owners below app
  composition, so a feature edit can compile a smaller source unit. Existing tests move with
  their internal owners. One resource module preserves generated imports and
  packaging without duplicating assets. Module boundaries do not make optimized
  native Release linking incremental; measure affected edit loops before
  claiming a build-time improvement.
- Browsing shares state projections and test internals across its screens.
  Keeping them in one feature avoids fragmenting that ownership or duplicating
  fixtures; its existing stateless content functions remain usable by root
  previews.
- Playback state and platform UI hosts share lifecycle and test internals, so
  they move together without changing controller or application-startup owners.
  Existing neutral input callbacks belong in foundation rather than introducing
  an upward dependency or a second callback owner.
- Detail and player share selection memory and formatting, not each other’s
  platform presentation. Foundation owns these existing contracts and the focus
  and backdrop policies used by multiple screens; root providers retain their
  lifecycle and account cleanup responsibilities.
- Authentication presentation shares a pre-login journey and dependency closure.
  Keeping it separate from post-login Settings avoids coupling their screens and
  platform adapters; credentials and session state remain core-owned.
- Downloads list/detail presentation forms one boundary over existing core
  operations. It stays separate from transfer/storage ownership and Detail’s
  enqueue dialog, avoiding a dependency between presentation features.
- Reusable platform services sit below application policy so future storage and
  player owners can use the same IO, environment and cancellation contracts.
  The closed platform enum belongs with diagnostic vocabulary, keeping those
  adapters independent of upper playback formatting. Private DI and concrete
  native cleanup retain their application lifecycle owners.
- Native execution depends on policy, network and platform primitives while
  application planning, account authority and reporting retain their core owner.
  Co-owning native factories, private helpers and whole existing suites avoids
  dependency cycles and test-only APIs; cinterop stays with its native users.
- Capture, timeline preparation and process hooks share bounded history and
  privacy lifetimes. Keeping their complete tests with the runtime owner avoids
  test-only APIs; concrete persistence and delivery retain application authority.
  Runtime sits above platform and playback policy so lower diagnostic vocabulary
  remains reusable without a dependency cycle.
- Player contracts expose coroutine flows and shared lease exclusion, so their
  separate owner keeps the model-value layer free of runtime dependencies.
  Existing file/volume ports separate native playback from concrete persistence;
  one lease registry preserves playback-versus-deletion authority across owners. Validated observation values
  keep construction with private invariants; native polling and reporting side
  effects remain with their existing lifecycle owners. The two production Kotlin
  bridges preserve those invariants without adding a Swift API.
- Capability, stream/track and recovery decisions share the same closed playback
  vocabulary and private helpers. Keeping that closure below native execution
  avoids upward capability, URL or sealed-reason dependencies. Typed planning
  failures cross the boundary without importing planner execution; existing
  production Kotlin bridges retain a narrow Swift surface. Caller-owned scope
  and callbacks preserve presentation lifetime and side-effect authority.
- The iOS framework boundary keeps unused presentation declarations out of the
  Swift API and its native export roots. It preserves full Release optimization;
  fewer exported roots can reduce the graph processed by global optimization.
  Framework header size alone does not establish a build-time improvement.
- Native tvOS preserves Siri Remote focus and keeps SwiftUI/AVKit outside common
  code while sharing Kotlin presentation/domain. Platform-family implementations
  stay in Apple source sets.
- Downloads is installed only through platform graphs that supply real storage
  and lifecycle contracts; cache cleanup must not own downloaded media.
- Duplicate iOS/JVM Skia ambient-color actuals remain because an intermediate
  source set would disable the default hierarchy and restructure every target for
  a bounded narrow seam.
- Nullable side interfaces keep platform-only capabilities out of
  `PlayerController`; shared persisted decoding keeps defaults local so platform
  policy and on-disk compatibility do not silently converge.

### Android playback integration

Android mpv wrapper provenance belongs to
[Android Native Dependencies](../operations/android-native-dependencies.md).

### Playback contracts

- Live backend switching stays in `PlayerViewModel` because it shares launch
  identity, controller ownership, reporting, and installer authority.
- Launch reads are shared; memory, explicit intent, validation, reconciliation,
  and stale-result handling remain with each caller.
- `LocalSubtitleFileStore.resolvePath` remains a bounded synchronous stat/path
  seam; byte movement and reconciliation use injected IO owners. No common
  staging lifecycle exists to justify a wider suspend abstraction.

### Diagnostics

- Closed sanitized causal records support first-pass diagnosis without exposing
  external data; a generic unavailable flag cannot identify the failing boundary.

### Engineering discipline

- Remove obsolete compatibility fields so the compiler exposes consumers. Share
  only real common behavior; controller retries and mutable session state remain
  local where assets, timing, order, or lifecycle differ.
- Native Objective-C dispatch uses one typed lazy `libobjc` facade, while each
  feature owns selectors, callbacks, AppKit dispatch, and lifecycle.
- Concurrency fixes match access discipline: volatile for independent reads,
  atomics for indivisible updates, monitors for confined compound state, and
  cancellation/join outside locks.
