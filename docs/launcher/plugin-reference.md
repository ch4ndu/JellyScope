# Launcher plugin reference

[Launcher guide](README.md#install-the-jellyfin-plugin) owns installation and
user-facing customization instructions. This page owns schema, authorization,
editor persistence and compatibility details.

## Plugin identity

Plugin ID: `29172c40-7d54-4d15-9673-a772a14d5f3c`. The assembly is
`Jellyfin.Plugin.Launcher.dll`; its embedded dashboard resource is
`Jellyfin.Plugin.Launcher.Configuration.configPage.html`. Build and runtime
compatibility limits are owned by the [installation guide](README.md#install-the-jellyfin-plugin).

## Dashboard editing

Open **JellyScope Launcher** in the plugin dashboard. Add a row, edit its title,
query, tab, library, visibility, aspect and item limit, then use Up/Down to order it.
Remove deletes a row; saving no rows intentionally leaves the launcher's
local controls and Jellyfin empty state. Save validates the draft and reports success only after the
server update succeeds. Errors preserve the draft. An unavailable configured
library remains visible as an unavailable option so it can be replaced or removed.

The editor uses the existing elevated plugin configuration API. Its custom
`GET /Launcher/Libraries` request also requires elevation and a real user session;
it returns that administrator's visible libraries and the full shared editor
layout. API-key authentication is rejected by both custom routes. Authentication
uses the dashboard's existing API client; no tokens belong in configuration.

Semantically invalid stored settings produce safe defaults and an admin warning.
Saving that recovery draft explicitly replaces the invalid settings. Malformed
XML is different: Jellyfin's base plugin loader resets and overwrites it with
defaults before this plugin can observe the failure. The plugin cannot report
that lost history. Failed updates restore the previous in-memory configuration
reference and report failure; persistence uses the host's standard XML mechanism,
not an additional crash journal. Own-controller reads return detached snapshots;
the host's generic configuration GET returns its base configuration object.

## Wire schema

`GET /Launcher/Configuration` requires an authenticated, non-API-key user session.
It accepts no user-ID override. Both custom endpoints send `Cache-Control:
no-store`; the launcher separately owns its account-scoped last-good cache.
The custom wire format is camelCase. Generic host configuration uses explicitly
PascalCase properties, and the editor maps between those forms.

| Field | Contract |
| --- | --- |
| `schemaVersion` | Integer `2`; launcher also accepts legacy `1` |
| `revision` | 64 hexadecimal characters; server SHA-256 of canonical content |
| `playbackPreference` | `jellyscope`; explicit matching-account handoff to the JellyScope TV app |
| `rows` | Ordered array of 0–12 rows with unique IDs |
| Row `id` | `[a-z0-9][a-z0-9_-]{0,63}` |
| Row `title` | 1–80 characters, without controls or line separators |
| Row `visible` | Boolean |
| Row `aspect` | `landscape`, `poster`, or `square` |
| Row `tab` | Optional `essentials` or `jellyfin`; absent/null uses the query default |
| Row `query` | `resume`, `nextUp`, `latest`, `favorites`, `library`, `latestMovies`, `latestEpisodes`, or `latestSeries` |
| Row `libraryId` | Required nullable field; otherwise 32 lowercase hex GUID, Jellyfin N format |
| Row `limit` | Integer 1–20 |

The dashboard label **Essentials** and wire value `essentials` map to the launcher
**Home** tab. The wire value is unchanged for compatibility.

All listed fields except `tab` are required. Unknown additional fields are ignored, but an
unknown schema, query, aspect or playback preference rejects the complete layout.
`library` requires a library ID; other queries may optionally be scoped to one.
No arbitrary URL, intent, script, filter program, credential or user override is
accepted. Fresh defaults are Play Next, Up Next and Favorites on Home, and Recently
Added on Jellyfin. Apps always stays local on Home. The server may assign
any media row to either tab; the 12-row limit applies across both. Existing
stored titles are preserved, including older Continue Watching/Next Up labels.

Absent/null placement maps Resume, Next Up and Favorites to Home, and
Latest/Library and the three typed Latest queries to Jellyfin. Canonical responses emit explicit placement. The optional tab field remains readable in legacy schema-1 configurations and
snapshots. Explicit unknown placement
is invalid. Moving a row changes the canonical revision but not its media-query
identity, so compatible cards remain cached. Revisions may change across plugin
versions as canonical fields expand; they are not stable cross-version IDs.

Plugin 0.2.0 accepts valid stored schema 1 or 2 and emits canonical schema 2,
preserving administrator rows and titles. The editor saves schema 2. Schema 1
permits only the five original queries; typed queries require schema 2 on both
wire and nested cache validation. Snapshot container version 2 is unchanged.
Unknown future schemas are unsupported. Install the updated launcher first:
older launchers reject all schema-2 responses and retain cached/default layouts.

`latestMovies`, `latestEpisodes` and `latestSeries` use recursive `/Items` requests
with exactly Movie, Episode or Series, DateCreated descending, the configured
limit and optional parent library. Episodes are individual items; Series orders
series creation, not the addition of episodes to an existing series. Their query
identities isolate their card caches from the original mixed `latest` query.

`library` queries recursive Movie/Episode items ordered by DateCreated descending.
`latest` uses Jellyfin's Latest endpoint, including its episode grouping. Next Up
excludes resumable episodes; Favorites adds the IsFavorite filter. Responses
omit inaccessible library-scoped rows, including their titles, and hash the
projected content. Media requests still execute with the signed-in user's token.

## Launcher behavior and remaining qualification

The launcher bounds configuration responses to 64 KiB, validates the complete
layout, and preserves a validated last-good layout on failure. Without one it
uses its four local defaults. A valid empty layout stays empty. Current user
views are intersected again on the client, including when using cached settings.
Rows removed by that projection require a successful configuration fetch to return.
Configuration-endpoint authorization failures alone do not sign the user out.

Future authorized qualification should cover admin add/reorder/disable/save,
restricted-user projection, plugin absence/offline/invalid-schema fallback,
logout/account switching, and TV focus across row changes. Live server behavior,
browser rendering, installation, and device geometry/focus are not established by
host build or source review.

Owned source uses MPL-2.0. The separately built artifact follows the repository's
[licensing and distribution policy](../operations/licensing-and-distribution.md).
