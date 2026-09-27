# TV UX Behavior Contract

This contract applies to Android TV.

## Focus-group traversal contract

- Build every D-pad screen from logical `focusGroup` regions with explicit
  entry/exit targets. Whole-screen geometric search is not the navigation model.
- Each kernel-managed group owns a stable-key `TvFocusScopeNode` and writes to
  its route's `TvFocusMemoryState`; it must not also use `focusRestorer`. Static
  non-lazy groups may use `focusRestorer` only when they do not own route memory.
- Focus groups nest: a parent remembers its child group, and that group remembers
  its item. The rail restores the selected destination; Home restores its ribbon
  and media target; Detail-family actions, tabs, and shelves restore stable items
  with explicit Play/selected/first-item fallbacks.
- `TvFocusResolver` resolves a missing or unattached item through saved semantic
  position, first child, sibling group, or the screen fallback. A failed request
  never consumes restoration. Call `requestFocusSafely()` and use its Boolean
  result; exception-free `requestFocus()` does not prove focus moved.
- Restoration waits for route entry, settled transition, group registration,
  and enough data to resolve the target. Reveal an off-screen item once, then use
  bounded attachment retries. Fixed delays and `withFrameNanos` are not readiness
  signals.
- Only an unconsumed hardware key-down delivered after the returning route is
  interactive cancels a pending restore. The initiating Back, key-up, media
  keys, modal-consumed keys, `onFocusChanged`, and temporary geometric landings
  do not. A timeout may park focus and log diagnostics but never proves absence
  or consumes the transaction.
- Route-entry IDs remain monotonic after process restoration; orphaned focus
  state is discarded. The pending restore transaction is saveable with its
  route, because a snapshot alone cannot distinguish pending from completed.
- Ribbon restore focuses an attached key without scrolling. An unattached key
  receives one centered reveal and bounded retries. Automatic bring-into-view is
  zero only during that handoff; afterward Home/Recommended use Mario centering,
  Detail/Series/Season use their dead zone, Find uses row-aware vertical visibility,
  and Person uses minimal visibility.
- A route-owned ribbon restore suppresses ordinary entry autofocus. Saved
  positions are semantic content positions, mapped separately from lazy slots
  such as Home's loading and View All tiles. Missing items remain within their
  ribbon/group fallback chain.
- Modal pickers trap focus, define initial and fallback targets, consume boundary
  directions, and restore the invoking control on Back.
- Override traversal only at logical group boundaries where geometric behavior
  conflicts with the product contract.

## Launch and Home focus

- Launch with the drawer closed. Home renders fixed slots in order: Continue
  Watching, Favorites, Next Up, Recently Added. There is no Libraries ribbon.
  Loading rows expose a real focusable placeholder so Continue Watching can
  receive initial focus. Resolved content transfers focus to the first card;
  resolved-empty rows collapse and transfer to the next visible ribbon.
- A restore is row-scoped `(row, itemId)` and is consumed only when that exact
  target receives focus. View All is a first-class `(row, viewAll)` target.
  After successful restore, moving away expires it so recomposition cannot steal
  focus back.
- Home captures initial row, item, semantic position, View All flag, and matching
  ready-restore token per route-entry identity and presentation epoch. A matching
  ready restore wins; otherwise it reads the current-entry path without observing
  live movement. Recording D-pad movement must not invalidate the route lambda.
- Back from Detail/Player restores the exact card and scroll. Attached targets
  remain in place; unattached targets receive one reveal. A removed item falls
  back to its clamped semantic position in the same ribbon, then the next
  focusable ribbon, and only then the first visible card.
- Home, Discover, Find, Favorites, and Library silently refresh on re-entry while
  retaining visible content. Errors leave content unchanged and in-flight work
  deduplicates. If refresh removes the focused card, Home uses the first card in
  that ribbon; grids use a successor then stable header fallback. Focus never
  strands on the drawer.
- Removed-item rescue requires proof from ready/terminal data while the active
  route and scope own focus. Loading or partial data cannot trigger it. Identity
  includes container and asset; another copy in another ribbon does not count as
  presence in the focused ribbon.

## Home scrolling

- The vertical shelf is a plain `Column`; all rows remain composed. One explicit
  vertical scroller pins the focused ribbon to the top with the next row peeking.
  Disable vertical focus bring-into-view so it cannot compete with the pin.
- Vertical pinning runs at about 320 ms per normal row pitch, clamped to
  160-700 ms. Horizontal ribbons use a retargeting medium-low-stiffness spring,
  center the focused card, and clamp at both ends.
- Each ribbon is a stable-key child scope and restores its card. Fresh or
  unavailable memory falls back to the first card; lazy disposal does not erase
  memory.
- Hero content changes after focus settles for 220 ms through a two-layer
  600 ms fade. Settled artwork derives a normalized ambient tint; late palette
  results are ignored. The roughly 16:9 backdrop extends beneath the first shelf
  without changing layout height. View All pins the row but does not change hero.

## Home back stack (BACK button)

- Below the first ribbon: rewind and focus the first ribbon's first card.
- On the first ribbon: open the drawer on the selected destination.
- In the drawer: exit the app.

## Discover back stack (BACK button)

- Back opens the drawer with Discover selected; it does not switch to Home.
- Back from the drawer exits.
- Landscape ribbons reserve leading inset for a 1.1x first card and always show
  a shape-matched View All tile.

## Navigation drawer contract (all screens with the drawer)

- The drawer appears on Home, Discover, Find, Favorites, Settings, enabled
  Downloads, and Library browse. Detail, Series, Season, Grid/View All,
  Collection, Person, and FilteredLibrary are nested and fill the width.
- Nested navigation uses a saveable LIFO of payload snapshots. Each Detail,
  Person, Collection, Series/Season, Grid, FilteredLibrary, and Player push
  preserves origin context; Back pops exactly one entry through multi-hop paths.
- One app-owned drawer host owns focus-driven expansion and content push. Its
  rows keep the 48 dp collapsed and 180 dp expanded footprint. Do not add
  per-screen rails.
- With download permission, order is Search, Home, Favorites, Downloads, user
  libraries, Discover, Settings; otherwise omit Downloads and its ordinary
  route. The list scrolls as one column, including Settings. Search opens Find,
  Favorites opens favorite-filtered Library, and library items keep the drawer
  while marking the current library.
- The drawer consumes real width. Content is measured at collapsed-drawer width,
  placed after the animated drawer, and clipped at the end while opening; it does
  not reflow, overlay visible content, reserve another spacer, or do local width
  math.
- Drawer entry focuses the selected destination. Before the selected Library
  item loads, Home is a focus-only fallback without becoming selected.
- Focus-dwell navigation occurs after about 500 ms and keeps focus in the drawer.
  The current destination is a no-op. Right invokes the active route's registered
  content-entry action and consumes only an accepted entry. Home enters its
  content scope, loaded Library enters a media tile, and Settings restores its
  last focused tile, defaulting to Server in the Account row. Temporary loading
  anchors are valid only without real content.
- Library drawer exit resolves the selected inner view at invocation and consumes
  Right during a temporarily rejected request, preventing stale geometric entry.
- TV has no visible Back buttons.
- Top-level browse and View All retain their ViewModels across drill-down. A
  different top-level destination resets its saved focus/scroll presentation
  while keyed ViewModels remain retained. Drawer-origin switches suppress entry
  autofocus and keep drawer focus until Right; content-origin switches may use
  destination defaults.
- A destination without a real target parks focus in its content while loading.
  It transfers to content, Retry/sort, or visible empty/error controls when ready.
  Back/Left opens the selected drawer destination; Select, Play, and other
  directions do nothing while parked.
- Series and Season share one retained `SeriesViewModel` per session/series until
  leaving the flow. Detail ViewModels are retained by route-entry ID while active
  or in history; render leases keep outgoing content alive through disposal.
  Silent metadata refresh does not replace or complete Related loading. Person,
  Collection, and Player remain route-scoped. Each shared Detail focus bridge is
  bound to its rendered entry so outgoing content cannot consume incoming focus.
- Player launch inputs stay with their rendered entry through exit animations;
  restoring the parent must not recreate the player or change its offline mode.

## Screen behavior

<a id="detail--series-screens"></a>

See [detail / series screens](tv-screen-behavior.md#detail--series-screens).

<a id="season-screen"></a>

See [season screen](tv-screen-behavior.md#season-screen).

<a id="player"></a>

See [player](tv-screen-behavior.md#player).

<a id="grid-view-all-screens"></a>

See [grid view all screens](tv-screen-behavior.md#grid-view-all-screens).

<a id="library"></a>

See [library](tv-screen-behavior.md#library).

<a id="find"></a>

See [find](tv-screen-behavior.md#find).

<a id="downloads"></a>

See [downloads](tv-screen-behavior.md#downloads).

<a id="discover-people-and-collections"></a>

See [discover people and collections](tv-screen-behavior.md#discover-people-and-collections).

<a id="settings-and-authentication"></a>

See [settings and authentication](tv-screen-behavior.md#settings-and-authentication).

<a id="watch-next"></a>

See [watch next](tv-screen-behavior.md#watch-next).

<a id="mandatory-mpv-playback-recovery"></a>

See [mandatory mpv playback recovery](tv-screen-behavior.md#mandatory-mpv-playback-recovery).

<a id="home-media-card-geometry"></a>

See [home media card geometry](tv-screen-behavior.md#home-media-card-geometry).

<a id="player-trickplay-preview"></a>

See [player trickplay preview](tv-screen-behavior.md#player-trickplay-preview).

<a id="related-shelf-focus"></a>

See [related shelf focus](tv-screen-behavior.md#related-shelf-focus).

[Screen contracts](tv-screen-behavior.md) own detail, player, library, search,
download, settings, and recovery focus behavior.

## Playback Links

- Android TV accepts `ACTION_VIEW` links shaped as
  `jellyscope://play/ITEM_ID?serverId=SERVER_ID&userId=USER_ID` on cold launch
  and through `onNewIntent`. Each identifier must contain 1–128 ASCII letters,
  digits, hyphens, or underscores. Extra or duplicate parameters are rejected.
- Links wait for session restoration, then require the signed-in server and
  user to match. Logged-out and mismatched-account links are discarded; they
  never select a server, sign in, or carry credentials or media URLs.
- From a browsing screen, an accepted link opens the ordinary player at zero
  with the current backend, quality, and track preferences. Normal authenticated
  planning and error handling apply. Back returns to the originating screen.
- Exit playback before invoking the next link. Links received while the player
  is open are discarded rather than creating overlapping native player owners.
  Consumed links are removed from the Activity intent to prevent recreation
  from replaying them. Watch Next continues to open item details.
- `TvPlaybackLink` records received, rejected, and handed-off outcomes without
  logging the URI or identifiers. A handoff is not proof of successful playback.

## Tiles And Cross-Cutting Rules

- Focus uses a cyan border and optional 1.1x scale; disabling zoom retains the
  other focus treatment. Do not add drop shadow.
- Home row identity owns card shape. Next Up, placeholders, and View All are wide
  even for an anomalous non-episode response. Continue Watching, Recently Added,
  and Favorites are 2:3 posters. Use shape-specific lazy content types. Library
  tiles remain wide. Inset progress by focus-border width; watched uses a cyan
  check and unplayed count a cyan top-end pill.
- Reused geometry comes from `TvDimens`, tuned at 1080p/320 dpi and checked on 4K.
- Focus/saveable state relies on stable app-owned keys, never unverified Compose
  `compositeKeyHash` behavior.
- Diagnostics follow the shared
  [privacy policy](diagnostics.md#diagnostics-logging-and-privacy).

## Why

- Find separates scroll axes so horizontal focus movement cannot shift the page.

- **Playback links reuse normal playback ownership.** Account-qualified item
  identifiers preserve session boundaries, settings, and normal planning.
  Requiring the previous player to close avoids a second native owner.

- Paging errors preserve pending restore and require explicit Retry because a
  failed page does not prove a target absent; search uses request identity for
  the same stale-result reason.
