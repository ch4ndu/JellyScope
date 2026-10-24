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
- Drawer width animation is read in layout and drawing. Rail measurement and
  the underlay clip share rounded, host-clamped pixel geometry; the content slot
  uses the Row's remaining width. Settled Home/Library routes clip the shell
  ambient underlay to the logical-start rail without resizing its brush. During
  route transitions it draws in full; Settings keeps its full underlay. The
  exposed strip redraws during the 180 ms width animation.
- Home, Library, Recommended, and Collection draw their ambient tint and overlay
  from one 700 ms animation. Preserve both gradients in order, including overlay
  shading before a color arrives. The shell rail retains its separate tint
  without that overlay, so its existing tint seam remains.
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

- Contract v1 is the existing unversioned `ACTION_VIEW` URI
  `jellyscope://play/ITEM_ID?serverId=SERVER_ID&userId=USER_ID`. Each identifier
  contains 1–128 ASCII letters, digits, hyphens or underscores. Extra/duplicate
  parameters, additional path segments, port, user info and fragment are rejected.
  Do not add a version query parameter to this strict v1 shape.
- The launcher-owned vendored `JellyScopePlaybackContract.kt` builds the URI;
  `android-tv-app/TvPlaybackLink.kt` owns receiver enforcement. Keep these two
  app-local copies and this guide synchronized; compare the exact URI shape,
  identifier rules and component when either changes. There is no contract SDK.
- The launcher explicitly targets package `com.udnahc.jellyscope`, Activity
  `com.jellyscope.tv.MainActivity`, and declares package visibility. It validates
  the current account/epoch and selected visible item before resolution and again
  immediately before synchronous dispatch. Credentials and media/server URLs
  never enter the intent. Missing/disabled player, failed launch and stale
  selection use focused local dialogs; Apps and settings remain available.
- The receiver handles cold launch and `onNewIntent`, waiting for restoration.
  Logged-out or mismatched-account links are consumed and show a notice directing
  the user to matching-account setup and a fresh launcher selection. They never
  select an account, sign in, or replay after login/switching. Notices reset on
  session-state changes; requested identifiers are not displayed.
- Matching links from browsing open the ordinary player at zero with current
  backend, quality and track preferences. Normal authenticated item lookup and
  visible Retry/Cancel error handling apply. A link received while a player is
  open is consumed with a notice, preserving the existing player. Each parsed
  delivery has distinct identity, including repeated identical URIs.
- Consumed data is cleared from the current Activity intent to avoid rehandling
  it during ordinary recreation; this is not durable process-death deduplication.
  Notice Back only dismisses it. Player Back returns to JellyScope's prior route,
  not necessarily immediately to the launcher. When the launcher actually resumes
  after external dispatch, its existing focus restoration and manual Jellyfin
  refresh run. Playback progress depends on the player's server reporting.
- Dispatch is not a playback-success acknowledgement. Diagnostics record only
  received/rejected/handed-off stages, not URI or identifiers. Watch Next continues
  to open item details. Actual delivery, dialog focus, playback and return behavior
  remain device qualification items, separate from source and host checks.

## Tiles And Cross-Cutting Rules

- Focus uses a cyan border and optional 1.1x scale; disabling zoom retains the
  other focus treatment. Do not add drop shadow.
- Shared ribbon and grid cards keep unscaled focus/semantics bounds outside the
  animated visual layer. Zoom must not retarget bring-into-view. Preserve each
  screen's existing reveal, dead-zone and restoration ownership; verify zoomed
  artwork visibility at viewport edges separately from logical visibility.
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

## Independent Android launcher

The launcher uses Apps-first rows, not the player application's drawer and hero
contracts above. Apps, launcher settings and Android System settings remain
reachable before login and during media errors. Pins are device-local; the
trailing `+` opens the full launchable-app list with remote-focusable Open,
Pin/Unpin and ordering actions. Unavailable packages produce a visible error and
fall back to a surviving component or `+`.

Server-configured visible media rows follow their declared order, title and
landscape (176×99), poster (128×192), or square (160×160) shape. Media focus keys
use `row:<id>` and `row:<id>:<itemId>` so remote IDs cannot alias local controls.
Settings reports current, cached, default, unavailable, invalid or unsupported
configuration. Removing or emptying the focused row uses its saved clamped
vertical position among surviving media rows, then Apps.

Each row remembers its stable item identity and semantic index. Vertical movement
restores the destination row; missing items use the clamped index, next available
row, then Apps. Horizontal movement reveals the focused card without changing
row identity. Modal controls trap focus and restore their invoker. Return from
System settings restores the control that opened it, including the settings
screen entry, rather than forcing focus to the Home button.

Back dismisses a dialog first, then returns from Apps/settings to its Home
invoker. Login Back cancels outstanding work, clears password input and returns
to settings; a login already durably committed remains reflected in session
state. Home Back below Apps returns to Apps; at Apps it retains ordinary activity
exit behavior unless the public resolver reports this Activity as the default Home;
in that case root Back is consumed without navigation. Device qualification is
separate from this source behavior.
Media selection uses the explicit JellyScope handoff above. The Settings message
explains matching-account setup and the return path; it does not claim playback
success from an Activity launch.

### Standard Home integration

The independent launcher declares a separate MAIN/HOME/DEFAULT filter and uses
singleTask. A new MAIN+HOME intent without data returns to Apps (`apps:+`), clears
a modal/auth error and replaces Home focus state. Cold handling runs only without
saved instance state, so configuration recreation does not replay the original
Home reset. Ordinary app/Leanback intents and external-app return preserve normal
navigation. No Home key interception, vendor redirect, accessibility service or
boot receiver is installed.

Home while Login is visible routes directly to Apps and disposes the password/IME
UI. The existing cancellation fence invalidates the attempt and joins the old job;
a login commit that already won remains authoritative. Account actions can remain
briefly busy while that fence finishes; Apps and System settings stay accessible.

Settings offers **Choose default Home app** through Android's standard selector.
Return restores the settings button's focus and rechecks the default using public
intent resolution with exact component equality. Resolver/unknown/error results
show conservative not-selected status. Missing/blocked selectors produce a focused
message; System settings remains separately available. Firmware may not expose or
honor third-party Home selection. See the
[qualification record](../operations/android-launcher-qualification.md) for evidence
levels and the unrun device checks.

## Why

- Background clipping avoids painting beneath opaque browse content while
  retaining coverage during transitions. Sharing the route's ambient animation
  removes duplicate animation work without changing the contributing gradients;
  reading drawer width during layout avoids per-frame scaffold recomposition.

- Stable card focus bounds prevent zoom from shifting scroll targets on either
  axis without adding another reveal owner. Visual overhang still needs edge
  clearance; stable bounds alone do not prove that clearance.

- Find separates scroll axes so horizontal focus movement cannot shift the page.

- **Playback links reuse normal playback ownership.** Account-qualified item
  identifiers preserve session boundaries, settings, and normal planning.
  Requiring the previous player to close avoids a second native owner.

- Paging errors preserve pending restore and require explicit Retry because a
  failed page does not prove a target absent; search uses request identity for
  the same stale-result reason.

- Launcher row identity and explicit invoker restoration keep Apps and recovery
  controls usable without importing the player UI or its navigation drawer.
