# Jellyfin API And Caching

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

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

## Why

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
