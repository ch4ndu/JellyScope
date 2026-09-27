# Android TV Screen Behavior

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Detail / series screens

- Entry focuses primary Play/Resume. Version, audio, and subtitle dialogs trap
  focus on the current or first option and restore the opener on Back.
- Download dialog options scroll at full row height; Cancel and confirmation
  stay outside the scroll region.
- Detail subtitle search follows Off, Jellyfin tracks, and installed assets.
  Language, results, progress, errors, quota, close, delete, and retry are
  D-pad-reachable. Search replaces the track dialog and restores the subtitle
  action when closed. Player pickers contain installed rows only; passive notices
  are not focus targets.
- Detail action buttons use 12/6 dp padding, wrap width, pill shape, and one fixed
  row height whose children fill it. Labels do not clip. D-pad Version/track
  choices with at least two options are compact action buttons that expand to a
  capped, ellipsized current label plus arrow; the modal shows full labels.
  Version is omitted with zero/one source; track omission/static-text rules stay.
- If refresh removes the active Version choice or action, close the dialog and
  restore the surviving Version action or Play/Resume. This applies to movie,
  concrete episode, series, and season.
- Buttons are dark raised surfaces at rest and solid white with dark content only
  while focused. TV Material setting toggles are one focusable `ListItem` with a
  non-interactive trailing `Switch`, using the JellyScope palette.
- Play reads "Play"; Resume reads `Resume from <pos>` with time-left below.
  Favorite/Watched toggle labels and icons with state and remain focusable during
  an in-flight write, while click is gated.
- Episode metadata starts with series and S/E label in Detail, Home hero, and the
  player top bar.
- Related groups use the shared [data order and cap](api-and-caching.md#related-shelves)
  and [UI presentation](ui.md#related-shelves). TV creates a stable focus/scroll
  group for each rendered non-empty shelf.
- When available, Detail may show clear-logo title, italic tagline, studio,
  Trailer, IMDb/TMDb, and resume progress. External-link chips stay
  compact/desktop-only. Person cards keep poster width with a 3:4 portrait.
- TV lazy text containers use wrap content, bounded lines, and padding, never a
  fixed height. Hero title uses at most two lines and overview three. The
  Detail/Series list uses `heroTopInset`; entry and the rising edge of any action
  row focus animate to item zero so the complete hero remains visible.

## Season screen

- Keep season tabs visible. Initial entry may focus the first episode at item
  zero; season changes preserve tab focus and vertical scroll.
- Reserve stable lines for focused episode metadata: title two lines and overview
  three, ellipsized. This is not a fixed-height lazy text container.
- Up from episodes targets the selected season tab, which scrolls into composition.
  Horizontal episode/cast motion uses the shared dead zone and does not move the
  vertical list.
- Episode strip, episode actions, and cast focus are keyed per selected season.
  Season changes reset that state and the next action target to Play. Loading
  stays in the episode area; the header and overview remain composed without
  a route transition or new entry.

## Player

- Controls auto-hide after five seconds of idle playback; every key press resets
  the timer. Reveal focus is Play/Pause, except Left/Right seek reveals the scrub
  bar and picker return restores its opener.
- Root media-key handling works in every overlay, picker, loading, buffering, and
  error state. Rewind/Fast-forward seek; Play starts; Pause pauses; Play/Pause
  toggles; Next advances queue; Previous restarts after five seconds or selects
  the prior item; Stop follows stop-and-back. Dedicated media keys are consumed
  before other branches and never activate segment Skip. Remote Play on a
  playable browse tile starts it at resume position; non-playable tiles ignore it.
- Pickers own normal navigation while root transport keys remain active. Closing
  track, quality, Chapters, Speed, Subtitle Style, Resize, or backend menus restores
  an attached opener, otherwise Play/Pause.
- Audio is available with at least two tracks or timing support, keeping Offset
  reachable for one track. Subtitle is available with at least one track because
  Off is another choice. Chapters appears only with chapters. On menu open,
  focus and reveal the currently playing chapter once; distinct chapter-index updates change
  selection without moving focus. Invalid open menus close and fall back safely.
- Subtitle Style appears only for active local-text rendering, never Off, Unknown,
  or burned/transcoded subtitles. Installed subtitle selection is response/native
  confirmation-authoritative, not inferred from codec. Replans retain installed
  plan truth until replacement; stale confirmations remain pending. Exact late
  confirmation may activate. Local activation failure shows a token-keyed,
  passive six-second server-rendered fallback banner without focus or scrim.
- Health/recovery notices persist bottom-center above either overscan or visible
  controls. They do not alter focus when they appear. Every advertised action is
  reachable after deliberate entry. Removing a focused notice first reveals
  controls and, after a short delay rather than `withFrameNanos`, safely focuses
  Play/Pause, falling back to the player root while a modal is open; an unfocused
  notice acts immediately. Re-arm auto-hide afterward. PiP defers interactive
  replans.
- Recovery actions and stream-change authority follow
  [quality policy](playback-policy.md#quality-semantics). TV makes
  each advertised action D-pad-reachable and dispatches semantic ViewModel
  events, never controller calls. Apple TV presentation belongs to
  [native tvOS screens](tvos-ui.md#native-tvos-screens).
- TV follows the shared
  [player overlay-layer order](ui.md#player-overlay-layers); each TV surface
  selects the layer assigned by that UI contract.
- The seek bar shows buffering, thickens with a thumb when focused, and routes
  Down to Play/Pause. Trickplay starts only for a pending seek, not focus/current
  position. Show a frame only after the current sprite tile loads. Draw the full
  sheet with uniform aspect-fit scaling, centered and clipped to the selected
  frame. Keep that frame through seek loading/buffering, then fade/collapse it. Clamp horizontal tracking and use
  a shallow pointer that narrows near edges. Item changes dispose old preview.
- Play/Pause and overlay controls use translucent dark circles at rest and white
  with dark glyphs when focused.
- Debug toggles a persistent, non-modal, non-focusable top band. It has no scrim,
  close row, or Back behavior and stays above transport controls. It renders only
  shared `playerDebugSections` in two columns, including transcode reasons and
  subtitle rows. No playback info yields Unavailable plus Status. Display refresh
  matching remains TV runtime policy outside the shared projection.
- Back closes the innermost local menu, picker, or Up Next before the main overlay.
  Back hides the main overlay in every playback state; the next Back exits.
- Loading/Buffering use a white arc without text or scrim. `AdaptiveSpinner` uses
  `withInfiniteAnimationFrameMillis`, not tween-based infinite transition, so TV
  system animation scale zero cannot freeze functional loading feedback.
- LibVLC seek and non-zero-resume buffering remain until the clock advances after
  its initial target jump. A delayed probe does not clear a stalled spinner;
  repeated native buffering callbacks are coalesced.
- Non-fatal audio loss shows a passive six-second notice and persistent audio-off
  glyph without focus/input changes. Fatal errors retain Retry/Cancel and primary
  focus.
- Lifecycle `ON_STOP` stops playback, reports progress, and pops Player; relaunch
  returns to its origin. `MainActivity` remains `singleTask`. True queue-end
  `playbackEnded` exits once; raw Completed may advance queue or wait for Still
  Watching.
- Display refresh matching is default-off and uses plan frame-rate metadata only.
  It preserves resolution, classifies exact/integer/2.5x matches, and resets mode
  on stop/back, lifecycle stop, queue end, fatal error, setting disable, invalid
  metadata, or disposal. It never changes Jellyfin planning or becomes a debug row.
- With a queue, exactly one page below the scrubber shows controls plus Up Next
  hint or the Up Next ribbon. Down/Up swaps pages and focus; Back dismisses the
  whole overlay. Each reveal starts on controls. Selecting a queue tile stops the
  current item before planning the selected item; mark the current tile. Page
  visibility follows authoritative `controlsVisible` state. Episode badges use
  Jellyfin season/episode numbers, and metadata never falls back to a raw ID.
- Segment Skip appears only for matching Ask policy: Intro/Recap use Skip Intro,
  Outro/Preview Skip Credits, Commercial Skip. Ignore hides; AutoSkip seeks once
  per segment/item while Playing; unknown types hide. With hidden controls,
  Select/Enter/Space activates Skip; dedicated Play/Pause remains transport.
- Left/Right and Rewind/Fast-forward hold-to-seek maintain an item-bound pending
  target with duration-scaled acceleration. Each second repeat advances it;
  key-up commits one seek through the shared watchdog/cadence contract. Mid-hold
  Play/Pause/Select commits first; Back cancels then dismisses; item change drops
  the hold. Root preview handling owns key-up. Up Next has no pending-target UI,
  so Rewind/Fast-forward commit per press there.
- Up Next may appear near the end, but countdown starts only at Completed. Play
  Now hides controls before switching. Not Now dismisses/cancels only the current
  item. New item, queue position, or generation re-arms it. After the auto-advance
  threshold, Completed waits for Still Watching. Snapshotted autoplay uses
  disabled, immediate zero, or configured positive delay (default ten seconds);
  stale identity cancels countdown.
- Audio/subtitle Offset is one row inside its track picker and appears only when
  supported. The nested panel returns to that picker, shows cumulative value,
  uses 50/250/1000 ms steps and Reset, clamps to +/-20 seconds, and preserves
  focus. ExoPlayer, mpv, and LibVLC audio support both signs; mpv and LibVLC
  subtitles support both signs. Media3 subtitle is positive-only with negative
  controls hidden and an explanation. Persist the applied post-clamp value per
  key/source when the latest adjustment flushes.
- The remote Player backend modal uses policy order, traps focus, disables current
  and unavailable rows, focuses the first selectable row, supplies Close when
  none is selectable, omits Auto, and restores its opener after Back or planning
  failure. New `PlayerPicker` values update every exhaustive branch.

## Grid (View All) screens

- Stable IDs preserve focus, while click/Play callbacks capture current complete
  card data so same-ID refreshes update playability and resume state.
- Entry and return request grid focus. Back restores the exact card across View
  All, Library, Collection, Discover, and Find. Reveal an off-screen target; a
  missing target uses saved semantic position then first child in the same group.
- Restore paging stops when saved position is covered or no more items exist; it
  does not page indefinitely for a missing ID. Library, Collection, and Person
  pause automatic restore paging on page error while preserving target and
  `hasMore`.
- Focus-memory reads are entry-only and untracked inside entry effects. D-pad
  movement must not invalidate the grid. Entry targets a real card, never only a
  `focusGroup` node.
- Down from Shuffle/Sort enters the grid; Right from drawer enters the grid.
  Root Back closes sort first, otherwise leaves the screen.
- Picker selection restores its button synchronously, then applies sort/filter
  while keeping that button focusable through reload. A grid-scope reset opens a
  no-autofocus window so recreated content cannot steal focus. Sort application
  returns content to the top. Back restoration occurs through root interception
  and the picker-open effect.
- Movie Library offers focus-stable Shuffle All and conditionally exposes
  Bitrate; shows expose Last Episode Added. Passive text reports empty/failure.
  Sort persists per server/row or server/library; filters do not. TV shuffle
  queues are route-local and clear when Player exits.
- Grids use center-pivot vertical Mario scrolling and four-edge focus-scale
  padding. `TvGridFocusNav` routes Up/Down by index and column count, clamps
  partial rows, consumes movement past the last row, and excludes loading/Retry
  from media indices. Top-row Up leaves toward the explicit header target.
  Approaching the end pages unless latched by error; error navigation reveals
  Retry and returns to the originating media item. Person ribbons follow the
  same Retry-return rule.

## Library

- Drawer library tiles open Library, never Detail. Library cards are kind-aware:
  movie -> Detail, series -> Series, folder -> nested Library; Play starts a
  playable card.
- Movie/show hubs expose Recommended and Library, defaulting to Recommended.
  Other types expose Library only; Discover owns Genres and Collections. Right
  from rail enters the selected view's content, including on first entry; Up
  from its top content row reaches the selected Recommended/Library button.
  Down from that button enters the view's content.
- The selected library name is not repeated. Library view uses one persistent
  chrome row for inner tabs, count, optional Shuffle All, Sort, and Filter.
  Sort/Filter exist only in Library view. Chrome draws only for chrome/first-row
  focus, but remains composed, measured, semantic, focusable, and space-reserving
  while visually hidden for lower rows or rail focus.
- Tabs are one stable focus group while only the body swaps. Entry and dismissal
  resolve current actions at invocation. Down enters first card, Retry, or status;
  content autofocus never overrides a tab/control. Sort/Filter use opaque focus
  traps and retain opener focus throughout reload.
- Recommended uses Home-style hero and shelves. Library grid and library-origin
  collections show the settled focused-item hero only when Show library grid hero
  is enabled (default). The image and ambient tint occupy Home's effective hero
  footprint, extend beneath foreground without adding height, and continue under
  the collapsed rail on top-level Library. Disabling hero removes image, tint,
  and reserved height without resetting grid focus/scroll. Discover/Favorites
  surfaces never use it.
- Recommended reserves title-to-card focus room and uses one vertical scroll
  owner with horizontal Mario rows. Hero-constrained Library scrolling reveals
  the full poster plus labels; hero-disabled/full-height grids use base
  poster-centered Mario behavior.
- Library focus regions are inner tabs, Library-only actions, and grid. Tabs
  restore selected view; actions restore their stable key or leftmost available;
  grid restores stable item or first card. Right from the last tab enters actions;
  Up from actions returns to selected tab; top grid row Up returns to selected
  tab, never Sort. `TvGridFocusNav` consumes held-repeat overflow. Explicit scroll
  is only for unattached restore targets; full-screen grids use their row policy.
- Remember last library view defaults on and restores an available view per
  server/user/library on next entry. Disabling it does not move the active screen.
  Loading/error states retain a route back to always-composed tabs.

## Find

- The on-screen keyboard drives query; tabs are All, Movies, Shows, Episodes.
  Empty query shows recent searches and Clear history with disjoint saveable keys.
- Explicit IME/recent submission cancels debounce and captures the complete
  request. Result focus waits for that request's terminal success; stale visible
  results cannot satisfy it. Error/empty/replacement consumes the pending intent;
  typing, clear, person selection, or tab navigation cancels it.
- Field focus only highlights/restores. Select/click opens the keyboard.
- The results container owns row-aware vertical focus scrolling. Result and chip
  rows explicitly own horizontal scrolling, so moving within a visible row
  preserves vertical position; Up/Down reveals the destination row.

## Downloads

- With permission, Downloads is a top-level drawer destination between Favorites
  and libraries. One session-keyed shared `DownloadsViewModel` lives in route
  scope; TV adds no queue, policy, or rail.
- Movie/concrete Episode Detail places Download in the primary actions. It opens
  the request dialog, existing transfer, or completed offline Player route.
- One focus scope owns Manage, conditional Resume, and each stable `DownloadId`.
  Resume is preferred while present. Initial loading parks focus; restore reveals
  its semantic target and falls through remaining controls when a row disappears.
  Left opens the drawer only from the first grid column; other columns move
  to the preceding card. Back opens the selected drawer destination.
- Standard asset cards open offline details with the normal hero and actions.
  Source, track, credit and chapter facts are display-only; Up/Down scrolls them.
  Back restores the card or nearest survivor after deletion.
- Delete requires confirmation and is disabled while leased; routine cleanup
  shows no in-use error. Queued offers Cancel only; Completed Play starts offline
  playback. Allocation accepts presets and bounded whole-GB input; Back leaves
  nested text editing before dismissal.

Transfer and storage semantics belong to
[Downloads And Offline](downloads.md#downloads-and-offline).

## Discover, People, And Collections

- Genres, Studios, Collections, Suggestions, and Upcoming tabs share one grid.
  Down enters and Up returns; right-from-drawer/re-entry uses the first visible
  composed card with tabs as fallback, never a lazy item-zero requester.
- Genre/studio opens filtered Library, collection opens Collection, and person
  opens Person with paged films.
- Person uses a fully composed `Column`: portrait plus first non-empty ribbon is
  section one, remaining ribbon section two. One explicit vertical scroll owner
  keeps section one at offset zero and minimally reveals section two. Ribbons use
  minimal horizontal visibility. At Large tile size, the portrait takes priority
  over first-row labels below the fold.

## Settings And Authentication

- Advanced playback includes **mpv video output** with **GPU (default)** and
  **Direct MediaCodec** choices when mpv is available. The dialog describes
  capabilities and next-session application without device-test anecdotes.
  Selection persists through the device settings store and restores focus to the
  opening tile; Back dismisses without changing the preference. The Resize menu
  for direct output explains its limitation and offers Close instead of ineffective
  Fit/Fill/Zoom choices. See the [output policy](android-playback.md#android-mpv-backend).

- Quick Connect shows a large code and polls without rendering/logging its
  Secret. Settings supports switch/add account, Logout, and playback preferences;
  TV has no per-account Sign out.
- `TvSettingsTileId` defines seven rows: Account; Appearance; Playback; Skip
  segments; **Advanced playback**; Services & About; Diagnostics. Rows are fully
  composed horizontal lists inside one vertical scroller. Playback may extend
  for backend/autoplay; Advanced playback has a sixth tile for mpv video output.
  Account is Server, Signed in user, Switch Users, Add
  account, and Logout. Standard rows have five tiles. Diagnostics contains
  Collect diagnostic logs, Send
  diagnostics to server, Verbose system logging, and Show playback info at start.
  Every tile, including read-only detail, is focusable.
- Tile circles hold only the glyph; centered single-line title/value sit below.
  Focused overflow marquees; unfocused text ellipsizes. Border/glow/zoom affect
  only the circle, with fixed reserve so metadata does not move or clip.
- Rail entry retries the last focused tile, using Server before any tile has
  received focus. A pure grid resolver moves exactly one column,
  preserves column vertically, clamps only a shorter adjacent row, sends Left
  from column zero to rail, and consumes other outer boundaries. Row-aware and
  horizontal Mario policies own scrolling; geometric search does not.
- One centered window-backed dialog is the only reachable focus tree while open.
  It focuses the current enabled choice or primary action, consumes boundaries,
  applies Select once, and cancels on Back without writing. A non-focusable
  `Back to cancel` footer replaces Close/Cancel buttons. Disposal restores the
  exact opener. Unavailable capability choices remain focusable with explanation;
  missing capability is Unknown. Text-only dialogs focus their content fallback.
- Switch Users lists accounts and marks active, or instructs adding an account.
  Add account opens credentials; Logout is the only sign-out surface. Account,
  clear-subtitle, and refresh actions gate duplicates and keep progress/error in
  their dialog.
- All TV text fields use click-to-edit: focus highlights a non-editable display;
  Select mounts `BasicTextField` and opens IME; Done/Search/Next/Go or Back returns
  to display and hides IME. The device-global OpenSubtitles key is masked and
  survives Jellyfin logout. Clear downloaded subtitles is separate from watch state.
- Dialogs expose persisted option sets: Ocean/Midnight/Ember; Small/Medium/Large;
  On/Off; shared eight quality rungs plus Auto, Original, and exact Fixed Custom;
  ExoPlayer/mpv/LibVLC (beta); optional VLC default quality; autoplay
  Immediately/5/10/15/30/60; Still Watching; Auto/Stereo PCM/Passthrough;
  Auto-skip/Ask/Ignore; Auto HDR/Prefer SDR. In-player quality adds Use playback
  default and its active inherited source. Backend choices follow the
  [shared Android policy](playback-runtime.md#backend-selection); TV exposes no Auto,
  and changes apply next session. Read-only tiles open detail dialogs.
- Focus zoom follows the existing preference (1.1 or 1.0). Theme glow is 4 dp at
  14% accent opacity, and theme choices show shared swatches.

## Watch Next

- WorkManager reconciles Fire TV Watch Next by Jellyfin item ID off Main without
  blocking startup; program clicks deep-link through `onNewIntent`.
- Passive reconciliation preserves engagement timestamps. Only new inserts
  receive their constructed timestamp, so periodic sync does not reorder unchanged
  programs as fresh activity.

## Mandatory mpv playback recovery

The [mandatory recovery decision](playback-runtime.md#mandatory-mpv-recovery)
uses a window-backed modal. Initial focus goes to the first enabled action in
Switch, Continue, Stop order. Transport keys cannot resume playback beneath it;
normal player Back navigation is available once Switch closes the dialog and
starts planning. Preserve the shared action and failure semantics.

## Why

### Home media-card geometry

- Next Up is an episode feed whose reliable art path is wide. Row-owned shape
  prevents loading/content swaps or anomalous item kinds from changing measurement.

### Player trickplay preview

- Successful sprite fetch controls visibility because metadata can outlive the
  image. Pending target/item identity reject stale frames; Canvas cropping keeps
  the full sheet available, and uniform scaling preserves frame proportions.

### Related shelf focus

- Shared data/UI own shelf identity, order, and cap; TV owns only focus and
  scrolling. This keeps D-pad presentation aligned with other Detail surfaces.
- Player backend switching reuses the existing focus-trapping picker contract.
  Debug reuses the shared row projection while TV retains display-mode policy.
- `TvFocusCoordinator`, route transactions, and stable-key group memory form one
  restoration system; competing route, lazy-index, or Compose-restorer memories
  could consume a restore without confirmed focus.
- TV text entry is click-to-edit so D-pad arrows remain navigation outside an
  explicitly opened editor.
- Library chrome hides only at draw time because its stable footprint, semantics,
  requesters, and focus routes remain active.
- The TV grid hero is a product feature, but palette extraction still uses
  bounded decoding and off-Main work as required by
  [the performance guide](compose-performance-audit.md).
