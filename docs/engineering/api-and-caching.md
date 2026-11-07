# Jellyfin API And Caching

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

`network` owns shared authorization syntax, server-URL normalization and
credential-origin policy. `transport` owns HTTP request execution with configured
clients and the existing auth-header interface, plus shared HTTP configuration
policies. Session-backed identity belongs to `auth-session`; the existing client
factory and its engine overloads remain in `shared-core`. The launcher composes
its own clients over the same policies. `jellyfin-api` owns Jellyfin wire DTOs,
metadata-to-domain mapping, device-profile mapping and typed API failures; JSON
encoding and media-mapping tests follow that owner. The [architecture guide](architecture.md) defines the dependency and
export boundary.

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
  server entry and restored sessions/accounts remain supported. The ATS
  local-network exception covers unqualified and `.local` hostnames; use HTTPS
  for qualified custom or public hostnames. Numeric-IP HTTP behavior depends on
  the Apple OS version and requires runtime validation. Do not broaden ATS
  exceptions or substitute Bonjour or a permission probe for discovery.
- Keep auth token/header creation centralized. Password authentication sends the
  supplied password unchanged, including trailing whitespace; username and
  server-input normalization remain separate from secret handling.
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
  satisfy target-support, API-compatibility, and licensing constraints.

## Seerr requests

Seerr is an optional, separate service targeting the v3.5.1 API. `SeerrApi` in
`transport` owns its DTOs and protocol; `DefaultSeerrRepository` in `shared-core`
owns account/connection authority, request policy and verified library handoff.
Presentation consumes domain models through individual UseCases/Actions. Setup
and supported user flows are in the [Seerr guide](../user/seerr.md).

- Use the dedicated nonlogging client with redirects disabled. Jellyfin tokens,
  authorization headers, API keys and administrator impersonation never enter
  Seerr requests. Editing the destination invalidates authentication eligibility
  and clears transient credentials until that destination is validated again.
  Cookies belong to one validated origin and deployment subpath;
  rejected redirects cannot replace them. Bootstrap CSRF protection and send the
  current token on unsafe methods. Secure cookies require HTTPS; never weaken
  cookie or server protection to make HTTP work.
- `/auth/me` verifies login and restore. Its 401/403 proves expiry; an ambiguous
  403 elsewhere gets one guarded `/auth/me` probe. Valid-session permission or
  quota denial preserves the connection, and an unavailable probe remains an
  actionable verification failure. Do not replay the original mutation.
- Every operation captures account, boundary epoch and connection generation.
  Cookie persistence, content publication and library navigation revalidate
  that authority. Restricted Jellyfin accounts cannot read or request the
  external catalog. Observe live parental policy, including same-epoch changes,
  and clear external content and pending confirmations when restricted.
- Discover/search advances by the server's page metadata; My Requests advances
  by received server offsets. Deduplication does not change transport progress.
  My Requests always uses the authenticated Seerr user and hydrates metadata
  only for the bounded received page.
- Admission uses selected-quality availability, active requests, server
  capabilities, permissions and quota. Standard and 4K grants are independent;
  4K also requires server configuration. TV requests use explicit returned
  seasons; specials require the server setting. Partial seasons and active
  failed requests block another request; completed history alone does not block
  content that has since been deleted. Unknown metadata fails conservatively.
- Valid quota objects with `restricted=false` and no `limit` or `remaining`
  represent unlimited quota; explicit zero limits do likewise. Missing,
  malformed or failed quota is unavailable. A movie consumes one unit; TV
  consumes the selected eligible season count.
- Send only media type, TMDB identity, quality and explicit TV seasons. Server
  acquisition defaults and approval policy remain authoritative. Valid 201
  responses establish creation, including a valid nonempty subset of submitted
  TV seasons; retain the actual returned seasons. Empty or unrequested-season
  results remain uncertain. 202 `NoSeasonsAvailable` is non-creation.
  Lost or malformed success responses retain an uncertain intent until read
  reconciliation. Never queue, automatically retry or silently resend requests.
- Seerr's Jellyfin item ID is a candidate only. Resolve it for the active
  Jellyfin user and verify exact TMDB identity and Movie/Series kind before
  opening the normal library route. Missing or inaccessible matches stay
  unavailable; do not match titles, open external media URLs or start playback
  from an external identifier. Selecting an existing standard or 4K library copy
  does not depend on request permission or acquisition configuration. Artwork
  uses validated public TMDB URLs without
  Jellyfin image credentials.

## Collection and Person pagination

Collection and Person advance by the server response's received row count,
independently of discarded rows and duplicate display IDs. Person preserves
that count through `PersonFilmography` for shared and native tvOS presentation.
A zero-row response or reaching the reported total ends paging. Pages that add
no cards continue automatically for at most three consecutive responses; the
existing error/retry controls then resume from the consumed offset, even when
no cards are visible. Failed requests retry their unconsumed offset.

## Related shelves

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
- General Home, TV Home, the Fire TV Watch Next provider, and the generic Next
  Up ribbon request `enableResumable=false` because those surfaces already own
  or merge Continue Watching. Series-detail and other series-scoped Next Up
  requests retain the compatible resumable-inclusive default.
- Find sends one bounded 60-item request for each selected Movies, Shows, or
  Episodes category. The repository starts at most those three requests in one
  structured scope, joins them in the requested category order, de-duplicates
  by item ID, and then applies `FindProjection`. Any request failure fails the
  whole search; partial category results are never published.

## Cache

Watch Next stores outage fallback artwork separately from tagged remote posters.
A subsequent sync retries the remote source; only successful image bytes satisfy
its cache key. Remote keys are versioned to exclude older cached fallback bytes.

- Distinguish re-fetchable server cache from durable user-authored local data.
- Room downgrade recovery is cache-scoped: only refetchable recent searches and
  Watch Next rows are recreated when a higher-version database is opened by the
  current app schema described in
  [persistence and account isolation](accounts-and-persistence.md#persistence-and-account-isolation).
  Durable
  user-authored Room rows are never covered by a destructive all-table fallback.
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
- Native tvOS artwork snapshots a cache generation with its request context.
  Account-scope replacement and logout advance that generation while purging
  the cache. Completion validates the generation and cancellation under the
  same lock as insertion, so retired requests cannot repopulate the cache.
- Authenticated Coil keys use a length-qualified server/user namespace and never
  include authorization headers or access tokens. Memory keys also distinguish
  source URLs and decode variants. Disk keys contain the account namespace and
  effective image URL after any `maxWidth` lowering, without decode dimensions:
  identical URLs share encoded bytes across sizes, while different URLs remain
  isolated. Existing size-qualified disk entries age out through normal eviction.
  The logged-in composition owns the loader
  installation; the serialized runtime-cache boundary clears and shuts down the
  retired loader on platform I/O before the replacement composition explicitly
  installs a fresh Coil singleton and reuses the single disk-cache owner.
- `PlaybackSelectionMemory` and the Room-backed, server/account/item/media-
  source keyed `PlaybackSelectionStore` retain audio choice only. Player quality
  is session-only; legacy quality columns remain for schema compatibility but
  are ignored on read and cleared on write. Account/server cleanup removes
  durable audio rows, while a source change isolates them. Subtitle intent and
  timing offsets remain separate records. Subtitle-only changes update subtitle
  intent and session memory without rewriting durable audio selection.
- Selection and timing saves capture the current account and boundary epoch
  when submitted, require matching key identity, and guard the physical write
  with that captured lease. A delayed write cannot become valid again after an
  account switch away and back; immediate selection writes use the same gate.
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

## Discovery Data

- Discovery browse data remains behind `MediaRepository` and shared use-cases:
  collections use `/Items` with `includeItemTypes=BoxSet`; collection contents,
  genre browse, studio browse, and person filmography use `/Items` with scoped
  query parameters; upcoming TV uses `/Shows/Upcoming`.
- The recommendations endpoint is intentionally not assumed. Shared
  `getSuggestions()` currently implements "because you watched" by seeding from
  the first continue-watching item and fetching `/Items/{itemId}/Similar`.
  Replace this only after confirming a stable Jellyfin recommendations endpoint.

## Date/Time

- Use `kotlinx-datetime` or `kotlin.time.Clock.System.now()` for time.
- Avoid raw day-millis literals such as `86400000L`; use date/time utilities or
  named constants.
- Keep server UTC values and local display conversion at explicit boundaries.

## Media-source projection

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

- Multi-server support should not be added accidentally; treat it as an explicit
  product decision.

## Android launcher queries and artwork

See the dedicated [launcher data and artwork contract](../launcher/engineering.md#android-launcher-queries-and-artwork).

## Why

- Seerr has independent credentials, permissions and identity. Origin isolation
  prevents credential crossover; guarded publication prevents retired work from
  reaching another account. Read reconciliation avoids duplicating a request
  whose response was lost, and exact library matching keeps availability separate
  from access to the current Jellyfin server.

- **Transport progress is independent of presentation.** Dropped or repeated rows
  must not shift the next server offset or duplicate lazy keys. Bounded empty-page
  continuation preserves reachability without an unbounded request loop.
- **Purging alone does not retire artwork work.** A completed response can still
  be decoding during an account transition; atomic generation validation prevents
  it from restoring entries after the purge.

- HTTP execution and response contracts stay together below application
  orchestration. Retaining configured clients and session identity in core keeps
  credential and client lifetimes with their existing owner.

- **Encoded and decoded images have different identities.** Decode dimensions
  change memory requirements, but only a different effective URL changes the
  requested source bytes. Both caches retain the account boundary.

- **Discovery availability is a platform capability.** iOS omits the multicast
  entitlement required by UDP discovery, so marking discovery unavailable
  prevents futile network work while preserving manual URLs and restored
  sessions under the platform ATS policy. Bonjour and permission probes are not
  equivalent to the Jellyfin broadcast protocol.

- **Related shelves and Find use deterministic ordering.** Priority-gated joins,
  per-kind limits, ID de-duplication, and all-or-nothing failure prevent latency
  from reordering shelves or making failed categories look empty.

- **Public server identity is authoritative.** `/System/Info/Public` binds
  credentials before persistence; optional display/auth-result fields cannot
  replace it. Password bytes remain untouched, while authorization syntax uses
  the shared CR/LF-safe RFC 3986 encoder.

- **The API facade owns transport and wire-format drift.** Mapping transport
  failures below presentation keeps retryability stable if the HTTP stack
  changes. The hand-rolled Ktor client avoids an SDK dependency but requires
  explicit wire and behavior verification for each supported server release.

- **Source identity remains exact and session-scoped.** Version changes reproject
  source-owned fields together; persisting transient response IDs risks mixed
  state.

- Watch Next outage artwork uses a separate cache identity. Otherwise a temporary
  failure remains a successful cache hit and prevents the real image
  from being retried.
