# Data And Playback Rules

Load this for Jellyfin API work, auth, server settings, local cache, playback
planning and behavior, downloads, progress reporting, or timestamp/date work.
The ordered playback decision pipeline and platform/backend ownership are
defined in [`playback-architecture.md`](playback-architecture.md); this guide
owns the detailed data, request, persistence, player, and runtime contracts.

## Jellyfin API

- Keep API clients behind repositories or API facades.
- Map client-specific transport failures to project-owned domain failures in
  the repository before playback planning or presentation sees them. Preserve
  only the safe retryability and allowlisted source-type facts required by the
  existing planner diagnostics; never make a ViewModel inspect a transport
  exception class.
- Normalize server URLs consistently, usually by trimming trailing slashes
  before composing endpoints and image URLs.
- Automatic network discovery is a `ServerDiscovery.isAvailable` capability.
  Android mobile/TV, desktop, and the existing tvOS graph use
  `KtorUdpServerDiscovery` UDP broadcast; iOS overrides it unavailable, so its
  logged-out flow makes no automatic or retry UDP call. iOS deliberately does
  not request the multicast entitlement required for UDP broadcast/multicast.
  Keep `NSLocalNetworkUsageDescription` and `NSAllowsLocalNetworking`: manual
  server URLs, including direct local HTTP, and restored sessions/accounts remain
  supported. Do not substitute Bonjour or a permission probe for discovery.
- Keep auth token/header creation centralized.
- Every value in both Jellyfin `MediaBrowser` authorization forms uses the same
  encoder: trim outer whitespace, remove CR/LF, then UTF-8 percent-encode every
  byte except RFC 3986 unreserved characters using uppercase hex and `%20` for
  spaces. A token that is empty after normalization produces no token-only
  header.
- `/System/Info/Public` display fields are optional. Its trimmed nonblank `Id`
  is the canonical server identity; a missing or blank ID fails validation
  before authentication can publish or persist a session. Server name falls
  back from a trimmed display value to the normalized URL authority and then
  the normalized URL, while missing product/version display values become
  empty strings. Password and Quick Connect authentication both accept a
  missing/blank result `ServerId`, require any nonblank result ID to match the
  canonical public ID exactly, and always persist that public ID in `Session`.
- Shared UI may build platform image loader requests, but Jellyfin
  `Authorization` header values must come from core auth-header providers; UI
  should not construct `MediaBrowser` auth headers or inject app/device identity
  directly.
- Use domain models at ViewModel/UI boundaries; do not expose raw DTOs to UI.
- Start with a project-owned KMP Ktor facade for Jellyfin API work. The client
  is hand-rolled — Ktor facade and DTOs with no Jellyfin SDK dependency — so
  wire-format and behavior tracking against each supported server release is a
  manual obligation.
- Detail item fetches request `People` in the default item fields so TV detail
  can render cast/crew and directed-by rows from domain `MediaPerson` values.
- Series/season browsing uses `/Shows/{seriesId}/Seasons` and
  `/Shows/{seriesId}/Episodes` through the shared API facade and media
  repository. Requests include `userId`, explicit item fields, image types, and
  episode `MediaSources` so TV season focus can show metadata, stream badges,
  progress, and watched state without exposing DTOs to UI.
- Season episode-list requests pass the selected season index when available.
  Shared repository filtering keeps Season 0 specials visible in the Specials
  season, but excludes confirmed specials from regular season episode lists.
- Shared player episode continuation uses the existing series seasons and season
  episodes repository/use-case paths. When an episode starts without a usable
  explicit playback queue, `PlayerViewModel` derives a chronological queue from
  `/Shows/{seriesId}/Seasons` and `/Shows/{seriesId}/Episodes` so player Up Next
  does not depend on `/Shows/NextUp` updating after playback completion.
- Watched toggles use Jellyfin `/UserPlayedItems/{itemId}` through the shared
  API facade and media repository: POST marks watched, DELETE marks unwatched.
- Use kotlinx.serialization for Jellyfin DTO JSON unless a later API decision
  changes the facade implementation.
- Any future SDK adoption must remain behind the same repository boundary and
  pass target-support, API-compatibility, and license review first.

### Related shelves

- `DefaultMediaRepository.getRelatedGroups` owns related-source selection,
  cross-shelf deduplication, and publication order. Non-episode detail starts
  up to two Cast jobs first, then Similar, the first Genre, and the first
  Studio; empty results are dropped. Jobs may run concurrently, but results
  are awaited and emitted in that priority order, so a completed lower-priority
  job never publishes ahead of an unresolved higher-priority job.
- `RELATED_GROUP_DISPLAY_LIMIT` in the shared media model is the cross-platform
  visible maximum of four shelves. Shared Detail and tvOS apply it at their
  presenter/ViewModel boundaries. Episode detail keeps its separate Next Up
  then Similar sequence; Series detail intentionally flattens its related
  groups into one Related shelf. There is no related-source Director job.

## Settings And Credentials

- Server URL, auth token, selected user, and lightweight preferences should flow
  through settings repositories and UseCases/Actions.
- Settings value models live in `domain.model`; persistence stores under
  `data.local` expose those domain values. Existing persisted enum
  names remain stable and unknown persisted names fall back to safe
  defaults rather than crashing startup.
- Whole-snapshot settings writes are serialized per setting family and may
  coalesce queued values to the newest snapshot. A slower older write must not
  complete after and overwrite a newer user choice.
- Room player-device settings publish the submitted value and suppress delayed
  initialization only after the DAO upsert succeeds. A failed upsert publishes
  no failed value and leaves the stored initialization value eligible to win.
- The Audio and HDR portions of player-device settings (`PlayerDeviceSettings`)
  live behind `PlayerDeviceSettingsStore` and are interpreted through
  `resolvePlayerDevicePolicy` before shaping Jellyfin PlaybackInfo requests.
  Android persists these values in SharedPreferences with tolerant enum-name
  fallback; iOS and JVM desktop persist these values in Room KMP. Never let an
  unknown persisted player setting crash startup.
- `matchDisplayRefreshRate` is a separately persisted, default-off presentation
  preference in that same settings snapshot. It is surfaced only in Android TV
  settings and deliberately does not participate in effective device policy,
  device profiles, PlaybackInfo requests, or stream-mode selection.
- Session persistence flows through the project `SecureStore` boundary. Android
  uses Android Keystore-backed storage under `Context.noBackupFilesDir`, so Auto
  Backup never captures the undecryptable `secure-store.bin`; unreadable or
  undecryptable data is deleted and treated as an empty store. iOS and tvOS use
  Apple Keychain generic password items with
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and synchronizable=false
  (no iCloud sync). Signed macOS desktop releases use a narrow lazy
  Security.framework `SecItem` boundary with the same item policy and migrate
  the legacy JSON file once through it. Native failures expose only a fixed
  operation label and numeric `OSStatus`; account names and credential values
  never enter process arguments or diagnostics. Non-macOS JVM hosts retain
  plaintext JSON storage under `~/.jellyscope`. This is an accepted risk limited
  to the unsupported development targets identified in
  [`README.md`](../../README.md#platforms), not encrypted or release-hardened
  storage. Any future decision to distribute or support either target must
  reopen and complete native OS credential storage before release. The legacy
  Apple `NSUserDefaults` JSON blob is imported once into Keychain and deleted
  only after successful import.
- `SessionStore` persists ordered accounts, active account, envelope-era marker,
  and logout-pending state in one versioned secure envelope; the device ID is a
  separate durable key and survives session clearing. Envelope absence alone
  permits one import from the known legacy session/account/active/pending keys.
  The envelope is committed before best-effort alias cleanup, and a valid empty
  envelope is a durable logout tombstone. Any present malformed, incomplete, or
  unsupported envelope fails closed and never falls back to stale aliases.
- For non-credential settings, RAM-only storage is not acceptable for a working
  feature. Every current user-visible setting must use normal persistent app
  storage on every platform where that feature exists, even when credential
  hardening remains deferred. App theme, Picture-in-Picture, library sort, and
  grid sort currently persist through Android SharedPreferences, iOS
  `NSUserDefaults`, and JVM desktop JSON preferences under `~/.jellyscope`.
- Apple targets must not use in-memory session storage for login restore. The
  shared Apple Keychain store keeps session credentials across relaunches on iOS
  and tvOS; UserDefaults remains the persistence boundary for non-credential
  preferences.
- Clear-login flows must stop authenticated reads before clearing credentials
  and cached library data that could leak data from the previous account. Logout
  commits a durable pending state before publishing logged-out state, commits an
  empty authoritative session envelope first during credential clearing, and
  clears the pending state only after that credential clear succeeds. Every
  persistent and runtime store is attempted independently under
  `NonCancellable`; failures are aggregated after all clears and cancellation is
  reconciled only at the outer boundary. If the pending state survives a crash
  or failed clear, cold restore treats the process as logged out, clears the
  in-memory account list inside the boundary gate, and retries cleanup instead
  of restoring a stored session.
- `AccountIdentity` is the canonical `(serverId, userId)` boundary key. Runtime
  refetchable caches register with `ServerScopedStoreRegistry`; persistent
  session, playback-preference, recent-search, Watch Next, subtitle-selection,
  player-backend override, and sort/view stores are explicit dependencies of
  `PersistentAccountStoreCleaner`, so cold-process logout does not depend on
  feature-store resolution order. Player-backend overrides are server/item
  state, are never registered with the runtime cache registry, survive removal
  of a sibling account on the same server, and clear only on full logout or
  removal of the last account for that server.
- Shared persistence stores expose commonMain interfaces with plain data
  classes. Android, iOS, and JVM desktop back playback preferences,
  player-backend overrides, recent searches, and watch-next sync state with Room
  KMP in `shared-core` using the bundled SQLite driver. iOS and JVM desktop also
  back player-device settings with the same Room database. The owner schema is
  version 10 with explicit migrations 1→2 through 9→10. If a future app
  downgrade encounters a database with a higher `user_version`, the
  bundled-driver recovery recreates only refetchable `recent_searches` and
  `watch_next_sync` tables before Room validates the repaired schema.
  Playback preferences, player-device settings, subtitle selections, and local
  subtitle assets remain intact; no blanket destructive fallback may replace
  durable user-authored data.
- Recent-search recency is a durable database-owned ordering sequence scoped by
  server and user, not a process-local counter or wall-clock timestamp. Assign
  the next sequence and trim old rows in one Room transaction so recreation
  cannot reorder entries. Watch Next sync state uses the same account scope.
- Library browse sort memory is a lightweight preference behind
  `LibrarySortStore`: only sort (`sortBy`/`sortOrder`) is remembered per
  server/library key. Grid and library sort store implementations are
  server-scoped clearable so logout removes persisted sort memory for the
  affected server. Do not use in-memory sort stores for shipped platform paths;
  unknown persisted enum names must return `null`/defaults rather than crashing
  startup.
- Library inner-view memory is a lightweight preference behind
  `LibraryViewPreferencesStore`. The global remember toggle defaults to enabled,
  while selections are keyed by server, user, and library and are removed by
  server-scoped logout cleanup. Persist enum names defensively: unknown values
  fall back to the library type's default view.
- Library browse sort options are collection-aware: movie libraries may sort by
  Jellyfin `VideoBitRate`, show libraries may sort by `DateLastContentAdded`,
  and an incompatible persisted sort falls back to name ascending. Both
  media-specific sorts initially select descending order.
- Movie-library Shuffle All resolves the complete filtered queue with one
  recursive `/Items` request for movies sorted by `Random`. The request has no
  page limit, fields, image types, user data, or total count; the repository
  returns distinct item ids in server order. Playback starts the first id with
  normal media-version selection and keeps the process-local queue only for the
  active player session.
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
  URL/token/path/address scrubber runs. Collection defaults on only when the
  preference key is absent; an explicit false remains off. The history is
  capped at 4,000 entries/500,000 UTF-8 bytes with a 4,096-byte line limit,
  is deleted when collection is turned off, and is uploaded only after the
  user explicitly presses Send to their Jellyfin server. Android, Android TV,
  desktop, iOS, and tvOS register the writer and expose bounded collection
  controls through their native settings surfaces. This allowlist-first capture
  boundary remains defense in depth; console-safe failure formatting is required
  independently for every Kermit writer.
- Playback diagnostics preserve a closed causal chain without media identity:
  planner resolution records the concrete backend, request policy, capability
  conclusion, quality mode/cap origin, and request cap separately from the
  effective transcode cap; player events record backend fallback, process-local
  session/prepare sequences (including request before the synchronous prepare
  boundary), first-output availability/observation, qualified health evidence,
  bounded Auto recovery decisions/budgets, persistence read/write outcomes,
  and terminal native outcomes. A cancelled asynchronous persistence write is
  `Cancelled`, not `Failed`, so teardown races do not masquerade as storage
  failures. The
  first-output and health-summary records include bounded runtime resolution,
  frame rate, decoder token, source bitrate, bandwidth estimate, and available
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
  playback fails, and from exactly two places because neither sees both failure
  classes: the planner records its own capability fallbacks (it publishes them
  internally and still returns a successful plan), and the player ViewModel
  records controller failures. A successful playback must never write to it —
  recording "current state" on success lets a later good session overwrite the
  failure the report exists to explain. Adding a third writer, or widening an
  existing writer to the success path, destroys the diagnostic silently.
- Release app entries retain the sanitized `LogBufferStore` writer and install
  a bounded platform writer that forwards only allowlisted structured
  diagnostics after the same scrubber accepts them. It drops ordinary messages
  and throwable payloads, so causal playback fields remain available in Android
  logcat, Apple unified logging, and desktop stdout without enabling raw release
  logging. Android uses `BuildConfig.DEBUG`; desktop uses its debug JVM flag;
  iOS derives the flag from the Xcode `CONFIGURATION`. Debug builds or the
  explicit verbose-log preference install the raw platform writer instead. This
  gate does not alter the bounded safe-history store or its scrubber.
- `adb shell setprop log.tag.<TAG> <LEVEL>` cannot turn logging on in a release
  build. A log-level system property only filters writers that already exist, so
  a build that installed no raw platform writer emits nothing regardless of what
  the host sets. Verbose on-device logging therefore has to be an in-app,
  persisted, off-by-default preference that re-installs the writer at runtime;
  there is no adb-side substitute, and the preference is not redundant
  machinery.
- Jellyfin auth headers use injected device identity and injected app client
  version. Do not hardcode `Device` or `Version` values in shared UI, playback
  bridges, or API clients.
- Platform-player Jellyfin credentials may be attached only when the remote
  resource URL passes the active session's `CredentialOriginGuard`: HTTP(S)
  scheme, case-insensitive host, effective port, and server base-path must
  match, with no userinfo, IDN, punycode, traversal, or HTTPS downgrade.
  Header-capable platform-player attach sites consume the guard's shared
  `decideResourceCredentials` result, which evaluates the original candidate,
  strips credential query parameters from the HTTP(S) resource URL, and keeps
  credential material out of the decision.
  App-built API/image requests use the shared Ktor client and authenticate only
  via the `Authorization` header; Ktor's built-in redirect handling drops that
  header on cross-authority redirects and refuses HTTPS->HTTP downgrades, which
  is sufficient because the client sends no other credential. Media3 consumes
  the shared decision for the initial resource and every OkHttp network hop,
  removes inherited credential headers case-insensitively, and adds the one
  current Authorization header only for a trusted decision. AVFoundation main
  and external-subtitle assets consume the same decision for their initial URL
  and receive headers only when it permits attachment. AVFoundation offers no
  supported per-asset redirect hook, so a same-origin authenticated main or
  subtitle asset that later redirects remains an Apple runtime residual; do not
  claim per-hop Apple enforcement without a separately approved transport.
- Android and desktop mpv authenticate trusted resources with the same modern,
  comma-free token-only `Authorization: MediaBrowser Token="..."` line. The
  token-only value comes from `AuthHeaderBuilder`; the full comma-bearing
  client/device Authorization form remains the API/image contract. Desktop mpv
  writes `http-header-fields` on every prepare and writes an empty value for a
  blank token or untrusted resource so a reused native context cannot retain a
  credential. Do not add a legacy-header fallback or server-version branch.
- VLC-family backends cannot inject auth headers, so they are the deliberate
  query-auth exception and use the modern `ApiKey` spelling. Android and desktop
  consume `CredentialOriginGuard.authorizedUrl`, which returns a credentialed URL
  only for a trusted resource. The iOS VLCKit backend (`VlcKitPlayerController`,
  unified VLCKit 4) keeps its separate guarded path because it may load an
  untrusted HTTP(S) resource after stripping credentials. Per URL (main media
  and each remote Jellyfin subtitle playback child), every VLC path strips
  inbound auth query params first; non-HTTP(S) or userinfo-bearing URLs fail
  closed. Exactly one current-session `ApiKey` is appended only after the
  original candidate passes the active-session origin guard; the iOS path
  otherwise loads the stripped, token-free URL. Local subtitle assets never
  receive a token. Native VLC redirects can carry the query string across
  authorities — an accepted trusted-initial-authority residual, parallel to the
  AVPlayer same-origin subtitle-redirect residual above. No other credential
  (header or cookie) is attached on the VLC path.
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
  `resolvePlayerBackend` picks one concrete backend once per playback session
  from the server setting, any compatible item override, source descriptor, and
  runtime availability. The backend is pinned for the session (queue switches
  reuse it; no live mid-playback switching) and flows into
  `PlaybackInfoRequestPolicy.backend` so PlaybackInfo uses that backend's device
  profile. Android's ExoPlayer path is Media3 1.9.0 with decoder fallback and
  the Jellyfin FFmpeg extension (`org.jellyfin.media3:media3-ffmpeg-decoder`
  from Maven Central); FFmpeg is an ExoPlayer decoder extension, not a third
  backend. Media3 is pinned to 1.9.0 because the published decoder's version
  tracks Media3 and 1.9.0 is the newest published pairing — so no local NDK
  build is required. Media3's server-facing audio capability is the union of
  its MediaCodec probe and a process-cached, fail-closed check that this shipped
  extension is available and supports E-AC-3. Only that E-AC-3 result is added;
  the extension's broader decoder list is not enumerated. Android's LibVLC path
  is the in-process libvlc 3.7.5 controller/surface bridge and is used only when
  selected and available. A
  startup/prepare failure in mpv or LibVLC ends that controller session and
  replans from the last confirmed position with ExoPlayer; the planner is never
  given an alternate-backend plan and then handed to ExoPlayer. Android mpv is
  the project-owned `android-libmpv` controller and project-owned SurfaceView
  bridge described in the Android mpv section below; its pinned
  `dev.jdtech.mpv:libmpv:1.0.0` AAR is a verified build input for the unchanged
  native graph, not the runtime wrapper dependency.
- Android LibVLC keeps its pinned-engine declaration as the base profile. The
  Android provider preserves each finite width, height, padded frame-area, or
  frame-area-per-second fact from the cached active MediaCodec probe only when
  the codec is declared by both LibVLC and the platform mapping and LibVLC's
  declared tuple is all Unknown. Missing fields stay Unknown; no maxima are
  rebuilt or synthesized. The projection changes no LibVLC container, codec,
  subtitle, HDR, audio, passthrough, or software-decoder declaration. This is a
  narrow hardware-delegation safety input, not proof that LibVLC can render the
  source. In particular, unprobed AV1 remains eligible for the pinned LibVLC
  software/dav1d path.
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
- List-style item requests for home rows, grids, search, similar items, and
  season lists use the lighter default field set without `People` or
  `MediaSources`. Detail and episode fetches use the detail/media-source field
  set because those screens need cast/crew and playable version metadata.
- Detail projection keeps valid, distinct media-source IDs in server order and
  maps every source's name, release basename, badges, size-backed media info,
  track options, and launch identity before UI. A blank server source name stays
  blank in the domain; presentation assigns the localized fallback `Version N`
  after invalid and duplicate IDs are removed. The selected source owns every
  top-level source-derived field rather than borrowing a mixed projection from
  another version.
- Media-version choice is session-only state in the existing Detail and Series
  ViewModels. Selection resolves the existing playback launch context for the
  exact source off Main, commits only the latest item/source generation, and
  atomically reprojects metadata, track defaults, and play/restart IDs. Series
  updates matching current, cached, focused, next-up, and series-play episode
  aliases without changing season or queue order. Refresh preserves a selected
  source that still exists and otherwise falls back to the first valid source;
  local-subtitle observation and installed state are keyed by source so one
  version cannot leak into another. No preference, Room row, refetch, new
  repository, or new use case is introduced.
- Because Fire OS runtimes have been observed to omit Debug/Verbose-priority
  logcat output, diagnostics that must be readable on TV use Info or higher;
  keep them low-volume (once per item/session, not per frame).
- Multi-server support should not be added accidentally; treat it as an explicit
  product decision.

## Cache

- Distinguish re-fetchable server cache from durable user-authored local data.
- Room downgrade recovery is cache-scoped: only refetchable recent searches and
  Watch Next rows are recreated when a higher-version database is opened by the
  schema-10 app. Durable user-authored Room rows are never covered by a
  destructive all-table fallback.
- `AccountIdentity` qualifies every media, image, playback-memory, Watch Next,
  recent-search, and non-local subtitle-selection namespace.
  `SessionState.LoggedIn` carries a process-local boundary epoch; cached
  repository work captures one immutable session plus epoch and passes both to
  the lease API.
- `DiscoveryCache` and `DetailRelatedCache` acquire a matching account/epoch
  lease on a miss and commit successful results only through the registry's
  guarded mutation gate. A retired snapshot cannot start a new guarded load or
  repopulate a cache after a transition. Full logout clears runtime stores;
  account transitions clear the retired account and server-wide state only when
  the final account for that server is removed.
- Coil memory/disk keys include account identity, image URL, and decode size but
  never authorization headers. The logged-in composition owns the loader
  installation; the serialized runtime-cache boundary clears and shuts down the
  retired loader on platform I/O before the replacement composition explicitly
  installs a fresh Coil singleton and reuses the single disk-cache owner.
- `PlaybackSelectionMemory` and the Room-backed, server/account/item/media-
  source keyed `PlaybackSelectionStore` retain audio choice only. Player quality
  is session-only; legacy quality columns remain for schema compatibility but
  are ignored on read and cleared on write. Account/server cleanup removes
  durable audio rows, while a source change isolates them. Subtitle intent and
  timing offsets remain separate records.
- `GetPlaybackLaunchContextUseCase` is the single read owner for normalized
  playback preferences, normalized durable audio selection, and durable
  subtitle intent once an exact item/media-source pair is known. It constructs
  both selection keys once and runs the three reads independently in the
  caller's work-dispatcher context: one non-cancellation failure defaults only
  that field, while cancellation aborts the whole call. Its closed
  `Present`/`Missing`/`Failed`/`Unavailable` outcomes contain no identifiers or
  exception text. Only `Unavailable` means no durable reader exists and permits
  legacy item-memory fallback; `Missing` and `Failed` retain durable-store
  precedence. Detail, Series, Player, and tvOS resolve explicit intent, process
  memory, available options, and local subtitle assets after receiving this raw
  context. Player maps the audio-selection outcome into its existing
  allowlisted persistence diagnostic at the same point in the backend/read
  sequence; the other owners do not publish persistence diagnostics.
- Library Recommended rows are user-specific and library-scoped. Continue
  Watching, Recently Added, and Next Up pass the library parent and matching
  item type to Jellyfin; movie recommendation categories use
  `/Movies/Recommendations`. Do not put these personalized rows in
  `DiscoveryCache`; refresh them independently while retaining successful rows
  if a silent refresh fails. Genre and collection discovery may use parent-aware
  refetchable cache keys.
- Durable user-authored data requires explicit migrations and careful deletion
  semantics.
- Cache replacement should be scoped and transactional where multiple tables or
  relationship rows must stay in sync.
- Cached image URLs should be reconstructable from current server settings and
  item IDs where practical so server URL changes do not strand old URLs.

## Direct OpenSubtitles And Local Subtitle Assets

- JellyScope talks directly to `https://api.opensubtitles.com/api/v1` through a
  dedicated `OpenSubtitlesApi`/repository boundary. API requests send only the
  user-supplied consumer key and JellyScope User-Agent; the separate download
  client never receives OpenSubtitles or Jellyfin credentials. Download links
  must remain HTTPS on an allowlisted OpenSubtitles host across at most three
  redirects, and neither URLs, keys, response bodies, titles, nor file paths may
  be logged.
- OpenSubtitles configuration is device-global and survives Jellyfin logout.
  The consumer key is the only secret value; the result-order preference is
  persisted beside it as `NoPreference`, `PreferHearingImpaired`, or
  `PreferForced`, with missing or unknown values falling back to `NoPreference`.
  It uses the existing platform `SecureStore` implementations, including their
  documented platform limitations. Search starts from movie or concrete episode
  detail, prefers IMDb plus season/episode identity, falls back to title/year,
  normalizes the preferred language to OpenSubtitles codes, and keeps search out
  of the in-player picker.
- Results are ranked before display, never left in server order: query
  specificity first (IMDb plus season/episode outranks IMDb alone, which
  outranks the title/year fallback), then installable results ahead of
  unavailable ones, then an explicit hearing-impaired or forced preference
  match, normalized release-name similarity to the selected source basename,
  OpenSubtitles' trusted flag, rating, download count, and original API
  encounter order as the final deterministic tie-breaker. Preference and
  basename evidence are neutral when absent. The preference only reorders; it
  never filters results. The selected source's full path is reduced to a
  basename at the DTO-to-domain boundary and is never sent to OpenSubtitles,
  logged, diagnosed, displayed, or persisted. The first result is only the
  best-ranked candidate. JellyScope never presents it as a confirmed match for
  the item, never auto-installs it, and always leaves the choice with the user.
- Search state retains raw keyed results. `LazyColumn.items` receives those
  results directly with provider file-ID keys, and localization plus row-model
  materialization happens only inside the composed item content. This preserves
  ordering, quota, focus, errors, and install behavior without eagerly mapping
  offscreen rows.
- Quota handling treats the API's returned remaining-download/reset values as
  authoritative; never hardcode authenticated quota counts. Anonymous guidance
  is five downloads per 24 h per IP (version-bound to OpenSubtitles' current
  public policy). Surface quota state to the user after a download.
- Only single-file SRT and WebVTT results are installable. Downloads are capped
  at 5 MiB, reject empty/HTML/unknown content, decode supported UTF encodings,
  and normalize to canonical UTF-8 WebVTT in persistent app-private storage.
  Local payload decode/canonicalization and final install WebVTT byte encoding,
  fetched-server canonicalization, and upload Base64 all run on the injected
  worker dispatcher. Network calls remain outside those CPU blocks and the
  mutation owner. The raw `ByteArray` download carrier is data-owned, never a
  compiler-declared stable domain model. Remote filenames are never used as
  paths. Metadata and selection are keyed by server, user, item, and media
  source; the file and Room row survive Jellyfin logout until explicit per-file
  or settings-wide deletion.
- A persisted asset row carries exactly: a primary-key row ID; the ownership
  tuple of server, user, item, and media source plus the provider name and the
  provider's file ID, which together are unique per row; the provider's subtitle
  ID; language and display label; release name; original format and normalized
  MIME type; the opaque local file identity used by the file store; the
  hearing-impaired, forced, and trusted flags; created and last-used timestamps;
  and the Jellyfin sync state with its confirmed stream index and upload
  baseline. Source FPS is deliberately not persisted — it is transient result
  metadata shown during search and nothing reads it after installation. Add a
  field only together with the behavior that reads it.
- A local selection is `SubtitleSelectionIntent.LocalAsset`, not a synthetic
  Jellyfin stream index. The plan carries a sealed `SubtitleAsset` that
  distinguishes authenticated `JellyfinRemote` resources from verified
  `LocalFile` assets. Android resolves local files into Media3 subtitle
  configurations, iOS inserts a local WebVTT legible track into its AVFoundation
  composition, and desktop passes the app-owned path to mpv `sub-add`; no path
  receives Jellyfin headers. Off removes the local track immediately. A local
  activation failure is nonfatal and never enters Jellyfin ForceEncode because
  the server does not own the file.
- One application-scoped `LocalSubtitleMutationCoordinator` FIFO actor is the
  sole writer of local subtitle files, asset metadata, and selections. The raw
  platform Room selection store is qualified in DI; the ordinary
  `SubtitleSelectionStore` contract is a coordinator-backed facade so normal
  writes and account/server/full cleanup cannot bypass the owner. Immediate
  work reserves its monotonic logical ticket and enters the FIFO in one short
  lock-protected submission step. Physical FIFO order governs persistence,
  while per-key intent tickets and key/account/server/global barriers govern
  whether delayed work remains valid.
- Install reserves before download and normalization, then writes file -> asset
  -> conditional selection. A genuinely newer selection prevents auto-select
  but keeps a successfully installed asset; a newer delete, clear, account,
  server, repair, or reconciliation barrier prevents an older delayed write
  from resurrecting state. Delete and missing-file repair clear the matching
  selection before asset metadata and file. Reconciliation snapshots and
  mutates in one actor request, and duplicate metadata whose file is missing is
  repaired by recreating the file rather than returning a dangling asset. Each
  platform file store treats an already-absent target as successful deletion;
  when deletion reports failure and the target remains, it throws exactly
  `Unable to delete local subtitle file.` without a file identity or path.
- Mutation requests distinguish queued, started, cancelled, and completed
  state. Cancellation before start skips persistence. Cancellation after start
  waits for non-cancellable in-actor compensation, which attempts every
  applicable cleanup, clears only the matching selection, preserves the
  original failure or cancellation, and settles before the caller rethrows. A
  failed or cancelled sync-state upsert restores its exact captured prior row
  only while the same asset generation and exact claim/order still own it;
  for terminal or inapplicable updates, committed success and compensation share
  one finalization winner, and only that winner owns release. Shutdown and
  undelivered work complete terminally without leaving a FIFO gap.
- Newly installed assets enter an app-scoped, sequential Jellyfin sync
  coordinator. Automatic upload is limited to a single source or the selected
  primary source because Jellyfin's upload endpoint has no media-source
  parameter; alternate sources remain `LocalOnlyAlternateSource`. Permission is
  checked from the current-user policy. Before POST and after any successful or
  ambiguous dispatch, compare candidate server WebVTT by canonical content and
  language/forced/hearing-impaired flags. Stale Uploading and Reconciling work
  only reconciles automatically and never repeats the POST. Each logged-in
  boundary takes one bounded current-account snapshot pass that admits
  `UploadedUnconfirmed` through the ordinary lease/reconciliation route; it is
  absent from the live pending query, cannot self-loop, and never reaches an
  automatic POST. Manual Retry also reconciles first and posts only after a
  conclusive no-match. Current playback remains local; a later stored launch may
  prefer the confirmed server stream while retaining the local fallback.
- Sync network work remains outside the mutation actor. One exact
  asset-generation/order claim is active at a time; every returned state update
  re-reads and conditionally validates that claim inside the actor, so a stale
  response cannot replace an asset deleted, cleared, or reinstalled while the
  request was in flight. The sole app-scoped startup owner awaits storage
  reconciliation before observing sessions or pending assets. A
  non-cancellation reconciliation failure settles after one fixed sanitized
  diagnostic before observation begins; cancellation is rethrown and prevents
  observation. It then runs one network job at a time, cancels on account
  switch, and resumes stale work on the next launch. Do not hand subtitle sync
  to an operating-system background scheduler — no WorkManager job, no
  `BGTaskScheduler` task, no desktop daemon. The prohibition is scoped to
  subtitle sync and says nothing about unrelated scheduled work such as the
  Android TV Watch Next job.

## Playback Planning

- `PlaybackPlan` is the shared contract between domain logic and platform
  players. Keep it limited to values platform players need to prepare and run a
  session; do not move Jellyfin metadata fetching or stream-mode decisions into
  platform code.
- Add new plan fields only with the capability that consumes them. Avoid
  speculative offline, track, chapter, trickplay, segment, or recovery fields.
- Model stream modes explicitly: direct play, direct stream, transcode, and
  offline.
- Keep capability modeling explicit: codecs, containers, HDR/Dolby Vision,
  subtitle formats, audio formats, bitrate limits, server transcode support, and
  platform player support.
- Player capability policy is split from raw detection. `DeviceProfileProvider`
  reports raw route/display capabilities, while `PlayerDeviceSettings` records
  the user's intent: audio Auto, Stereo PCM, or Passthrough When Supported; HDR
  Auto or Prefer SDR. Unsupported saved choices stay stored but resolve to a
  safe effective policy with a disabled reason, so settings can explain why an
  option is unavailable instead of silently changing the user's value.
- Capability restrictions require an active decoder/platform probe, a pinned
  engine/runtime declaration, or applicable platform-vendor documentation.
  Apple AVPlayer/VideoToolbox facts and VLCKit engine facts are separate; a
  hardware probe for one must not remove a codec from the other. Android
  ExoPlayer selects one ordinary MediaCodec candidate per video codec before
  projecting its complete resolution/throughput tuple and profile, level, and
  bit-depth constraints. Hardware is preferred over unclassified and software
  candidates; a secure-required decoder is ineligible for ordinary Jellyfin
  playback, and API 25-28 codec-name classification never becomes an
  authoritative hardware-probe claim. Media3 then adds only runtime-confirmed
  bundled-FFmpeg E-AC-3 audio decode support. Android mpv uses its pinned
  capability declaration plus only the explicitly delegated complete bounds,
  and Android LibVLC uses its engine facts plus only the intersecting finite
  probe limits described above. Media3 FFmpeg support never leaks into
  either VLC-family backend. iOS additionally intersects both AVPlayer and
  VLCKit with the same conservative 4K30 frame-area/throughput input envelope
  when playback compatibility is **Standard**. On macOS only, Standard
  intersects LibVLC with a separate 3840x2160, 8,294,400-pixel,
  497,664,000-pixels-per-second input envelope. The server profile receives
  only finite width/height conditions; finite frame area and proportional
  throughput remain local source-copy facts because the wire contract cannot
  encode them.
  **Unrestricted (experimental)** removes only the active backend's marked
  app-owned envelope from the device profile and source-copy preflight; it does
  not remove backend codec, container, range, explicit-quality, or
  runtime-health constraints. The envelopes are JellyScope product policy, not
  shared decoder evidence: codec declarations and decode provenance remain
  backend-owned. Every claimed codec also carries closed decode and finite-limit
  evidence. Multiple sources may compose one claim; missing facts remain
  `Unknown`, and a static fallback or legacy name classification is never
  promoted to a platform hardware/software probe. Desktop mpv and LibVLC use
  separate immutable declarations and snapshots: mpv owns pinned-engine
  evidence and its HDR10 ranges for software tone mapping, while LibVLC's base
  declaration remains static with unknown limits and SDR-only ranges so the
  server converts HDR to SDR. The macOS product envelope does not affect mpv or
  non-macOS LibVLC.
  Measurements from JellyScope's limited device/server/content combinations may
  guide validation and a deliberately conservative product envelope, but they
  never become universal platform decoder evidence or codec/range/bit-depth
  claims. Unknown decoder limits remain unknown, and neither window nor screen
  geometry is decoder capability.
- The known portrait VP9 safety gate is planner-first: host coverage must prove
  `Transcode` with `SourceCopyRejected`, `VerifiedDeviceCap`, and Auto/Original
  `NoClientLimit` before a candidate is installed. On a physical run, inspect
  that planner record before native prepare and abort if it still resolves
  DirectPlay; retained decoder-safety evidence is evidence, not a test oracle.
- Platform players should not directly decide stream mode or fetch Jellyfin
  metadata.
- Platform-player bridges follow `architecture.md`: common code owns the
  contract and platform code owns native mapping.

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

### VLC-family PlaybackInfo policy

The optional per-server VLC setting is a backend-specific default quality.
Disabled means inherit the general Playback default. An enabled value resolves
to Fixed before the initial PlaybackInfo request for LibVLC or VLCKit, and uses
the same exact bitrate and canonical resolution box as the matching Fixed rung.
It does not change decoder capabilities and never applies to Media3/ExoPlayer,
AVPlayer, or mpv. A player-picker choice has higher priority but lasts only for
that playback. No compatibility failure activates or lowers this value, and no
combination-test value, including 8 Mbps, is a built-in default, maximum, or
decoder rule.

Android seeds this per-server VLC value to the conservative 8 Mbps rung as a
stored value through a one-time gated data migration (NULL rows are seeded and
the no-row default supplies the same value), because VLC-family playback at
high bitrates can crash the app or device on Android. Four asymmetries are
accepted: the one-time seed is clearable back to inherit; NULL ambiguity
overwrites an explicit inherit; logout re-applies the seed; and a
downgrade/upgrade cycle replays data-only migrations once. Desktop and iOS are
not seeded because their VLC paths have not shown the crash class.

### Playback health and guidance

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

Timer evaluation is generation-scoped. Startup/buffering/output jobs are
canceled on failure, completion, stop, item switch, controller replacement, and
dispose; a stale callback cannot mutate a new item session. First-video-output
is evaluated only when video is expected, playback is actively Playing or
progressing, a reliable current-prepare observation has been armed, and the
session is outside seek/replan/resume/surface/backend/background/PiP-transition
exclusions. A positive Media3 first-frame fact, mpv software-frame publication,
VLC displayed-picture counter, or AVPlayer ready-for-display bridge is
sufficient; mpv OpenGL, Vout, and surface
attachment is not. The deadline begins at active playback, not request launch.
The coordinator rechecks that reliable first-output measurement is still
supported when the deadline fires; capability loss or an unsupported backend
cancels the signal rather than manufacturing a no-video recovery.

The no-video-output deadline additionally requires an **advancing playback
clock**: a clock progressing with no displayed picture is the black-video
symptom, while a frozen clock is ordinary slow startup and is already reported as
`SlowStartup`. The position at deadline arm is compared against the position at
fire; if it has not moved, no signal is emitted. The threshold is
backend-qualified — 20 seconds for VLC-family backends, 5 seconds elsewhere —
because LibVLC reports `Playing` well before its first displayed picture. The
diagnostic threshold class carries the bound that actually applied, so a
20-second signal is never reported as the 5-second class. Without
these two qualifications a false `NoVideoOutput` also forced an unnecessary
compatibility replan that abandoned a working DirectPlay stream.

A seek or a play/pause **restarts the evidence window**: accumulated buffering
intervals, stall events, and dropped-frame spans are cleared so evidence gathered
under previous conditions cannot reach a threshold. Restarting during an active
buffering episode reopens the interval at the boundary — the retained status is
unchanged, so without the reopen the wait the user is still experiencing would
stop being measured entirely. The once-per-session emission latches deliberately
survive a restart, so a signal kind already reported does not report again in the
same item session; that is why a second unreported stall remains a diagnosis
item rather than a fixed behavior. Auto's compatibility and quality budgets are
untouched by a restart and stay one-per-item-session. The bounded
health summary additionally records which gate suppressed a would-be warning
(`postStartGuidanceShown`, `noVideoOutputGuidanceShown`, the resolved guidance
policy, `guidancePublishable`, and whether a buffering interval opened since the
last restart), because gates that emit no signal are invisible to signal-scoped
logging.

Desktop subtitle vertical position is **per backend, not shared**. Desktop mpv uses its
native/default `34` scaled-pixel `sub-margin-y` when the bottom player chrome is absent.
The shared player surface derives one transient Boolean from normal-controls visibility,
the current bottom picker, and picture-in-picture state: normal controls or any bottom
picker adds the fixed `146` scaled-pixel controls offset, for a total of `180`;
picture-in-picture keeps the native `34` because the shared chrome is not rendered
there. The setter is live and changes only mpv's `sub-margin-y`; it does
not reprepare media or alter subtitle selection/style preferences. Android and iOS
accept this surface input inertly, and desktop LibVLC never receives it.

VLC does **not** use this policy: VLC 3.0.23 declares `sub-margin` as a plain integer
option with no proportional form, and the option is installed at instance creation
before any video dimensions exist, so one shared pixel value cannot express the same
clearance for both. VLC therefore uses its native default bottom placement. Removing
the override eliminates the app-forced displacement; it does not guarantee a final
position, since VLC defaults and any ASS/SSA positioning tags still apply. Do not
reintroduce a fixed VLC pixel value, and do not add per-media geometry without
plumbing post-playback video dimensions into a per-media option.

An explicit **Off** subtitle choice is durable on the detail screen as well as in the
player. `Off` and "no explicit choice" are distinct intents: `Off` persists a typed
no-subtitle selection, while `Unspecified` means fall back to the preferred language or
the container default. The detail picker exposes separate subtitle-track and
local-asset selection paths so the two cannot collapse into one nullable argument again.

Deleting a downloaded subtitle asset clears the durable selection locally, so the
rendered default must be **reprojected from the options already in state** before any
server refresh — a refresh needs a remote item-detail response and commits nothing when
it fails, which would leave the picker showing a selection playback has already
abandoned. Both the initial projection and the reprojection go through one shared
resolver so the two cannot compute different defaults from the same inputs. Reproject
from the durable selection re-read after the delete rather than from an assumption about
which asset was removed: deleting an unselected asset must leave the surviving
selection, and its rendered highlight, alone.

Track-resolution mapping diagnostics are **deduplicated per track kind** so a polling
controller does not repeat an unchanged outcome. The dedup is one shared
`TrackResolutionDiagnosticGate`, and its check-and-set is **locked**: mpv resolves tracks from
both a background poll and a synchronous command path, and without atomicity both can pass the
equality check before either records, emitting the same line twice.

Its `reset()` is **conditional, not a lifecycle requirement**, and that asymmetry is
deliberate. A reset is not an ordering barrier, so a controller whose resolution work can be
in flight across the call must not call it — a stale admission landing afterwards re-logs an
outcome already logged. The single-threaded controllers (Media3 on the ExoPlayer app thread,
VLCKit on its main-queue callbacks, AppleAV main-marshalled) reset at `prepare()`; **mpv
deliberately never resets** and retains recorded outcomes for the controller's lifetime, which
is bounded by the number of track kinds. Do not "fix" that inconsistency.

What makes retention safe is that activation targets are unique per request: both
`AudioActivationTarget` and `SubtitleActivationTarget` carry a monotonically incremented
`requestId`, so a new session's key can never equal the previous session's and no per-item
mapping line can be suppressed. If target reuse is ever introduced, the remedy is
epoch-aware admissions — capture an epoch with the selection and reject admissions from an
older epoch — not a reset. The key's `target` field participates in equality **only** and must
never be formatted or logged; it can carry stream indices, identities, or asset ids.

Android LibVLC reports **no** time-based buffered-ahead value. `Event.Buffering`
exposes only a cache-fill percentage, and the pinned VLC source computes it
against a dynamic buffering duration that folds in PTS delay, preroll, and extra
buffering rather than a stable absolute window, so no millisecond position can be
derived from it honestly. `bufferedPositionMs` therefore stays at the playhead for
that backend and the seek bar draws no secondary track. The raw percentage remains
runtime diagnostic state but is not a time-based estimate and is not a shared
overlay row. Do not substitute a `--network-caching` estimate.

The shared health evaluator supplies evidence and
`AutoPlaybackRecoveryCoordinator` supplies the bounded decision. The policy,
including every Original trigger and prompt action, is owned by
[playback architecture](playback-architecture.md#quality-semantics). PiP
suppresses interactive recovery until it is safe to show the consequence.
- PlaybackInfo requests include the effective player-device policy. Stereo PCM
  limits requested audio channels to 2, narrows profile audio codecs to AAC/MP3,
  and disables audio stream copy. Passthrough may advertise route-supported
  encoded audio codecs only when the active output route reports them. A
  per-codec local-decode ceiling takes precedence over the positive legacy
  global fallback; the route ceiling participates only for a codec on the
  passthrough list. Media3's bundled-FFmpeg E-AC-3 path therefore accepts up to
  eight encoded input channels even when the current PCM route is stereo, while
  it makes no E-AC-3 passthrough claim. Prefer
  SDR uses the range-type derivation with an SDR-only capability set but keeps
  video stream copy ALLOWED: the per-codec `EqualsAny VideoRangeType` conditions
  already deny copying non-SDR sources (the server tone-maps them), while SDR
  sources remux when only audio needs transcoding (probe-verified on Jellyfin
  10.11). `AllowVideoStreamCopy=false` is never sent as a routine safety flag:
  the server honors it by silently re-encoding video at the same codec and
  bitrate without adding any `TranscodeReasons` entry, so the quality loss is
  invisible in the PlaybackInfo response (the reasons list still shows only the
  original trigger, e.g. `AudioCodecNotSupported`) and surfaces only as
  `IsVideoDirect=false` in `/Sessions` TranscodingInfo (probe-verified on
  Jellyfin 10.11). Disabling stream copy is reserved for the explicit runtime
  fallback ladder below, where a re-encode is the intended outcome. Auto HDR
  falls back effectively to SDR when display/HDR support is not detected.
- Android caches decoder enumeration but re-probes the anticipated media audio
  route and primary app display for every capability read. API 33+ uses media
  `AudioAttributes`; older Android/Fire OS uses the current HDMI plug state.
  Unknown routes fall back to stereo with no passthrough, and duplicated active
  routes advertise only common encoded formats and the conservative
  passthrough-channel limit. MediaCodec input-channel counts use Media3 1.9's
  effective adjustment rather than uncorrected framework values; bundled
  FFmpeg E-AC-3 keeps its separate eight-channel decode-input ceiling.
- Device profiles model video range capabilities per advertised codec. The
  mapper emits exactly one required `EqualsAny VideoRangeType` condition for
  each codec, using a pipe-joined supported allowlist; unknown or newly added
  server range strings therefore transcode rather than leaking through an
  exclusion rule. `NotEquals` range conditions are never used: the server's
  condition processor treats subtype ranges as satisfying their base type
  (`HDR10Plus` satisfies an `HDR10` condition), so a `NotEquals` denial list
  leaks ranges it was meant to exclude — only a pipe-joined `EqualsAny`
  allowlist admits and denies exactly the listed ranges (probe-verified on
  Jellyfin 10.11). `SDR` and `DOVIWithSDR` are always allowed. The remaining
  entries are derived as follows:

  | Capability | Additional advertised range types |
  | --- | --- |
  | HDR10 | `HDR10`, `HDR10Plus`, `DOVIWithHDR10`, `DOVIWithHDR10Plus` |
  | HDR10+ | `HDR10Plus`, `DOVIWithHDR10Plus` |
  | HLG | `HLG`, `DOVIWithHLG` |
  | Dolby Vision base | `DOVI`, `DOVIWithHDR10`, `DOVIWithHDR10Plus`, `DOVIWithHLG` (a DV decoder renders the DV layer regardless of fallback-range display flags) |
  | Dolby Vision enhancement layer | `DOVIWithEL`, `DOVIWithELHDR10Plus` |

  `DOVIInvalid` is never advertised. Base Dolby Vision and its enhancement layer
  are independent capabilities: the latter is reported only when Android detects
  profile 7 and multi-instance HEVC decode. Android maps profiles 5 and 8 to
  HEVC base Dolby Vision and profile 10 to AV1 base Dolby Vision; a Dolby Vision
  MIME with no enumerable profiles falls back only to HEVC base. Non-HDR codecs
  therefore advertise exactly `SDR|DOVIWithSDR`, and Prefer SDR uses that same
  two-member allowlist for every codec.
- Explicit per-codec range exclusions are stronger than that derived positive
  set and are subtracted after canonical, case-insensitive normalization. The
  Android provider attaches only the HEVC exclusions
  `DOVIWithHDR10Plus` and `DOVIWithELHDR10Plus` for exact device models
  `AFTKRT`, `AFTKA`, `AFTKM`, and `AFTMM`; blank, case-different, near-match,
  and other model IDs infer nothing. The DTO still emits one positive
  `EqualsAny` allowlist. Local source-copy preflight applies the same explicit
  exclusion before DirectPlay, DirectStream, or PlaybackInfo-failure fallback,
  so local copy cannot bypass the wire subtraction. Auto and eligible Fixed may
  use the existing bounded forced-re-encode request; Original fails closed, and
  Unrestricted never removes a hardware exclusion. An empty exclusion map is
  behavior-preserving for every other provider and model.
- Android's named `VideoProfile` list must be **ordered best-first** (high,
  main, main 10, high 10, baseline, constrained baseline; unknown names last):
  Jellyfin uses the first listed profile as its transcode **encode target**, so
  an enumeration-ordered `baseline|main|high` list can select a systematically
  dropped-frame-prone constrained-baseline transcode. `EqualsAny` direct-play
  eligibility is order-insensitive, so keep the transcode encode-target order
  explicit rather than inheriting decoder-enumeration order.
- Device profiles must equally not **under-claim** from unreliable decoder
  enumerations. Android's named `VideoProfile` and `VideoLevel` conditions are
  derived only from the selected decoder and advertised only when its mapped
  `profileLevels` contain the codec's trust-anchor set (`h264: main|high`,
  `hevc: main|main 10`, `av1: main|main 10`) — defense-in-depth for vendor
  decoders that under-report profiles. When the selected decoder gives
  different maximum levels to its advertised profiles, JellyScope emits the
  minimum of those per-profile maxima because Jellyfin applies one level
  condition to the entire profile set. A
  degenerate enumeration drops the profile and level claims but retains the
  conservative `VideoBitDepth <= 8` condition, so the server keeps its default
  encode profile (High) while 10-bit sources still transcode instead of direct
  playing. Accepted residual risk: degenerate-reporting hardware that truly
  cannot decode Main/High transcodes fails fatally, because transcode failures
  deliberately bypass the runtime fallback ladder. The omission is logged once
  per decoder enumeration as sanitized fixed-label Info
  (`codec-constraints-untrusted codec=<name> mappedProfiles=<count>`).
- Advertised `VideoProfile` values use Jellyfin's lowercase space-form
  spellings ("main 10", "constrained baseline"), never MediaCodec underscore
  forms — the server compares them case-insensitively but not
  punctuation-insensitively. `VideoLevel` conditions use Jellyfin's numeric
  encodings: H.264 levels ×10 (4.1 → 41), HEVC levels ×30 (5.1 → 153), and
  AV1's raw level index (e.g. 15, 19). Wrong spellings or scales silently fail
  the condition and change direct-play/transcode decisions (probe-verified on
  Jellyfin 10.11; the spellings and scales are an ecosystem-wide convention).
- Transcode plans must cap output resolution to the target codec's decoder box.
  Jellyfin's transcode scaler is width-tier based and does not enforce the
  profile's `Height` condition on portrait sources, so a response can exceed a
  height-limited decoder and produce black video or stuck buffering. When the
  response video stream cannot fit the transcode target codec's detected
  resolution limits, the planner rewrites the transcoding URL with an
  aspect-preserving integer-math fit on **both** parameter sets the server
  consults:
  `MaxWidth`/`MaxHeight` are replaced (the bitrate-driven resolution normalizer,
  active whenever the output bitrate is below the source bitrate, takes
  `min(tier, MaxWidth)` and ignores explicit dimensions) and explicit
  `Width`/`Height` are appended (honored on the non-normalized path). Log only
  the dimensions — the capped URL carries the ApiKey. The planner may rewrite
  the returned `TranscodingUrl`'s query parameters (resolution caps, subtitle
  fields) because those parameters are the transcode job spec itself: the
  server builds the ffmpeg job from the URL's query values at segment-fetch
  time and no signature covers them, so an edited parameter changes the job
  (probe-verified on Jellyfin 10.11). Both this resolution-cap rewrite and the
  server-attached subtitle strip below depend on that contract; if a future
  server release signs or ignores these parameters, both features break
  together and must be re-verified.
- Runtime player failures may retry PlaybackInfo with stricter request policy
  before surfacing a fatal error. For direct-play/direct-stream decoder,
  unsupported-media, or unknown failures, shared player logic first retries with
  direct play disabled; if that also fails, it retries with direct play, direct
  stream, and audio/video stream copy disabled. Transcode/offline failures do
  not enter this fallback ladder. Fallback policies preserve the incoming
  backend, so a future VlcKit retry continues to use VlcKit capability data. A
  request whose backend is not AVPlayer is non-default even when all stream
  flags retain their defaults; its PlaybackInfo failure propagates rather than
  silently reinstalling the local direct-play plan.
- PlaybackInfo decisions are server-authoritative. Select the requested media
  source when returned, otherwise the first source. Prefer `SupportsDirectPlay`
  with the static URL, then `SupportsDirectStream` with a usable response
  container and a `/Videos/{itemId}/stream.{container}` URL carrying the media
  source, device, play-session, response audio/subtitle indices, and subtitle
  method. Use a nonblank `TranscodingUrl` only when transcoding is supported. A
  malformed direct-stream response falls through to valid transcode before a
  typed planning failure. Response container/subprotocol determines stream MIME;
  HLS always uses the HLS MIME. Runtime decoder fallback ordering remains direct
  play disabled, then direct stream and stream copy disabled.
- Device profiles are platform/container-qualified, not one global container
  list. They intersect platform containers/codecs with the effective audio/HDR
  policy and mirror a non-null bitrate cap into request, device-profile, and
  transcoding-profile bitrate fields. Android also maps available H.264, HEVC,
  and AV1 codec profile/level/bit-depth data. Named video profiles are
  advertised as Jellyfin `EqualsAny` sets (for example `main|main 10`), never
  ordered comparisons; keep detected resolution limits and do not invent
  reference-frame constraints. The HLS-TS transcoding profile advertises the
  best-first intersection of `aac,mp3,ac3,eac3` and effective audio codecs
  (falling back to `aac` when empty) so aac remains the re-encode target while
  compatible ac3/eac3 sources can copy; Stereo PCM retains its narrower policy
  codecs.
- Android Media3 treats persistent audio-output failures as recoverable when
  video can keep playing: after transient AudioTrack init retries are exhausted,
  the controller disables Media3's audio track type, re-prepares the current
  plan at the saved position, and reports `audioUnavailable = true` instead of
  failing the whole player. A fresh media item re-enables audio; an explicit
  audio-track selection also re-enables audio for the current item so users can
  recover after fixing receiver/TV output.
- Subtitle launch intent has four states: omitted `Unspecified`, explicit `Off`
  (`SubtitleStreamIndex=-1`), `Track(index)`, and `LocalAsset(assetId)`. Startup
  resolves explicit intent first, then the durable selection keyed by
  server/user/item/media source, preferred subtitle language, Jellyfin's default
  subtitle, and finally Off. Invalid stored tracks are deleted and fall through;
  invalid route values are ignored and never persisted. Detail launches send
  their current Off/Track choice explicitly, while shuffle, autoplay, and queue
  transitions omit it and resolve each new item/source independently.
- Explicit launch and in-player choices persist through the Room-backed subtitle
  store. Additive version-2-to-3-to-4 migration preserves existing rows. Logout
  clears Jellyfin Off/Track intent for the affected server but retains local
  asset rows and LocalAsset selections; explicit deletion clears them. Writes
  are serialized so rapid choices cannot finish out of order. Process-local
  playback memory remains limited to audio and quality.
- PlaybackInfo always receives the resolved subtitle index, including `-1` for
  Off or a local asset and the real Jellyfin index for remote external tracks.
  `PlaybackPlan` carries response-derived embedded audio descriptors and a
  response-authoritative `PlannedSubtitle`: Off, a track with an optional
  embedded descriptor, delivery method (Drop, Embed, External, HLS, or Encode),
  text/bitmap kind, optional external resource, and local activation target, or
  Unavailable. Each embedded descriptor records Jellyfin stream index, filtered
  container ordinal, nullable response-authoritative cohort size, codec,
  normalized language, and label; detail-metadata fallback descriptors leave
  that cohort size unknown. Filtered container ordinals exist because
  Jellyfin's `MediaStream.Index` is a global response index that lists
  external streams first (an external SRT can be index 0) — it never reliably
  equals a player's container track position, so no bridge may use a Jellyfin
  index directly as a native track ordinal (probe-verified on Jellyfin 10.11).
  Subtitle descriptors
  canonicalize only known Jellyfin/native codec and MIME alias families; BOTH
  kinds use the raw source `Title` as comparable identity — never the
  server-synthesized DisplayTitle, which cannot equal a native candidate's
  container track title and would force TitleConflict transcodes (readable
  display labels are UI-only). Audio compares codec families only during native
  mapping, and track languages canonicalize through the complete ISO
  639-1→639-2/T table plus the 639-2 B→T variant pairs, so `te`/`tel` or
  `fre`/`fra` style taggings never conflict. The unknown-language family (`und`,
  `unknown`, `undetermined`, `mul`, `zxx`) canonicalizes to null, so it never
  creates a language conflict with a real tag; other unknown subtitle/audio
  codec and language values stay isolated rather than being guessed into a
  family. Successful PlaybackInfo plans build descriptors only from the selected
  response media source. Detail streams are consulted only after the initial
  default PlaybackInfo request fails. Audio descriptors exclude external audio;
  subtitle ordinals include only response-approved Embed/HLS streams, so
  External, Encode, and Drop entries never shift them. Transcoding URLs carry
  only the resolved requested subtitle intent: when a server-attached
  `SubtitleStreamIndex` differs from that intent (including Off or a local
  asset), the planner strips it and `SubtitleMethod` before the plan reaches a
  controller. The strip exists because the server attaches these parameters on
  its own — the user's server-side `SubtitleMode` setting can add
  `SubtitleStreamIndex=N&SubtitleMethod=Encode` to a transcode URL even when
  the request omitted a subtitle or sent `-1` — and burn-in forces a full
  video re-encode while rendering subtitles the user never selected in-app;
  stripping the two parameters restores video stream copy on the same asset
  (probe-verified on Jellyfin 10.11). The response's
  `DefaultSubtitleStreamIndex` never becomes
  installed subtitle truth. Matching user-selected bitmap subtitles retain their
  server-selected burn-in path during transcodes. Stream mode never determines
  subtitle rendering. Every platform controller disables native subtitle
  auto-selection during prepare before loading or playing new media; Embed/HLS
  activation applies in direct play, direct stream, or transcode plans. Only
  confirmed local text is styleable.
- Subtitle selectors remain available when exactly one real server or app-local
  track is present because Off and that track are two distinct choices. One
  shared predicate controls CC visibility and picker dismissal; the picker
  closes only when both lists become empty.
- Device profiles always enumerate a `SubtitleProfiles` delivery method for
  every subtitle format the platform can render, because the server plans
  burn-in for a default-flagged or auto-selected subtitle whose format has no
  advertised delivery — denying direct play for the entire item, not just that
  track (probe-verified on Jellyfin 10.11). Normal device profiles advertise
  local subtitle methods before Encode: Android
  Media3 supports its verified text formats, VTT HLS, and bitmap Embed only
  where `DefaultSubtitleParserFactory` confirms the MIME; desktop libmpv
  supports broad embedded text/bitmap and authenticated external text; iOS
  limits AVPlayer text delivery to Embed (direct-play container tracks matched
  by stable index) and Encode. It advertises NO External (unreliable
  `AVMutableComposition` sidecar splice) and NO HLS text (transcode text tracks
  carry no stable source index, so native selection false-conflicts on the
  manifest language string), so text subtitles are burned in (Encode) during a
  transcode — ForceEncode forces one on a direct-play video — and render with no
  native track selection. The iOS VLCKit profile uses the SAME text policy
  (Embed + Encode, no External/HLS): VLC renders embedded container tracks
  natively on direct play, but an external subtitle slave is not rendered over
  an HLS/transcode stream, so text subtitles burn in (Encode) on a VLCKit
  transcode just like AVPlayer. VLCKit bitmap subtitles
  remain Embed + Encode. Each device profile lists Jellyfin's equivalent codec
  spellings separately (`vtt`/`webvtt`, `srt`/`subrip`, `ass`/`ssa`, and bitmap
  aliases) while applying the same platform policy to each. Do not collapse
  aliases in the request profile because exact format names participate in
  server delivery negotiation. Capability lookups use canonical subtitle-format
  equivalence, so a provider advertising one spelling still matches an
  equivalent stream spelling. External subtitle files are sideloaded from the
  response `DeliveryUrl`; missing/unknown MIME or URL is unsupported and never
  guessed as SubRip. Server-relative delivery URLs are resolved against the
  active session server URL. Platform bridges should treat unsupported sidecar
  formats as non-fatal: keep video playback alive, leave that sidecar
  unselected, and log only sanitized diagnostics.
- External/Encode subtitle changes, local activation fallback, and all quality
  cap changes re-request PlaybackInfo with the selected stream fields. Before
  installing a successful replacement, the old progress-reporting session is
  stopped exactly once at the captured position; play/pause intent is preserved.
  Off is immediate only for a locally selected track. Removing an External or
  Encode selection re-plans with `SubtitleStreamIndex=-1`.
- Requested subtitle memory updates immediately, but picker selection, debug
  state, and styleability remain derived from the installed `PlaybackPlan`.
  During a re-plan the old track therefore stays active until the replacement
  plan is installed; a PGS/external/off request must not be classified as failed
  merely because it is being compared with the previous plan. A newer subtitle
  choice cancels any older pending re-plan so a stale response cannot overwrite
  the latest request.
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
  native attempt**, taking the same single DirectPlay-disabled recovery below.
  Without this, an in-player switch to an undecodable codec selects successfully
  and then plays silence with video still running — the server validates the
  default track against the profile we sent, but never validates a mid-session
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
- Successful PlaybackInfo response subtitle delivery is authoritative; detail
  metadata never overrides it. On an initial/default PlaybackInfo failure, Off
  remains direct play with subtitles disabled. A Track may use detail metadata
  only when the platform profile proves its embedded/external delivery safe;
  otherwise video continues with Unavailable and no retry of the failed request.
  ForceEncode and decoder-fallback PlaybackInfo failures propagate to their
  caller and must never silently reinstall the minimal direct-play plan. If the
  selected subtitle is missing only from a successful PlaybackInfo response, its
  exact detail stream may supply a known format/kind for one Encode attempt;
  missing detail format disables the retry. A fallback response is accepted only
  when it returns Encode at the exact failed Jellyfin stream index. Embed,
  External, HLS, Off, missing, and wrong-index responses leave the subtitle
  unavailable without replacing the playing plan.
- Playback metadata extensions stay shared: item detail maps Jellyfin
  `Chapters`, `Trickplay`, and `RemoteTrailers`; media segments come from `GET
  /MediaSegments/{itemId}` with segment types `Intro`, `Outro`, `Recap`,
  `Preview`, and `Commercial`. Legacy Intro-Skipper endpoints are not part of
  the shared contract unless a later feature adds an explicit fallback.
- `PlaybackPlan` carries chapters, trickplay info, media segments, playback
  speed, and subtitle style so platform players can consume them at prepare
  time. `PlayerViewModel` enriches `PlaybackState` with the current segment and
  exposes playback-speed/subtitle-style updates through `PlayerController`.
- `PlaybackPlan.videoPresentation` is passive response-derived video metadata
  (width, height, frame rate, and range). It supports debug presentation,
  Android TV panel presentation, and Android mobile's stable coded PiP
  aspect. The dimensions remain rotation- and pixel-aspect-blind; they must
  never change a device profile, PlaybackInfo request, or the selected stream
  mode.
- Local subtitle state uses a project-owned request identity containing request
  id, item id, local kind, and either a Jellyfin stream index or local asset id.
  `PlaybackPlan` carries the expected target and `PlaybackState` carries
  `Pending`, `Active`, or `Unavailable` for that target. Shared render
  diagnostics accept only an exact target match; stale platform callbacks remain
  pending and cannot enable subtitle styling. Media3 may prefix a sidecar
  `Format.id` with its media-period identifier, so Android matches the complete
  configured activation id after that delimiter; partial or suffix-collision
  matches remain invalid. Once the native player is ready/loaded, one
  non-extending three-second confirmation deadline turns a still-pending target
  into `Unavailable`. An exact Jellyfin-track local failure disables the failed
  selection, publishes a six-second passive notice token, and re-plans once at
  the same position with only Encode exposed for that normalized format. That
  server-burn-in fallback MUST disable both direct play and direct stream in its
  request policy so the server performs a real transcode; Encode delivery
  renders only inside the transcoded video. This invariant applies to both the
  shared-UI and tvOS fallback paths. A local-file failure instead disables that
  app-owned asset, keeps video playing, and never requests ForceEncode. A newer
  selection cancels either path; stale targets are ignored. The Jellyfin retry
  must return Encode for the exact failed stream index. Failure retains the
  playing video, leaves the subtitle unavailable, and never loops or becomes a
  fatal error. `PlaybackSessionRecoveryPolicy` owns the one-shot,
  generation/item-scoped decision and exact request identity for both shared UI
  and tvOS; each shell still owns its suspended request and cancels it when a
  newer choice/item or stop/close makes the result stale.
- Seeking a transcode stream to a position outside the produced window
  (`[plan.startPositionMs, bufferedPositionMs]`) restarts the transcode at the
  target (re-plan) instead of an in-stream seek. This is gated by the
  `PlayerController.transcodeSeekRestartsStream` capability (default false; true
  only for AVPlayer, which wedges when a not-yet-produced HLS segment times out)
  so Android/desktop seek behavior is unchanged. Direct-play and in-window seeks
  stay in-stream on every platform. The re-plan reuses the quality/track-change
  path and its default request policy, so a seek after a forced subtitle
  fallback may return to DirectPlay and repeat the native subtitle failure and
  one-shot fallback; this is the accepted transcode-seek behavior. It is guarded
  against concurrent restarts.
- End-of-playback is a one-shot: `PlayerViewModel` emits `playbackEnded` only at
  a true end-of-queue `Completed`, and the shared player closes once through a
  single guarded path; mid-queue `Completed` auto-advances instead.
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
- Observable player UX is the regression contract below.

### Verifying server behavior

Server-behavior facts in this guide carry a "(probe-verified on Jellyfin
10.11)" stamp; a new server major requires re-probing every stamped fact
rather than porting code. When probing a live server:

- A transcode job only starts when a SEGMENT is fetched: GET the master
  playlist, then the child `main.m3u8`, then at least one `.ts` segment.
  Fetching only the master playlist never starts an encode, so `/Sessions`
  will show no `TranscodingInfo` and the verification silently observes
  nothing.
- The PlaybackInfo response's standalone `TranscodeReasons` list is often
  empty; when it is, URL-decode the `TranscodeReasons=` query value from the
  returned `TranscodingUrl` instead. The authoritative record of what the
  server actually did for a session is `/Sessions` — `PlayState.PlayMethod`
  plus `TranscodingInfo` — not the PlaybackInfo response the client planned
  from.
- Any PlaybackInfo replay used to verify direct-play decisions MUST include
  the client's real `SubtitleProfiles`. A profile without them makes the
  server plan burn-in for a default-flagged or auto-selected subtitle and deny
  direct play for the whole item, producing false direct-play denials that do
  not reproduce the app's actual behavior.

## Discovery Data

- Discovery browse data remains behind `MediaRepository` and shared use-cases:
  collections use `/Items` with `includeItemTypes=BoxSet`; collection contents,
  genre browse, studio browse, and person filmography use `/Items` with scoped
  query parameters; upcoming TV uses `/Shows/Upcoming`.
- The recommendations endpoint is intentionally not assumed. Shared
  `getSuggestions()` currently implements "because you watched" by seeding from
  the first continue-watching item and fetching `/Items/{itemId}/Similar`.
  Replace this only after confirming a stable Jellyfin recommendations endpoint.

## Progress Reporting

- Report playback start, periodic progress, pause, seek, stop, completion, and
  failure through the shared progress-reporting boundary.
- A per-owner `PlaybackReportingCoordinator` owns Start/pending state and
  ready-state retry, pause/unpause edges, periodic sampling, progress/Stop
  eligibility, stale Start rejection, duplicate Stop suppression, and final
  disposal for both `PlayerViewModel` and the tvOS playback presenter. Compose
  supplies its stable shared playback-state projection across controller
  replacement; the coordinator never retains a replaceable controller flow.
- A single ordered executor (`PlaybackReportingQueue`, shared-core
  `playback/`) serializes each owner's immutable playback sessions as Start ->
  Progress -> Stopped. Old-session Stop drains before a replacement session can
  Start, and disposal drains its final Stop independently of owner-scope
  cancellation. No coordinator or shell sends reports through another path.
- A reporting session becomes started only after Start succeeds. Progress and
  Stopped are suppressed for a failed Start; later ready-state samples may retry
  Start without allowing a failed attempt to reset Continue Watching state.
- Every accepted Stop attempt publishes an app-scoped settlement after the
  report succeeds or terminally fails. The replayable settlement registry is
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
  [Downloads And Offline](#downloads-and-offline); it does not treat server
  progress as authoritative for that local session.
- Offline reporting to a matching currently online account is best effort only.
  There is no offline reporting outbox, delayed-sync queue, acknowledgement,
  server overlay, cross-device conflict resolution, or eventual-delivery
  guarantee.

## Durable Playback Controls And Android Engines

### Android mpv backend

- `docs/guides/playback-architecture.md` owns Android backend policy — the
  default, `Auto` normalization, and the visible per-shell order. Within that
  policy, mpv is the TV opt-in alternate backend and the existing backend
  dialog is the explicit opt-in; selection is resolved before PlaybackInfo,
  persisted server-scoped, and pinned for the session.
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
  mpv `no` log request. The controller explicitly selects `Error` immediately
  before native initialization; only the existing error observer crosses the
  JNI boundary, and `AndroidMpvEngine` reduces its prefix/text to an
  allowlisted severity/category. The bridge never writes raw mpv message text
  or routine event names to Android logcat, even when verbose platform logging
  is enabled. A failed Error request is recorded as the fixed
  `mpv-log-level` option and bounded native result code, without enabling raw
  logging or disabling playback policy.
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
  video output (`gpu-next` for the copy/software classes, classic `gpu` for TV;
  the renderer rationale is owned by
  [`playback-architecture.md`](playback-architecture.md)),
  Android/OpenGL ES, no mpv config/scripts/ytdl/cookies/autoloaded resources or
  native UI/input bindings, disabled automatic track selection, `idle=yes`,
  `keep-open=always`, TLS verification, and cache pause. The initial cache policy
  is a 10-second target with 64 MiB forward and 16 MiB backward caps. Mobile
  uses `mediacodec-copy`, emulators use software decode, and TV uses zero-copy
  `mediacodec` with the `fast` profile in its explicitly selected opt-in
  path.
- Android mpv writes its own verbose log to app-internal
  `files/mpv-logs/mpv-verbose.log` (the prior file is retained as
  `mpv-verbose.prev.log`; rotation happens per engine instance and on each
  mid-session re-enable, and a 30-second watchdog closes the file once it
  crosses 32 MiB). Writing happens only while the diagnostics-collection
  setting is on — mid-session toggles close or reopen the file live, and
  disabling collection deletes both retained files even with no player
  active. This file is the primary evidence for user-reported playback
  defects; the in-process observer seam stays reduced to sanitized
  categories. The RAW file contains secrets — mpv echoes the Authorization
  header via its `http-header-fields` property at verbose level — which is why
  it lives in internal storage and never enters client-log uploads. Disabling
  diagnostic collection deletes both retained files; uploads contain only the
  structured diagnostics report and scrubber-admitted bounded history.
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
  trust managers, and no `tls-verify=no` fallback is permitted.
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
  not restore the earlier Subtitle Off state. A native
  create/init/decoder/unsupported failure releases
  the alternate controller and requests one fresh ExoPlayer-qualified plan at
  the last confirmed position; it never changes a live controller in place.
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
- The Android native dependency is the project-owned `android-libmpv` module,
  built from the audited `dev.jdtech.mpv:libmpv:1.0.0` source/native input
  recorded in the [Android native dependency runbook](../operations/android-native-dependencies.md).
  The API-26 artifact, NDK-29 `libc++_shared.so` selection, shipped/excluded ABI
  set, required native libraries, license assets, attribution, and
  corresponding-source route are checked by the APK verifier.
  The wrapper POM's MIT declaration is not treated as the complete native
  license; the distributed FFmpeg/GPL payload remains a GPLv3-compatible
  licensing and source-obligation gate. Desktop libmpv remains a separate
  unchanged integration.

- `PlaybackPreferences.autoPlayNext` defaults to enabled with a shared 10-second
  delay. The persisted delay is normalized to 0–60 seconds. Disabled autoplay
  never starts a countdown but keeps manual Play Next available; a zero delay
  advances once immediately. The active queue snapshots this policy with its
  item, queue identity, and playback generation so a stale Completed emission
  cannot advance a replacement queue.
- `PlaybackPreferences.stillWatchingPrompt` defaults to enabled and gates the
  still-watching safety prompt, which otherwise interrupts after three
  consecutive automatic advances. When disabled the gate is skipped entirely and
  the consecutive-advance counter is not kept, so re-enabling it starts a fresh
  count rather than prompting on the next advance. Preferences resolve once per
  playback start, so a mid-session change applies from the next start. Resume is
  unconditional: there is no start-over preference, and the per-launch Start
  over action remains the only way to ignore a stored position.
- A queue switch request reports whether it was **accepted**: `playNext` returns
  false for no next item, a stale auto-advance generation, or the still-watching
  gate taking over. The Up Next affordance may only dismiss itself (and the
  overlay may only hide its controls) on an accepted switch — dismissing on a
  refusal strands the user on a finished item with no visible way to continue.
- Timing offsets are reached from a single **Offset** entry in the
  audio/subtitle track picker (value shown on that entry only when non-zero);
  the offset panel leads with the cumulative value, and closing it returns to
  the parent track picker rather than dismissing to the player.
- A backend reporting subtitle timing as Supported is **not** a reason to show
  the subtitle control: the Android backends report Supported unconditionally,
  and a subtitle delay with no subtitle track has no observable
  effect. Gate the subtitle button and its picker auto-close on an actual
  selectable track or local asset (`hasSubtitlePickerChoice`), never on timing
  support. Neither desktop controller implements `PlayerTimingController`, so
  `timingController` stays null there and the Offset entry is hidden on desktop
  entirely; wiring mpv `audio-delay`/`sub-delay` behind the existing seam is an
  open capability gap, not a regression.
- Audio and subtitle timing offsets are signed, finite milliseconds, clamped to
  the shared ±20-second limit, and keyed by server/account/item/media source and
  stable track identity. The ViewModel loads an offset **before** the first
  prepare once the source/track is known (so the initial sink configure applies
  it without a post-start re-prepare), resets the previous key on source/track
  changes, applies it through the optional `PlayerTimingController`, and
  persists **the value the controller actually applied** (a negative subtitle
  request is stored as 0 on Media3), not the raw request. Unsupported platforms
  expose an explicit unsupported capability rather than a misleading applied
  value.
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
  one-third-heap target capped at 384 MiB exists only as an A/B candidate and
  must not become the default without the validation rows recorded in
  `.local/KNOWN-ISSUES.md` (Android Media3 load-control
  rows).
- The Media3 byte value is a **load-decision target**, not a Java-heap or
  process-memory ceiling. Time priority may allocate beyond it while reaching
  the duration floor, and codec, decoder, audio, graphics, and other native
  allocations are outside Media3's allocator. The existing one-second position
  ticker samples buffered-ahead and allocator bytes; callbacks aggregate first
  frame, true post-start rebuffers, and audio underruns once per prepare epoch.
  Process PSS and memory-pressure evidence are sampled externally.
- Retained validation artifacts are sanitized like release diagnostics: they
  never contain credentialed URLs, tokens, item/source/session IDs, server
  identity, titles, or device serials. Release-equivalent validation builds
  emit only scrubber-accepted structured diagnostics by default.
- `PlaybackPlan.maxStreamingBitrate` is the exact PlaybackInfo **request cap**
  for every final stream mode. Its typed origin survives planning. An
  effective-transcode cap is populated only when the installed result is
  `Transcode`; DirectPlay and DirectStream may retain a request cap without
  claiming that it limited the delivered stream. App policy never modifies the
  server's `transcodeReasons`.
- Android LibVLC attaches its `VLCVideoLayout` only after the Compose view is
  attached to a window. The vout callback re-enables the video track and
  re-issues a pending play request, which prevents an initial attach from
  leaving audio running with no video surface. Android uses LibVLC's
  `SurfaceView` output path as the backend presentation contract.
- Android LibVLC **coalesces** seek requests through `SeekCoalescer` (250 ms
  window, newest target wins) before touching the native player. Every
  `setTime()` flushes libVLC's decoder, so a burst of discrete seeks (repeated
  D-pad taps, double-tap skips) otherwise presented frames from superseded
  targets and restarted the arrival-then-advance detection on each one, leaving
  the published status trailing reality until the fallback probe corrected it.
  The window is flushed by `pause()` (so a seek made just before pausing still
  lands) and cancelled by `prepare()`/`stop()`/`release()`. While a seek is
  outstanding — either inside the coalescing window or issued but not yet
  arrived — the published position is the **requested target**, not the native
  clock: that clock still reads the pre-seek position, and letting it through
  made the seek bar travel target -> old position -> target on every skip. The
  target is published as soon as the seek is requested, so the bar moves once
  and stays.
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
  native media, then serialize the potentially blocking `MediaPlayer.stop()` and
  media assignment on the platform IO dispatcher. The existing release ordering
  showed that detachment must precede native teardown, but detachment alone did
  not protect `prepare()` while its stop remained on Main. Prepare completion is
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
- Android LibVLC's end-of-stream decision is owned by the pure commonMain kernel
  `resolveVlcTerminalStatus` (+ `hasVlcPlaybackProgressed`), never by live
  native reads: at `EndReached` libvlc's clock is already stopped and reports
  -1.
  - libvlc emits `Stopped` immediately after `EndReached`. That trailing
    `Stopped` must **preserve** a `Completed` status; mapping it to `Buffering`
    (as the code once did while the play intent stood) cancelled the Up
    Next countdown keyed on `Completed` and stalled the queue one event later.
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
- Android LibVLC's media listener belongs to the assigned native media
  generation. `prepare()` detaches the old listener before generation advance
  and old-media teardown, assigns and publishes the replacement, installs a
  listener capturing the replacement generation, and only then attaches any
  subtitle slave. `stop()` and `release()` likewise detach before generation
  advance and teardown. Events are handled directly on LibVLC's documented
  main-thread callback; stale captured generations are dropped. A genuine error
  while paused becomes `Failed`, while post-stop/detached-generation errors
  cannot overwrite `Idle` or a replacement session. Native events from old media
  delivered to a newly installed listener remain a device-validation residual.
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

## Crash Report Scrubbing

- Never send auth tokens, request headers, server URLs, media paths, media
  titles, usernames, local file paths, or raw Jellyfin responses to crash
  reporting.
- No crash or playback diagnostic data is ever uploaded automatically or sent
  to a third-party service. Diagnostics leave the device only through the
  explicit, user-triggered Send Client Logs upload.
- Diagnostics upload is server-upload-only; there is no local export path. The
  export path was deleted rather than fixed because the platform share intent
  no-ops on the TV platform where playback bugs actually get reported, so the
  button silently did nothing — worse than absent. The Send Client Logs action
  posts the structured diagnostics report plus bounded, `LogScrubber`-admitted
  `LogBufferStore` history to the connected Jellyfin server's
  `/ClientLog/Document` endpoint. Raw native-player files and free-form excerpts
  never enter this payload; a server that disallows
  client logs surfaces `UploadDisallowed` rather than failing silently. An
  upload proceeds even with an empty safe history, carrying the capability and
  failure snapshot alone.
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

## Downloads And Offline

This section is the authoritative data, transfer, storage, and local-playback
contract for Downloads. Secondary architecture and UI guides link here rather
than restating it.

### Scope and identity

- Downloads support individual Movies and Episodes on Android mobile, Android
  TV, iOS, and JVM desktop. tvOS has no Downloads runtime binding, screen,
  scheduler, artifact-store path, validation obligation, or feature promise;
  unavoidable shared model or generated Room output does not imply support.
- `DownloadId` and `DownloadArtifactKey` are opaque device-local identities.
  The one retained-download business identity is
  `(serverId, userId, itemId, mediaSourceId)`: enqueueing the same identity
  returns its existing record instead of creating a second copy. UI/player
  routes carry only the opaque download identity, never a raw server URL,
  credential, `Session`, or absolute artifact path. Package checkpoint
  manifests and diagnostics omit account/media/download identity as well as
  tokens, remote URLs, and paths; only the trusted resolver may turn an opaque
  artifact key into a lease-owned controller resource.
- Downloads are account-owned. Lists and commands expose only the current
  account's records. Device-wide usage may identify that account's physical
  bytes but represents every other account through one aggregate physical-byte
  count, never identities or titles. Switching accounts checkpoints active work
  and retains every artifact; only the current account is eligible to claim
  queued work.
- The effective Downloads permission is carried by the authenticated `Session`
  and `StoredSession` projection. During the temporary disablement, password and
  Quick Connect authentication deliberately project
  `EnableContentDownloading` to `false` even when the raw `UserPolicyDto` says
  `true`; the raw DTO and unrelated policy fields, including subtitle
  management, remain unchanged. Missing stored fields default to `false`, and
  no policy refresh or synchronization flow is introduced. A future re-enable
  must revalidate the remote policy before restoring the dormant `true` path.

### Admission, quality, and artifacts

- Every new request starts at `Original`; Downloads has no Auto choice, custom
  bitrate, or remembered/default download quality. A user may instead choose a
  finite canonical Fixed rung for that request.
- Admission first fails closed unless there is an authenticated session whose
  effective session permission enables content downloading. This client-side
  permission gate runs before current-account matching, account-boundary lease
  acquisition, API preflight, enqueue, or platform wake work; the temporary
  `false` projection therefore rejects before any downstream work. Once a
  request passes that gate, Jellyfin current-user account, raw policy, item, and
  exact-source facts are revalidated during admitted preflight and again
  immediately before every start or resume. The effective session permission is
  a separate client-side gate, not a substitute for those remote facts. The
  Jellyfin static stream route is an exact-source byte route, not server-side
  proof of the user's download permission; JellyScope supplies that policy
  boundary. The final admission result and every bounded durable attempt commit
  must still match the captured account-boundary work lease; streaming body I/O
  never holds the account mutation gate.
- Original downloads the exact selected static source. Preflight sends a
  one-byte Range request and accepts only a truthful `206 Partial Content` with
  a complete `Content-Range` total and `Last-Modified` validator. Start and
  resume use Range plus `If-Range`; a `200` fallback, changed total/validator,
  invalid range, missing selected track, or changed source fails as
  `SourceChanged` instead of appending incompatible bytes. The resulting private
  artifact preserves all embedded tracks from the source, and its snapshot
  remembers the selected audio/subtitle intent for offline playback.
- Original localizes one selected external text subtitle into the same private
  package, whether it came from a bounded Jellyfin subtitle response or an
  installed local OpenSubtitles asset. It cannot copy an external bitmap
  subtitle: the user must explicitly continue without it or choose a compatible
  embedded text subtitle for confirmed Fixed burn-in. Manual download deletion
  never deletes the separately installed subtitle asset.
- Fixed always asks Jellyfin to encode the exact selected source to the chosen
  canonical rung as finite HLS-TS VOD with H.264 video and AAC audio. Direct
  play, direct stream, and audio/video stream copy are disabled. The package
  contains exactly the selected audio track, or the source default/fallback
  audio when none was selected. Subtitles are either Off or one compatible
  embedded text track after explicit confirmation that it will be permanently
  burned into the video; Fixed never writes a selectable subtitle sidecar. Its
  bitrate-times-duration plus ten-percent admission estimate is not a promised
  final size or per-record limit.
- The Fixed localizer accepts only one bounded relative master variant and a
  finite relative `.ts` media playlist with `PLAYLIST-TYPE:VOD` and `ENDLIST`.
  It rewrites the completed package to deterministic local relative names and
  rejects encryption/key, map/init-segment, absolute or cross-origin resource,
  traversal, live/low-latency HLS, and every other unverified tag or resource
  shape. Every admitted Fixed preflight/transfer exit attempts the exact active-
  encoding cleanup; cleanup failure does not replace the authoritative transfer
  outcome.

### Queue, quota, and recovery

- One device-global FIFO sequence and one device-global active slot cover every
  account. Among the current account's eligible rows, the oldest queued row is
  the only claim candidate; a quota-blocked head blocks later rows for that
  account. Pause checkpoints the item and requires explicit Resume. Cancel
  removes the record and partial artifact; Retry increments the attempt
  generation before reusing a failed record.
- The user must configure a device-wide hard allocation in positive whole GiB,
  with a 1 GiB minimum. Admission and every durable transfer checkpoint account
  for completed and partial physical bytes plus outstanding reservations, and
  retain the Finalizing reservation until Completed commits. The safe maximum
  also preserves 1 GiB of filesystem free space. Lowering the allocation below
  committed/reserved use shows over-allocation and blocks new work; JellyScope
  never evicts or automatically deletes another download. Before a writer can
  exceed its reservation, it extends that reservation transactionally under
  both limits or stops at the next bounded pre-write boundary as
  `BlockedByQuota`; an underestimated admission estimate is not a per-item cap.
- Android uses exactly one OS execution family: API 34+ user-initiated data
  transfer work, or API 33-and-lower foreground WorkManager work. A vanished
  API 34+ job is paused for explicit Resume rather than silently restarted after
  a possible Task Manager stop. iOS and JVM desktop transfer only while the app
  is active/open; suspension or graceful exit checkpoints the active attempt
  back to the runnable queue, and the next active launch recovers it. Neither
  platform promises a background daemon or closed-app transfer.
- Original byte checkpoints and Fixed package checkpoints are generation-bound.
  Fixed resume re-fetches and authenticates the finite playlists, then requires
  the same normalized variant, media sequence, and segment identity. Its
  checkpoint persists only relative local part names, lengths, and completion
  facts; completed parts may be retained, an incomplete segment restarts, and a
  remote-shape mismatch fails instead of persisting a token-bearing URL.
  Recovery cancels duplicate/stale native work before reassociation, validates
  staging facts before atomic promotion, and reconciles Finalizing without
  marking an incomplete/corrupt artifact Completed or starting a duplicate
  transfer. Missing/corrupt completed artifacts fail visibly and remain
  explicitly deletable rather than being trusted by metadata alone.
- Download metadata uses an isolated `DownloadDatabase`, separate from the
  ordinary refetchable cache. Database files, journals, checkpoints, staging,
  and completed artifacts share Android no-backup storage or one iOS Application
  Support subtree excluded from backup; JVM keeps them under one private app-data
  subtree. Mobile restore therefore cannot recreate completed rows without their
  media, and the device-local allocation returns unconfigured with that subtree.

### Offline playback, progress, and removal

- Local playback is entered only from the explicit Download Play action. Normal
  remote Play never silently substitutes a local copy. The offline branch reads
  a persisted snapshot before any detail, PlaybackInfo, image, trickplay,
  segment, or autoplay request, and admits only a current-account, Completed,
  current-generation artifact whose exact package is complete.
- Controllers receive only a generation-bound `OfflineArtifactRef` and must
  acquire a project-owned artifact lease before resolving a local resource.
  Delete and account cleanup return `ArtifactInUse` while that generation is
  leased. Original preserves the normally resolved platform backend. On iOS
  only, `LocalHlsPackage` requires VLCKit for that playback session without
  changing the stored backend preference; missing, wrong, or failed VLCKit
  returns `OfflinePlayerUnavailable`, never falls back to AVPlayer, and retains
  the artifact.
- Offline playback durably coalesces the latest local resume position onto the
  download record first. Only a genuine controller `Completed` state marks the
  record watched; an ordinary stop or near-end position cannot. When the same
  account is currently online, the existing Jellyfin Start/Progress/Stopped
  calls may also run and failures remain best effort. There is deliberately no
  reachability monitor, outbox, delayed synchronization,
  server-progress overlay, cross-device conflict claim, or guarantee that local
  progress ever reaches Jellyfin.
- Manual Cancel/Delete never marks the server item unplayed and never rewrites
  Jellyfin watch history. If the server item is deleted after completion, the
  verified local artifact and snapshot remain usable; there is no periodic
  reconciliation. If the item/source disappears before completion, the next
  admission or resume fails visibly.
- Account removal and full logout quiesce affected attempts, show the exact
  affected record count and bytes, and require explicit permanent-deletion
  confirmation. The authorization is bound to that quiesced membership
  snapshot, so membership change causes a stale-confirmation re-prompt. A
  durable removal operation is written before credential deletion and replayed
  before session restore until both account absence and artifact/row cleanup are
  verified. Ordinary account switch uses no deletion path.

## Date/Time

- Use `kotlinx-datetime` or `kotlin.time.Clock.System.now()` for time.
- Avoid raw day-millis literals such as `86400000L`; use date/time utilities or
  named constants.
- Keep server UTC values and local display conversion at explicit boundaries.

## Shared Player UX Contract

These are regression rules for the shared player used by Android mobile, iOS,
and desktop. Android TV player-specific behavior remains in
`tv-ux-behaviors.md`. Platform tags: **[all]** shared, **[touch]** mobile plus
desktop pointer, **[ios]** iOS, and **[mobile]** Android mobile plus iOS phones.

### Controls overlay

- **[all]** Controls auto-hide after 4s while playing. Every control, seek,
  picker, gesture, pointer-move, or controls-scroll interaction resets the
  timer. Loading, buffering, and paused transitions reveal controls. BACK
  dismisses a visible picker first, then visible controls even while paused; a
  subsequent BACK leaves the player.
- **[all]** Only POINTER-device movement reveals a hidden overlay; touch
  movement merely keeps an already-visible overlay alive. A revealing touch made
  the overlay appear during the Android back gesture's own touch stream, so BACK
  always resolved to "hide the controls" and the player could never be dismissed
  under gesture navigation. It also raced the tap toggle, showing the controls
  on finger-down and hiding them on lift. Keep the reveal gated on pointer type.
- **[touch]** A centre tap toggles the controls immediately — the centre zone
  has no double-tap gesture, so it never needed to wait out the double-tap
  window — and a second centre tap inside that window is swallowed so the
  overlay cannot flicker. Side taps keep the delay because a second tap there
  seeks. The decision is the pure `playerTapAction` policy.
- **[touch]** The edges where the player ignores its own taps and swipes are the
  existing screen-size ratios OR the device's real `WindowInsets.systemGestures`
  insets, whichever is larger, so back-gesture and home-indicator bands are
  honored as the device reports them. On desktop those insets are zero; on iOS
  they are the root view's layout margins, which the ratios already exceed at
  realistic sizes.
- **[all]** Buffering shows a plain spinner without a scrim. Controllers report
  seek stalls as Buffering; desktop does so immediately after `seekTo` and while
  mpv reports `seeking`.
- **[touch]** Double-tap left/right seeks and long-press temporarily boosts
  speed, each with a transient HUD. Vertical swipes adjust brightness/volume on
  **[android]** only — iOS and desktop install a no-op gesture controller whose
  brightness/volume updates return no value, and the shared gesture code
  silently skips the HUD when there is no value to show, so the swipe is inert
  rather than broken.
- **[desktop]** Window-level capability bridges deliver Space, Left/Right, Esc,
  fullscreen double-click, and cursor hide/restore even when inner controls own
  focus. While the player is fullscreen, pointer inactivity hides the cursor
  after the configured delay regardless of play/pause state; pointer movement
  reveals it and restarts the delay. Pickers keep the cursor visible. The root
  focus shortcut path is used only without the bridge. The desktop cursor bridge
  must apply visibility to the embedded Compose AWT component tree, not only the
  outer window, because child components can own the effective pointer cursor.
  On macOS, controls are composed only in the parent scene. A
  `BlendMode.Clear` cutout exposes the native video through that scene, and a
  1dp `SwingPanel` supplies only the AWT peer anchor while Compose layout drives
  the real native extent. mpv and LibVLC views stay below Compose through
  negative layer `zPosition` and return nil from AppKit `hitTest:`, leaving
  hover, click, double-click, and control hit testing on the normal
  AWT-to-Compose path. mpv uses OpenGL swap interval zero so presentation does
  not block its render executor on display refresh.
- **[all]** Picker sheets are bottom-centered and capped on wide windows. Use a
  dark flat-row panel with title divider and selected checkmarks, not full-width
  chip grids.
- **[all]** Up Next and the bottom controls share one bottom layout stack. When
  controls are visible, Up Next sits above their actual composed height; when
  controls are hidden, it returns to the bottom safe area.
- **[all]** Up Next can be previewed during the end window, but automatic
  countdown and queue advancement begin only after the current item reports
  `Completed`. Not Now dismisses only the Up Next card (and cancels its
  countdown for that item); the player stays open and BACK remains the exit
  path. The shared contract is only an immutable dismissal identity derived
  from the target item, queue index, and autoplay countdown key; shared and TV
  surfaces retain their own local mutable dismissal/focus state. A new target,
  queue position, or playback generation changes the identity and re-arms the
  card, superseding the earlier stop-and-dismiss rule.
- **[all]** Hide Chapters when there are none. The selected chapter is the last
  timestamp not after the current position; before the first timestamp none is
  selected. An open chapter picker reads the live playback position so selection
  advances across chapter timestamps without reopening the picker.

### Seek bar

- **[all]** Taps/scrubs commit on release (`onValueChangeFinished`), not
  continuously.
- **[all]** Draw buffered progress and show a trickplay thumbnail at the scrub
  target when available.

### Hold-to-seek (TV D-pad/media keys, desktop keyboard)

- Held seek keys accumulate a UI-local **pending target** (`HoldSeekAggregator`)
  instead of committing per keypress: the initial tap advances by 10 s and
  every second repeat advances the target with a duration-scaled step curve
  (base 10 s until the ramp threshold, then geometric growth capped by content
  duration; unknown duration never accelerates), while skipped repeats remain
  activity for the watchdog. The seek bar, position label, and trickplay
  thumbnail follow the pending target during the hold. Exactly one `seekTo`
  commits on key-up, producing one `TimeUpdate` report.
- Timing lives in `HoldSeekTimers`: a two-stage inactivity watchdog (600 ms
  grace before the first key repeat — Android's first repeat arrives at ~400 ms
  — then 300 ms of repeat silence) final-commits lost key-ups and terminates the
  session (no cadence tick can follow); a session-keyed 2 s cadence commits
  mid-hold catch-ups with the ramp retained and the session re-anchored at the
  committed target.
- Direction reversal rebases from the pending target and resets the ramp.
  Transport keys (play/pause/SELECT/Space) commit the pending target before
  acting; BACK/Escape cancels it without seeking.
- Every hold session is **item-bound**: `HoldSeekController` captures the
  published `playbackItemId` (read synchronously from the ViewModel state, not
  the composed snapshot, which lags a frame) when the session starts, refuses to
  start while identity is unstable (null), and drops any final, watchdog, or
  cadence commit whose identity no longer matches — so a key-up or watchdog
  racing a queue switch can never seek the replacement item.
  `Content.playbackItemId` goes **null synchronously in `startQueueSwitch`**
  before planning suspends; only the playback-start `installPlan` restores it
  (`stabilizesQueueSwitch = true`) — a replan re-prepares the same item and
  never stabilizes switch identity, and `startQueueSwitch` cancels any in-flight
  `replanJob` so a stale quality/track plan cannot install mid-switch. Leaving
  `Content` or disposing the player cancels the pending state too.
- **[desktop]** Pointer scrubbing owns the pending state: an active slider press
  (`pointerScrubActive`, hoisted from `SeekSlider` press/release/ cancel
  interactions) makes keyboard seek keys inert, and a pointer press mid-hold
  cancels the keyboard session without committing. The window key bridge reveals
  and keeps controls alive from the first LEFT/RIGHT key-down so the pending
  target is visible during the hold.

### Segment skip policies

- Each known media-segment type (Intro, Outro, Recap, Preview, Commercial) has a
  per-server `SegmentSkipPolicy` in `PlaybackPreferences`
  (`AutoSkip`/`Ask`/`Ignore`, default Ask; unknown persisted names fall back to
  Ask). Unknown segment types are always Ignore. Preferences resolve once per
  playback start; mid-session settings changes apply from the next start.
- `Ask` surfaces the segment through the derived `Content.skipPromptSegment`;
  both players render the Skip button from it while `currentSegment` remains the
  unfiltered truth (the Outro-based Up Next window logic is unaffected by
  policy).
- `AutoSkip` seeks in-item to the segment end through the normal `seekTo` path,
  **once per segment identity (type+start+end) per item playback session**, only
  while `Playing`: resuming or seeking into an auto segment skips it once;
  seeking back into an already-skipped segment never re-yanks; the session set
  clears on each playback start (including queue advancement). Auto-skip never
  calls `playNext`, never opens Up Next early, and cannot bypass the Still
  Watching gate — completion always arrives through the normal path.

### Quality, tracks, and subtitles

- **[all]** Quality policy is typed: `Auto`, `Original`, or `Fixed(exact
  bitrate)`. Resolution order is current-playback choice, VLC-family default
  when a VLC backend is active, then general per-server default. Auto and
  Original map to `NoClientLimit`, which encodes the
  `Int.MAX_VALUE` protocol sentinel in the request/device profile but omits the
  transcoding-profile cap and `VideoBitrate` condition. Fixed uses an exact
  user constraint. Runtime Auto recovery uses `AutoSessionLimit`, which is
  never persisted. The UI never displays the sentinel as a quality value.
- **[all]** The player quality picker keeps **Use playback default** and
  explicit **Auto** as separate rows even when they resolve to the same policy:
  the first clears the current-playback choice and displays a second line naming
  the effective value and its general/VLC settings source; the second selects
  Auto for this playback and is the only Auto row that authorizes automatic
  quality lowering from health evidence. An inherited Auto default instead
  presents manual in-player choices. Their pure lazy-list identities are therefore distinct: the
  inherited-default action uses a dedicated sentinel, while explicit Auto,
  Original, canonical Fixed, and off-ladder Custom Fixed rows derive identity
  from mode and bitrate. This identity does not change row ordering, selection,
  replanning or picker dismissal behavior. No player quality row persists by
  asset; a fresh playback starts from settings again.
- **[all]** A user-selected Fixed quality rung is an **absolute resolution cap**, not
  just a bitrate cap. Rungs carry real dimensions; the planner derives the bound
  centrally by mapping the request's bitrate back to its rung
  (`qualityRungForBitrate`), so no caller can opt out and no signature carries
  dimensions. The bound travels on `PlaybackInfoRequestPolicy` into the device
  profile's per-codec `Width`/`Height` conditions and the transcode-URL rewrite.
  The effective bound everywhere is the **tighter** of the rung cap and the
  device decode ceiling, reconciled in one place
  (`reconcileVideoResolutionBounds`). Auto and Original carry no quality-rung
  bound and remain limited only by authoritative decoder/user-device facts.
  An Auto-session recovery uses the next lower canonical rung and therefore
  carries that rung's dimensions. The optional VLC-family setting is not an
  automatic cap or second request: it resolves as the corresponding Fixed
  default before the initial request and uses the same canonical-rung semantics.
  A source whose bitrate
  fits a Fixed rung but whose resolution exceeds it may transcode; uncapped
  Auto/Original do not add that quality restriction.
- **[all]** Every path that hands source video to a decoder unchanged —
  server-approved DirectPlay, server-approved DirectStream, and the local
  PlaybackInfo-failure fallback — passes one planner preflight against that
  reconciled bound (box, frame area, and throughput). Refusal recovers through a
  bounded sequence according to policy: a transcode already in the response,
  else at most one backend-preserving re-request with DirectPlay and
  DirectStream disabled for Auto or eligible Fixed, else a real error/action
  notice. Original never authorizes that automatic stream-changing request. An
  unknown source codec or missing dimensions refuse only the
  copy (the transcode outputs a declared, condition-bounded codec); a missing
  frame rate under a finite throughput ceiling fails closed even after recovery,
  because no profile condition can express throughput — the recovery re-request
  pins `MaxFramerate`, but that pin is not yet probe-verified against the
  server (see Verifying server behavior above); until a probe confirms the
  server honours `MaxFramerate`, the fail-closed refusal stands.
- **[all]** Missing stream metadata is borrowed from item detail only within
  the same media-source version. The planner falls back to the response's
  first media source when the requested id is absent, and detail streams
  describe the requested version — an unconditional borrow could fill a 60fps
  alternate's missing frame rate with 24fps and let an unsafe copy through the
  preflight. A mismatched source keeps its own metadata and fails closed on
  what it lacks.
- **[all]** Startup quality resolves a current-playback choice first, then the
  optional VLC-family Fixed default for a VLC backend, then the server-scoped
  general default. Player choices and Auto recovery never persist by source;
  reopening starts from settings. Diagnostics record the effective policy and
  origin, not a fake nullable-bitrate interpretation.
- **[all]** Non-direct audio changes and External/Encode subtitle transitions
  re-request PlaybackInfo at the current position. Response-approved Embed/HLS
  tracks switch locally regardless of video stream mode; local Off is immediate.
  A DirectPlay plan also retains capability-approved embedded text descriptors
  while subtitles are Off, so selecting one of those tracks switches in the
  current native session without another PlaybackInfo request or reprepare.
  Bitmap, External, HLS, unsupported, missing-descriptor, DirectStream, and
  Transcode Off-to-track selections retain the server-authoritative replan path.
- **[all]** Player-device settings expose Audio Auto/Stereo/Passthrough and HDR
  Auto/Prefer SDR separately from per-item pickers. Unsupported values remain
  visible with a reason, stored unchanged, and resolve to safe effective policy.
  Android capability refresh re-probes the active route/display.
- **[all]** Audio requires at least two real tracks. Subtitle remains available
  with one real track because Off is an additional selectable state.
- **[all]** Subtitle style is visible only for exact platform-confirmed local
  text AND a backend that can apply it
  (`PlayerController.appliesSubtitleStyle`). Burned/transcoded subtitles are
  pixels and cannot be restyled; close an invalid open subtitle/style picker.
  Android LibVLC reports the capability as false because libvlc-android 3.7.5
  exposes no subtitle text-scale API (font size is a `LibVLC` construction-time
  option), so its size control is hidden rather than inert. The ViewModel
  publishes one `subtitleStyleable` truth so the shared controls, TV overlay,
  picker auto-close, and debug row cannot disagree.
- **[all]** Requested and active subtitles are distinct. Do not mark a local row
  selected/styleable until the exact activation target is confirmed; stale
  confirmations do not activate a newer request. During re-plan, keep reporting
  the installed plan's active track until the replacement plan is installed.
  None is selected only when subtitles are actually Off.
- **[all]** Requested and installed audio are distinct. Keep the installed row
  selected while a DirectPlay switch is Pending; update it only after exact
  native activation or a response-selected DirectStream/Transcode plan.
- **[all]** Matching local activation failure keeps video alive and disables the
  failed local track or file. A Jellyfin-track failure shows the token-keyed,
  passive “Local subtitles unavailable; switching to server-rendered subtitles.”
  notice for six seconds and retries once through Encode. An app-local-file
  failure shows a passive unavailable notice and never requests ForceEncode.
  Neither notice takes focus, adds a scrim, appears in PiP, or becomes fatal.
- **[all]** External, transcoded, and burned-in transitions close the picker,
  re-plan at the current position, stop the old reporting session once, and
  preserve playing/paused intent.
- **[ios]** Embedded tracks use response descriptors mapped against Ready
  AVFoundation media-selection groups and confirm the exact selected option;
  None clears the legible group. External composition is used only for a
  compatible legible asset and unsupported sidecars remain non-fatal.
- **[Android]** Media3 confirms the selected embedded group or request-specific
  external configuration id before reporting active.
- **[desktop]** mpv pins its base subtitle font size to `38` scaled pixels,
  then applies the shared style scale. It resolves non-external `track-list`
  entries to actual selector ids, confirms embedded selection by `aid`/`sid`,
  and confirms external selection by request-specific track title.
- **[desktop]** `loadfile replace` acceptance is not subtitle readiness.
  External/local `sub-add` waits for the matching lifetime-unique playlist
  entry's start-file then file-loaded events. One background event consumer
  correlates playlist ID plus prepare generation; newer prepare, stop/release,
  matching end/load failure, or queue overflow invalidates pending work.
- **[desktop]** Every mpv `loadfile` explicitly sets its resume offset; a
  zero-start item first resets the persistent `start` option to `none`, and a
  failed reset aborts the load rather than inheriting stale state.
- **[desktop]** Content scale opens a Fit/Crop picker; Crop maps to mpv
  fill/crop.

### Transcode seeking

- **[all]** Seeking within `[transcode start, buffered-ahead]` is an in-stream
  seek. Direct-play seeks are always in-stream.
- **[ios]** Seeking outside the produced transcode window re-plans at the target
  because AVPlayer otherwise requests an unavailable HLS segment and can wedge.
  `transcodeSeekRestartsStream` gates this to AVPlayer; concurrent restarts
  share one cancellable re-plan job.

### End of playback

- **[all]** True end-of-queue emits `playbackEnded` and closes exactly once;
  mid-queue completion advances instead.
- **[all]** Episode playback without a usable explicit queue derives a
  chronological season/episode queue for Up Next. Explicit launch queues remain
  authoritative.
- **[all]** Failed/aborted playback that never started must not clear Continue
  Watching progress.

### Error and audio recovery

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
  recovery plan for the same session does not.
- **[all]** Policy-gated decoder, unsupported-media, health, and recovery
  behavior is owned by [playback architecture](playback-architecture.md#quality-semantics).
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
- **[Android]** Both Android backends declare
  `PlaybackHealthMeasurementCapabilities.BufferingAndDroppedFrames` and emit
  `DroppedFrameMeasurement` values on `PlayerController.droppedFrameMeasurements`.
  A measurement is an **occurrence with a known duration** — dropped frames, the
  interval they were measured over, and the resulting rate — not a state sample.
  It is built only through `DroppedFrameMeasurement.create`, which reuses the
  shared `droppedFrameRatePerSecond` kernel and returns null for a non-positive
  interval, a negative count, or a non-finite rate, so a half-formed measurement
  is never emitted at all. Media3 emits one per `onDroppedVideoFrames` callback
  using the `elapsedMs` it carries; LibVLC emits one per poll that yields a real
  `lostPictures` delta over a real interval, sampling only while Playing. The
  primitive is `Channel(Channel.BUFFERED).receiveAsFlow()` per
  [`architecture.md`](architecture.md): occurrences need reliable
  single-consumer handoff, and because an event cannot be re-delivered, the
  consumer needs **no novelty inference** of any kind.
- **[Android]** The policy is **accumulated qualifying measured duration**, which
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
  only for higher sustained rates than its documented threshold. No diagnostic
  field or log line is emitted for the rate.
- **[all]** A `Network` playback failure gets exactly one automatic
  position-preserving replan with the default request policy before becoming
  fatal. The one-shot flag is per item playback session (reset with the
  decoder-ladder budget at playback start and retry), never per installed plan,
  so a flapping stream cannot loop. Network failures never consume
  decoder-ladder budget; a still-down network surfaces through the replan's
  planning failure.
- **[all]** Initial DirectPlay audio mapping alone gates startup. Pending
  mapping starts its deadline only after native readiness; failure gets one
  independent DirectPlay-disabled recovery and never consumes decoder or
  subtitle fallback.
- **[Android]** A ready Media3 item held by the initial audio gate remains
  `Buffering`, not `Paused`, while play intent is active. This prevents the
  DirectPlay-disabled recovery from mistaking the gate for a user pause; an
  explicit user pause remains authoritative across the re-plan.
- **[all]** Non-fatal `audioUnavailable` keeps video playing, shows a transient
  explanation, and retains an audio-off glyph until recovery.
- **[Android]** Media3 degrades to video-only only after AudioTrack retry
  exhaustion. New items retry audio; explicit audio selection re-enables it.

### iOS recovery

- After seek, AVPlayer disables automatic wait-to-minimize-stalling, uses a
  small tolerance, and coalesces rapid seeks. `prepare()` restores
  wait-to-minimize-stalling to true, so the post-seek relaxation never leaks
  into the next prepared item, replan, or queue advance (retry()/replans reset
  it via prepare — intended semantics).
- `playWhenReady` reasserts play after seek/re-plan when a Ready item has rate
  0.
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
  after a newer activation. System interruptions (call/Siri/alarm) pause through
  the controller's normal `pause()` path (clearing play intent so the state poll
  cannot fight the OS) and auto-resume only when iOS reports `shouldResume` AND
  playback was playing before the interruption.
- Unified VLCKit 4 drives buffering from its typed progress callback and maps
  audio/text tracks from typed ordered lists with stable native IDs. Jellyfin
  identity remains ordinal/language/readback based; external subtitle IDs are
  excluded from embedded candidates, and requested activation is confirmed only
  by selected-track readback.
- The VLCKit controller resolves terminal events through the **shared**
  `VlcEndOfStreamEvidence`/`resolveVlcTerminalStatus` kernel, the same one the
  Android and desktop VLC backends use (see the kernel rules above). Unified
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
- The VLCKit controller publishes the **requested target** through prepare,
  deferred resume, and seek transitions. For video with play intent, it keeps
  `Buffering` until the native clock arrives near an explicit seek target, or a
  fresh-session resume is first observed at or beyond its target, then later
  advances and the displayed-picture counter proves two fresh advances; paused
  and audio-only transitions settle without video evidence. The strict hold is
  bounded: a timeout remains an explicit diagnostic outcome but releases
  `Buffering`, follows later native clock samples, and no longer excludes those
  samples from terminal progress evidence. This prevents an unavailable VLCKit
  picture counter from pinning the timeline or turning a genuine natural end
  into `Paused` for the rest of the session. Each transition has a generation-
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
  duration, then use VLCKit's completion-capable `jumpWithOffset`. A generation-
  tagged single-flight gate consumes the system completion exactly once from the
  native seek-finished callback; after matching the request token and native
  session, it refreshes the cached native clock before invoking AVKit's
  completion. The shared transition independently owns the target-pinned
  timeline, buffering state, and picture-readiness evidence, and never blocks a
  later native PiP skip once the previous native command has finished. Rejection,
  bounded deadline, stop/release, Play replacement, and source replacement still
  release the system completion safely on the iOS Main owner lane.
- VLCKit failures are classified `Network` only when the media parse failed or
  timed out before playback ever progressed; everything ambiguous stays
  `Unknown` (fallback-ladder eligible). Never guess Decoder/UnsupportedMedia.
- Apple controllers feed the shared health evaluator with native buffering,
  startup, and dropped-frame facts. VLCKit opts into the shared actionable
  guidance presentation; the banner may offer a safe next lower rung and Open
  Playback Settings, but no controller changes quality automatically. Android
  and desktop use their own source-level presentation policies (actionable on
  Android, passive on desktop); their device/runtime acceptance is still a
  separate gate. All platforms retain the same 2 s exclusion, rolling-window,
  first-signal, separate startup/post-start budget, generation-cancellation,
  and sanitized decision/session-summary rules.
- iOS and macOS share the Now Playing snapshot publication policy:
  title/series/duration/status and configured playback-speed changes push full
  metadata, while ordinary position ticks remain suppressed. A speed change in
  either direction republishes even while paused, where the exported playback
  rate remains zero. On iOS, `PlayerNowPlayingEffects` also installs
  play/pause/toggle/skip±15s/change-position commands; they hop to the main queue
  and route through ViewModel callbacks so remote seeks honor transcode-window
  restart and reporting rules. Android and non-macOS desktop actuals are no-ops.
- AV1 direct play is advertised only with hardware decode support. Unsupported
  extreme sources should fail cleanly rather than stall.
- On iOS, AVPlayer is the recommended fresh-install default. Auto remains an
  explicit user choice and may route a source to VLCKit when AVPlayer cannot
  direct play it. VLCKit is labeled beta in Settings; its Picture in Picture
  integration is experimental and seeking may close PiP or interrupt playback.
  This disclosed limitation does not change foreground playback planning or
  silently disable the user's PiP preference.
- Session restore uses the persistent Apple Keychain store. AVPlayer retains its
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
  epoch changes. Gravity and PiP-enabled presentation changes do not rebind;
  an immutable delegate retains each retired binding's epoch for any already
  queued native callback.
- Diagnostics include sanitized player/wait/buffer/error/access state and never
  include URLs, auth headers, or tokens.

### Desktop recovery and system integration

- Desktop mpv completion readiness is bound to the current prepare generation
  and replacement playlist entry. Every prepare increments the generation and
  disarms completion in one lifecycle-locked transition before `loadfile`; only
  matching `START_FILE` and `FILE_LOADED` events arm EOF for a resolved entry.
  Each poll revalidates its generation-tagged readiness observation under the
  same lock before setting `loaded`, applying selections, or publishing playback
  state. Stop, release, load failure, engine replacement, and prepare
  supersession invalidate readiness. If mpv cannot expose the replacement entry
  ID, the controller stays disarmed until it observes an explicit
  `eof-reached=false`; an unavailable/null read is not evidence that the stale
  latch cleared. It then permits a later EOF and emits one sanitized fallback
  diagnostic containing only the allowlisted reason and candidate count.

- Desktop VLC publishes audio/subtitle activation through the **shared**
  `AudioActivationConfirmation`/`SubtitleActivationConfirmation` kernels, as mpv
  does. Its VLC-specific retry loop (10 x 250 ms) still *drives* selection —
  elementary streams exist only during playback — but the state transitions and
  the single non-extending three-second deadline belong to the kernels, so
  repeated `TracksChanged` cannot restart the deadline and an exhausted attempt
  cannot overwrite a newer `Active` (exhaustion publishes through the kernels'
  still-pending guard). Mapping diagnostics use the allowlisted fixed grammar
  rather than free-form lines. A slave-attached plan target still auto-confirms
  on the `Playing` event, and `stop()`/`release()` clear both confirmations so an
  armed deadline cannot fire over a stopped session.
- Desktop playback probes use one JVM `DesktopPlaybackProbe` gate and typed
  Kermit sink across mpv, LibVLC, native surfaces, hierarchy, and input/window
  sources. The gate is enabled dynamically by **Collect diagnostic logs** or
  forced on by the compatible `jellyscope.desktop.playbackProbeLog=true` JVM
  property; changing Settings takes effect without restarting, while the
  OpenGL presentation force remains CLI-only. Event sources stay installed for
  their application/window lifetime and gate each callback before emission.
  Records contain only closed enums, booleans, bounded numbers/lists, and
  normalized technical tokens—never track labels or media identity. The
  dedicated `JellyScopePlaybackProbe` scrubber schema requires the exact fields
  for each event and rejects unknown, duplicate, malformed, credential-like,
  path/URL, or free-form values before the bounded safe-history store.
- Desktop native events and poll publications are drained by ONE serialized
  consumer (an unlimited `Channel` on the state scope). libvlc's callback thread
  only `trySend`s, so it never blocks and its arrival order is preserved, and
  the end-of-stream evidence plus the seek pin therefore have a single writer.
- mpv teardown is asynchronous on EVERY presentation, including the software
  path used by Windows and Linux, so closing the player never runs
  `mpv_terminate_destroy` on the caller thread. Native commands hold a
  refcounted engine lease taken atomically with the context read; teardown nulls
  the context under the same lock and waits (bounded) for outstanding leases
  before freeing. A lease that outlives the wait defers destruction until the
  count reaches zero — the OpenGL surface stays open and detach callbacks stay
  queued until that real destroy completes, then report `Terminated`. Permanent
  quarantine remains reserved for an undrained render executor. Moving `sub-add`
  off the lifecycle lock removes the app-side stall; `mpv_command` is still
  serialized inside libmpv, so a slow sidecar fetch can delay a concurrent
  native command.
- mpv `END_FILE reason=ERROR` for the current playlist entry surfaces as
  `Failed` (handled BEFORE the pending-subtitle early return; stale entries,
  STOP, and REDIRECT never fail playback). Classification is native-code first —
  `MPV_ERROR_LOADING_FAILED (-13)` → Network (feeds the shared one-shot retry),
  `NOTHING_TO_PLAY (-16)`/`UNKNOWN_FORMAT (-17)`/ `UNSUPPORTED (-18)` →
  UnsupportedMedia — then the keyword classifier over `mpv_error_string`. This
  is what engages the shared recovery ladder and network retry on desktop.
- Buffering derivation includes `core-idle` (gated on loaded/not-paused/not-
  completed) alongside `paused-for-cache` and `seeking`.
- `track-auto-selection=no` is a required init option: mpv never auto-picks
  tracks. On `FILE_LOADED` the controller performs deferred default selection
  (first video track's selector id → `vid`; resolved pending audio target, else
  first embedded audio → `aid`; `sid` stays off until selected). The explicit
  selection paths remain authoritative and overwrite defaults.
- Init options are split REQUIRED (abort init on failure) vs BEST_EFFORT
  (`tone-mapping=bt.2390`, `target-peak=auto` for the software render path;
  rejected options log one sanitized diagnostic and continue). A desktop-only
  Settings caption under the HDR picker (`isDesktopHdrToneMapNoticeVisible()`)
  explains mpv's software tone mapping and LibVLC's server HDR-to-SDR
  conversion; neither necessarily matches native HDR presentation.
- `DesktopDisplaySleep` (jvmMain, JNA → IOKit `IOPMAssertionCreateWithName`
  PreventUserIdleDisplaySleep) holds one idempotent assertion while status is
  Playing or Buffering, released on pause/stop and always on dispose; no-op off
  macOS. mpv cannot inhibit sleep itself (`vo=libmpv` has no window).
- In-app volume/mute (desktop only): `PlayerVolumeController` side-interface
  (commonMain contract, implemented by **both** desktop controllers —
  `MpvPlayerController` and `DesktopLibVlcPlayerController`; clamped 0-100).
  `Content.volumeControl` is null on platforms without the interface, hiding the
  desktop-style controls (mute button + adjacent slider in the desktop
  control cluster) and making ArrowUp/ArrowDown/M keys unconsumed.
- **Desktop volume persistence — the write bound lives in the store, not the
  controller.** `setVolume`/`setMuted` publish to `_volumeState` and submit to
  `DesktopPlayerVolumeStore` **immediately, with no controller-side timer**;
  identical on both backends. `JvmFileVolumeStore`'s
  `SerializedLatestValueWriter` then debounces the disk write until **500 ms of
  quiet** on an injectable **monotonic** clock (never wall-clock: a forward jump
  would fire early, a backward jump would strand the write), restarting the
  interval on every submission and always writing the *trailing* value. Rules
  that are load-bearing, not stylistic:
  - **`submit` suppresses a state equal to `latestState`** (it still assigns it).
    Without this, a held volume key at the 0/100 clamp re-submits the same value
    forever and starves the write for as long as the key is down. **A failed write
    revokes that suppression for one retry** (`lastWriteFailed`), because
    suppression means "already accounted for" and a failure means it is not —
    otherwise resubmitting the same value could never repair a failed write, and a
    cold start would restore the older one with no user-reachable fix. Do **not**
    recast this as suppressing against a "last *persisted*" value: nothing has
    persisted during the pending window, so held-key repeats would stop being
    suppressed and the starvation above comes straight back.
  - **There is exactly one writer and no flush, drain, or other direct write
    path.** That absence is what makes the single-writer invariant hold by
    construction, so adding an "urgent" path re-opens the ordering hazard the
    design removes. `Main.kt` has no close-path drain by owner decision.
  - **The engine is never a source of volume truth.** The mpv poll must not
    publish `volume`/`mute` into `_volumeState`; state flows app → engine only
    (restored onto a newly attached engine), because a native readback landing
    between a user change and a write persists the stale value.
  - **Accepted, documented loss:** a change made within 500 ms of *quitting the
    app* is lost on both desktop backends. Closing the player and staying in the
    app persists normally via the in-process `latestState`.

  This is a documented controller-owned persistence exception to the
  UseCase/Action write rule; the writer runs `NonCancellable` on IO.
- macOS Now Playing loads MediaPlayer.framework before Objective-C lookup and
  resolves/dereferences its exported `MPMediaItemPropertyTitle`,
  `MPMediaItemPropertyArtist`, `MPMediaItemPropertyPlaybackDuration`,
  `MPNowPlayingInfoPropertyElapsedPlaybackTime`, and
  `MPNowPlayingInfoPropertyPlaybackRate` globals; the publication dictionary
  uses only those framework-owned key objects. The bridge retains its
  per-signature `objc_msgSend` bindings and main-queue publication through the
  `_dispatch_main_q` global symbol (`dispatch_get_main_queue` is a macro).
  Command-target registration remains process-lifetime, while per-session
  gating and ViewModel command routing preserve transcode-seek rules. Load,
  install, asynchronous publish, and clear are separate failure boundaries;
  each degrades to no-op playback behavior and emits only the closed event plus
  sanitized exception class through `formatSafeFailureDiagnostic`, never a
  message, cause, or stack.

### tvOS system player

- The tvOS shell reuses the Apple-family AVPlayer controller
  (`AppleAVPlayerController`, `appleMain`) behind the shared `PlayerController`
  contract; `TvPlaybackSessionPresenter` (shared-tvos) owns plan -> prepare ->
  play, executes `PlaybackSessionRecoveryPolicy` decisions (including the
  one-shot runtime network retry and typed activation fallbacks), owns
  transcode-seek restart, and retains the full ordered reporting contract above.
- `TvPlaybackSessionPresenter.state` retains every raw internal position and
  buffer update for reporting, segment/up-next decisions, and other Kotlin
  consumers. Its Swift watch projection suppresses only changes limited to
  those two clock fields. Any semantic boundary still emits the complete
  current state, including the latest position and buffer values.
- Playback UI is the system `AVPlayerViewController`. Its transport drives
  AVPlayer directly (the polling controller still observes pause/play edges for
  reporting), so free scrubbing must be disabled for transcode plans via
  `requiresLinearPlayback` — a system-UI seek outside the produced window would
  bypass the Kotlin transcode-restart path and wedge AVPlayer.
- Track/quality selection rides `transportBarCustomMenuItems` over the shared
  option builders (`audioOptions`/`subtitleOptions`/`qualityOptions`) with the
  PlayerViewModel local-switch-vs-replan rules and durable subtitle intent
  (stored intent validated against options, invalid entries deleted — the intent
  store is device-local — then preferred language, default track, Off). Menus
  render confirmed activation truth, never pending requests, and rebuild only on
  a menu-configuration key (plan epoch + track lists + confirmed selections +
  bitrate), never per position tick. Settings and the in-player menu share the
  same quality ladder so defaults always match a rung.
- Chapters render as `AVNavigationMarkersGroup` on the player item; segment
  skipping honors the per-type `SegmentSkipPolicy` (`contextualActions` for Ask,
  presenter-driven once-per-item auto-skip while Playing, ordered after the
  Start report); next-episode auto-advance requires a verified natural end
  (position within ~1.5 s of a known duration) and never publishes the Completed
  phase mid-queue — Completed is terminal-only, so the Swift dismiss handler
  can't race an advance. Item-scoped AVKit decorations re-apply on every
  plan-epoch change. A queue advance cancels any in-flight replan and clears
  track menus so stale selections cannot act on the outgoing item.
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
- Deferred to the custom overlay: trickplay scrubber thumbnails (transcodes get
  HLS I-frame previews from the system scrubber) and the in-player episode
  panel.

### Runtime playback diagnostics

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
- Runtime diagnostics are developer diagnostic data. Only the compact allowlist
  below is projected on screen; a separate bounded subset of current
  allocation, buffered-ahead, cache, output, and health facts is copied into
  correlated `PlaybackDiagnostic` health and terminal records. Related logging
  remains low-volume, sanitized fixed-label numeric data and must never include
  URLs, headers, tokens, or other identity-bearing values.
- The diagnostics snapshot renders each normalized codec's closed decode and
  finite-limit evidence. Missing or empty provenance renders `Unknown`; raw
  decoder names and any unrecognized codec value remain outside the report.
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
### Debug overlay

- The bug-report control toggles a persistent non-modal playback-info panel.
  [`ui.md`](ui.md#player-playback-notices) owns the exact compact visible-row,
  ordering, emphasis, unavailable-state, and privacy contract shared by every
  player shell. This data/playback guide owns the distinction between the compact
  projection and the richer modeled runtime, policy, health, recovery, and
  identity-bearing facts that remain available to their existing non-overlay
  consumers.

### Desktop player presentation

- The frame-ready, double-buffered software Render API bridge remains
  the Windows/Linux presentation and the automatic macOS compatibility
  fallback. It is not a high-resolution acceptance path; the remaining
  presentation limitation and candidate order are owned by the internal
  `.local/KNOWN-ISSUES.md` ledger.
- macOS mpv uses an app-owned IOSurface Render API surface by default. A private
  CGL context renders into a three-buffer IOSurface swapchain, and the render
  executor publishes each completed surface through a disabled-actions
  `CATransaction` on an app-owned sublayer. The host falls back to the
  generation-scoped `NSOpenGLView` surface when IOSurface construction or its
  FBO self-check fails, then to the software bridge when the engine cannot
  initialize. The IOSurface route requests `videotoolbox-copy`; OpenGL and
  non-macOS routes retain mpv's `auto-safe` policy. Neither native path supplies
  `wid` or `gpu-context=macvk`.
- The app-owned IOSurface removes the shared-context presentation bottleneck,
  but libmpv's OpenGL-only Render API still has a high-resolution and pointer-
  movement limitation on macOS. A Metal-capable embedded render API or a
  contained `wid` revisit remains future work; the full ruled-out list and
  candidate order are owned by the internal `.local/KNOWN-ISSUES.md` ledger.
- The earlier `wid` experiment remains rejected. Although its playback of that
  source path opened a separate full-resolution window with independent
  scaling, cropping, and focus instead of rendering inside the player region.
- Settings → Playback → Default player exposes independent macOS `mpv` and
  `LibVLC` choices for the next playback session. LibVLC is selectable
  only when the cached file-based runtime check finds a usable VLC layout; the
  check does not create a native engine, and a VLC install appearing after the
  cache is evaluated needs an app restart before the Settings listing changes.
  VLC 3 version validation remains at engine creation; an incompatible runtime
  follows the existing backend-fallback notice. Its native AppKit child surface
  does not share mpv surface contracts or the mpv OpenGL limitation, so it may
  be worth trying when continuous pointer movement degrades mpv. The Standard
  compatibility envelope in Playback Planning bounds LibVLC's independent
  very-high-resolution input risk; Unrestricted permits a deliberate unchanged
  attempt, so LibVLC is still not a universal fallback.
- macOS Apple Silicon release package tasks stage the audited VLC **3.0.23
  arm64** runtime with `scripts/prepare-desktop-vlc-bundle.sh`, validating
  `scripts/vlc-bundle/manifest-3.0.23-arm64.txt` before copying the `lib/` and
  `plugins/` layout that `DesktopVlcRuntimeDiscovery` expects. The bundled
  resource root is preferred; development runs retain the `/Applications/VLC.app`
  fallback. Sparkle/Growl UI plugins, dvdread/dvdnav/libbluray disc plugins,
  and `plugins.dat` are excluded; libvlc rescans plugins when the cache is
  absent. GPL-2.0/LGPL-2.1 texts and `ATTRIBUTION.md` ship in `licenses/`,
  with `ATTRIBUTION.md` recording the corresponding-source obligation. The existing native-dylib
  signing walk covers VLC alongside mpv, and the per-variant
  `verifyPackagedDesktopVlcBundleMain`/`verifyPackagedDesktopVlcBundleMainRelease`
  tasks check the required files in their own variant's app image.
- Desktop player hosts and native video surfaces start black. For a non-zero
  LibVLC start, the controller supplies a per-media `:start-time` input option
  before playback and retains its bounded post-start seek as a compatibility
  fallback. The native view stays transparent, playback status stays
  `Buffering`, and native audio stays suppressed until the clock is near the
  requested position and at least two pictures beyond the post-seek baseline
  have been displayed. One picture is not sufficient because cold decoder
  startup can leave that opening picture frozen while the audio clock advances.
  The shared buffering indicator therefore covers decoder startup or fallback-seek work
  instead of exposing a stale opening frame while audio advances. Once ready,
  the surface becomes visible and the controller restores the user's actual
  mute state.
- The shared controls render only in the full-player parent Compose scene above
  either native surface; there is no desktop controls window or synchronization
  lifecycle. Very-high-resolution desktop playback limits are backend-specific:
  mpv can accumulate output drops under continuous system pointer movement even
  with IOSurface presentation, while LibVLC can independently lose output
  pictures on streams beyond sustainable client capacity. Current exceptions
  and candidate work are owned by the internal `.local/KNOWN-ISSUES.md` ledger.
- Resolved playback diagnostics keep Jellyfin `Transcode reasons` separate from
  `App trigger`. The compact desktop/TV overlay shows only the server's
  `Transcode reasons`; app triggers remain in structured diagnostics, including
  client recovery and Android TV LibVLC capability preflight. Requested start
  position is retained in structured planning diagnostics so track or fallback
  replans can be checked for accidental restart.
- Optional development launch flags make this baseline repeatable for a stored
  authenticated session. `jellyscope.desktop.initialPlaybackItemId` opens the
  normal player route. `jellyscope.desktop.initialDetailItemId`, or the
  `jellyscope.desktop.deepLink` property containing the canonical
  `jellyscope://details/<itemId>` URI, opens the normal item-detail route; an
  explicit detail target wins if both a detail and player target are supplied.
  Packaged macOS apps also register `jellyscope` and handle that URI while the
  app is running through the platform open-URI callback.
  The legacy `jellyscope.desktop.playbackProbeLog=true` launch property is the
  force-on compatibility input for the same Settings-backed typed probe above;
  it does not restore the former stdout/free-form path. The Compose `hotRun` task
  must receive the same macOS interop/layer module-open arguments as normal
  desktop launch plus the development native-library path; otherwise the
  AppKit surface cannot attach and VLC remains pre-play with black video and
  suppressed audio. Native bridge or JVM-argument changes still require a fresh
  Hot Reload process because class reload cannot retrofit process launch
  options. `jellyscope.desktop.forceMpvOpenGlSurface=true` is a development-only
  presentation force that skips the macOS IOSurface attempt and exercises the
  mpv OpenGL surface; it has no planning, credential, backend-policy, or
  default-navigation effect. None of these flags changes planning, credentials,
  backend policy, or default navigation.

### Android TV display refresh-rate matching

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
  the default must not change until the pending validation gate recorded in
  `.local/KNOWN-ISSUES.md` is complete.

## Why

Rationale for rules this guide states: the choice, the reason, and the
rejected alternative. An entry here is deleted when its rule changes; a rule's
operative text lives in the body sections above, never here.

### Server contract and capability modeling

- **Discovery availability is a platform capability, not a retry state.** iOS
  intentionally declines the multicast entitlement required for UDP broadcast,
  so its unavailable discovery path prevents UDP work while preserving manual
  URLs, direct local HTTP, and restored sessions/accounts through the existing
  Local Network declarations. Rejected: a multicast entitlement, a permission
  probe, and treating Bonjour as an equivalent protocol.
- **Related shelves are priority-gated data, not a completion race.** The
  repository launches independent source requests to reduce total latency, but
  awaits them in the selected Cast-first order so visible shelf order and
  deduplication remain deterministic. A shared four-shelf cap is applied by
  each cross-platform consumer. Episode Next Up/Similar and the Series single
  flattened shelf are separate product exceptions. Rejected: progressive
  completion-order publication, a Director source job, and platform-local
  shelf limits.

- **Apple profile conditions are per-player.** AVFoundation only decodes HEVC
  in MP4/MOV when the sample-entry tag is `hvc1`/`dvh1` (parameter sets
  in-container); `hev1`/`dvhe` (in-band parameter sets) produce black screen
  with audio and no hard decoder error. VLCKit decodes `hev1` and deinterlaces;
  AVPlayer does neither and its HEVC path is bounded to 60 fps — so a shared
  Apple profile would either deny valid VLCKit playback or mis-offer sources
  AVPlayer cannot render. The tag and frame-rate bounds stay HEVC-only because
  there is no evidence for narrowing AVPlayer H.264 or AV1.
- **Denying direct play can be cheap.** When a direct-play condition fails on
  a container/tag technicality, the server typically remuxes (bitstream
  repackage) rather than fully re-encoding — so tight, correct profile
  conditions cost little.
- **HDR/DV range conditions are a 10.11+ contract.** Per-codec
  `VideoRangeType` conditions are only handled correctly by server 10.11+; the
  entire server-behavior contract is verified against 10.11 specifically, and
  any new server major requires contract re-verification, not code porting.
- **Explicit range exclusions remain small device facts, not a quirk
  framework.** The four exact Fire TV models and two demonstrated HEVC
  Dolby-Vision-plus-HDR10+ combinations are subtracted from the positive set
  because the devices can advertise decode and still render black. A global
  Dolby Vision/HDR10+ restriction, inferred model families, remote quirk
  service, and `NotEquals` wire conditions were rejected: each weakens truthful
  capability claims or leaks subtype ranges through Jellyfin's condition
  processor. Applying the same fact in local source-copy preflight prevents a
  failed PlaybackInfo request from bypassing it.
- **No SDK, wire-format obligation.** The client deliberately ships a
  hand-rolled Ktor client and DTOs with no Jellyfin SDK dependency, so server
  upgrades can never break the build at compile time — the trade-off is that
  the obligation is manual wire-format and behavior tracking against each
  server release.
- **Public system identity is authoritative across authentication variants.**
  Display metadata and authentication-result IDs vary across supported server
  responses, but `/System/Info/Public` supplies the stable identity used before
  credential persistence. Treating an auth response mismatch as cosmetic could
  bind a credential to the wrong server; requiring display fields or a duplicate
  result ID would reject otherwise compatible servers. Rejected: trusting a
  nonblank conflicting auth ID or deriving identity from optional display text.
- **Authorization parameters use one CR/LF-safe RFC 3986 wire policy.** Raw
  quoting leaves delimiter, control-character, and non-ASCII ambiguity, while
  form encoding changes spaces to `+`. One UTF-8 percent encoder for both full
  and token-only headers keeps their shapes stable without allowing any value
  to bypass normalization. Rejected: field-specific escaping and token-only raw
  interpolation.
- **Playback transport failures are mapped below presentation.** Retryability
  and the allowlisted source exception type are stable domain facts used by the
  planner, while client exception classes are an implementation detail that may
  change with the HTTP stack. Rejected: importing `JellyfinApiException` into a
  ViewModel or discarding the existing safe diagnostic source type.
- **The portrait transcode-URL rewrite is future-proof by construction.** It
  stays correct even if a future server fixes the width-tier scaler, because
  it only ever requests dimensions already inside the decoder's limits.
- **`SubtitleStreamIndex=-1` was rejected as the strip mechanism.** The
  verified server ignores it on transcode URLs, so honesty about subtitle
  intent requires stripping the server-attached parameters instead.
- **Every track-identity canonicalization rule traces to a real defect.**
  Alias spellings, server-synthesized display titles, unknown-language
  taggings, and codec-family distinctions each caused a real activation
  timeout that replanned a healthy DirectPlay into a transcode; capability
  providers keep advertising exact wire spellings because Jellyfin negotiation
  is exact-name-sensitive, while local matching compares canonical forms.
- **Platform audio capability baselines are truthful, not conservative.**
  Hardcoded stereo ceilings transcoded every 5.1 asset for nothing; each
  platform advertises what its selected backend can actually accept. Android
  advertises DTS only with a confirmed decoder or passthrough route, and Media3
  advertises E-AC-3 from bundled FFmpeg only after the shipped runtime confirms
  that decoder. A single active-route channel count was rejected because a
  stereo PCM sink can still render multichannel encoded input after local
  decode/downmix, while passthrough is a separate route fact. Per-codec decode
  ceilings plus codec-qualified passthrough preserve both truths; the Stereo PCM
  policy mode keeps its explicit clamp.
- **Capability provenance explains a claim without changing the claim.** Decode
  support and a finite ceiling can come from different sources, so flattening
  them into one label was rejected. Closed multi-source evidence preserves a
  platform probe, bundled runtime probe, pinned engine declaration, static
  declaration, documented limit, or explicit unknown independently. Sending
  that metadata to Jellyfin was also rejected: it is diagnostic context, not a
  server negotiation field. Separate desktop snapshots prevent a later mpv or
  LibVLC correction from leaking across engines: mpv retains HDR10 wire ranges
  for its software tone mapping, while LibVLC intentionally advertises SDR-only
  ranges for server HDR-to-SDR conversion.
- **The iOS 4K30 envelope is product policy, not a per-model decoder table.**
  AVPlayer already rejects sources outside that frame box and throughput. iOS
  VLCKit now intersects its separately owned codec declaration with the same
  envelope so oversized sources transcode before native prepare under the
  default Standard setting. Unrestricted is an explicit, persistent escape
  hatch for future hardware: on iOS it bypasses only the app envelope for both
  AVPlayer and VLCKit while preserving their real compatibility facts. Copying
  AVPlayer codec filtering or hardware-probe provenance was rejected because
  VLCKit can use different decoders and pixel-conversion paths. Per-model rows
  were rejected because a sparse hardware catalog would generalize local
  validation inconsistently; a custom device-profile editor was rejected as
  unnecessary and unsafe for this bounded override; an unbounded default was
  rejected because it makes visible output failure the first compatibility
  decision.
- **The macOS LibVLC 4K60-equivalent envelope is backend-scoped product
  policy.** A clean 4K/60 path and a failing 8K/60 path justify a conservative
  Standard input boundary for that backend only; they do not establish mpv,
  Windows/Linux, codec-wide, or machine-model capability. Width/height go on
  the wire while local proportional throughput avoids inventing a constant
  60-fps limit. Reusing the existing Unrestricted escape hatch was chosen over
  a new desktop policy or custom profile editor; it removes only the marked
  envelope and leaves Original's no-stream-change promise intact.
- **Media-version choice is exact-source and session-only.** All source-owned
  fields are reprojected together from the already-fetched source, and playback
  defaults resolve through the existing exact-source launch-context use case.
  Persisting a preferred version was rejected because it adds schema and stale
  source identity for a choice that is meaningful only among the current
  response. Refetching, adding a repository/use case, or keeping separate
  composable-owned fields was rejected because the data and boundary already
  exist and partial updates can mix two versions in one frame.
- **Backend availability is domain truth from `DeviceProfileProvider`.**
  Settings previously trusted a platform actual that claimed every backend
  available, so devices without a runtime offered it and playback fell back.
  Filtering unavailable backends out of the list was rejected: it deletes the
  "unavailable on this device" row and contradicts the policy that an
  unsupported stored choice stays stored and resolves to a safe effective
  policy with a visible reason.
- **Metadata borrowing across media-source versions was bounded, not chosen
  once.** Always borrowing was rejected for the cross-version hazard (a 60fps
  alternate inheriting 24fps detail metadata); never borrowing was rejected
  because it hard-errors a playable file whose response merely omits optional
  metadata that detail carries.

### Persistence

- **Direct Security.framework ownership keeps macOS session secrets out of
  process metadata.** The JNA boundary calls `SecItem` directly, reports only a
  fixed operation name and numeric `OSStatus`, and explicitly releases every
  CoreFoundation dictionary, created value, and copied result it owns.
  `/usr/bin/security` was rejected because writes would put credentials or
  serialized sessions in child-process `argv` and expose them through process
  metadata.
- **A present session envelope is the only authority because fallback can
  resurrect credentials.** Independent account, active, and pending keys could
  expose a mixed generation after a partial write, while treating an empty
  result as absence lets stale aliases undo logout. The versioned envelope makes
  one commit authoritative, including an empty tombstone; migration commits it
  before best-effort alias cleanup, and corrupt or unsupported envelopes fail
  closed for diagnosis instead of consulting legacy state. Rejected: continued
  dual writes, delete-on-empty, and fallback from any present envelope.
- **Delayed Room settings initialization yields only to a successful write.**
  Marking a write before its DAO upsert can suppress the stored value even when
  persistence failed, and publishing first can expose a value that never became
  durable. DAO success therefore precedes both the write marker and publication.

- **Plaintext non-macOS JVM credentials are an accepted development-target
  risk, not a release storage design.** Replacing the fallback now was rejected
  because README makes no distribution or support commitment for those targets;
  recording it as open release work would contradict that scope. Any future
  support or distribution decision invalidates the acceptance and must add a
  native OS credential store before release.

- **The Room owner preserves durable state across schema 10 migrations.** The
  database registers the complete 1→2 through 9→10 migration chain, including
  data-only preference changes, so an installed alpha can reach the current
  schema without a destructive reset. A future-version downgrade driver may
  recreate only refetchable recent-search and Watch Next cache tables before
  Room validates the schema; durable playback, settings, subtitle, and local
  asset rows are preserved. Rejected: the retired clean-install-only v1
  premise, a missing historical migration chain, and whole-database
  destructive fallback.
- **Launch persistence reads fail independently and report closed outcomes.**
  A single aggregate try/catch was rejected because one corrupt or unavailable
  store would erase successful sibling values; sequential owner-local reads
  were rejected because they duplicated key construction, cancellation rules,
  defaults, and durable-store availability semantics. Concurrent child reads
  with per-field fallback preserve successful values and latency, while
  rethrowing cancellation prevents a superseded launch from becoming a
  default-valued launch. Raw keys, exception text, and media identity stay out
  of the result and diagnostics.

### Downloads and offline

- **Download authorization is an app-owned current-user boundary.** Jellyfin
  exposes user policy through an authenticated endpoint and exact-source bytes
  through a separate static route; treating success from that byte route as
  permission proof was rejected because the verified server contract does not
  enforce those facts as one operation. Revalidating the Jellyfin current-user
  account, raw policy, item, source, and Range validators during admitted
  preflight and before every start or resume fails closed when access or content
  changes without inventing a server guarantee. The effective
  `Session.enableContentDownloading` gate is checked earlier, before lease/API/
  enqueue work, and does not replace those remote revalidations.
  The temporary disablement projects the effective content-download value at the
  authentication/session boundary rather than rewriting the raw DTO or adding
  an app-wide flag, so the retained route and admission contracts remain
  dormant until a separately validated re-enable.
- **Original and Fixed are two closed artifact contracts, not one adaptive
  download mode.** Original preserves the selected source and embedded tracks;
  Fixed deliberately converts one canonical rung/audio/subtitle choice into the
  small finite HLS-TS/H.264/AAC subset JellyScope can validate and localize.
  Auto/custom quality, progressive transcode output, OS-managed HLS packages,
  and a general HLS parser were rejected because they make artifact identity,
  resumption, completeness, or offline backend behavior ambiguous. AVPlayer is
  not used for app-authored local HLS; iOS selects VLCKit for that session only
  and fails visibly when the required backend is unavailable.
- **One active transfer and one hard device allocation keep resource use
  explainable.** Parallel workers, per-account quotas, automatic eviction, and
  best-effort overage were rejected: concurrency races reservations, per-account
  limits hide a shared filesystem, and eviction can silently delete another
  account's media. One global slot, current-account eligibility, whole-GiB
  allocation, outstanding reservations, and a free-space floor make every
  admission and block visible.
- **Offline progress is local truth with an optional online side effect.** A
  reachability monitor, reporting outbox, server overlay, and conflict resolver
  were rejected because none can promise acknowledgement or define which device
  wins. Persisting local resume/watch state first keeps the downloaded artifact
  useful without a server; ordinary reporting is allowed only for the matching
  currently online account and remains explicitly best effort.
- **Account deletion is a durable cross-store saga because credentials and
  artifacts cannot commit atomically together.** Deleting downloads before the
  account, deleting credentials first without a recovery record, or trusting a
  stale dialog snapshot can each strand private media or an unrepeatable partial
  removal. Quiesced exact-count confirmation, a pre-credential durable header,
  generation-bound leases, and startup replay make the irreversible operation
  explicit and recoverable; account switching stays non-destructive.

### Quality policy

- **A quality rung caps resolution absolutely because a label must not lie.**
  Rung resolution used to be a display label the request never sent, so a low
  rung capped bitrate alone and the server transcoded at source resolution no
  device could decode. The planner derives the bound centrally so every caller
  inherits the cap with no signature change. Rejected: threading a dimension
  parameter through every caller (a future caller silently opts out), clamping
  the rung to the device ceiling only (the picker's label would stay a lie),
  persisting dimensions (schema change for a derivable value), and mapping
  automatic bitrate ceilings to rungs (content nobody asked to downscale).
- **The copy preflight never assumes a worst-case frame rate.** Assuming 60fps
  was rejected because 120fps sources exist; preflighting DirectPlay only and
  unbounded retry were rejected with it.
- **The VLC 8 Mbps seed is a stored value, not a resolution-time platform
  default.** A resolution-time default was rejected because it makes "Use
  playback default" a lie and risks persisting a default as a user choice.
- **The no-video-output threshold is backend-qualified rather than tuned
  globally.** Raising the threshold globally delays genuine detection on
  faster backends; disabling the check for the affected backend loses
  detection exactly where the symptom was reported from.
- **The health evidence window restarts instead of fully resetting.** A full
  evaluator reset was rejected because it also clears the once-per-session
  emission latches and startup semantics; resetting the one-per-item-session
  recovery budgets was rejected with it.
- **Media3 keeps the default byte target with time priority.** One aggressive
  target for every device was rejected, as was treating a warning's absence as
  proof of headroom, and wake mode as an active-stutter fix without a
  suspension reproduction; the larger regular-memory target stays an
  evidence-gated A/B candidate.
- **The dropped-frame warning carries no telemetry and changes nothing
  automatically.** Rejected: forking the player's renderer stack to lower the
  notify batch, automatic quality reduction (the user owns that choice),
  automatic A/V drift correction (the actual offset cannot be measured, and a
  guess can make it worse), and a new numeric log field for a warning already
  visible on screen.
- **The Android LibVLC capability projection stays narrow on purpose.** A
  global resolution ceiling, VP9 ban, or forced transcode was rejected as
  broader than the evidence; importing MediaCodec capability wholesale erases
  software capability; automatic backend switching changes the user's
  decision; an unbounded output re-lock loop thrashes the decoder.

### Backends and players

- **Desktop mpv subtitle clearance follows the composed bottom chrome.** mpv's
  native/default `34` scaled-pixel margin is the correct hidden-controls and PiP
  baseline, while the existing accepted controls-visible position is retained by
  adding a fixed `146` offset when normal controls or a bottom picker is active.
  The base font size stays pinned at `38` so mpv runtime updates cannot redefine
  the desktop Normal size.
  One Boolean from the already-owned controls, picker, and PiP state is sufficient;
  a preference, timer, measured geometry, media heuristic, animation, or shared
  player abstraction would create state or ownership beyond this transient
  presentation need. LibVLC remains on its native placement because its instance
  option cannot express the same proportional clearance.

- **Android TV wakefulness belongs to the mounted player route and its Activity
  window.** A nested playback-view flag was insufficient on the connected
  Chromecast and can disappear during backend replacement, while the route
  remains stable through loading, paused, picker, and dialog states. The TV
  route therefore owns the standard window flag and retains the per-view flag
  as a fallback. A process singleton, service, CPU wake lock, permission,
  Ambient Mode mutation, polling loop, and vendor branch were rejected because
  they outlive or exceed the actual player-route ownership boundary. Hardware
  acceptance remains open in `.local/KNOWN-ISSUES.md` until playback exceeds the
  configured screensaver timeout.

- **Desktop mpv completion is a generation-and-entry fact, not a raw EOF
  property.** mpv keeps `eof-reached` readable and may leave it latched while a
  reused context replaces a file. Accepting EOF before matching replacement
  lifecycle events can therefore complete the wrong item; rebuilding the
  backend or launching an external mpv process was rejected because a small
  controller-owned readiness state plus a lifecycle-locked commit closes the
  race. When the playlist entry ID is unavailable, only an explicit non-EOF
  sample proves that the outgoing latch cleared; treating a failed/null property
  read as false would recreate the same false-completion path.
- **tvOS keeps the system player.** `AVPlayerViewController` provides chapters,
  segment skipping, and track menus far more cheaply than a custom overlay.
  A custom overlay, and trickplay thumbnails with it, are deferred behind it.
- **iOS ships two players; the backend is resolved once per item.** One
  controller per session; live mid-playback switching was rejected
  (deferred). AVPlayer stays the default and Auto routes to it — including
  HDR/Dolby Vision — falling to VLCKit only when the source needs something
  AVPlayer lacks. VLCKit is iOS-only; tvOS never links it.
- **iOS VLCKit transition readiness is strict only inside its bounded window.**
  Prepare, deferred resume, and seek keep the requested target published until
  target arrival, later clock progress, and two fresh displayed-picture
  advances agree. Target arrival alone was rejected because clock/audio progress
  can precede video; a continuous whole-session cadence detector was rejected
  because advancing or intentionally dropped frames are not frozen-output
  evidence. Physical-device HLS evidence also proved that VLCKit's public
  `displayedPictures` statistic can remain zero while video is visibly advancing.
  It therefore remains useful as best-effort transition evidence but is not
  advertised to shared health policy as reliable first-video-output measurement.
  A generation-local sequence rejects stale timeout/success observations; when
  the bounded window expires, `TimedOut` remains observable but fails open to
  honest native state instead of becoming a permanent UI/end-of-stream lock.
  PiP admission retains a separate session-local content latch so marking the
  shared measurement unsupported cannot disable PiP; its timeout fallback needs
  both `hasVideoOut` and active native playback.
  A fresh Resume accepts its first serialized clock observation at or beyond the
  requested target: native playback can advance before the
  always-asynchronous owner lane samples the new session, and requiring one
  absolute in-tolerance sample left healthy output permanently pinned once that
  sample had already been skipped. Explicit seeks retain the symmetric
  tolerance because their pre-seek clock belongs to an already-running session
  and must not establish arrival.
  The active transition's bounded kind, state, native/target clock, arrival,
  later-clock, and displayed-picture facts remain available in runtime
  diagnostics but are deliberately outside the universal compact developer
  overlay owned by [`ui.md`](ui.md#player-playback-notices). Event-scoped support
  logs add the public output-existence, decoded/displayed/
  lost-picture, buffering, published-clock, progress-latch, and terminal-decision
  facts needed to reconstruct the visible outcome; they add no polling loop,
  media identity, URL, path, credential, or free-form native payload.
- **iOS VLCKit PiP follows the engine's public protocol ownership.** The
  retained drawable supplies VLCKit's PiP drawable and media-controller
  contracts, VLCKit supplies its window controller, and the surface binds the
  active prepare generation even when Compose creates it after prepare. Private
  layer traversal and a project-owned sample-buffer/AVKit controller were
  rejected because they duplicate native ownership and depend on internals.
  Relative PiP skips use VLCKit's public `jumpWithOffset(...completion:)` path,
  matching VideoLAN's own PiP integration: the completion is the engine's native
  seek-finished event, while JellyScope's transition remains the separate owner
  of UI buffering and output readiness. Three device candidates still collapsed
  PiP because JellyScope exposed the transition-pinned requested target as
  VLCKit's current PiP media time—one used native seek-finished completion, one
  completed at absolute command acceptance, and one delayed completion until
  picture readiness. AVKit's time range must instead describe the actual sample-
  buffer clock. Caching that native clock separately preserves the foreground
  target → target seek-bar contract without telling AVKit that output has already
  reached the target.
  A foreground session that never requested or entered PiP releases its window
  controller without calling native PiP stop; only a requested/started window
  needs that teardown. This keeps ordinary Player Back independent of unused
  PiP shutdown while preserving explicit and automatic PiP close behavior.
- **iOS player-layer ownership follows prepare identity, not diagnostics
  cadence.** Runtime dimensions, bandwidth, and dropped-frame snapshots can
  change many times inside one prepare. Rebinding AVPlayer/PiP from those hot
  snapshots duplicated native ownership work and could relabel a queued old
  callback with the new epoch. Exact controller/player/epoch identity plus an
  immutable delegate preserves stale-callback rejection without making gravity
  or the PiP preference part of source identity.
- **AVPlayer is the iOS default; VLCKit PiP is a disclosed beta capability.**
  Making Auto the default would implicitly route unsupported AVPlayer sources
  into a backend whose foreground compatibility is broader but whose native PiP
  seek behavior is not yet reliable. AVPlayer therefore remains the recommended
  unset/fresh-install choice, while Auto and VLCKit remain explicit choices for
  users who accept that tradeoff. Hiding VLCKit or globally disabling its PiP
  route was rejected because entry, playback, pause, and restore work on tested
  assets and the limitation is concentrated around PiP seeking.
- **mpv is gated to API 26+ by typed availability refusal, not a rebuilt
  native stack.** The pinned libmpv payload requires API 26 (AImageReader
  hwdec) and is consumed unmodified per the wrapper provenance contract; older
  devices keep the app with the default backend. Rebuilding mpv/FFmpeg for an
  older API level was rejected — it forks the entire native supply chain for
  one aging device whose validated backend is ExoPlayer.
- **Android mpv treats native option and command shapes as versioned API, not
  best-effort strings.** The engine rejected an empty singular `script` option
  and `start=` in `loadfile`'s fourth argument (that position is the playlist
  index); `sub-add` likewise treats its fourth argument as the title value, not
  a `title=` assignment. The accepted shapes and their real native readbacks are
  locked with tests. Rejected: ignoring
  negative option results, removing
  resume-at-load semantics, a tokenized-URL credential fallback, and a
  misleading hardcoded network timeout for an unclassified startup failure.
- **Android mobile platform playback uses plan truth and owner identity, not
  layout churn.** Unknown or unbounded content cannot truthfully advertise an
  in-item MediaSession seek even if a native backend accepts arbitrary seek
  calls, while queue navigation remains meaningful independently. A normally
  returning prepare is likewise not installed truth when the controller has
  already published `Failed`; treating it as installed would expose selection,
  play, or seek commands after terminal refusal. Monotonic registry/surface
  ownership prevents stale Compose release or update work from reaching a
  replacement without adding a timer or backend-specific UI path.
  Animated controls, insets, and player transitions can move or briefly
  collapse the composed surface many times while the video itself remains the
  same. Feeding those rectangles into the aspect repeatedly exhausts Android's
  aspect-change quota, so only the stable coded dimensions own aspect and the
  live rectangle remains an aspect-free transition hint. A timer or debounce
  was rejected because it makes correctness depend on animation timing, and
  backend-specific PiP handling was rejected because the platform owner already
  serves ExoPlayer, LibVLC, and mpv through one command contract.
- **Hot playback clocks are reduced at their owning boundary, not downstream.**
  Launching one Main coroutine per mpv callback and publishing both halves of a
  LibVLC callback pair made scheduler and observer work scale with native event
  rate. Throttling the tvOS presenter itself would instead make reporting and
  segment decisions stale. An mpv replacement generation alone is insufficient:
  a late outgoing callback could otherwise be retagged as replacement truth.
  Keeping hot admission closed through the retiring `END_FILE` and opening it
  only on the following `START_FILE` for the awaited replacement generation
  rejects those samples without delaying lifecycle or other immediate edges.
  Generation-bound latest-sample ownership for mpv, native-freshness ownership
  for LibVLC, and a clock-only Swift watch projection then bound ordinary work.
  Rejected: downstream debounce, arming replacement samples at command dispatch,
  ticker ownership during healthy native callbacks, and suppression of the raw
  tvOS state.
- **Raw mpv logs stay app-internal and out of uploads.** Uploading or attempting
  to redact free-form native text cannot prove that arbitrary titles, usernames,
  IDs, paths, or credentials are absent. The raw file remains useful for local
  device collection, but the user-triggered server upload uses only typed,
  allowlisted records; disabling collection deletes both retained raw files.
- **Desktop clamps foreign player-backend values instead of changing the
  shared default.** Switching the shared default to Auto would make iOS
  resolve per source and hand non-direct-playable items to the secondary
  backend — a behavior trade nobody asked for. Suppressing only the
  backend-fallback notice was rejected because it leaves the wrong backend
  feeding the PlaybackInfo device profile.
- **Retry replay is controller-owned.** Moving replay ownership into the
  ViewModel/presenter was rejected because controller-internal re-prepares
  still need local state, and a common retry helper was rejected because
  lifecycles and retained fields differ per backend.
- **The desktop OpenGL force flag exists because the seam was otherwise
  unreachable.** The normal presentation attempt is deliberately never taken
  on macOS, so consolidation work under it could never be validated; a
  multi-value presentation selector or force-software option was rejected so
  the flag cannot become presentation policy.
- **macOS Now Playing uses exported framework key objects, not matching text.**
  MediaPlayer owns the `NSString * const` identities consumed by
  `MPNowPlayingInfoCenter`; ad-hoc strings can look correct while failing
  publication. Separate load/install/publish/clear boundaries keep native
  failure observable without allowing throwable payloads into diagnostics.
- **macOS IOSurface uses VideoToolbox copy-back to avoid startup flashing.**
  Counterbalanced packaged-app testing isolated direct decode as the trigger;
  copy-back removed it without replacing the IOSurface presenter. Copy-back can
  cost memory bandwidth and power, but making OpenGL the default and adding
  codec- or asset-specific exceptions were broader than the evidence.
- **The desktop volume write bound lives in the store because a caller-side
  bound produced four defects at once** (a stale non-volatile read,
  cancel-then-submit reordering on release, a false "same value means settled"
  heuristic, and a native readback re-persisting a stale field). Rejected: a
  critical section (serializes the ordering bug), `@Volatile` alone
  (visibility, not ordering), check-then-act `isActive` guards, and a
  start-to-start throttle (which does not provide the same write coalescing as
  the debounce). A final volume adjustment may be lost when the app quits within
  roughly 500 ms; the policy deliberately has no close-path drain or second
  write path. Closing only the player is unaffected because the in-process
  `latestState` persists normally.
- **DirectPlay keeps approved embedded text descriptors while subtitles are
  Off** because treating Off as proof that no descriptor is needed forces a
  replan for a switch the native session can do locally; forcing all subtitle
  formats through the native fast path was rejected with it.

### Subtitles and OpenSubtitles

- **The OpenSubtitles consumer key is user-supplied, not app-owned.** A key
  embedded in a client binary is inherently extractable, and its quota is
  abusable against the whole install base. A project-owned or pro key would
  need OpenSubtitles
  consumer/subscription terms, quota, key rotation, revocation, and
  app-identification review before distribution.
- **A sealed `SubtitleAsset` exists instead of reusing the Jellyfin
  external-subtitle URL type** because the Jellyfin-remote type is
  credential-bearing (iOS attaches Jellyfin auth headers; desktop rejects URLs
  outside the active server) — reusing it for an OpenSubtitles/CDN URL either
  rejects a valid subtitle or leaks Jellyfin credentials cross-origin.
- **Explicit subtitle-result preference outranks release-name and provider
  popularity heuristics, but reorders instead of filtering.** A user choice is
  stronger evidence than a filename similarity or popularity signal, while
  keeping nonmatching candidates visible avoids hiding the only installable
  result. Release matching stays local and uses only the selected source
  basename; bounded normalization removes allowlisted extensions, separators,
  codec/channel aliases, and technical noise before exact/Jaccard comparison,
  with original encounter order retained for deterministic ties. Sending,
  logging, displaying, or persisting the full media path and filtering out
  nonpreferred results were rejected as unnecessary privacy and usability
  regressions.
- **One subtitle mutation actor and one startup owner preserve ordering and
  compensation because per-call locks and live observation cannot order delayed
  work.** Download, normalization, and network completion can arrive after a
  newer user intent or destructive boundary; logical tickets and barriers reject
  stale writes while the FIFO keeps file/metadata/selection order deterministic.
  Startup reconciliation settles before observation, and restored
  `UploadedUnconfirmed` work enters through one bounded boundary-snapshot pass
  instead of a self-triggering live query. CPU transforms stay on the injected
  worker while network stays outside the actor. Sync compensation restores only
  the exact prior generation still owned by its claim/order, and only the
  terminal/no-op finalization winner owns release. Idempotent absent-file
  deletion plus one fixed path-free still-present failure keeps cleanup
  retryable without leaking local identity. Rejected: independent action
  writers, direct DAO cleanup, live `UploadedUnconfirmed` admission, and a
  mutex-only approximation.
- **OpenSubtitle rows stay raw until composition, and raw bytes stay below the
  stable domain-model boundary.** Eager localization/materialization performs
  work for offscreen results, while a `ByteArray` is mutable and cannot satisfy
  the package-wide Compose stability promise. Stable provider-file keys retain
  list identity without either compromise. Rejected: eager row projection and
  annotations that assert stability without immutable structure.

### Diagnostics, logging, and privacy

- **Trust and URL sanitization are one project-owned decision.** Keeping those
  operations inline in each native adapter allowed a caller to authorize one
  URL while loading another or to retain inherited credentials on a redirect.
  A credential-free shared result was chosen so Media3 can apply it per hop and
  AVFoundation can apply the same rule to its initial assets. A custom Apple
  resource loader or proxy was rejected for this batch because it would require
  separate streaming, seeking, HLS, cancellation, and credential contracts;
  the unsupported AVFoundation redirect hook remains explicit instead.
- **Modern native authorization is one backward-compatible server contract,
  not a Jellyfin-version branch.** Jellyfin 10.11.11 and 12 RC5 accept the
  comma-free token-only Authorization value and guarded `ApiKey` spelling, so
  desktop mpv and the VLC family use those forms unconditionally. A legacy
  `X-Emby-Token`/`api_key` fallback or server toggle was rejected because it
  preserves a code path disabled by default on Jellyfin 12; the broad inbound
  query stripper remains only to sanitize stale URLs. The full MediaBrowser
  client/device header was also rejected for mpv because its comma-delimited
  option would split that value.
- **No automatic third-party crash or behavior reporting.** Local, sanitized
  diagnostics retained in the isolated bounded store leave the device only
  through an explicit user action (the server upload) were chosen instead;
  every upload path in this guide (structured report and allowlist buffer)
  exists inside that boundary. Raw native-player files never cross it.
- **Desktop probes join the typed diagnostic boundary instead of stdout.** A
  dynamic Settings gate makes Finder-launched reproductions collectable, while
  the legacy property remains a force-on development seam. Closed records plus
  per-event admission were chosen over redacting free-form lines because
  redaction cannot prove that an unknown field never carried identity.
- **The scrubber allowlist derives from the same source the formatter reads**
  because the two had silently drifted, dropping whole classes of diagnostic —
  a tag that is permitted but always dropped. Broadening the allowlist while
  leaving messages unstructured was rejected for the same reason.
- **The verbose platform-writer opt-in enables release-equivalent capture.**
  Debug builds already install the raw platform writer, but they do not
  reproduce release-only native playback behavior faithfully; the opt-in
  installs that same raw writer in release builds for controlled reproduction.
- **iOS ships no bespoke release-log mechanism.** The in-app buffer plus
  server upload is the primary path, and the existing verbose toggle already
  routes Kermit through OSLog into Apple's unified logging on release builds,
  which persists beyond the bounded safe-history store and remains collectible after the
  fact with `log collect --device`, live through Console.app, or through
  sysdiagnose when a Mac is unavailable. A dedicated OSLog writer is warranted
  only if the open privacy-redaction check in `.local/KNOWN-ISSUES.md` shows
  that unified logging elides Kermit message bodies.
- **Accepted cosmetic debt:** the Settings toggle says "logcat" on all
  platforms; on iOS it means the system log. A per-platform string would stop
  implying the toggle is Android-only.

### Player UX

- **Do not enable approximate constant-bitrate seeking without a demonstrated
  failure that requires it.** The flag trades seek accuracy for an unproven
  workaround, so the normal container/index-aware seek path remains the
  default.
- **Playback speed is a Now Playing publication edge.** A speed-only change
  alters the system transport rate even when metadata and position are stable,
  so waiting for a later metadata edge or a greater-than-five-second position
  drift publishes stale state. Paused playback still exports zero rate; the
  speed edge only ensures the snapshot is refreshed.
- **Only pointer movement reveals the overlay.** BACK exiting unconditionally
  on touch was rejected, and widening the gesture-exclusion band treats the
  symptom while costing usable area.
- **Up Next "Not now" dismisses only the card** because keeping it as a player
  exit read as data loss when the user only meant "stop counting down". A
  shared immutable item/queue/generation identity re-arms new content, while
  mutable dismissal remains local because shared and TV surfaces have
  different BACK, focus, and Still Watching behavior; one global UI state was
  rejected.
- **The resume-behavior preference was removed rather than fixed.** It only
  overrode the start position after the user pressed Resume, so detail could
  offer "Resume from a position" and start from zero — the surfaces disagreed
  by construction. Teaching detail screens to respect it was rejected (still a
  setting whose only effect is to ignore server progress), as was hiding it
  behind Advanced (keeps the contradiction).
- **Still watching stays a separate toggle from Autoplay next** because the
  prompt is a distinct safety concern; tvOS gets no toggle because it has no
  gate to disable, and an inert control is worse than its absence.
- **Inactive player controls are hidden, not disabled.** The audio-offset
  control keeps the one timing exception: an audio offset is meaningful with a
  single track (A/V sync) while a subtitle offset is meaningless with no
  subtitles.
- **Hold-to-seek velocity is halved by gating repeat advancement, not by
  halving step constants.** Halving the base step would silently change tap
  granularity; throttling at the key-event layer was rejected because the
  aggregator owns step semantics and is unit-testable.
