# JellyScope Architecture

Load this when working on architecture, source layout, dependency flow,
ViewModel state/events, platform integrations, or cross-layer behavior.

## Stack Target

- Kotlin Multiplatform Compose targeting Android TV, Android mobile, iOS, and
  JVM Desktop, plus a native SwiftUI tvOS shell (Compose Multiplatform
  publishes no tvOS UI targets). Android TV and Android mobile are the
  primary product surfaces; iOS and desktop reuse the shared app root and
  have active platform playback bridges; tvOS reuses shared-core through the
  `shared-tvos` presenter layer.
- Compose Multiplatform for shared UI where stable enough.
- Platform-native playback bridges where required: Android Media3/ExoPlayer
  (with decoder fallback and the optional Jellyfin FFmpeg extension), the
  project-owned `android-libmpv` bridge based on the pinned
  `dev.jdtech.mpv:libmpv:1.0.0` native input (selectable on mobile and TV),
  or the in-process LibVLC 3.7.5 bridge (beta on Android and desktop),
  plus desktop `libmpv` and native Apple playback for iOS/tvOS. The Android
  wrapper/native-input path and desktop libmpv path are separate integrations.
- Koin for dependency injection unless the project later chooses a different
  local standard.
- Ktor for KMP HTTP behind a project-owned API facade. Replacing that
  implementation must preserve the same repository boundary.
- kotlinx.serialization for JSON DTOs unless the API facade decision changes.
- Coil 3 for image loading/caching where supported.
- `kotlinx-datetime` or `kotlin.time` for time logic.
- `kotlin.test` for shared tests in KMP source sets.

## Layering

- Data layer owns Jellyfin API clients, response models, local cache, settings,
  repositories, and platform data boundaries.
- Domain layer owns read UseCases, write Actions, domain models, mappers, search
  projections, playback planning, and every result/protocol that a ViewModel or
  presenter uses to make a product decision. Session-removal authorization and
  errors, download command outcomes, subtitle-selection identity, and the
  visible-related projection therefore remain domain-owned even when a data
  repository implements the operation.
- Settings value models such as `AppColorThemeId` and `PlaybackPreferences` are
  domain models. Data-layer stores persist and expose those models; UI and
  ViewModels read/write them through UseCases and Actions.
- ViewModels are per screen and inject UseCases/Actions, never repositories.
- Direct OpenSubtitles traffic stays behind `OpenSubtitlesApi` and
  `OpenSubtitlesRepository`. ViewModels search through a UseCase and
  install/delete/retry through Actions; wire DTOs, file stores, Room DAOs, and
  Jellyfin upload calls remain below the ViewModel boundary.
- ViewModels must not construct Jellyfin transport queries such as
  `ItemsQuery`; expose focused UseCases such as `GetItemsByIdsUseCase` and keep
  query shape inside data/domain layers.
- UI renders state and sends events. Filtering, sorting, grouping, mapping,
  row-packing, and playback decision-making belong in UseCases or ViewModels,
  not composables.
- Screen entry composables may collect state and effects; plain UI/content
  composables receive immutable UI state and explicit callbacks.
- Platform player implementations consume `PlaybackPlan`; they do not decide
  Jellyfin stream strategy themselves.
- Persistent subtitle files use the platform `LocalSubtitleFileStore` boundary.
  Room owns asset metadata and selection persistence, while one app-scoped
  coordinator is the only writer of local subtitle files, asset rows, and
  selections. Its selection-store facade also routes account cleanup through
  that owner. Platform players receive only the sealed remote/local
  `SubtitleAsset` plan contract.
- Transport-specific playback failures are mapped to project-owned domain
  failures below playback planning and presentation. ViewModels and presenters
  do not import data-client exception types to decide retryability or UI state.
- Platform player implementations also expose project-owned player control,
  observable playback state, and progress-reporting contracts; UI and ViewModels
  must not talk directly to native player APIs.
- Platform integrations sit behind project-owned interfaces or narrow
  `expect`/`actual` boundaries, as defined below.

## Account Boundary And Lifecycle

- `AccountIdentity(serverId, userId)` is the shared identity for account-owned
  cache, activity, playback-memory, and provider state. Connection URLs remain
  replaceable session metadata and are not account keys.
- `SessionTransitionCoordinator` serializes restoration, login/add-account,
  switch, removal, and logout through the existing
  `ServerScopedStoreRegistry` mutation gate. Network authentication runs
  outside the gate; its opaque attempt token must still be current at commit.
- Cold restoration installs the active identity and exact boundary epoch under
  the gate before publishing `SessionState.LoggedIn`. Repositories and
  background-work callers such as `WatchNextSyncWorker` capture that immutable
  snapshot and acquire an account work lease; stale leases cannot start or
  commit account-bound work.
- The shared and Android TV logged-in composition roots are keyed by account
  identity plus boundary epoch. Re-authentication with a rotated token gets a
  fresh lifecycle even when server/user identity is unchanged.
- `SessionRepository.accounts` and `SessionState` are derived together from one
  committed session snapshot. Account `isActive` markers use that snapshot's
  active identity, so account tiles and account-switch surfaces cannot observe
  an active-account generation independent of the published session.
- Cold restoration reads one committed session snapshot. Login, account switch,
  and same-identity reauthentication finish applicable precommit validation and
  participant preparation, then explicitly check caller cancellation before
  crossing the irreversible boundary. Their complete tail — authoritative
  envelope persist/load, the applicable outgoing-account subtitle barrier,
  boundary epoch and registry transition, and exact committed-snapshot
  publication — runs in one `NonCancellable` phase. After that tail completes
  successfully, caller cancellation is surfaced only after those durable and
  observable facts agree. Account removal likewise carries its immutable
  committed snapshot through publication. No observable account transition
  rereads secure storage after its commit.
- Account removal is atomic. Destructive cleanup runs outside cancellable
  caller context, per-phase failures aggregate instead of abandoning remaining
  stores at the first throw, any value needed after the first destructive write
  (such as the sibling-server answer) is computed before that write under the
  store mutex.
- Runtime caches remain registry-managed. Persistent cleanup is owned by the
  explicitly resolved `PersistentAccountStoreCleaner`, which covers full,
  server, and account cleanup without depending on lazy feature-store
  registration. App-global theme, tile, PiP, log, OpenSubtitles consumer-key,
  player-device capability policy, and local subtitle files/assets remain
  outside that cleaner according to their existing policies.
- `ServerScopedStoreRegistry` clears through two non-overlapping interface
  filters: the account-switch path (`transitionToAccount`) clears only
  `RefetchableServerCache` implementations, and the account-removal path
  (`clearAccount`) clears only `AccountScopedClearableStore` implementations.
  Neither filter sees the other's interface. A runtime store that must be empty
  on both boundaries has to declare **both** interfaces; declaring one leaves
  previous-account state readable on the other path, and nothing at either call
  site says so. Each interface needs its own test — one driving a switch, one
  driving a removal — because the missing half fails silently on exactly one
  boundary. `PlaybackDiagnosticsContext`, `DiscoveryCache`, and
  `DetailRelatedCache` each declare both.

## UseCases And Actions

- Reads use one UseCase class per read operation.
- Writes and side-effecting operations use one Action class per operation.
- Actions expose `suspend operator fun invoke(...)` unless a flow contract is
  explicitly needed.
- New features should create or reuse UseCases/Actions first, then wire them
  into the relevant ViewModel.
- Keep repositories behind UseCases/Actions. Screens should not inject
  repositories directly.
- Documented exception: desktop player volume/mute persistence is
  controller-owned (`MpvPlayerController` submits to the store-owned
  serialized latest-value `JvmFileVolumeStore` writer, injected via
  `desktopCoreModule`). It is player-internal restore state that never
  crosses a ViewModel as a settings surface; do not generalize this pattern
  to other settings (rules in `docs/guides/data-playback.md` → Desktop recovery).
- Shared UI may use domain-level identifiers such as `MediaRibbon`; it must not
  import repository-owned enums or DTOs for screen decisions.
- New values added to the compiler-declared stable domain-model package must be
  structurally immutable. Raw mutable byte carriers stay data-owned, and opaque
  protocols belong in their domain action/playback package instead of being
  masked with stability annotations.
- App-global settings stores, such as the selected app color theme, are not
  server-scoped and must not be registered with `ServerScopedStoreRegistry`.
- ViewModels read settings through UseCases and write settings through Actions;
  they do not inject settings stores directly. Reuse the settings wrappers:
  `ObserveAppThemeUseCase`/`SetAppThemeAction`,
  `ObservePictureInPictureEnabledUseCase`/`SetPictureInPictureEnabledAction`,
  `GetPlaybackPreferencesUseCase`/`SavePlaybackPreferencesAction`,
  `GetGridSortUseCase`/`SetGridSortAction`,
  `GetLibrarySortUseCase`/`SetLibrarySortAction`, and
  `GetRecentSearchesUseCase`/`AddRecentSearchAction`.

## State And Event Modeling

Choose the Flow primitive from product semantics, not convenience:

- `StateFlow` for state with a current value; ViewModels expose it read-only and
  keep mutable state private.
- Cold `Flow` for work that should execute per collector.
- `SharedFlow` for deliberately configured hot broadcast behavior.
- `Channel(BUFFERED).receiveAsFlow()` for reliable single-consumer one-shot
  handoff. It is fan-out, not broadcast: multiple collectors divide events.

State rules:

- Prefer `_state.update { current -> current.copy(...) }` for atomic
  read/modify/write. Keep update blocks pure and fast; calculate network results,
  time, random IDs, and expensive projections before entering them.
- Model loading, absence, and errors explicitly rather than inventing sentinel
  domain values.
- Guard a persistence write on the latest user intent, never on a shared
  load/sort generation: a reload does not invalidate intent.
- Define `stateIn` as a stable property, not a function that creates a new
  sharing coroutine per call. Use `WhileSubscribed` only when stale or
  uninitialized synchronous `.value` reads are acceptable.
- Keep navigation, snackbar, toast, and similar one-shot effects out of durable
  UI state unless they are intentionally persistent. A default
  `MutableSharedFlow()` loses emissions when no collector exists.
- State-holder composables may collect state/effects and wire dependencies;
  plain content composables receive immutable state and callbacks. UI-local
  scroll, focus, animation, and interaction state can remain in the composable
  that renders it.
- Player picker visibility and selected track/quality values are durable player
  state. Requested and installed audio are distinct: request intent drives
  PlaybackInfo and process-local memory, while picker/debug truth changes only
  after response-authoritative or exact platform-confirmed activation. Requested
  and active subtitles are distinct: only a server-rendered
  active subtitle or an exact platform-confirmed activation target is published
  as selected. While PlaybackInfo re-plans a requested change, render state stays
  tied to the installed `PlaybackPlan` so the old active track is not confused
  with the new request. Stale confirmations remain pending; explicit failures or
  an unconfirmed target three seconds after player readiness become
  `Unavailable`, and a late exact confirmation may recover to `Active`.
- The six-second subtitle-unavailable banner is UI-local presentation derived
  from durable state, not a ViewModel event. Fatal player errors and non-fatal
  `audioUnavailable` are also durable state; UI chooses their presentation.
- OpenSubtitles search/download progress and quota feedback are dialog state;
  successful installation is a buffered one-shot handoff to its detail screen.
  Installed asset rows and upload/reconciliation state remain durable Room state.

State review checks:

- Does the primitive match replay, fan-out, buffering, and `.value` semantics?
- Can an important event be lost while there is no collector?
- Are updates atomic and sharing coroutines stable?
- Do plain composables remain independent of ViewModels, repositories, and
  navigation controllers?

## Source Layout Target

```text
shared-core/src/commonMain/kotlin/.../
+-- data/
|   +-- remote/     # Jellyfin HTTP/API clients and DTOs
|   +-- local/      # Cache, settings, persistence
|   +-- repository/ # Repositories wrapping API/cache/settings
+-- domain/
|   +-- model/      # Domain models and mappers
|   +-- usecase/    # One class per read operation
|   +-- action/     # One class per write or side effect
|   +-- playback/   # PlaybackPlan and playback planning
+-- di/             # Shared dependency modules

shared-ui/src/commonMain/kotlin/.../
+-- component/      # Reusable composables
+-- screen/         # Shared screens/content
+-- theme/          # Material theme and dimensions
+-- navigation/     # Shared route/state helpers where appropriate

android-libmpv/
  # Project-owned Android Kotlin/JNI wrapper; its pinned AAR is a build input
  # for unchanged native libraries, not a runtime Maven wrapper dependency.

android-app/
  # Android mobile shell with shared UI navigation and selectable playback.

android-tv-app/
  # Primary TV shell with D-pad navigation, TV screens, player, and Watch Next.

desktop-app/
  # JVM Compose Desktop shell with the libmpv playback bridge.

ios-app/
  # Shared Compose app with AVPlayer playback bridge.

shared-tvos/
  # tvOS Kotlin presentation layer: presenters over shared-core
  # UseCases/Actions, StateFlow-to-Swift watch bridge, TvosEntry Koin
  # facade; builds the static `SharedTv` framework (exports shared-core).
  # Its jvm target exists solely to run the presenter test suite.

tvos-app/
  # Native SwiftUI Apple TV shell over `SharedTv`. Swift renders presenter
  # state and never touches Koin, repositories, or vendor SDKs directly;
  # playback hosts AVPlayerViewController over the shared Apple controller.
```

Platform directories are `androidMain/`, `appleMain/` (iOS + tvOS family),
`iosMain/`, `tvosMain/`, `jvmMain/`, and target-specific app modules as
needed. Use `expect`/`actual` only when shared common code cannot reasonably
handle the behavior.

Inside shared modules, prefer feature grouping once a feature becomes large
enough to own data, domain, presentation, and DI code. Do not force premature
feature modules during the scaffold phase, but avoid letting global layer
folders become dumping grounds.

Large source files should be split by ownership, not by artificial abstraction.
Keep public entry contracts in accurately named contract/interface files, move
platform implementation helpers, query constants, fixtures, chrome, rows, and
other reusable top-level declarations into same-package sibling files, and treat
roughly 500 lines as a review trigger rather than a hard limit. Do not split a
single cohesive class or function body just to hit the line target if doing so
would change behavior, state flow, navigation, playback, DI, or API boundaries.

## Platform Bridge Rules

Keep platform code at the edges and share it in the highest valid source set.
Use intermediate source sets such as `appleMain` or `iosMain` when one
implementation serves a platform family; do not duplicate identical actuals
across concrete targets.

When a platform store persists a value, the **wire format and its tolerant decode
belong in `commonMain`, not in each store.** Three platform stores independently
re-implemented the library-sort `SortBy:SortOrder` string and its parser, so the
persistence contract had three homes and no shared test; it now lives in one codec with
the malformed-input table under test. Two rules follow. The stored shape is a contract
already on user disks and cannot change without a migration. Tolerant decoding is
deliberate — an unknown enum name written by a newer build must decode to null so the
caller falls back to its default, never throw, or a downgrade corrupts the preference.
Storage mechanics (SharedPreferences, file, `UserDefaults`), key names, prefixes, and
server-scoped clear semantics stay per platform.

Be wary of sharing *near*-identical platform helpers. The settings stores share
one nullable tolerant-enum parser because the decode shape is identical, while
each fallback remains at its call site so the helper cannot equalize persisted-
preference behavior invisibly. Tabulate defaults before extracting any similar
parser. The composite `SavedLibrarySortCodec` remains a separate wire codec with
its own malformed-input contract.

Prefer an injected project-owned interface when a dependency has meaningful
behavior, needs fakes, has lifecycle/setup, may have multiple implementations,
or represents a player, store, downloader, or OS integration. Prefer a narrow
`expect`/`actual` function, property, or factory when the surface is tiny and no
alternate implementation is expected. Avoid `expect`/`actual` classes unless
simpler seams cannot express the boundary.

Platform entry points construct implementations and provide them through DI or
factories. Shared code receives already-built interfaces and shared app identity
(`ClientInfo`/`AppInfo`); it must not locate native services or vendor SDKs.
Native callbacks become project-owned state/events before crossing into shared
layers, and bridge failures use project-owned error models.

Current entry-point responsibilities:

- Android mobile wires the selectable Media3/mpv/LibVLC backend, secure storage,
  filesystem, notifications, callback-owned MediaSession, and PiP. Android TV
  owns a separate launcher/navigation/focus shell while reusing the shared
  Android player, surface, audio-focus, and backend integrations; its production
  selector admits ExoPlayer, mpv, and LibVLC (beta). Android mpv requires
  API 26+ and refuses via typed availability on older devices.
- iOS wires AVPlayer, persistent session/settings storage, and Apple lifecycle
  integrations. Session credentials and selected preferences use app-owned
  `NSUserDefaults`; Room remains the store for the data families bound below,
  and application credential persistence never accesses Keychain.
- Desktop wires the Compose window, Koin, window-level fullscreen/key/cursor
  capability bridges, the app-owned plaintext JSON secure store under
  `~/.jellyscope`, and the JNA `libmpv` player. macOS application credentials do
  not access Keychain; release signing credentials are a separate concern.
- tvOS wires Koin, presenter factories, and dev prefill through `TvosEntry`
  (the entire Swift-facing surface). Apple-family implementations live in
  `appleMain` (`Apple*`-named); only the store-directory choice (iOS
  Documents / tvOS Caches, per tvOS purgeable-storage policy) and the Koin
  entry modules are per-target. Apple TV hardware playback, display, and
  remote behavior remain explicit simulator-unverifiable risks.

### Downloads module boundary

Offline Downloads is an opt-in supported-platform graph, not part of the
unconditional core module. Android mobile, Android TV, iOS, and JVM desktop
install `downloadsModule` and supply their project-owned database factory,
artifact store, and execution/lifecycle host at the platform edge; tvOS installs
none of those bindings and makes no feature claim. The feature retains normal
layering inside that graph: download repositories and platform adapters feed
domain UseCases/Actions, ViewModels consume only those domain surfaces, and UI
never owns the queue or filesystem. Its `DownloadDatabase` is isolated from the
ordinary media/cache database so durable transfer/removal state cannot inherit
refetchable-cache cleanup policy. The complete data and storage contract lives
in [Downloads And Offline](data-playback.md#downloads-and-offline).

### Android TV focus ownership

Android TV route focus is owned by `TvFocusCoordinator`. Every back-stack entry
has a monotonic route-entry ID, and route snapshots persist one logical
`TvFocusPath` plus the route's `TvFocusMemoryState`. Focus identity is made from
stable string scope and target keys, never a requester instance or lazy-layout
index. A route pop creates a tokenized restore transaction; only confirmed
focus on the resolved target consumes that transaction. Directional hardware
input may cancel a pending restore only after the route transition is settled.
Every route-level `SaveableStateProvider` key includes that route-entry ID and
the presentation epoch, because `AnimatedContent` may compose outgoing and
incoming entries simultaneously.

TV Detail ViewModels are retained per route-entry ID while that entry is active
or remains in route history. Navigation retention and rendered-content leases
are tracked separately: outgoing content keeps its owner until Compose disposes
it, while an unrendered parent keeps its owner while it remains on the stack.
Owners clear when neither condition applies and all clear at the session
boundary. Render keys capture the Detail item ID, so concurrent parent and child
compositions never read one mutable route payload.

Kernel-managed groups use `TvFocusScopeNode` and the pure `TvFocusResolver`.
They do not also use Compose `focusRestorer`; static non-lazy chrome and
shared-ui detail-local traversal may retain it when they are not route-memory
owners. Shared detail composables expose stable string keys through the narrow
`DetailFocusBridge` and remain independent of Android TV coordinator types.
Android TV supplies that bridge per rendered route entry; inactive outgoing
entries cannot read, record, or consume the active entry's restore transaction.
Route-ready ribbon restores cross that bridge as a project-owned stable key plus
fallback semantic index. The focus kernels keep semantic positions separate
from lazy-slot indices, own the single off-screen reveal, and confirm focus with
both key and semantic index so removal can resolve within the same ribbon.
A restore-aware `BringIntoViewSpec` suppresses automatic child scrolling only
while that kernel owns the handoff and otherwise delegates to the surface's
existing scrolling policy unchanged.

Silent Detail refresh replaces the server-refreshed projection atomically while
preserving the latest Related groups and their loading state from current UI
state. It never owns or completes the Related stream, which may continue in the
retained ViewModel while a child route is open.

### Desktop libmpv boundary

On macOS arm64, development and packaging use the same pinned IINA 1.4.0 dylib
set. `scripts/fetch-desktop-mpv-runtime.sh` validates the complete 69-file
cached inventory on every use; `scripts/prepare-desktop-mpv-bundle.sh` copies that exact
inventory into prepared app resources, rewrites non-system load paths to
`@loader_path`, restores valid staged signatures, and verifies architecture,
deployment floor, and closure.
`LibMpv` loads only the prepared `libmpv.2.dylib` on macOS, so a missing bundle
fails engine initialization without searching Homebrew, MacPorts, `/usr/local`,
or a bare library name. Linux retains its system-path lookup and Windows must
ship `mpv-2.dll`. `LibMpv` sets `LC_NUMERIC=C` before use. Release builds use
`desktop-app/proguard-desktop-release.pro` to keep runtime-loaded
Ktor/Coil/Room/SQLite/JNA/libmpv providers.

On macOS Apple Silicon, release package tasks also run
`scripts/prepare-desktop-vlc-bundle.sh`. It validates the audited VLC 3.0.23
arm64 manifest, copies the runtime under the platform-scoped resource root, and
ships the license texts and attribution in `licenses/`. Intel macOS packages
are not supported by the current desktop distribution.
`DesktopVlcRuntimeDiscovery` prefers the bundled root; development runs fall
back to `/Applications/VLC.app`. The desktop availability listing is a cached,
file-based check rather than a full-engine probe, while VLC 3 version validation
remains at engine creation. The existing `signBundledMpvDylibs` walk signs every
prepared native dylib, including VLC, and the per-variant
`verifyPackagedDesktopVlcBundleMain`/`verifyPackagedDesktopVlcBundleMainRelease`
tasks check that the required VLC files survive into their own variant's app
image (each is scoped to its variant directory so a stale pre-VLC image from
the other variant cannot fail the build). The packaged/runtime acceptance gate
remains pending.

The desktop mpv surface uses an mpv-only JVM side contract. The software
rendering path remains the portable implementation on Windows and Linux and the
automatic compatibility fallback on macOS:
`shared-ui` owns two persistent Skia BGRA bitmaps and passes their validated
native addresses through `MpvVideoOutput`; `shared-core` asks libmpv to render
into the current back target on a frame-ready-driven off-Main consumer. Only a
complete front image crosses to Compose on Main, with a one-frame ownership
acknowledgement before the prior front can be written again.

macOS mpv uses an app-owned IOSurface Render API by default. `shared-ui` creates
a generation-scoped plain `NSView` child with a private CGL context and a
three-buffer IOSurface swapchain; libmpv renders into the current FBO and the
render executor publishes the completed IOSurface through a disabled-actions
`CATransaction` on an app-owned sublayer. `shared-ui` owns the AppKit, Retina
backing-size, CGL, swapchain, resize, and detach lifecycle. `shared-core` retains
the libmpv OpenGL callbacks and render context, renders on one dedicated
executor, and acknowledges detach only after callbacks stop and the render
context is freed. Engine initialization and release are admitted under the
existing `engineLock`; initialization jobs are registered lazily, and teardown
joins captured jobs only after leaving the monitor so a released controller
cannot publish a late active presentation. IOSurface construction or validation
failure falls back to the generation-scoped `NSOpenGLView` surface, then the
software path before loading the item. This path never supplies `wid` and never
lets mpv create or focus a window. The macOS launcher always carries the Compose
interop and narrow JDK module-open arguments required by both native child surfaces;
backend selection no longer changes build arguments.
Desktop player controls are composed only in the parent Compose scene. A
`BlendMode.Clear` rectangle cuts the video area through the opaque scene, while
a 1dp `SwingPanel` supplies only the AWT peer anchor; Compose-measured bounds
drive the real native view extent. Both native child views are ordered below
Compose with a negative layer `zPosition`, and their AppKit subclasses return
nil from `hitTest:` so pointer input follows the normal AWT-to-Compose path.
The fallback mpv OpenGL context uses swap interval zero so `flushBuffer` does not
block the render executor on display refresh. The IOSurface path removes the
AppKit shared-context lock and keeps presents in the app-owned sublayer. There is
no desktop controls popup, overlay-window synchronization, move observer, or
synthetic native mouse forwarding path. libmpv exposes only the deprecated
OpenGL render path, so very-high-resolution mpv playback can still drop output
under sustained system pointer movement. LibVLC's native AppKit path avoids
that specific Render API bottleneck, but it can independently exceed its output
capacity on very-high-resolution streams and is not a universal fallback. The
defect records — what is ruled out and what is still candidate work — are owned
by the internal `.local/KNOWN-ISSUES.md` ledger.

The rejected `wid` experiment is not a production surface: packaged
playback and isolated typed-handle probes opened a separate mpv window for both
the AWT component peer and the real window `NSView`.

Independent macOS LibVLC is exposed as the `LibVLC (beta)` desktop player
choice when a usable VLC 3 app runtime is installed. Its controller, event
translation, and AppKit child-video surface do not implement or emulate the mpv
render-target contract. The server-scoped Settings preference chooses `mpv` or
`LibVLC (beta)` for the next playback session; absent LibVLC normalizes at runtime to
mpv and surfaces the existing backend-fallback notice. For packaged macOS
launches the audited VLC runtime is bundled and preferred; development lookup
falls back to `/Applications/VLC.app`. Native handles, raw software addresses,
Skia types, mpv/VLC callbacks, and presentation generations never enter
`PlayerController`, `PlaybackPlan`, or common playback policy.

### Playback bridge rules

The ordered shared decision sequence and the platform/backend ownership
comparison live in [`playback-architecture.md`](playback-architecture.md).
This section owns the bridge and layering rules that make that architecture
enforceable in source.

- Shared domain owns `PlaybackPlan`, typed `PlaybackQualityPolicy`, internal
  `PlaybackBitrateConstraint`, device capability models, pure bounded Auto
  recovery, the immutable `PlaybackSessionRecoveryPolicy` decision/state
  kernel, the pure `RetrySelectionPolicy`, the cancellation-safe
  `GetPlaybackLaunchContextUseCase`, and stream strategy. The launch UseCase
  returns only normalized durable inputs plus closed read outcomes; Detail,
  Series, Player, and tvOS retain process memory, explicit intent, option
  validation, local-asset reconciliation, stale-result gates, and diagnostics
  publication. Platform players map plans to native APIs and
  never fetch Jellyfin metadata.
- `DeviceProfileProvider` reports raw capabilities; the data layer maps them to
  Jellyfin request DTOs. Platform providers must not construct transport
  requests or decide stream strategy.
- `PlayerDeviceSettingsStore` records the local server-scoped general quality
  policy and optional VLC-family Fixed default. A player quality choice belongs
  only to the current playback and is never written to `PlaybackSelection`;
  that source-keyed store retains durable audio choice only. Android uses
  tolerant SharedPreferences persistence for settings; iOS/JVM use the shared
  Room store. Legacy Room quality columns remain schema compatibility data and
  are ignored on read.
- Platform controllers implement `PlayerController` and publish
  `PlaybackState`, runtime diagnostics, measurement capabilities, and
  prepare-generation-scoped output observations. Native callbacks and failures
  become project-owned typed state before reaching ViewModels or UI. A controller
  never decides a policy, replan, notice, or persistence action.
- Platform `prepare()` installs a plan without starting playback. Initial
  DirectPlay audio mapping may hold play intent; subtitle activation never does.
- Platform controllers resolve verified app-owned `LocalFile` identities only
  through `LocalSubtitleFileStore`. They never accept arbitrary paths from UI or
  attach Jellyfin authentication headers to local subtitle files.
- `PlaybackHealthSessionCoordinator` owns generation-safe timer/exclusion and
  health-evidence collection. `AutoPlaybackRecoveryCoordinator` is a pure
  common-domain state machine with one compatibility and one quality budget.
  `PlaybackSessionRecoveryPolicy` separately owns activation-first precedence,
  exact current-target validation, typed recovery causes, and item/session-scoped
  network, audio, and subtitle attempt facts. The policy returns semantic
  decisions only; it never launches work or accesses a controller.
  Only an explicit in-player Auto choice authorizes the quality-lowering budget;
  an inherited Playback-default Auto policy may collect evidence but presents
  manual in-player choices instead. The ViewModel and
  `TvPlaybackSessionPresenter` own player verbs, persisted user selections,
  navigation, notice state, and cancellation. They defer an interactive
  stream-changing consequence while PiP or lifecycle state makes it unsafe to
  show the user.
- Policy normalization, bounded recovery decisions, and cheap `StateFlow`
  writes remain main-owner work. PlaybackInfo/repository calls, persistence,
  DTO mapping, and native I/O remain on their injected work/IO/native
  dispatchers; controllers publish facts through project-owned flows rather
  than invoking ViewModel or UI callbacks on a native thread. A controller
  prepare-generation change cancels its old observation work before a new plan
  can be acted on.
- Native presentation measurement stays narrow. Media3 exposes its native
  first-frame callback and mpv's software path reports a published frame; mpv
  OpenGL explicitly declares first-output measurement unsupported until it has
  a reliable compositor-presentation callback. VLC-family controllers expose
  positive displayed-picture counters; iOS VLCKit also scopes prepare/resume/
  seek readiness by generation and transition sequence. Apple presentation
  hosts call the AVPlayer ready-for-display bridge.
  Surface/Vout attachment is attachment diagnostics, not output evidence. A
  backend that cannot expose a reliable fact declares that explicitly.
- Player surfaces are narrow platform composables. One
  `shared-ui/androidMain` `AndroidPlayerSurfaceHost` serves mobile and TV: it
  attaches `PlayerView` for ExoPlayer or the project-owned surface bridge for
  mpv and LibVLC, and accepts the shell-owned subtitle bottom inset as
  presentation input. iOS attaches `AVPlayerLayer` for AVPlayer or a retained
  VLCKit drawable implementing the engine's public PiP drawable/media-controller
  contracts behind separate iOS-only capability interfaces; desktop's mpv-only
  JVM capability publishes into the persistent UI-owned BGRA surface.
  Shared UI never casts an Android controller directly to ExoPlayer, and common
  player contracts never acquire mpv/AWT/Skia/native-address mechanics.
- The Android LibVLC bridge waits for the `VLCVideoLayout` window attachment
  before attaching vout views, re-enables the native video track from the vout
  callback, and replays play intent when the surface becomes ready. A TV
  backend replacement must not dispose the owning ViewModel merely because the
  surface/controller instance changed.
- `PlayerController` has an optional `PlayerTimingController` capability. The
  common ViewModel owns timing persistence and UI state; the Android
  `Media3TimingController` coordinates timing state over a PCM
  `Media3AudioDelayProcessor` and a project-owned subtitle overlay, while LibVLC
  maps both values to its native delay APIs. Unsupported Apple and desktop paths
  remain explicit no-op/unsupported capabilities until a separate parity
  milestone.
  - Media3 audio delay inserts silence / drops leading PCM with NO media-clock
    compensation: the inserted/dropped samples flow through the master audio
    clock so the audible audio shifts against the clock-slaved video. Reported
    position and Jellyfin progress therefore track VIDEO; the resulting ≤10 s
    cosmetic offset vs the audible audio is an accepted trade. An audio-offset
    change is applied by a coalesced (~400 ms) re-prepare that preserves live
    position, playback speed, and subtitle style; the processor is inert at zero
    offset (`AudioFormat.NOT_SET`), and `DefaultAudioSink.Builder(context)`
    keeps encoded passthrough/offload at zero offset while a non-zero offset
    forces a PCM decoder route.
  - Media3 subtitle delay renders through a dedicated overlay `SubtitleView`
    (PlayerView's own subtitle view is hidden) as the single order-independent
    cue target. Media3 subtitle timing is `PositiveOnly` (a negative shift is
    clamped to zero because a cue cannot be pulled earlier than its decoder
    callback); LibVLC honors both signs. Persisted offsets are the applied
    (post-clamp) value, so the stored value never disagrees with what the
    backend applied. Full both-signs Media3 subtitle timing would require
    replacing the final `TextRenderer` and is deferred.
- The two debounced durable playback writers (`SavePlaybackSelectionAction`,
  `SavePlaybackTimingOffsetAction`) are latest-wins, per-key coalesced writers on
  an injected background dispatcher backed by the shared
  `DebouncedKeyedStoreWriter`. `BackendSessionResolver` was deliberately NOT extracted from
  `PlayerViewModel`: the backend resolve plus post-prepare fallback swap stay
  inline because extracting them would be a redesign of coupled ViewModel state
  and re-entrant replan rather than a mechanical move.
- Android backend selection is a concrete server-scoped setting resolved before
  initial planning. That durable initial choice remains authoritative for queue
  items unless the user makes an explicit session-only switch; the selected
  backend is passed to the planner and device profile.
  `docs/guides/playback-architecture.md` owns the backend policy itself —
  defaults, `Auto` normalization, and the visible per-shell order. A selected
  mpv or LibVLC construction failure triggers a fresh ExoPlayer-qualified replan
  rather than mutating a live controller.
- `PlayerViewModel` additionally owns the narrow explicit remote backend-switch
  transaction. It holds only session-local UI/generation state, projects the
  common policy and availability snapshot, plans off Main while the healthy
  controller remains installed, then uses the existing serialized
  release-first installer. It revalidates switch, disposal, stop, and
  installed-plan/reporting authority after its committed suspensions and before
  reporting installation, `prepare`, and play/pause. It keeps durable backend
  preference and offline resolution unchanged, and releases each untransferred
  candidate exactly once. The factory-reported concrete backend, not the
  request, selects the plan that may reach `prepare`; the switch-only
  construction fallback is one platform concrete default attempt. Platform
  controllers/surfaces and tvOS presenter code remain outside this shared
  transaction.
- Android mpv keeps `dev.jdtech.mpv.MPVLib` inside the Android engine adapter;
  the class is supplied by the project-owned `android-libmpv` module, whose
  pinned AAR input supplies the unchanged native graph and whose bridge
  requests no native log messages until the controller deliberately selects
  Error before initialization. The JNI bridge does not echo raw mpv message
  text or routine event names to logcat. `AndroidMpvPlayerController` owns native command serialization, generation-safe
  lifecycle, track/timing/diagnostic projection, audio focus, retry, and
  release. `AndroidPlayerSurfaceHost` maps Fit/Fill/Zoom and the shell's
  subtitle inset into `AndroidSurfacePresentation`; `AndroidMpvSurfaceView`
  and `AndroidPlayerSurfaceBridge` own SurfaceView attachment and native
  presentation. Each Compose host is fresh and owner-qualified: an outgoing host's release/destroy callback
  cannot detach its replacement. The bridge explicitly enables/disables mpv's
  native window and publishes bounded surface dimensions on attach/resize;
  same-host prepares retain the engine-bound surface while replacing media so a
  quality replan cannot rebuild the native video output before `loadfile`.
  Vendor types, raw callbacks, Surface handles, and native addresses do not
  cross into common playback contracts, ViewModels, Compose UI, or diagnostics.
- Android LibVLC capability claims come from its pinned engine/runtime facts,
  not MediaCodec, another backend's experiment, or another backend's ceiling.
  ExoPlayer owns the independently probed MediaCodec path. The optional
  VLC-family Fixed default is disabled by default; when selected, its exact
  numeric cap applies to the initial VLC-family PlaybackInfo request. It is
  never an 8 Mbps platform rule, a decoder fact, or a quality-rung resolution.
- LibVLC runtime diagnostics expose native codec, dimensions, frame rate, and
  lost-picture counters through `PlaybackRuntimeDiagnostics`. Its bandwidth
  remains null until the 3.7.5 API's bitrate unit is verified. Native audio and
  subtitle IDs are resolved through `NativeTrackResolution`; subtitle Disable
  remains the native `-1` track.
- Android's project-owned `AndroidAudioFocusCoordinator` is the sole focus and
  becoming-noisy owner for the Media3, LibVLC, and mpv controllers; Media3's
  internal focus manager and noisy receiver stay disabled. Media3 also uses bounded streaming load
  control so HLS sample queues cannot reserve the library's large default
  allocation target on low-memory TV devices.
- LibVLC seek transitions remain `Buffering` through the native clock's initial
  jump to the target and return to `Playing` only after the clock advances
  again. A delayed progress probe cannot force readiness while the stream is
  stalled; high-frequency native buffering callbacks are coalesced before they
  reach Compose state. Initial playback uses the same clock-advance gate,
  including non-zero resume seeks, rather than treating an early native
  `Playing` event or target readback as rendered-video readiness.
- Android system-control integration is callback-owned: PiP and MediaSession
  invoke `PlayerPlatformCommandCallbacks` captured from the owning ViewModel.
  A backend-neutral `BackendMediaSessionPlayer` projects the active state into
  AndroidX MediaSession, so neither raw controller calls nor native LibVLC/mpv
  objects cross the platform-owner boundary.
- User-report diagnostics must be both sanitized and causally complete enough
  for a first bug-fix pass from user-supplied logs alone. Every user-visible
  failure, blocked action, degraded path, or automatic fallback records the
  feature/operation, relevant closed decision inputs, exact failed stage,
  outcome, and an allowlisted exception class or native result code when the
  platform exposes one. State-driven flows also record the bounded state
  transitions needed to reconstruct why the visible result occurred.
  Presentation flows record owner-safe attach/size/detach milestones when those
  facts distinguish audio-only progress from a missing or stale video surface.
  Catch-and-fallback boundaries must not discard that evidence with an
  unobserved `getOrNull()`. Never log stream URLs, headers, tokens, paths,
  media/account identity, exception messages, stacks, or raw server and native
  payloads.
- Track activation, recovery, device-profile behavior, and platform player
  mapping details are owned by `docs/guides/data-playback.md`.

### Other bridges

- Shared detail UI uses the project-owned `AmbientColorExtractor` over Coil
  `Image`; Android may use Palette internally and other platforms may return
  `null`. Shared UI must not import Android graphics or resource APIs.
- Represent capabilities explicitly instead of inferring them from platform
  type names.

Platform review checks:

- Is the code in the highest valid source set and is `expect`/`actual` narrow?
- Would an injected interface improve replacement or testing?
- Are vendor types contained at the edge and setup centralized at entry/DI?
- Can common tests use fakes without native platform bootstrapping?

## Platform Strategy

- Share domain models, contracts, and UI at the highest valid source set, but
  keep platform shells separate when navigation, focus, lifecycle, player, or
  packaging behavior differs materially.
- Android TV and mobile share domain/playback contracts without sharing phone
  navigation assumptions. Apple and desktop keep native player and lifecycle
  integrations behind project-owned bridges.
- Current platform support and distribution constraints live in the top-level
  `README.md`; deferred targets, open defects, and pending device validation
  live in `.local/KNOWN-ISSUES.md` — not in this architecture contract.

## Known Duplication

Current cross-platform duplication and borrowable patterns. Entries here
are either accepted (with the reason) or open extraction candidates; treat an
extraction as a normal scoped change, not a sweep, and re-verify an entry
against source before planning from it. Verify liveness against the kernel's
exported symbols at the claimed call sites, not its file name — a filename grep
has produced false "not adopted" claims here.

TV player UI vs shared player UI:

- Notice visibility (audio/subtitle/backend) is keyed identically in both
  shells, but the mechanisms have drifted: shared couples the audio notice to
  the controls inactivity keep-alive while TV uses fixed-delay effects. A
  headless shared visibility helper must reconcile that drift first.
- Picker row layout (TV `TvPickerRow` vs shared `PickerRow`): same structure,
  but TV adds focusRequester/focus-strip/no-indication semantics that are
  regression-contract material — deliberately NOT merged.
- Picker labels: blocked by contract — Android TV user-facing text must live in
  Android TV resources and the TV picker set differs. Do not merge.
- Metadata/skip-label formatters: the "Series · S1E1" precedence is duplicated,
  but segment labels intentionally diverge (TV maps Recap→"Skip Intro" and
  Preview→"Skip Credits" per the TV UX contract; shared maps both to generic
  "Skip"). Any unification is a product decision, not a refactor.
- Back-action kernels are genuinely different state machines (TV adds local
  menus, Up Next, queue paging) — keep both.

Player-controller borrowable patterns still open:

- iOS VLCKit seek: no `SeekCoalescer` (Android LibVLC's 250 ms newest-wins
  coalescing of rapid seek requests).
- Desktop-only duplication inside the macOS interop remains accepted and out of
  scope for sharing: IOSurface swapchain create/destroy helpers are duplicated
  within one file, and the mpv and LibVLC AppKit host canvases are structurally
  identical (~150 lines with already-drifted invariants). A separate
  demonstrated presentation defect would be required before sharing either
  path.

## Testing Boundary

- Shared business rules, mappers, playback planning, search/filter logic, and
  repository contracts belong in `commonTest` where possible.
- Platform bridges should be testable with common fakes before platform runtime
  tests.
- Local verification script comes first.
- Follow `docs/guides/workflow.md` when adding or reviewing test strategy.

## Crash And Telemetry

- Diagnostics cross platform boundaries through project-owned sanitized models;
  native exception, URL, header, payload, and path data must not leak into
  shared UI or logs.
- User-report diagnostics follow a causal-chain contract: a report must be
  diagnosable from the collected logs without a debugger or the original user
  data. Playback logs distinguish selection/planning, platform admission,
  controller construction, prepare, native runtime, fallback/recovery, and
  terminal outcome. If any boundary catches a failure it owns the corresponding
  sanitized operation/stage/result record before recovering.
- Upload policy, allowed diagnostic fields, and scrubbing requirements are owned
  by `docs/guides/data-playback.md` under Crash Report Scrubbing.

## External Source Boundary

JellyScope is independently implemented. Do not copy third-party source,
structure, or assets without a deliberate file-level license review. Current
project-license status and compatibility gates are owned by
[`licensing-and-distribution.md`](../operations/licensing-and-distribution.md).

## Why

Durable rationale for rules this guide states — the choice, the reason, and
what was rejected. Delete an entry when its rule changes.

### Platform strategy and sharing

- **JellyScope is an original KMP client, not a fork.** Its Kotlin
  Multiplatform, Compose, native-player, and platform-shell boundaries require
  a project-owned architecture. Third-party source and assets are not reused
  without a deliberate license review.
- **tvOS is native SwiftUI over shared Kotlin, not Compose.** Compose
  Multiplatform publishes no tvOS targets and the community fork that renders
  cannot receive Siri Remote focus input; owning a fork of the Compose stack
  was rejected. Everything below the UI layer compiles for tvOS, so a Kotlin
  presenter module sits behind a narrow Swift-facing facade.
- **iOS is a full shared-Compose platform, with Apple frameworks kept at the
  iOS platform boundary.** iOS runs the shared Compose application; AVPlayer,
  VLCKit's public drawable/media/window PiP protocols, and Apple lifecycle
  behavior are supplied through project-owned platform capabilities, and common code never
  depends on AVFoundation or AVKit types. This keeps
  shared logic portable and testable with common fakes, while Apple-specific
  behavior receives platform tests where fakes cannot prove it. Two inferences
  were explicitly rejected: tvOS support does not follow from iOS source
  compatibility (it needs its own capability probing, player behavior, and
  hardware acceptance — it ships as a separate native shell), and App Store
  distribution is not assumed until the atomic migration and distribution gate
  in [`licensing-and-distribution.md`](../operations/licensing-and-distribution.md)
  passes. Changes to Apple player policy or distribution need a recorded
  decision, not status prose.
- **One implementation serves an Apple platform family.** Duplicating identical
  actuals per target was rejected; only the store-database directory and DI
  entry points stay per-target, and tvOS storage is purgeable by platform
  policy.
- **Downloads is installed only by supported platform graphs.** Putting its
  isolated database, transfer runner, and lifecycle host in the unconditional
  core module was rejected because that would manufacture a tvOS runtime
  obligation and make cache cleanup an accidental owner of durable user media.
  Common repositories, UseCases/Actions, queue policy, and recovery remain
  shared, while each supported entry point supplies the real storage and OS
  lifecycle boundary it can honor.
- **Keep duplicated skiko ambient-colour actuals rather than add an
  intermediate source set.** The iOS and JVM implementations are
  byte-identical, but deduplication needs an intermediate spanning only those
  targets, and any manual `dependsOn` edge makes the Kotlin plugin stop
  applying the default hierarchy template module-wide — restructuring how every
  target compiles to remove 48 lines. Rejected: the experimental
  hierarchy-template extension, which introduces a JVM+Native shared metadata
  compilation that must commonize a third-party typealias against skiko
  metadata — unverifiable by inspection. The duplication is bounded and
  drift-visible: both files are actuals of one narrow expect with no platform
  branching.
- **Platform-only player capabilities cross layers through side-interfaces,
  not by widening `PlayerController`.** Desktop volume and the iOS-only stall
  nudge both follow this shape, surfaced as nullable state so UI stays
  platform-agnostic.
- **Shared policy extractions return tagged domain values, stay plain functions
  when DI would not reach the real caller, and model only cases that exist.**
  Watch Next returns tagged candidates because the platform contract constant
  cannot be named in common code — a bare item list would drop provenance; it
  is a plain function because its consumers construct the sync manually, so
  DI-only wiring would miss them; the Now Playing publication decision has
  exactly three cases because the dominant runtime path (`NoChange`) is what a
  speculative position-only-push design could not represent. A speed-only
  change deliberately does not republish (stale lock-screen rate is explicit
  and tested; revisit as its own item).
- **Tolerant enum decoding is one common parser while defaults remain local
  policy.** A nullable exact-case common helper backs every settings-store
  site; unknown/null/empty names keep their existing null/default outcomes and
  every fallback default is still written at its original call site (the drift
  check found zero defaults to unify). Rejected: centralizing defaults or
  changing the persisted name wire shape — that turns a behavior-neutral
  extraction into an invisible preference migration.

### Accounts and lifecycle

- **Account identity is server plus user, everywhere.** Any cache holding
  media, activity, or transient playback state is keyed under it; refetchable
  caches commit only if their generation is still current; one immutable
  session serves both a request and its cache key, never read twice; every
  session mutation is serialized so a commit is rejected once a switch,
  removal, logout, or newer authentication invalidated it.
- **Persistent cleanup does not depend on lazy DI resolution.** Cleanup
  correctness must never rest on incidental resolution order, so a common
  cleaner receives every persistent clearable store directly; the runtime
  registry covers only in-memory caches.
- **Account removal is atomic because partial removal is unannounceable.** The
  clear paths had abandoned remaining stores at the first throw, and removal
  ran destructive cleanup in a cancellable context — a cancelled removal left
  an account deleted, stores partly cleared, and no state transition announced.
  Sibling-server identity is computed before the first destructive write; a
  later read cannot be made correct with a catch because either fallback answer
  can target the wrong account. A read failure before mutation aborts with the
  account intact.
  Removing a failure mode beats handling it. The intent-guard rule for
  persistence writes exists because guarding on a shared load/sort generation
  reintroduced the divergence it was meant to fix — a reload does not
  invalidate intent. New diagnostic values implement the existing sealed
  reason/operation grammar with their existing wire strings rather than growing
  the grammar or rewriting log lines.
- **Observable account transitions finish the committed boundary before
  surfacing caller cancellation.** Login, switch, and same-identity
  reauthentication validate while cancellable, then settle the exact durable
  snapshot, applicable subtitle barrier, registry identity/epoch, and published
  session/accounts in one `NonCancellable` tail. On successful completion, the
  captured caller context is checked; cancellation between those steps would
  otherwise expose split durable and runtime truth. Carrying the committed
  snapshot also prevents publication from observing a different store
  generation. Cold restore needs one authoritative read, and removal carries
  its committed snapshot. Regression fakes reject every read after commit.
  Rejected: a publish-time secure-store refresh or protecting only the final
  publication from cancellation.
- **UI-visible operation contracts are domain contracts because presentation
  must not depend on an implementation layer.** Session-removal authorization,
  download outcomes, subtitle-selection identity, visible-related projection,
  and mapped playback failures describe decisions made above repositories.
  Keeping them in data packages inverted that dependency and exposed transport
  exceptions to ViewModels. New opaque protocols and raw byte carriers stay
  outside the compiler-declared stable model package rather than being made
  falsely stable. Rejected: repository-owned presentation contracts and
  stability annotations that hide mutable structure.

### Desktop playback embedding

- **IOSurface presentation is the unconditional first macOS mpv tier, then the
  app-owned OpenGL view, then software fallback; the limitation is scoped to
  mpv's OpenGL Render API.** IOSurface removed the app-side shared GL-context
  stall, so the residual pointer-movement stutter is `mpv_render_context_render`
  itself rather than anything the app holds; libmpv exposes only the deprecated
  OpenGL render path, so no in-app tier can avoid it. The software path had
  already failed its very-high-resolution gate and is retained only as the last
  fallback and diagnostic path. VLC's native AppKit path avoids that specific
  Render API bottleneck, but very-high-resolution streams can independently
  exceed its output capacity, so backend switching is not a universal remedy. Rejected:
  keeping the OpenGL view primary, gpu-api switches (unavailable inside the
  Render API), and further presentation tuning; a Metal-capable embedding
  remains the only future direction. The defect record — everything ruled out
  and the candidate order — is owned by the internal
  `.local/KNOWN-ISSUES.md` ledger.
- **macOS mpv `wid` embedding is rejected; embedding must be an app-owned
  surface.** The window-id path fails containment: the engine opens a separate
  full-resolution native window instead of rendering in the player region.
  Rejected: shipping a separate player window, treating teardown success as
  embedding success, and more JDK-peer reflection.
- **In-scene Compose controls are the only desktop player presentation.**
  Controls, pickers, fullscreen, resize, movement, and auto-hide remain in the
  parent Compose scene; the separate controls-window setting and popup
  synchronizer are removed. Rejected: retaining a fallback popup path or
  synthetic AppKit-to-AWT mouse forwarding.
- **Desktop mpv teardown is asynchronous on every presentation, with a deferred
  disposition behind an engine-use lease.** Native commands acquire a
  refcounted lease atomically with the engine read under the engine lock;
  teardown nulls the context under the same lock, waits bounded for outstanding
  leases, and a lease that outlives the wait defers destruction rather than
  leaking the engine. Render paths deliberately take no lease — the render lock
  plus executor drain already order them against teardown, and a second
  protocol would put lock traffic on the per-frame path. Rejected: reusing the
  permanent quarantine path for a slow subtitle add (a process-lifetime native
  leak for finite network I/O).
- **Desktop Settings uses a cached file-based VLC availability check.** The
  runtime listing discovers once through a lazy cache and checks expected
  library/core/plugin files; it never creates an engine or loads native
  libraries during recomposition. Version validation stays at engine creation.
  Accepted: an install appearing after cache evaluation needs an app restart.
  Rejected: an asynchronous full-engine probe for a cheap capability listing,
  retaining build properties as product switches, a routine OpenGL off toggle,
  and coupling LibVLC to the mpv render contract.
- **The macOS package bundles the audited VLC 3 runtime, pinned by manifest.**
  UI plugins and disc plugins are excluded — the DVD plugins statically link
  libdvdcss and disc access is dead weight for a streaming client;
  `plugins.dat` is omitted because its paths are relocation-specific, so libvlc
  rescans. License texts and attribution, including the corresponding-source
  obligation, ship in the bundle. Rejected: bundling every upstream file,
  relying on an installed VLC for packages, or dropping the backend.
- **macOS mpv uses one pinned upstream runtime for development and packaging.**
  Host discovery made identical JellyScope source package different native
  graphs and let missing app resources fall through unnoticed. The versioned
  IINA input fits the existing dynamic-libmpv boundary, so custom native
  producers, machine-installed fallback, and runtime selection are rejected.

### Android playback integration

- Android mpv wrapper provenance — why a project-owned wrapper over the pinned
  native AAR input, not the upstream artifact — is owned by
  `docs/operations/android-native-dependencies.md`.

### Playback contracts

- **The live backend switch remains inline in `PlayerViewModel`.** It shares
  launch identity, explicit media intent, controller ownership, reporting, and
  the serialized installer with normal playback, so a new coordinator would
  duplicate mutable authority. Rejected: persisting the switch as a Settings
  change, teaching platform controllers to fetch/replan, a second healthy
  controller, and extending Android's automatic health fallback to user choice.

- **Launch reads are shared; launch resolution stays owner-specific.** Detail,
  Series, Player, and tvOS previously rebuilt the same source keys and repeated
  cancellation/default handling for preferences, audio selection, and subtitle
  intent. One domain UseCase now performs those independent reads concurrently
  and returns closed outcomes so Player can preserve diagnostics and every
  owner can distinguish an absent reader from a missing or failed durable row.
  Moving process memory, explicit route intent, track-option validation, local
  subtitle cleanup, or stale-result publication into that UseCase was rejected:
  those decisions depend on owner state and data that exists only after the raw
  context is returned.

- **`LocalSubtitleFileStore.resolvePath` stays synchronous as a bounded
  installed-path/stat seam.** Byte-moving operations dispatch onto the
  injected IO dispatcher inside each platform store, while
  `LocalSubtitleActions`, the platform stores, and
  `LocalSubtitleStorageReconciler` own installation, deletion, and
  reconciliation. A playback controller receives `SubtitleAsset.LocalFile`,
  resolves one installed path, and retains its backend-specific URL/URI
  conversion, origin guarding, attachment, and failure semantics. VLCKit
  deliberately invokes the synchronous resolution from
  `stateScope.launch(ioDispatcher)` and resumes native attachment
  asynchronously on Main after its generation/identity guard. A
  `LocalSubtitleStager` wrapper is rejected because no controller staging
  lifecycle exists to consolidate; making `resolvePath` suspend is also
  rejected because it would widen each backend's prepare/mapping path without
  changing ownership or behavior.

### Diagnostics

- **Player failures leave a sanitized causal chain sufficient for a first
  bug-fix pass from user-supplied logs alone.** Every boundary that catches a
  user-visible fallback or fatal failure records selected and active backend,
  gate, exact stage, closed result, and safe exception class; native option
  rejection reports the fixed option key and numeric result, never the value.
  The same causal-record rule applies to every blocked action, degraded path,
  and automatic fallback. Rejected: raw exception messages/stacks,
  media/account identifiers, credentialed URLs, and a single `unavailable`
  boolean that cannot distinguish packaging, trust, native-create,
  configuration, or runtime failures.

### Engineering discipline

- **Delete compatibility fields instead of defaulting them to null.** A
  rung-resolution string kept as defaulted-null "legacy" let every stale
  consumer keep compiling while rendering fallback text because the type system
  had been told nothing changed. Deleting the field made the compiler enumerate
  every consumer. Rejected: fixing named call sites and keeping the field
  (leaves the trap armed), and a deprecation cycle for an internal-only type.
- **Do not extract shared mutable state when the candidate behavior is not
  actually common.** VLC-family session-reset transitions are three disjoint
  field sets on three controllers; only the existing kernels, audio gate, and
  start-position clamp are genuinely shared. Extracting further would require a
  cross-controller mutable state holder. Full retry capture and native replay
  remain local because their fields, assets, timing, engine setup, and command
  order have already drifted; only the pure `RetrySelectionPolicy` validates the
  retained intent each
  controller already classified. A dedup gate carrying a latent defect also remains
  excluded because extraction would preserve the bug in shared code or smuggle
  a fix into a refactor.
- **macOS Objective-C dispatch uses one lazy, signature-safe `libobjc` facade.**
  The facade owns one function-mapped `objc_msgSend` binding and the shared
  runtime entry points; each feature retains ownership of its selectors,
  framework handles, callbacks, AppKit dispatch, and player lifecycle. Dispatch
  methods encode the verified return and argument ABI, and untyped dispatch is
  rejected. Compilation-only verification was rejected because an ordinary
  session did not exercise the forced-OpenGL surface; the development-only
  `jellyscope.desktop.forceMpvOpenGlSurface` seam remains so both presentation
  routes can be runtime-verified.
- **Controller concurrency fixes follow the audited access discipline, never a
  blanket annotation sweep.** Only genuinely lock-free values gain `@Volatile`;
  indivisible updates use atomics; monitor-confined fields stay unannotated.
  Lazy jobs are registered under the lock with captured handles cancelled after
  unlock; a fallback deadline validates job identity under the lock because
  cancellation cannot stop non-suspending code already past its delay; a
  prepare-generation producer returns the value its own increment produced so
  an older replay cannot adopt a newer generation; a countdown CAS-claims its
  zero-to-reset transition so exactly one caller samples. Rejected: annotating
  compound state, eager launch under the lock, and adding a new monitor.
