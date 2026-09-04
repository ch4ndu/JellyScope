# TV UX Behavior Contract

This is the normative Android TV behavior contract. Treat every item as a
regression contract: do not change one as a side effect of unrelated work. When
a behavior must change, update this file in the same commit.

## Focus-group traversal contract

- Every D-pad screen is composed from logical `focusGroup` regions. Directional
  movement crosses those regions through explicit group entry/exit targets;
  screen-wide geometric focus search is never the navigation model.
- Every kernel-managed group owns a stable-key `TvFocusScopeNode` and writes to
  the route's `TvFocusMemoryState`. It must not also use Compose
  `focusRestorer`. Static non-lazy groups may use `focusRestorer` only when they
  are not route-memory owners. If a saved item is gone or unattached, the pure
  resolver chooses the saved position, first child, sibling group, or explicit
  screen fallback. A failed request never counts as a consumed restore.
- Groups nest. A screen/content group remembers its last focused child group,
  and that child group remembers its last focused item. Entering the parent
  therefore restores the right region first and then the right tile/control.
- The navigation rail is one focus group. Its initial/fallback target is the
  selected destination; returning to it must not use the geometrically nearest
  rail item.
- Home content is one focus group that restores the last focused ribbon. Every
  ribbon is a nested focus group that restores its last focused media ID and
  falls back to its first tile. RIGHT from the rail enters the remembered ribbon
  and tile; a missing ribbon/item falls through to the first available ribbon
  and its first tile.
- Modal pickers must use a focus-trapping dialog/window. While open, their
  controls form the only reachable focus tree, initial focus has an explicit
  fallback, and BACK dismisses to the invoking control.
- Detail, Series, and Season screens use the same nested contract. Their action
  rows fall back to the primary Play action; season tabs fall back to the
  selected season; and every cast, season, episode, and related-content ribbon
  remembers its last stable item key and falls back to its first available item.
- Request focus through `requestFocusSafely()`, never by treating
  `runCatching { requestFocus() }.isSuccess` as proof that focus moved. The raw
  API may return `false` without throwing, so fallback chains must use the real
  Boolean result.
- Restore waits for the route entry, settled transition, registered group, and
  sufficient data to resolve the target. Reveal an off-screen target before
  requesting it; bounded attachment retries may be used after reveal, but fixed
  route-transition delays and `withFrameNanos` are not readiness signals.
- A pending focus restore is cancelled only by an unconsumed hardware key-down
  delivered to the returning route after it becomes interactive — never by
  `onFocusChanged` or an interim focus landing. The BACK press that initiated
  the pop, key-up events, media keys, and events consumed by a modal or focus
  trap never cancel it. Deliberate D-pad navigation therefore wins over a
  delayed restore, while a temporary geometric landing cannot consume it.
- A stalled restore may park focus on the route's loading target and emit
  diagnostics, but a timeout alone can never prove the target absent and can
  never consume the pending restore; only confirmed focus or an explicit
  cancellation resolves it.
- After process restoration, derive the next route-entry ID from the active and
  historical IDs, and discard focus state left orphaned by IDs no route entry
  claims.
- Pending restore-transaction state is itself saveable. A route snapshot alone
  cannot distinguish an unconsumed restoration from one that already succeeded
  before process recreation, so the transaction must survive process death with
  the route it belongs to.
- Ribbon restoration first focuses an already-composed stable key without
  moving its row. An uncomposed target receives one centered lazy-row reveal and
  bounded attachment retries. While that handoff is active, automatic
  bring-into-view returns zero; afterward Home and Recommended retain exact
  Mario centering, Detail/Series/Season retain their dead zone, and Find and
  Person retain Compose minimal visibility.
- A route-owned ribbon restore suppresses that screen's ordinary first-entry
  autofocus for the handoff. Initial autofocus must not run after the kernel has
  focused the resolved card and steal focus back to the first item.
- Saved ribbon positions are semantic content indices. Lazy-slot indices are
  mapped separately, including Home's optional leading loading tile and
  trailing View All tile. A missing key falls back to the saved semantic
  position in the same ribbon; an absent ribbon keeps the screen's existing
  parent or sibling fallback.
- Override traversal only at logical group boundaries where geometric search is
  not the product behavior.

## Launch and Home focus

- App launches with the navigation drawer CLOSED; Home renders every ribbon slot in
  fixed order from the first frame, with Continue Watching first. Home's ribbons
  are Continue Watching, Favorites, Next Up, and Recently Added — there is no
  Libraries ribbon, because the drawer already lists the user's libraries. Loading
  ribbons render a real focusable placeholder tile in their slot so initial
  focus can land on Continue Watching immediately without waiting for data.
  When a focused placeholder resolves to content, focus moves to that row's
  first card in place; when it resolves empty, the row collapses and focus moves
  to the next visible ribbon. The drawer must never flash open at launch.
- The focus-restore target (launch default or return-from-detail) is consumed
  only when the exact row-scoped target receives focus. A temporary geometric
  focus landing while an off-screen target is being composed/scrolled must not
  cancel that pending restore. After the target has received focus, moving to
  another card expires it so a re-composed target can never steal focus back
  mid-browse.
- Focus restore is row-scoped `(row, itemId)`: the same item shown in two
  ribbons must not let the other copy grab focus.
- The Home row's View All tile is also a first-class row-scoped restore target:
  opening a View All grid records `(row, viewAll)`, and BACK from the grid
  restores focus to that row's View All tile instead of the previous asset.
- Home derives its initial restored row, item ID, fallback index, and View All
  flag from a route-entry snapshot keyed by route-entry identity, presentation
  epoch, and the matching ready-restore token. A matching ready restore wins;
  otherwise Home reads the matching current-entry live path without observation.
  Narrow consumers still record every D-pad movement, but those updates must
  not invalidate the whole Home route lambda.
- Returning from detail/player restores scroll AND focus to the exact card from
  the route snapshot and nested focus-memory map. A composed target receives
  focus in place without a manual row scroll; an uncomposed target is revealed
  once before focus transfers. If the drilled-into item is GONE on return
  (unfavorited, watched-to-completion out of Continue Watching, or reordered/paged out),
  focus stays in the SAME row and lands on that row's first card — it must NOT
  jump to another ribbon or dead-end on the drawer. If that row is now
  empty/gone, focus the next focusable row; only when no recorded row applies
  does it fall back to the first visible row's first card.
- Silent refresh runs on every (re)entry to Home: cached rows keep showing,
  a row only changes when fresh data arrives, refresh errors never replace
  visible content, and unchanged rows must not recompose. If a refresh
  removes the live-focused card, focus falls back to the first card of the row
  it was in (not the first ribbon) instead of dying/orphaning to the drawer.
- Discover, Find, Favorites, and Library follow the same re-entry refresh
  contract. When a Favorites or Library grid refresh removes the live-focused
  card, focus stays inside the content surface by resolving a successor card
  first, then the grid's stable header fallback; it must not strand on the
  drawer.
- Focus identity is `(ribbon, asset)`, not the asset id alone: the same asset
  routinely appears in more than one ribbon (a watched favorite is in both
  Favorites and Continue Watching). The rescue that catches a removed focused
  card MUST check whether the asset left the ribbon it was focused in — NOT
  whether it still exists in some other ribbon, or it will conclude the card is
  "still present" and let focus stay stranded on the drawer. Each ribbon is a
  distinct composition (`key(homeRow)` wraps each `TvHomeRow`), so the two
  copies are already separate view entities.
- Removed-item rescue needs proof, not absence: it runs only while the
  affected route and scope are active, that scope currently owns focus, and
  ready or terminal data proves the focused child left the scope's key set.
  Loading or partial data must never trigger a rescue.

## Home scrolling

- Returning from a Home ribbon item detail restores focus after the route-pop
  transition to the same stable item ID in the same ribbon. If that item is no
  longer present, restore the prior card position when it still exists, falling
  back to the first card when necessary.
- Vertical shelf is a plain Column (NO LazyColumn): all rows stay composed;
  row pins never fall back to a snap because a row was unmeasured.
- The focused ribbon pins to the TOP of the shelf; the next row peeks below;
  rows above scroll away. The pin is the ONLY vertical scroller — vertical
  focus bring-into-view is disabled (it fought the pin and bounced rows).
- Vertical pin transitions run at constant SPEED (~320ms per standard row
  pitch, clamp 160–700ms), not constant duration, so short hops and long
  jumps feel uniform.
- Horizontal ribbons center-pivot the focused card ("Mario" scrolling) with a
  retargeting spring (StiffnessMediumLow) so held keys cannot outrun the
  scroll and land focus on an edge item. Rows clamp at both ends.
- Each ribbon remembers its last-focused card: moving UP/DOWN between ribbons
  restores where you were in that row (not Compose's geometric X-aligned pick,
  which drifts as rows Mario-scroll). First entry into a ribbon (or a remembered
  card that scrolled away) falls back to that row's first card, so DOWN into a
  fresh ribbon lands on its first item and UP returns to the exact card you left.
  Each ribbon is a stable-key child scope under Home; lazy disposal never owns
  or erases its remembered child.
- The hero (title/backdrop/ambient) updates only after focus SETTLES for
  220ms; the swap is a hand-rolled two-layer 600ms fade that runs full-length
  in BOTH directions (Compose Crossfade resumes partial alpha and snaps).
- Every TV focused-item hero uses the same ambient contract as Home and Detail:
  derive a normalized primary tint from the settled hero asset, animate it into
  the full-screen background, and ignore late palette results from an item that
  no longer owns the hero.
- The approximately 16:9 backdrop is an image layer behind the foreground: its
  bottom fade extends beneath the first shelf without changing the hero or
  shelf layout height.
- Focusing the View All tile pins the row but must NOT change the hero.

## Home back stack (BACK button)

- Focus below the first ribbon → BACK rewinds the first ribbon to its start
  and focuses its first card.
- Focus on the first ribbon → BACK opens the drawer with the SELECTED tab
  focused.
- Focus in the drawer → BACK exits the app.

## Discover back stack (BACK button)

- BACK on Discover opens the drawer with the Discover tab focused (it must NOT
  auto-switch to Home). Implemented as `BackHandler(enabled = !railHasFocus)`
  that requests `railSelectedTabRequester` — the same pattern as Home.
- Focus in the drawer → BACK exits the app.
- Landscape ribbons: the LazyRow leading inset (`TvDimens.ribbonStartPeek`) must
  leave room for a focused (1.1x) first tile so it is never clipped; "View All"
  is ALWAYS present, including on wide/landscape rows (with a wide-sized card).

## Navigation drawer contract (all screens with the drawer)

- The drawer is shown on the top-level hub screens (Home, Discover, Find,
  Favorites, Settings, and Downloads when the effective session content-download
  permission is enabled) AND on the `Library` browse (a library is a drawer
  destination, not a nested screen — opening one from the drawer keeps the
  drawer available and highlights the current library). NESTED screens hide it
  entirely and let content fill the width: Detail, Series, Season,
  Grid/View-All, Collection, Person, and the `FilteredLibrary` (genre/studio)
  browse. On a nested screen BACK is the only way back (every remote has it).
- Nested routes use a saveable LIFO history of payload-bearing snapshots. Every
  Detail, Person, Collection, Series/Season, Grid, FilteredLibrary, and Player
  push preserves its originating item/route context; BACK pops exactly one
  snapshot. Multi-hop paths such as Detail A -> Person X -> Detail B must return
  Person X -> Detail A and must never alternate between the newest two routes.
- Android TV uses one app-owned navigation drawer host. The shell owns
  focus-driven open/close and content push behavior, while JellyScope owns the
  narrow drawer rows so the app keeps its tuned 48dp collapsed / 180dp expanded
  footprint. Do not add a new per-screen drawer or rail for a top-level
  destination.
- When the effective session content-download permission is enabled, drawer
  destinations (top to bottom) are Search, Home, Favorites, Downloads, then the
  user's real media libraries (from `GetUserLibrariesUseCase`), then Discover,
  then Settings. When it is disabled, the Downloads destination is omitted and
  the ordinary Downloads route is not rendered.
  Search opens `TvFindScreen`, Discover opens `TvDiscoverScreen`, Favorites opens
  the favorite-filtered library browse, a library item opens its `TvLibraryScreen`
  browse (drawer stays available, current library marked selected). The drawer
  content is one vertically scrolling list; Settings is not bottom-pinned.
- The drawer width comes from `TvDimens` and owns real layout space. Route
  content is measured at the collapsed-drawer viewport width, placed after the
  animated drawer, and clipped on the right as the drawer opens. The drawer must
  not overlay visible content, and route content must not compress/reflow when
  the drawer opens. Top-level screens add only the drawer content gap through
  list/content padding; they must not reserve a hardcoded drawer spacer, render a
  duplicate rail, or do screen-local width math.
- Focus entering the drawer lands on the SELECTED tab, not the geometrically
  nearest item.
- If the current Library route's selected library has not loaded into the drawer
  yet, focus entering the drawer lands on Home as a focus-only fallback without
  marking Home selected. Once the selected library item is present, Back/focus
  entry targets that library item.
- Moving focus within the drawer selects/navigates using the drawer-origin path
  only after focus dwells on the item for roughly 500ms, and keeps focus in the
  drawer. Selecting the CURRENT tab/library is a focus no-op. The user must
  press DPAD RIGHT to move focus from the drawer into content.
- RIGHT from the drawer uses the active route's registered content entry action
  and requester. The requester is exposed through focus properties for visible
  targets; the key fallback invokes the route-scoped action and consumes RIGHT
  only when that action accepts focus entry. Home can return through its content
  focusRestorer; loaded library grids must land on a visible media tile; loading
  focus parks and other temporary anchors are valid only while no real content
  target exists. Settings enters on the Server tile and reveals the Account
  section at the top of the scrollable grid; there is no separate Settings page
  title.
- Library drawer exit always resolves the currently selected inner-view tab at
  invocation time. It consumes RIGHT while retrying a temporarily rejected
  focus request, so restored ribbon/grid scroll state cannot fall through to a
  stale or geometrically adjacent tab.
- No visible Back buttons anywhere: every TV remote has one.
- Top-level browse surfaces with the drawer (Home, Discover, Find, Favorites, and
  library browse) plus View All grids retain their screen ViewModels across
  drill-down routes, so BACK from detail/player returns to already-loaded
  content instead of flashing empty content and moving focus to the drawer.
- Switching to a DIFFERENT top-level tab is still a fresh start for destination
  scroll/focus state: Home's restore args and the per-tab `SaveableStateHolder`
  state are dropped, while ViewModels remain keyed separately and retained.
  Content-origin switches may land on the destination's DEFAULT focus as before.
  Drawer-origin focus/click switches suppress that route-entry autofocus and keep
  focus on the drawer until the user presses RIGHT; RIGHT then enters the reset
  destination at its registered default content target.
- When a top-level tab's default content target is not focusable yet, the
  destination must park focus inside its own content area while showing the TV
  loading spinner. The parked loading anchor is temporary: once real content,
  retry/sort controls, or a visible empty/error control is available, focus must
  move there instead of falling back to the drawer.
- From a parked loading state, BACK follows the screen's normal top-level
  BackHandler and opens the drawer with the selected destination focused. DPAD-LEFT
  also opens the drawer. SELECT, PLAY, and other D-pad directions do nothing until
  the destination has a real focus target.
- Series and Season share one retained `SeriesViewModel` keyed by session and
  series ID, so moving series -> season -> back or opening a route-scoped child
  from the series flow does not reload the series data. The retained series store
  clears on session change, series ID change, and after leaving the series flow.
  Detail ViewModels are retained by route-entry ID while active or present in
  route history, including while another Detail, Person, Series, or Player is
  open above them. BACK reuses the parent's loaded and partially loading Related
  shelves; a silent metadata refresh must not replace or finish that Related
  stream. Render leases keep outgoing Detail content valid until Compose
  disposes it, then clear owners no longer represented in history. Collection,
  Person, and Player routes remain route-scoped and clear their own ViewModels
  when dismissed. Every shared Detail-family focus bridge is bound to its
  rendered route entry, so outgoing content cannot consume incoming focus.

## Detail / series screens

- Focus lands on the primary Play/Resume button when the screen opens.
- Detail Version, audio, and subtitle pickers are focus-trapping dialogs.
  Initial focus lands on the current selection (or the first option),
  directional traversal cannot reach the obscured detail screen, and BACK
  restores the invoking action.
- Movie and concrete episode detail subtitle pickers contain a Search subtitles
  row after Off, Jellyfin tracks, and installed local assets; search is not a
  separate action outside the detail focus group. Search language, result,
  download progress, error, quota, close, delete, and retry controls must all be
  reachable by D-pad. Opening search dismisses the track picker, and closing or
  backing out of the search dialog explicitly restores the subtitle picker
  action. The search dialog is detail-only; the player picker contains installed
  rows only. Passive sync/player notices never become focus targets.
- All action buttons use standard 12dp/6dp padding, wrap-content width, pill
  shape, and D-pad detail action rows use one fixed shared height with children
  filling it, so focus expansion never reflows content below the row; button
  labels must NEVER clip (no fixed expanded widths).
- Detail and episode Version/audio/subtitle selectors do not render
  multi-option chip rows in D-pad mode. When a choice has 2+ options, it appears
  as a compact action-row picker button beside the other detail actions; focus
  expands the button to the current selection label plus a down-arrow, capped
  to a short width with ellipsis. The picker opens as a TV overlay with full
  option labels and a current-selection marker. Version uses the valid,
  distinct source list and is omitted for 0-1 options; track types retain their
  existing static-text/omission rules.
- If refresh removes the selected source while Version is open, dismiss the
  modal rather than leaving it on the replacement choice. Restore focus to the
  surviving Version action when multiple sources remain, otherwise to primary
  Play/Resume. The
  same fallback applies when the invoking Version action disappears; no stale
  requester may strand focus on the obscured detail screen. This contract
  applies to movie detail, concrete episodes, series, and season detail.
- Buttons follow the theme: raised dark surface at rest, solid white with a
  dark glyph/label only when focused. Nothing renders "always lit".
- Appearance and boolean player-device setting toggles are the scoped TV
  Material exception: each toggle is one clickable `ListItem` focus target with
  a non-interactive trailing `Switch`. The switch must never become a second
  D-pad stop. These rows use TV Material's list-item focus scale and typography
  while their colors continue to resolve from the active JellyScope palette.
- The primary play label reads "Play" (not "Start"); resume shows
  "Resume from <pos>" with time-left underneath.
- Favorite and Watched are single toggle buttons whose label/icon flip with state
  (Add/Remove Favorite, Mark Watched/Unwatched). They stay FOCUSABLE while the
  toggle is in-flight (the click is gated, focusability is not), so toggling never
  drops focus to Play. This holds for movie detail, series header, and episode
  action rows (all `ExpandingActionButton`).
- Episodes lead the metadata line with the series name AND S/E label
  ("<Series> · S1E1 - <year> - <runtime>") in the detail view, the Home hero
  (`MediaCardUi.seriesName` + `episodeLabel`), and the player top bar
  (`PlayerMediaMetadata.seriesName` + `episodeLabel`).
- Related content is multi-shelf. Shared repository and UI owners define the
  Cast-first, priority-gated source order and shared maximum of four; see
  [`data-playback.md`](data-playback.md#related-shelves) and
  [`ui.md`](ui.md#related-shelves). Android TV owns only the D-pad behavior:
  each non-empty rendered shelf gets its own stable focus container and
  scroll-into-view hook, ordered after hero and cast; a sparse source simply
  removes its empty shelf, so later shelves fill the available positions.
  Series keeps its single flattened Related shelf and episode detail keeps
  Next Up before Similar.
- Detail enrichments, all guarded on availability: the hero shows the title
  clear-logo image (`JellyfinImageType.Logo`) instead of text when present; a
  tagline (italic) sits under the title; a studio line sits under directed-by; a
  Play Trailer button (opens the remote-trailer URL via the platform URL handler)
  and IMDb/TMDb buttons appear in the D-pad action row (external-link chips are
  compact/desktop-only because they aren't D-pad-friendly); and a resume progress
  bar shows under the action row for in-progress items.
- Person cards keep media-tile poster width but use a 3/4-height portrait image
  so Cast & Crew rows are shorter than poster shelves.
- The hero section must NOT use a fixed height (legacy Fire OS measurement
  behavior mismeasures fixed-height text columns inside lazy items — see
  docs/guides/ui.md). It uses
  wrap-content with bounded `maxLines` (title 2, overview 3) so the title never
  clips; the season metadata block does the same.
- The detail/series LazyColumn carries a small top inset (`TvDimens.heroTopInset`)
  and, when ANY action-row button gains focus (row-level `hasFocus` rising edge,
  not just Play), animates back to item 0 so the whole hero is visible; the entry
  effect also scrolls to item 0 after requesting Play focus so the hero is visible
  on first load.
- Do not give any TV lazy-item text container a fixed `height()`: legacy TV
  measurement can size it at a fraction of the request and leave children
  unplaced. Use wrap content with bounded text lines and padding; fixed
  image/backdrop layers outside the lazy list are unaffected.

## Season screen

- On load the Seasons tab row (item 0) stays visible and focus lands on the
  episodes ribbon (list pinned to item 0; first episode auto-focused).
- The focused-episode metadata reserves a STABLE number of text lines (episode
  title `minLines/maxLines = 2`, overview `minLines/maxLines = 3`, ellipsized) so
  the episodes ribbon does not jump vertically as focus moves between episodes
  with different-length titles/overviews. (Reserved lines, NOT a fixed height —
  legacy TV measurement contract.)
- DPAD-UP from the episodes ribbon focuses the CURRENT season tab
  (`focusProperties { up = selectedTabRequester }`); the tabs `LazyRow` scrolls the
  selected season into view so its `FocusRequester` is always composed (no crash
  on many-season shows).
- Navigating episode cards left/right must not scroll the vertical list (stable
  metadata height guarantees this); there is no Mario/center-pivot scrolling in
  the season screen.
- Focus is scoped PER SEASON: the episode strip, the episode action row, and the
  Cast & Crew ribbon are each keyed on the selected season (their focus containers
  reset on season switch), so switching seasons never restores focus to another
  season's card/index. Within a season, the action row's focus persists as you
  browse episodes; it resets (to Play) only when the season changes.
- Season-tab switches update the existing screen composition in place; they
  are a content swap, never a route transition, so switching seasons must not
  run route enter/exit animations or allocate a new route entry.

## Player

- Overlay auto-hides after 5s of no input while playing; EVERY key press
  resets the timer (focus must never strand mid-navigation).
- Overlay reveal focus: play/pause by default; the SCRUB BAR when revealed by
  a LEFT/RIGHT skip (so repeated presses keep seeking); the opener button
  when returning from a closed picker.
- Dedicated remote media keys work everywhere in the player, including the
  controls, queue/Up Next card, picker, loading, buffering, and error states:
  rewind/skip-back and fast-forward/skip-forward seek exactly like LEFT/RIGHT;
  dedicated Play only starts, Pause only pauses, Play/Pause toggles, Next
  advances the ViewModel queue, Previous seeks to the current item's start
  after five seconds or moves to the prior queue item, and Stop follows the
  existing stop-and-back contract. Each event is consumed once by the root
  player key bridge, so a picker does not lose transport control while its
  normal D-pad navigation remains local.
- Dedicated media keys are ALWAYS transport. The root bridge maps
  MediaPlay/MediaPause/MediaPlayPause/MediaNext/MediaPrevious/MediaStop and
  consumes them before any overlay/Up Next/skip-segment branch, so a dedicated
  Play/Pause key toggles playback even while the Skip button is showing. This is
  an intentional behavior change (previously a hidden-overlay Play/Pause performed
  the segment skip): the segment skip is now reachable only from SELECT/Enter/Space
  (see the Skip button rule below). Keep this contract when touching the root
  `onPreviewKeyEvent` handler.
- Remote PLAY with a tile focused (Home ribbons, grids) starts playback of
  that item directly — resuming from its resume point when one exists.
  Non-playable tiles (libraries, series) ignore it.
- While a picker (subtitles/audio/quality) is open, the overlay hides and the
  picker owns normal navigation keys; the root transport bridge still handles
  Play/Pause/Next/Previous/Stop. Closing it brings the overlay back with focus
  on the button that opened it. The same opener-focus rule applies to Chapters, Speed,
  Subtitle Style, and Resize local menus; request focus after the returning
  controls are attached rather than falling back to play/pause.
- Audio picker buttons are visible only when there are at least two real tracks
  to choose from. Subtitle picker buttons are visible whenever at least one real
  subtitle track exists because Off is an additional selectable state. If the
  track list changes while returning focus from a picker, focus falls back to
  play/pause rather than targeting a missing opener.
- The Chapters button is visible only when the current item has chapters. If
  the chapter list becomes empty while the menu is open or while focus is
  returning to controls, close the menu and fall back to play/pause focus. The
  chapter menu selects the currently playing chapter by timestamp: the last
  chapter whose start is less than or equal to the live playback position. Its
  selection must advance while the menu remains open. Opening the chapter menu
  reveals and focuses the currently playing chapter row even when it is far
  off-screen.
- The subtitle style button is visible only when the active subtitle is rendered
  as local text by the player. Hide it for subtitles off, unknown subtitle state,
  or server-burned/transcoded subtitles because those subtitles are already
  pixels in the video frame and cannot be restyled by the client. If a track
  change makes an open subtitle or subtitle-style picker invalid, close it and
  return to player controls.
- Subtitle picker selection reflects response-authoritative installed delivery,
  not a codec guess made before selection. Track rows may show language and
  External metadata, but must not speculate that transcode is required. A
  requested local subtitle is not marked selected until the player confirms the
  exact activation request; stale confirmations stay pending.
  A PlaybackInfo re-plan keeps showing the installed plan's active track until
  the replacement plan is installed. "None" is selected only when subtitles are
  actually off, including remembered off.
- Failed local subtitle activation shows the passive, non-focusable “Local
  subtitles unavailable; switching to server-rendered subtitles.” banner for six
  seconds, keyed by its notice token so successful Encode fallback does not hide
  it early. It does not open a menu, take D-pad focus, add a scrim, or change
  BACK behavior.
- Android TV playback-health and recovery notices are persistent,
  bottom-center action surfaces: controls auto-hide must not remove them. When
  controls are hidden, the surface uses the player overscan inset; when they
  are visible, it moves above the established controls reserve, clearing both
  transport chrome and top metadata. It never appears beneath the top bar.
- The TV player consumes the shared `PlayerOverlayLayer` contract's explicit
  **video → debug → chrome → popup → modal** order rather than composition
  order. Only the playback-info panel uses the debug layer; controls, metadata,
  Skip, and Up Next remain chrome above it. Passive fallback/audio/subtitle
  banners, action/guidance notices, and status glyphs remain popups above
  chrome; pickers, local menus, and fatal errors are modal above every popup.
  Every new surface must be assigned deliberately.
- Appearance never steals focus, reveals controls, or changes media-key/BACK
  behavior. When the user deliberately enters the notice, every advertised
  action is D-pad reachable. Acting on or dismissing a **focused** notice reveals
  the controls, moves focus to the play/pause control through the shared play
  focus requester, and only then removes the notice and runs the action — focus
  lands on a successor before the focused composable disappears, so remote input
  cannot strand. The handoff is scheduled with a short `delay` (never
  `withFrameNanos`, which stalls while the player is idle), uses
  `requestFocusSafely()`, falls back to the safe player root while a modal is
  open, and re-arms the controls auto-hide afterwards so the revealed controls
  still disappear on their own. A notice that was never focused runs its action
  immediately with no handoff. Previously this returned focus to the safe player
  root, which left the user on an unfocused surface; Try higher, Original, Auto,
  Keep, Settings, retry, and close return focus according to the resulting
  player/picker/navigation state. PiP suppresses interactive replan actions
  until safe presentation resumes.
- When bounded Auto recovery cannot select another stream, **Choose lower
  quality** dismisses the notice and opens the in-player quality picker. That
  exhausted-recovery notice does not offer a Playback Settings action; the user
  can choose a session-only rung without leaving playback.
- A failed Fixed policy uses the same in-player recovery boundary: Choose lower,
  Try higher, Try Original, and Dismiss are reachable from the persistent
  notice, with no Playback Settings action.
- The player owns no implicit quality gesture. Original never chooses a new
  stream automatically; the Android TV app root uses the common `Actionable`
  policy, so Auto may perform only the common bounded recovery policy, and any
  user-facing result is explained through this surface. Fixed never auto-lowers.
  A focused notice action dispatches a semantic presenter/ViewModel event, not
  a native-player call from the shell.
- tvOS uses the same semantic action state above AVKit controls. SwiftUI must
  keep its actions discoverable to VoiceOver and Siri Remote without rebuilding
  AVKit transport menus on playback ticks or letting passive appearance steal
  focus. The Kotlin presenter remains the sole playback/replan owner.
- Seek bar shows a buffered-progress layer; when focused it thickens and
  shows a thumb; DOWN from the seek bar goes to play/pause. Focus alone never
  requests or shows a trickplay frame for the live playback position. A preview
  is eligible only while LEFT/RIGHT or RW/FF owns a pending seek target. An
  advertised trickplay sprite is visually absent while loading and on image
  failure; its thumbnail frame appears only after the current target's tile
  loads successfully. The decoded sheet is painted at full grid size through
  the clipped thumbnail viewport so the parent frame cannot clamp it before the
  row/column crop. On final commit, the target thumbnail remains through the
  resulting loading/buffering recovery and disappears when recovery ends; an
  already-buffered seek uses a short bounded handoff. The thumbnail tracks the
  target horizontally, clamps inside the seek-bar edges, and uses a shallow
  curved pointer to identify the exact position when it cannot stay centered.
  The pointer uses three quarters of an option button as its maximum width but
  narrows near a hard endpoint so the edge-facing side stays compact instead of
  curling into a broad hook. When recovery releases the retained seek target,
  the last loaded preview fades while its lane collapses toward the scrubber;
  target changes during a hold remain immediate, and item changes dispose the
  old preview without carrying its animation into the next video.
- Play/pause and all overlay buttons: translucent dark circles at rest, solid
  white with dark glyph when focused (no always-white play button).
- The playback-info Debug control toggles a persistent, top-anchored,
  non-modal info overlay. It is not part of the picker/local-menu system, has no
  scrim or focusable close row, does not take focus from player controls, and
  BACK never closes it. The panel is a full-width, translucent top band that
  must fit above the transport controls, use a compact layout for scan-friendly
  playback data, and include transcode-reasons plus subtitle-rendering rows when
  playback info exists (`None` when absent, highlighted when the server returns
  reasons). It renders the shared compact generic rows from
  `playerDebugSections` in its two-column layout. When playback info is
  unavailable, those generic rows are only `Playback info: Unavailable` and
  live `Status`. The TV overlay adds no shell-local rows; display refresh
  matching remains TV-owned runtime policy and is not part of the shared overlay
  projection.
  Unavailable visible diagnostic values use the panel's unavailable marker.
- BACK closes the innermost player surface first: local menus, pickers, and Up
  Next are dismissed before the main overlay. BACK hides any visible main overlay
  in every playback state, including Paused, and the next BACK exits. Loading,
  Buffering, Paused, Completed, and Failed transitions may reveal controls, but
  they must not prevent that explicit two-step dismissal.
- Loading and Buffering show a plain white arc spinner — no text, no background
  scrim. This includes LibVLC's initial black-frame wait and non-zero resume
  seek, not only user-initiated seeks.
- On the Android LibVLC backend, an explicit seek enters Buffering once and
  remains there through the native clock's initial jump to the requested
  timestamp. It returns to Playing only after the clock advances again to prove
  playback resumed; a delayed fallback probe must not dismiss the spinner while
  the stream remains stalled. Repeated native buffering callbacks must not
  retrigger recomposition or focus changes.
  The spinner (shared `AdaptiveSpinner`) MUST be driven by the frame clock
  (`withInfiniteAnimationFrameMillis`), never a `tween`-based
  `rememberInfiniteTransition`. Fire TV devices ship with
  `animator_duration_scale = 0` (system animations off), which multiplies tween
  durations to zero and freezes any tween-based animation on a static frame. A
  buffering/loading spinner is functional feedback, not a decorative animation
  the user opted out of, so it must spin regardless of the animator scale. This
  applies to every TV loading spinner (all route through `AdaptiveSpinner`).
- Non-fatal audio-output loss never opens the fatal player dialog. The TV player
  shows a passive, non-focusable "audio unavailable" notice for roughly 6s and a
  small persistent audio-off glyph; neither element may request focus, consume
  D-pad input, or interrupt the transport controls.
- Fatal player errors may show cause-specific messages, but the existing
  Retry/Cancel buttons and focus-on-primary-action behavior must remain
  unchanged.
- Backgrounding the app (HOME) DISMISSES the player: playback stops, the
  resume position is reported, and the route pops — reopening the app lands
  on the screen the player was opened from, never a stale player. Playback
  must never continue behind the launcher (lifecycle ON_STOP observer —
  route-based disposal alone cannot catch this). MainActivity is
  singleTask so a launcher relaunch can never stack a second activity
  instance (BACK used to reveal the old instance's player).
- True end-of-queue `playbackEnded` leaves the player exactly once. Raw
  `Completed` state never dismisses TV content directly: it can instead advance
  the playlist or keep the route present for the Still Watching gate.
- TV display refresh-rate matching is controlled only by the default-off
  `matchDisplayRefreshRate` setting and the active plan's frame-rate metadata;
  it never changes what Jellyfin streams. The TV player releases its preferred
  display mode and restores Media3's normal frame-rate strategy before stop/back
  and on lifecycle `ON_STOP`, true end-of-queue, fatal error, setting disable,
  absent/invalid metadata, and composition disposal. Matching preserves the
  current display resolution and reports exact, integer-multiple, or 2.5×
  fallback selection as runtime policy; those display decisions are not rendered
  as debug-overlay rows.
- Playlist mode (queue > 1): below the scrub bar exactly ONE page shows at a
  time — the control buttons (with a static "Up Next" hint line beneath) or
  the Up Next ribbon. DOWN from the controls slides the buttons away and the
  ribbon in (list-style vertical swap, focus moves to the now-playing card);
  UP slides the controls back (focus returns to play/pause); the Up Next
  page is PART of the overlay, so BACK from either page dismisses the WHOLE
  overlay in one press (next BACK exits). Re-invoking the hidden overlay
  ALWAYS starts on the controls page, no matter which page was showing when
  it hid.
  Show/hide decisions must use the real `controlsVisible` state. Non-playing
  transitions reveal the overlay, including transcode Playing/Buffering changes,
  but a BACK dismissal remains authoritative until another status transition or
  user interaction reveals it. Controls must
  NEVER be visible while interacting with the ribbon. Selecting a tile stops
  the current item INSTANTLY (before the next item is planned — a
  still-playing video reads as "the click did nothing") and plays that item;
  the now-playing tile carries a marker.
- Queue episode badges use the Jellyfin episode number (`IndexNumber`) and
  season number when available, never the queue position. Queue metadata must
  resolve real titles/images where possible and must not show a raw item id as a
  fallback title.
- The Skip button appears only during a matching media segment whose
  per-type policy is **Ask** (`Content.skipPromptSegment`): Intro/Recap show
  "Skip Intro", Outro/Preview "Skip Credits", Commercial a generic "Skip";
  Ignore hides it, AutoSkip seeks automatically (once per segment per item
  session, only while Playing, via the normal seek path), and unknown types
  never surface. It seeks to the segment end without stealing focus from an
  active Up Next card. While the Skip button is visible and the overlay is
  hidden, the manual skip is performed by SELECT (DPAD center), Enter, or Space;
  the dedicated remote Play/Pause key does not skip (it stays transport, per the
  media-key rule above).
- LEFT/RIGHT and RW/FF are **hold-to-seek**: each key-down (press or repeat)
  advances a pending target with duration-scaled acceleration; the scrub
  bar, position label, and trickplay thumbnail follow the pending target;
  the single real seek commits on key-up (with the shared two-stage
  watchdog and 2 s cadence catch-up per `docs/guides/data-playback.md`). The
  initial tap still advances by 10 s, and repeat events advance the pending
  target on every second event to keep hold velocity at half rate. The
  root player key handler owns ALL seek-key key-ups (preview tunneling sees
  them first), so a hold survives focus moves; the focused scrub bar owns
  only LEFT/RIGHT key-downs. Play/pause/SELECT mid-hold commit first, BACK
  cancels the pending seek and then performs its normal dismissal in the
  same press, and an item change (queue advance/auto-advance) cancels the
  hold outright. Reveal-by-seek focus (scrub bar) is unchanged. While the
  Up Next card is visible the pending target has no surface to render on,
  so RW/FF commit each press immediately (pre-hold behavior) and SELECT
  with a live session (a hold carried in by cadence commits) commits it
  before the focused card acts. Every session is item-bound at start and
  a commit whose identity changed is dropped (see
  `docs/guides/data-playback.md`).
- Up Next may appear during the end window, but its auto-play countdown starts
  only after the current item reports `Completed`. Play Now hides the controls
  before switching items so focus cannot remain on removed chrome. Not Now
  dismisses only the Up Next card and cancels its countdown for that item;
  the player stays open and BACK remains the exit path. The TV shell compares
  its local dismissal state with the shared immutable target/queue/countdown
  identity, so a new item, queue position, or playback generation re-arms the
  card without sharing TV focus state with the other player surfaces. After the
  consecutive-auto-advance threshold the
  post-completion countdown resolves to a Still Watching gate; a completed item
  remains in the player until that gate is resolved. The countdown uses the
  active queue's snapshotted autoplay preference: disabled means no automatic
  advance, zero means one immediate advance, and a positive value is the
  configured delay (10 seconds by default). A stale item/queue generation must
  cancel the countdown.
- Audio and subtitle timing pickers are reached through a single **Offset** row in
  the corresponding track picker (its current value is shown on that row only when
  non-zero), and only when the active backend reports support. The offset panel
  shows the cumulative value as its heading, and BACK from it returns to the track
  picker that opened it rather than dismissing to the player. They show the current
  value, use 50/250/1000 ms steps, provide Reset, clamp at ±20 seconds, and
  retain focus in the picker. Negative-step controls appear only when the backend
  reports both signs: audio on both backends and subtitle on LibVLC show both
  signs, while Media3 subtitle timing is PositiveOnly (negative steps are hidden
  and a short caption explains the limit, because a cue cannot be shifted earlier
  than its decoder callback). The key/source-bound offset is persisted when the
  latest adjustment is flushed, and the stored value is the applied (post-clamp)
  value — a negative subtitle request on Media3 is stored as zero; changing
  tracks or sources starts from its own stored value.
- Chapters, trickplay thumbnails, speed, subtitle appearance, and aspect/zoom
  controls remain D-pad reachable and restore focus to their opener. The remote
  **Player backend** control follows the same rule for remote playback: its modal
  projects policy-ordered concrete choices, keeps current/unavailable choices
  non-focusable, places first focus on the first selectable target, and supplies
  a modal-owned localized Close action when no row is selectable (including
  during switching). It traps focus, dismisses with BACK, and restores the
  control opener after either dismissal or a target-planning failure. `Auto` is
  not a player-picker row. Adding a
  `PlayerPicker` value requires updating every exhaustive picker branch.

## Grid (View All) screens

- Focus lands on the grid when the screen opens AND when returning from a
  detail page (the request must re-fire on every composition entry).
- Returning from a detail page restores focus to the EXACT previously-focused
  card, not the first one. This applies to all grid surfaces — View All,
  Library, Collection, Discover, and Find — through the route snapshot's
  stable-key path and focus-memory map. The grid reveals an off-screen target
  before requesting focus; if the item no longer exists, resolution stays in
  that group and uses the saved position or first child.
- Restoring focus into paged content pages through the normal paging path only
  until the saved position is covered or the source reports no more items.
  Never page beyond the saved position just to search for a missing item ID; a
  missing item resolves through the normal saved-position / first-child
  fallback.
- Grid focus-memory reads are entry-time only: composition must not subscribe
  to focus-memory snapshot state while focus is inside the grid. Entry indices
  are consulted only on entry, and entry-focus effects read those values in
  their bodies where reads are untracked, so a D-pad move never invalidates
  the whole grid.
- Focus-entry targets must be a real focusable CARD (the first visible one)
  — requesting focus on a focusGroup node silently does nothing.
- DOWN from the Shuffle/Sort pills always enters the grid; RIGHT from the
  drawer returns to the grid, never dropping focus.
- The grid owns BACK explicitly (player-style key interception): close the
  sort picker if open, else leave the screen. The dispatcher-based
  BackHandler alone proved unreliable here (BACK consumed nothing and
  cleared focus).
- Closing the sort picker returns focus to the Sort button; applying a sort
  scrolls the grid back to the top. Applying a sort/filter KEEPS focus on the
  Sort/Filter button — it must not jump into the grid or the drawer. For the library
  browse this required two things:
  - The Sort/Filter buttons must stay FOCUSABLE through the reload. Applying a
    sort/filter clears items and sets `isLoading = true`; if the buttons gate
    `enabled` on `isLoading`, the focused button drops out of the focus tree
    mid-reload (a disabled `FocusableBox` calls `focusable(enabled = false)`) and
    Compose evicts focus to the navigation drawer — the visible "drawer jump"
    (drawer briefly opens, then focus snaps back once loading ends). The buttons are therefore
    always enabled; re-selecting during a load is harmless (the paginator
    cancels and re-issues). This is why dismissing with BACK was always graceful
    — BACK triggers no reload, so the button never went disabled.
  - A pending coordinator restore suppresses default autofocus. A sort/filter
    reset clears only that grid scope's memory and starts a no-autofocus window,
    so recreated content cannot grab focus from the invoking control.
  On SELECT the picker hands focus to the button synchronously before closing;
  on BACK the root Box consumes the key before the overlay's `onDismiss`, so an
  async `LaunchedEffect` on picker-open restores it. No post-reload re-assert is
  needed once the button stays focusable.
- Movie-library browse exposes a labeled Shuffle All button that stays
  focusable while its queue is prepared. It queues every movie matching the
  active filters once, starts the first item, and shows empty/failure results as
  passive non-focusable text so D-pad focus is not stolen. Library sort pickers
  expose Bitrate only for movies and Last Episode Added only for shows.
- The last-selected sort persists to disk and is restored on launch: View All
  grids via `GridSortStore` (per server+row), library browse via `LibrarySortStore`
  (per server+library). Filters are not persisted.
- A TV shuffle queue is route-local and is cleared on player exit; it is never
  retained as browse state after the player closes.
- The grid uses Mario/center-pivot scrolling on the vertical axis; the focused
  row rides the middle of the viewport and the lazy grid clamps at the true
  content edges. Grid content padding reserves room for the 1.1x focused card
  on all four sides so clamped first/last rows and columns never clip.
- Vertical grid UP/DOWN traversal is index-based, not LazyVerticalGrid's
  geometric beam search: DOWN targets `focusedIndex + columnCount`, UP targets
  `focusedIndex - columnCount`, partial last rows clamp to the last real item,
  and trailing loading indicators are never focus targets. UP from the top row
  leaves the grid upward; on the library browse it targets the selected inner
  section button explicitly rather than relying
  on the flaky default spatial search, and the grid reserves extra top content
  padding (`TvDimens.libraryGridContentTopReserve`) so the top row's focused (1.1x) card
  clears the control header. DOWN from the last row is consumed and stays put,
  triggering paging first when that grid has more items.

## Library

- Library tiles route HERE, never to Detail (the pre-P1 bug). A library grid
  paginates near the end, exposes a Sort control and a Filter control
  (played/unplayed/favorite/genre), and PLAY on a focused tile plays directly.
- A movie in the grid opens Detail; a series opens Series; a sub-folder re-opens
  the library grid (kind-aware via `openTvItem`).
- Movie and show library hubs expose Recommended / Library only (Genres and
  Collections were retired — Discover covers both, and their results spanned every
  library rather than the one being browsed). Recommended is the default; other
  library types remain Library-only. RIGHT from the hosted rail enters the selected
  inner tab. DOWN from any inner tab enters the currently selected view's
  content.
- Library hubs do not repeat the selected library name above their content.
  In the Library inner view, the inner-view tabs, item count, Shuffle All when
  applicable, Sort, and Filter share one chrome row. Sort and Filter are absent
  from every other inner view. On the top-level hub, that complete chrome draws
  while focus is in the chrome or the first content row, and draws hidden for a
  lower content row or while the hosted rail owns focus. It remains composed,
  measured, focusable, semantically present, and attached to its stable
  requesters, so hiding it never reclaims its reserved space or changes the
  grid's viewport. The hero backdrop and text keep their existing geometry.
  The inner-view buttons remain in one persistent focus group, with the
  selected view as its entry fallback, while only the body swaps; each button
  owns a stable requester, so activating a view must not briefly hand focus to
  the rail. DOWN from any button invokes the selected view's explicit
  content-entry action and lands on its first available card, retry, or status
  target. Content autofocus must not override the user's button focus. Picker
  dismissal must still restore the invoking Sort/Filter control, and applying a
  sort must retain Sort focus throughout the resulting grid reload rather than
  handing focus to an inner-view tab. Sort/Filter use an opaque, focus-trapping
  dialog; UP at its first row and every other direction remain inside the dialog
  until selection or BACK dismisses it.
- Recommended keeps the Home-style fixed hero and horizontal shelves. The
  paginated Library grid and collections opened from a library show a settled
  focused-item hero only when `Show library grid hero` is enabled (the default).
  TV library heroes use the same effective vertical footprint as Home: Home's
  top overscan inset plus its hero height. The approximately 16:9 backdrop
  remains full-bleed behind the existing Library chrome footprint and extends
  beneath the first foreground shelf/grid row; this image overlap must not add
  content height or change focus/scroll behavior. Recommended, Library-grid, and
  library-origin collection heroes also apply the shared settled-item ambient
  background; on the top-level Library route that tint continues beneath the
  collapsed hosted rail just as it does on Home. Disabling the grid hero removes
  both its image and ambient tint.
- Recommended shelves reserve focus-scale room between each ribbon title and
  its cards, so an expanded first-row poster never covers the title. Library
  grid focus scrolling accounts for the title/subtitle below the focusable
  poster and must reveal the complete card when entering later rows. This
  trailing-edge visibility guard applies only while the hero constrains the
  Library viewport; hero-disabled and other full-height grids retain the base
  poster-centered Mario policy. Recommended
  uses the same stabilized vertical-ribbon ownership as Detail Related: the
  parent list disables implicit focus bring-into-view, entering a different
  ribbon scrolls that section explicitly, and horizontal rows own only their
  horizontal Mario scroll.
- Library focus is divided into three explicit regions: the persistent inner-view
  selector, the Library-only Shuffle/Sort/Filter action strip, and the content
  grid. The selector restores the selected view. The action strip restores its
  last stable action and falls back to its leftmost available control (Shuffle
  All for movie libraries, otherwise Sort). The grid restores its last focused
  stable item and falls back to its
  first visible item. DOWN from any view button or the action strip enters the
  selected view's content; RIGHT from the selector's trailing item enters the
  action strip; UP from the action strip returns to the selected view; and
  top-row grid UP always returns to the selected inner-view selector, never Sort.
  Every Android TV `LazyVerticalGrid` (Library/Favorites, View All, Discover
  facets, and Collection) routes UP/DOWN through `TvGridFocusNav`, which consumes
  excess held-remote repeats so scroll, composition, and focus cannot outrun one
  another. Full-screen composed targets rely on the grid's row-aware Mario
  bring-into-view policy; the coordinator scrolls explicitly only when a target
  is not composed. The hero-constrained Library grid also reveals successful
  indexed vertical handoffs explicitly so partially composed rows remain
  attached; the hero-disabled Library grid uses the same single-owner behavior
  as full-height View All, and horizontal moves remain owned only by each grid's
  bring-into-view policy.
  Favorites, Discover-origin filtered grids, and Discover-origin collections
  are not hero-eligible. Disabling the setting must remove the hero composable
  and its reserved space so the grid expands immediately without resetting its
  scroll or focus state. The Library grid keeps enough top content padding for
  a focused/scaled first row to remain fully visible below the hero.
- `Remember last library view` is enabled by default and restores an available
  inner view per server/user/library on the next entry. Turning it off does not
  move the current screen. Loading and error content must retain a D-pad route
  back to the always-composed inner tabs; picker dismissal continues to restore
  the invoking control.

## Find

- D-pad on-screen keyboard drives the query; results are grouped by tab
  (All/Movies/Shows/Episodes); recent searches and a clear-history action show
  when the query is empty.
- Search field focus only highlights/restores focus. The soft keyboard opens
  only from explicit select/click activation, never from focus entry.

## Downloads

- When the effective session content-download permission is enabled, Downloads
  is a top-level destination in the shell-owned drawer, between Favorites and
  the user's media libraries. It keeps one shared
  `DownloadsViewModel` keyed by session inside the route's saveable scope; the
  screen must not add a second rail, queue, or transfer-policy implementation.
- Movie and concrete Episode details place Download in the primary action row
  alongside watched, favorite, and media information actions. The action opens
  the shared request dialog, opens Downloads for retained work, or starts the
  completed local item through the offline player route.
- One route focus scope owns the Manage allocation control, the conditional
  interruption Resume action, and one stable row target per opaque `DownloadId`.
  Resume is the preferred entry target while it is present. Initial loading parks focus in content;
  subsequent entry and restoration reveal the semantic target before requesting
  it and fall back through the remaining controls when a row disappears.
  DPAD-LEFT or BACK opens the drawer with Downloads selected.
- SELECT on a row opens the focus-trapping action dialog. Dismissal and the
  Cancel/Delete confirmation flow return to the invoking stable row when it
  still exists, while a leased artifact leaves Delete disabled. A Queued row
  waits for the single transfer slot and offers Cancel rather than a manual
  Start action. The media PLAY
  key on a Completed row invokes the same explicit offline-play action without
  opening the dialog. The allocation dialog offers policy-accepted presets and
  a capacity-bounded custom numeric whole-GB input; its nested editor stays
  focus-trapped, and BACK leaves text editing before it can dismiss the dialog.

Transfer, artifact, authorization, quota, and offline-resolution semantics are
owned by [Downloads And Offline](data-playback.md#downloads-and-offline).

## Discover, People, And Collections

- A section-tab strip (Genres / Studios / Collections / Suggestions / Upcoming)
  sits above a single grid that shows ONE section at a time (no filters). DOWN
  from a tab enters the grid; UP returns to the tabs. Focus MUST survive
  RIGHT-from-drawer and re-entry — use the grid entry-item pattern (focus follows
  the first VISIBLE item, always composed) with the always-composed section tabs
  as the fallback anchor; never pin the return target to a lazy list's item 0.
- Genre/studio tap opens the library grid pre-filtered; collection tap opens
  `TvCollectionScreen`; cast/crew and person taps open `TvPersonScreen` (header +
  films + paging).
- Person is a plain `Column` scroller (all sections stay composed, as on Home)
  composed of two sections: the portrait header plus the FIRST non-empty ribbon
  are one section, and the remaining ribbon is the second. The vertical axis runs
  a no-auto-scroll spec, so the only vertical movement is explicit: focus
  anywhere in the first section returns the column to offset 0 — the person image
  is always fully visible while its ribbon is focused — and the second section
  performs a minimal reveal of its own bottom edge. Each ribbon keeps Compose
  minimal visibility on its own horizontal axis. At the Large tile size the first
  ribbon's card labels may fall below the fold; the portrait wins.

## Settings And Authentication

- Quick Connect login shows a large code and polls until authenticated (never
  render/log the Secret). Settings hosts an account switcher (switch / add;
  per-account Sign out is not surfaced on TV) and editable playback preferences.
- Settings is one vertically scrollable stack of seven fully composed,
  horizontally scrollable category rows. The stable semantic matrix is Account
  (Server, Signed in user, Switch Users, Add account, Logout), Appearance,
  Playback, Skip segments, Device
  playback, Services & About, and Diagnostics (Collect diagnostic logs, Send
  diagnostics to server, Verbose system logging, and Show playback info at
  start) as
  defined by `TvSettingsTileId`. The standard rows have five tiles; the Playback
  row may extend horizontally for its backend and autoplay controls, and the
  Diagnostics row has four. Every tile is
  focusable, including read-only detail tiles. The hosted rail and shell-owned
  palette-aware backdrop remain; the page title, mockup logos, oversized titles,
  subtitles, and sticky section navigation do not. Each row owns an independent
  horizontal scroll state, fixed-size tiles, wider inter-tile spacing, and focus
  reserve inside its scrollable content so future additions can extend past the
  viewport without clipping focus chrome. Each tile uses a focusable circular
  icon-only surface with centered, one-line ellipsized title and current-value
  metadata below it; a focused overflowing title marquee-scrolls while an
  unfocused title remains ellipsized. Focus border, glow, and optional zoom
  apply only to the circle, not the metadata. A dedicated circle-to-title gap
  and row focus reserve must clear the scaled circle and glow without moving or
  clipping the metadata.
- Entry from the hosted rail retries the stable Server tile. The pure grid
  resolver owns every D-pad move: LEFT and RIGHT change exactly one column, UP
  and DOWN preserve the column when available (clamping only for a future shorter
  adjacent row), LEFT from column zero returns to the rail, and top/right/bottom
  outer boundaries are consumed. It never uses geometric focus search. The
  row-aware Mario policy owns vertical reveals while each category's horizontal
  Mario policy scrolls its focused tile toward center and clamps at the ends.
- A centered, window-backed Settings dialog is the only reachable focus tree
  while open. It dims but leaves the grid/backdrop visible; its current enabled
  choice (or primary action) receives focus after composition, its directional
  boundaries are consumed, SELECT applies once, and BACK cancels without a
  write. Every dialog shows one non-focusable "Back to cancel" footer hint and
  has no per-dialog Close/Cancel button. Disposal restores the exact stable
  opener tile. Capability-dependent audio/HDR options remain focusable with an
  unavailable explanation, but cannot be selected; missing capability data is
  shown as Unknown. Text-only dialogs focus their window content fallback.
- Switch Users opens the existing switch-account flow and Add account opens the
  credential flow. When no non-active account exists, the switch dialog says
  “Add an account before switching”; otherwise it lists the accounts and marks
  the live active account. Logout is the only sign-out surface; per-account
  sign-out is never rendered. Account, clear-subtitles, and refresh actions gate
  duplicate submissions and retain their progress/error state in their owning
  dialog.
- All TV text-entry fields, including Settings, authentication server-entry and
  login, and Find, use one click-to-edit contract: D-pad focus highlights a
  non-editable display field only; SELECT mounts the editable `BasicTextField`
  and opens the IME; IME Done/Search/Next/Go or BACK returns to the display
  field and hides the IME.
- The OpenSubtitles consumer-key field is masked, click-to-edit, device-global,
  and retained across Jellyfin logout. Clear downloaded subtitles is a separate
  destructive storage action and does not clear Jellyfin watch state.
- All mutable Settings values use their existing persisted option sets through
  centered choice/input/confirmation dialogs: Ocean/Midnight/Ember;
  Small/Medium/Large; On/Off; the shared eight-rung quality projection
  (80/40 Mbps at 2160p, 20/12/8 Mbps at 1080p, 4/2 Mbps at 720p, and 1.5
  Mbps at 480p) plus typed Auto, Original, and an exact Fixed off-ladder Custom
  bitrate-only row. In-player quality additionally offers Use playback default,
  which clears only the current-playback choice and shows the active inherited
  quality plus whether it comes from the general or VLC Playback setting;
  ExoPlayer/mpv/LibVLC (beta) in production; and an optional VLC
  default quality. That
  backend-specific default applies before the initial request whenever LibVLC
  is active and uses the same bitrate-and-resolution rung semantics as Fixed
  quality; "Use playback default" clears it back to inheriting the general
  quality policy, and the Android stored-value seed for this setting is owned
  by `docs/guides/data-playback.md`. Autoplay delay
  remains Immediately/5/10/15/30/60 seconds; Still watching On/Off;
  Auto/Stereo PCM/Passthrough; Auto-skip/Ask/Ignore; and Auto HDR/Prefer SDR.
  Android TV consumes the Android domain backend policy: ExoPlayer is the
  default, `Auto` is not visible, and the order is ExoPlayer, mpv, LibVLC.
  mpv is the opt-in alternate TV backend, selectable in the existing backend
  dialog as an ordinary option with no qualifier label — production
  option labels carry qualifiers (such as LibVLC's beta tag) only where trust
  is genuinely reduced. Selecting it persists through the same server-scoped
  preference and applies to the next playback session. Text inputs keep the shared
  click-to-edit contract. Read-only tiles open detail dialogs rather than
  becoming dead focus stops.
- Settings tiles follow the existing Focused-card zoom preference: enabled uses
  the standard `1.1f` TV tile scale and disabled keeps `1f`. Their theme-derived
  focus glow matches the launch/login treatment (4dp spread at 14% accent
  opacity), and the Theme dialog renders the shared palette swatches for Ocean,
  Midnight, and Ember.

## Watch Next

- A WorkManager job reconciles the Fire TV Watch Next row by Jellyfin item id
  (no duplicate programs); clicking a program deep-links via `onNewIntent` into
  the item. The normal (non-deep-link) launch path is unchanged, and sync never
  runs on the main thread or blocks app start.
- Passive reconciliation preserves each retained provider program's last
  engagement timestamp. Only a genuinely new insert receives the timestamp
  derived while building its Watch Next program, so hourly sync cannot present
  unchanged items as fresh user engagement or disturb launcher ordering.

## Tiles And Cross-Cutting Rules

- Focused tiles use a cyan border and 1.1x scale by default. The TV Appearance
  setting may disable focus zoom, but the border and remaining focus treatment
  stay. Do not add a drop shadow; it reduces title legibility on hardware.
- Home media ribbons use one shape per row. Next Up is uniformly wide because
  Jellyfin's `/Shows/NextUp` contract supplies episodes: every returned item,
  its loading placeholder, and View All use the existing wide backdrop/decode
  geometry. This remains true for an unexpected non-Episode response so one
  anomalous item cannot create a mixed row. Continue Watching, Recently Added,
  and Favorites are uniformly 2:3 posters. Shape-specific lazy content types
  prevent incompatible slot reuse. Library tiles remain wide.
  Progress bars inside scaled/bordered cards are inset by the focus-border
  width. Watched state uses a compact cyan check and unplayed count uses a cyan
  top-end pill.
- Reused dimensions come from `TvDimens`. TV geometry is tuned at 1080p/320dpi
  and must also be checked on 4K hardware.
- Never build focus or saveable-state behavior on claims about Compose's
  internal `compositeKeyHash` unless verified against Compose source. The
  focus architecture relies only on observed app behavior and stable
  application-owned keys.
- TV diagnostics follow the shared
  [logging and privacy policy](data-playback.md#diagnostics-logging-and-privacy).

## Why

Rationale for rules this guide states — the choice, its reason, and the
rejected alternatives.

### Home media-card geometry

- **Ribbon identity owns the Home card aspect.** Next Up is a Jellyfin episode
  feed, and episode primary art is not a reliable poster source, so the whole
  row uses the existing wide backdrop/decode path. The generic wire envelope
  does not justify allowing an unexpected response kind to change one tile's
  measurement; it stays wide with the row. Loading and View All match the row,
  while the other Home ribbons remain poster rows. Rejected: per-item geometry,
  a poster View All inside a wide row, changing shape after content arrives,
  regrouping server results, and sharing one lazy content type across shapes.

### Player trickplay preview

- **The fetched sprite decides whether preview chrome is visible.** Jellyfin
  metadata can outlive a deleted or unavailable trickplay image, so drawing the
  black frame from metadata alone leaves an empty box above the seek bar. A
  pending seek target, not focus, starts the image request because the live video
  already represents the current position. A final-commit callback retains that
  target across the seek-to-buffering handoff, and player status removes it when
  loading/buffering recovery ends; cancellation cannot retain a target, while a
  bounded grace clears already-buffered seeks that never report buffering. The
  preview is centered over the target until edge clamping is required, where
  its shallow curved pointer narrows and leans toward the exact position rather
  than forming a broad edge hook. A short presence-keyed fade and size collapse
  softens removal without cross-fading every D-pad target; item identity owns
  the animation so a prior video's frame cannot survive a source change. The
  frame and image draw only after success. A Canvas owns
  the full-sheet draw and clipped crop because a normal oversized child is
  constrained to the one-frame parent before its offset. Rejected: an
  administrator setting query,
  current-position prefetch or preview on focus alone, retaining canceled or
  stale targets, an unbounded wait for buffering, a permanent failure latch,
  visible loading or error placeholders, and constraint-clamped sprite
  children.

### Related shelf focus

- **TV backend switching reuses the player picker contract.** The existing
  focus trap, first-selectable target, modal-owned Close fallback, BACK
  dismissal, and opener restoration already express a bounded D-pad choice.
  Rejected: a separate backend dialog, focusable unavailable/current rows, an
  Android-TV-only backend policy, and changing queue/video navigation while the
  picker is added.

- **TV owns focus and scroll, not related-source policy.** The shared model,
  repository, and detail consumers already establish deterministic shelf
  identity and the four-shelf cap; TV only needs stable per-shelf containers and
  scroll handoff after empty groups disappear. Rejected: a TV-specific source
  list or count, which would let D-pad order drift from Shared Detail.

- **TV reuses the exact shared debug projection while keeping display policy
  local.** A shared popup layer put developer data over D-pad controls; hiding
  controls or making debug focusable would trade away playback interaction. The
  shared five-layer order preserves controls, notice priority, and modal
  ownership, while the exact shared projection prevents shell drift without
  treating the complete diagnostic inventory as suitable for the top-band panel.
  Display refresh matching remains owned by the TV controller rather than
  becoming cross-platform overlay data. Rejected: shell-local numeric/formatter
  copies, a TV-only generic visibility filter, the noisy complete overlay, and
  feeding TV display state into a cross-platform model.
- **TV owns mutable Up Next dismissal and focus.** Only the immutable
  item/queue/countdown identity is shared, because the TV shell has distinct
  BACK precedence, focus handoff, and Still Watching behavior. A global
  dismissal state would couple those platform-specific lifecycles.
- **One focus kernel with confirmed-focus consumption, because competing
  memory systems kept regressing.** TV focus restoration once had four
  unreconciled memory systems — Compose `focusRestorer`, app-level stable-ID
  saveables, index-based requester maps, and per-screen generation counters —
  and they regressed whenever nested routes disposed and recreated focus
  trees. Failure mechanisms included resetting a restore generation after a
  successful return re-armed its expiry flags, letting the armed Home card
  steal focus again when a lazy row recomposed it (directional navigation then
  jumped over cards); row re-entry hung off a `focusRestorer` fallback attached
  to a possibly uncomposed first lazy item; and Library return restoration
  marked itself handled even when focus never landed. The merged design gives
  restoration explicit, non-competing owners (a shell route coordinator, a
  key-based focus kernel, a narrow shared detail bridge), makes only confirmed
  focus consume a restore, and lets only deliberate key input cancel one.
  Another local fix was rejected: it would have patched one symptom while
  keeping the mechanisms that produced the regressions.
- **Downloads is a hosted-drawer, stable-key TV surface when the effective
  session permission allows it.** Reusing the retained shared state/action
  owner keeps TV focus and remote presentation separate from the authoritative
  download policy, while a route scope keyed by Manage and opaque download
  identity survives regrouping and lazy disposal. A screen-local rail, row-index
  focus memory, direct destructive row actions, and focusable section headers
  were rejected because each introduces a competing navigation owner or makes
  ordinary list changes redirect a destructive action. A false effective
  permission hides the drawer destination and ordinary route rendering. The
  conditional Resume action is another stable semantic key, rather than a
  transient unfocusable banner, so interrupted work is immediately actionable
  and focus can recover when the action disappears.
- **Lazy prefetch and media transport keys do not bypass the focus/input
  contracts.** Enlarging a lazy-layout cache window can compose or attach items
  around an active pending-focus transaction, so it requires specific proof of
  compatibility before adoption. Rewind and fast-forward retain media
  transport semantics rather than becoming grid-paging shortcuts. Rejected:
  speculative cache-window expansion and repurposing transport keys for browse
  navigation.
- **The TV shell decides how the panel presents; the domain decides what is
  streamed.** Refresh-rate matching consumes plan metadata *after* planning
  and never feeds back into device profiles, PlaybackInfo requests, or
  stream-mode decisions — that would violate the shared-domain-owns-playback-
  strategy rule. An inexact cadence match is recorded as a distinct fallback
  tier because it still judders.
- **TV text entry is click-to-edit only.** D-pad focus must never open the
  IME — arrow keys went to the text cursor instead of the focus system, the
  root of both "cannot navigate" reports. Commit/focus-restore ordering is
  fixed so a caller that moves focus elsewhere wins over the field's own
  restore.
- **The Libraries ribbon was removed from TV Home.** The TV drawer
  and the shared Library tab already list the user's libraries, so the shelf
  spent a request and a screenful of Home on a duplicate. Rejected: moving it
  lower in render order (duplicate content wherever it sits) and keeping the
  fetch behind a hidden row (a request nothing renders). The generic
  unsupported badge and kind-aware navigation branches stay for future
  producers.
- **TV Person groups the portrait and its first ribbon into one scroll
  section.** Default vertical bring-into-view scrolled just far enough to
  reveal a focused ribbon and clipped the portrait off the top; the fix is the
  detail-screen pattern — a no-auto-scroll vertical spec plus explicit
  per-section targets, with header and first ribbon as one section that always
  returns to offset zero. Rejected: a lazy column (lazy disposal of the second
  ribbon breaks its focus-restore effect) and pinning the header (costs the
  second ribbon its height).
- **Production option labels carry qualifiers only for genuinely reduced
  trust.** TV mpv is an ordinary option without a provisional qualifier; an
  interim "(tested)" label was rejected because a beta tag should remain only
  where trust is actually reduced.
- **Detail Version is a sibling of the existing modal track pickers.** The
  focus trap, current-row initial focus, BACK dismissal, and opener restoration
  already express the D-pad contract for a bounded choice. A new focus
  coordinator, chip row, or duplicate TV detail implementation was rejected;
  disappearing-source fallback is handled by closing the modal and targeting
  the surviving Version action or Play.
- **TV entry focus reads are scoped to a route entry, and the remediation is
  recorded as mechanism-only.** Home captures its initial row, item, index, and
  View All target once per route-entry identity, presentation epoch, and
  matching ready-restore token. A matching ready restore is observed and wins;
  otherwise the matching current-entry `activeRouteEntryId` and `activePath`
  are read without observation. That allows a newly ready restore to replace
  the entry snapshot while preventing `recordFocused` D-pad updates from
  invalidating the whole Home route lambda. Grid focus-memory reads likewise
  remain entry-time only: composition does not subscribe while focus is inside
  the grid, and entry-focus effects read their values in their bodies where
  reads are untracked. Rejected: a live route-level `activePath` subscription
  (it keeps the per-move Home invalidation), memoizing the grid index lookup
  (it keeps the per-move invalidation), copying focus state into a second owner
  (composition legitimately reads the snapshot state elsewhere), or omitting
  the ready-restore token from Home's entry identity (a newly ready restore
  would not replace the snapshot). The mechanism fix does not certify a
  performance result; traversal cost and focus-state invalidation are separate
  questions.
- **Library hub chrome hides at draw time because its footprint and focus
  contracts are persistent.** The hub can draw the tabs and Library actions only
  for chrome or first-row focus, while lower-row and hosted-rail focus gets an
  immediate visual hide. Keeping the nodes composed preserves the grid viewport,
  hero geometry, semantics, stable requester attachment, directional routing,
  and picker/reload restoration. Rejected: collapsing or removing the chrome,
  reclaiming its space, changing focusability, and animating the transition;
  each would change a contract that the visibility rule must preserve.
- **The TV grid hero and ambient backdrop are retained as product features, not
  as a waiver for expensive implementation.** Full-screen imagery is part of
  the intended TV experience, so removing the hero, replacing it with a static
  tint, suppressing its transition during sustained D-pad input, or shortening
  the ambient animation solely to improve traversal is rejected. Pixel
  summaries must still use bounded output-sized decoding and off-Main work as
  required by [`compose-performance-audit.md`](compose-performance-audit.md);
  revisit the feature only for functional input loss, and revisit its
  implementation whenever a cheaper equivalent exists.
