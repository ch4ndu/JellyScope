# Accounts And Persistence

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## General settings

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

- Image quality is a device/app preference, independent of account and video
  quality. Android uses SharedPreferences, Apple apps use NSUserDefaults, and
  desktop uses the existing file preferences store. Missing or unknown values
  resolve to High; settings writes use the existing serialized preference path.

## Seerr connection lifecycle

`SeerrSessionStore` uses the existing `SecureStore` with one record per
`AccountIdentity`; it inherits the platform storage guarantees above, including
plaintext Apple-family storage. Records hold the validated service address and
session cookies, never passwords or Quick Connect secrets. Seerr protocol and
admission rules belong to [API and caching](api-and-caching.md#seerr-requests).

`DefaultSeerrRepository` registers runtime state with the account boundary
registry. Switching accounts retires the in-memory connection; returning restores
only that account's saved connection and verifies it with Seerr. Disconnect
invalidates its generation and deletes the local record; remote sign-out is best
effort. Old responses cannot restore cookies after disconnect or replacement.

`PersistentAccountStoreCleaner` receives the Seerr store explicitly so cold
logout also removes secondary credentials before clearing `logoutPending`.
Failed secondary cleanup leaves the marker for retry, even if the primary
session envelope was already cleared. Account removal deletes that account's
Seerr record; full logout clears every record.

## Persistence and account isolation

`storage` owns ordinary file/preferences stores and Room databases, schemas and
migrations. `auth-session` owns `SessionStore`, credential adapters, the account mutation
registry, session/authentication repositories and `PersistentAccountStoreCleaner`.
`shared-core` retains feature caches and composes the concrete cleanup membership.
Root composition constructs databases and includes the lower store bindings;
this module boundary does not change cleanup ordering or credential authority.

- Session persistence flows through the project `SecureStore` boundary. Android
  uses Android Keystore-backed storage under `Context.noBackupFilesDir`, so Auto
  Backup never captures the undecryptable `secure-store.bin`; unreadable or
  undecryptable data is deleted and treated as an empty store. iOS and tvOS
  deliberately persist the secure-store map as plaintext JSON in application
  `NSUserDefaults`; macOS desktop deliberately persists it as plaintext JSON at
  `~/.jellyscope/secure-store.json`. These Apple-family stores accept lower
  at-rest security to avoid application-triggered Keychain prompts. Readable
  app storage, backups, or copied files may expose Jellyfin access tokens,
  account/device/logout state, Seerr session cookies, and the OpenSubtitles API key and preference;
  passwords are not persisted. Application credentials must never be written
  to, read from, migrated from, or cleaned up through Apple Keychain or
  Security.framework. Existing Keychain entries are intentionally ignored;
  Android Keystore storage is unchanged.
- Desktop malformed JSON or invalid UTF-8 is atomically moved to a unique owner-only
  sibling before returning an empty store. Other read or quarantine failures
  propagate. Replacement files receive POSIX `0600` at creation, or a verified
  owner-only ACL while still empty, before credential bytes are written.
  Storage without either permission mechanism fails closed.
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
  hardening remains deferred. App theme, Picture-in-Picture, player clock,
  library sort, and grid sort currently persist through Android
  SharedPreferences, Apple `NSUserDefaults`, and JVM desktop JSON preferences
  under `~/.jellyscope`.
- Apple targets must not use in-memory session storage for login restore. The
  shared Apple plaintext UserDefaults store keeps session credentials across
  relaunches on iOS and tvOS; macOS uses its plaintext JSON store.
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
- Once a new account boundary is installed, a refetchable-cache cleanup failure
  still publishes the committed session and its new epoch before reporting the
  failure. Credential, subtitle-barrier, and participant failures before that
  point remain fail-closed. Removal replay attempts every target credential
  change first, then verifies durable checkpoints and actual account absence.
  Only settled credential removal permits surviving sessions to restore while
  artifact cleanup remains pending; its durable removal records remain for retry.
- Playback preferences are keyed by `AccountIdentity`; deleting one account
  deletes only that account's preference row, while server and global cleanup
  retain their broader scopes. A schema-10 server-only row migrates under one
  empty-user sentinel. The first authenticated reader for that server claims it
  transactionally; later users receive platform defaults, including Android's
  seeded VLC transcode bitrate, so two accounts can never inherit one legacy
  preference snapshot.
- Shared persistence stores expose commonMain interfaces with plain data
  classes. Android, iOS, and JVM desktop back playback preferences,
  player-backend overrides, recent searches, and watch-next sync state with Room
  KMP in `storage` using the bundled SQLite driver. iOS and JVM desktop also
  back player-device settings with the same Room database. The owner schema is
  version 11 with explicit migrations 1→2 through 10→11. If a future app
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

## Resource authentication

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
- Desktop mpv secure construction exports the JVM default trust manager's
  accepted issuers to an atomically replaced PEM file under JellyScope's
  existing application-data subtree, then requires both `tls-verify=yes` and
  that `tls-ca-file` before `mpv_initialize`. This is exactly the JVM trust set,
  not a separate macOS Keychain query. Export or mandatory-option failure fails
  typed engine construction; it never downgrades verification. The current
  account/server may explicitly allow insecure desktop certificates in
  Playback Settings; that warning-gated choice sets `tls-verify=no`, omits the
  CA file, and applies only to newly constructed desktop mpv engines.
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
  Search owners capture their account epoch at construction. Recent-history writes
  validate that captured owner under the registry gate, so delayed results cannot
  recreate removed history or adopt a later login epoch.
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
  [Kids account playback](kids-viewing.md#kids-account-playback).
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

Removal presentation owns the account/scope and phase-specific preview. Cleanup
is a separate captured obligation. Shared dismissal keeps its preview published
until release finishes, so confirmation and repeated dismissal remain admitted
while release suspends. tvOS detaches its preview before asynchronous release and
retains its own cancellation, close and failure cleanup. Neither presentation
phase replaces the committed session snapshot.

| Transition | Prerequisite and commit | Publication and failure ordering |
| --- | --- | --- |
| Login or switch | Validate admission and outgoing participant, then check caller cancellation before the non-cancellable persistence/load tail. | Advance the subtitle barrier and account epoch, publish the committed snapshot, then surface returned transition-cleanup failure; check caller cancellation after the tail. |
| Account removal | Prepare authorization under the boundary gate, then remove credentials in the non-cancellable tail. | Aggregate post-removal mutation/cleanup failures, complete the account boundary and publish the carried snapshot before surfacing aggregated failure or cancellation. |

## Independent Android launcher state

See the dedicated [launcher persistence contract](launcher.md#independent-android-launcher-state).

<a id="why"></a>
<a id="persistence-and-transfer-recovery"></a>
<a id="persistence"></a>
<a id="accounts-and-lifecycle"></a>
See persistence, credential authority and transition rules above.
