# JellyScope Launcher configuration plugin

This independently built Jellyfin plugin supplies one shared launcher layout.
Administrators edit its rows in the server dashboard; signed-in launcher users
receive only rows whose configured libraries they can access. App pins, login,
launcher settings and Android System settings stay local to the launcher.
There are no per-user or per-device layout overrides.

## Build and compatibility

The initial source target is **Jellyfin Server 12.3**, `Jellyfin.Controller`
**12.3.0**, and **.NET 10**. Lower 12.x versions are not qualified. With a .NET 10
SDK, run from the repository root:

```sh
dotnet build jellyfin-launcher-plugin/Jellyfin.Plugin.Launcher.csproj --configuration Release --artifacts-path .local/launcher-plugin-artifacts
```

The assembly is `Jellyfin.Plugin.Launcher.dll`; the authored dashboard page is
embedded as `Jellyfin.Plugin.Launcher.Configuration.configPage.html`. Jellyfin
supplies its own server assemblies. This project does not install or start a
server, publish a package, or provide a plugin repository feed.

Live plugin loading and installation metadata are not yet qualified. A manual
installation may require host-specific metadata such as `meta.json`; a successful
build does not establish that copying a bare DLL loads the plugin. Qualify the
installation on an explicitly authorized 12.3 server before deployment.

## Dashboard editing

Open **JellyScope Launcher** in the plugin dashboard. Add a row, edit its title,
query, library, visibility, aspect and item limit, then use Up/Down to order it.
Remove deletes a row; saving no rows intentionally leaves only the launcher's
local controls. Save validates the draft and reports success only after the
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
| `schemaVersion` | Integer `1` |
| `revision` | 64 hexadecimal characters; server SHA-256 of canonical content |
| `playbackPreference` | `jellyscope`; explicit matching-account handoff to the JellyScope TV app |
| `rows` | Ordered array of 0–12 rows with unique IDs |
| Row `id` | `[a-z0-9][a-z0-9_-]{0,63}` |
| Row `title` | 1–80 characters, without controls or line separators |
| Row `visible` | Boolean |
| Row `aspect` | `landscape`, `poster`, or `square` |
| Row `query` | `resume`, `nextUp`, `latest`, `favorites`, or `library` |
| Row `libraryId` | Required nullable field; otherwise 32 lowercase hex GUID, Jellyfin N format |
| Row `limit` | Integer 1–20 |

All listed fields are required. Unknown additional fields are ignored, but an
unknown schema, query, aspect or playback preference rejects the complete layout.
`library` requires a library ID; other queries may optionally be scoped to one.
No arbitrary URL, intent, script, filter program, credential or user override is
accepted. Defaults are Continue Watching, Next Up, Recently Added and Favorites.

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
[licensing and distribution policy](../docs/operations/licensing-and-distribution.md).
