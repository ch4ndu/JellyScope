# UI And Compose Rules

## Edge Insets: contentPadding, Not Margins

Apply screen-edge insets as `contentPadding` on `LazyColumn`, `LazyRow`, and
`LazyVerticalGrid`, including any reserve for focused-item scaling. The
scrollable must fill the physical edge so content can scroll beneath the inset
and rest inside it. An outer `Modifier.padding()` clips the scrolling viewport
and is prohibited for this purpose. Non-scrolling content may use matching
padding.

## Theme And Resources

- The app is dark-only with Ocean, Midnight, and Ember palettes. Full-screen
  roots use `LocalAppBackgroundBrush`; shared UI reads
  `MaterialTheme.colorScheme`, and TV chrome reads `LocalJellyfinPalette` from
  `JellyScopeTheme`. `SafeAreaContent` paints the brush behind system bars.
  Theme pickers use `themeSwatchColors`: Compact shared Settings uses a modal
  bottom sheet, wider shared Settings uses a dialog, and Android TV uses a
  D-pad-focusable list.
- Ocean uses the navy `JellyfinBackgroundBrush` (`#12293D` to `#081420`), cyan
  accent (`#22B8D4`), navy surfaces, white primary text, and blue-gray secondary
  text. Midnight is charcoal/violet; Ember is black/white/orange. Do not add
  screen-local colors.
- Android TV Material components opt into the TV-module adapter only around
  migrated components. It maps `LocalJellyfinPalette` to TV Material roles; do
  not wrap the whole TV app in another Material theme or accept stock TV colors.
- Supported TV browse heroes and detail/series/season surfaces may use an
  artwork-derived ambient gradient. Shared touch/pointer Home keeps the selected
  theme background and makes no separate ambient-artwork request. Non-Android
  extraction samples decoded Skia images off Main, caches by item ID, and falls
  back to the selected theme brush.
- A screen does not replace the app's full-bleed background. The player may use
  black, and logged-in Settings may layer `SettingsBackdrop` over the theme
  brush with the active palette and shared launch artwork.
- Keep colors, typography, shapes, and reusable dimensions in theme files. Use
  `TvDimens` or shared dimensions for reused or tunable geometry, including
  screen padding, cards, heroes, rails, widths, and heights. Inline `dp` is for
  genuine one-off hairlines, preview containers, or local breathing room.
- Android TV geometry targets 1080p at 320 dpi: about 16 dp screen margins, a
  narrow app-owned drawer, about 116 dp poster cards, 26-28 sp headlines, and
  14 sp body text. Retune `TvDimens`, except for dimensions owned by TV Material.
- Consume media-tile sizes through `tileScaled()` and `LocalTileScale`, never
  raw card constants. Shared surfaces resolve scale from `WindowWidthTier` and
  `TileSizeId`; TV uses `tvTileScaleFor`. Overscan, focus chrome, spacing, and
  other non-tile geometry remain unscaled.
- Put all visible text in string resources. Prefer Compose Multiplatform
  resources in shared code and keep platform resources in platform source sets.
- Use the project icon system. Settings has one shared lazy, code-native set of
  30 `ImageVector` glyphs translated from the canonical SVG assets; shared and
  TV Settings use that set. Do not add runtime XML/path parsing, PNG copies,
  Material substitutes, or a second TV mapping.
- Ported vectors under `shared-ui/src/commonMain/composeResources/` must replace
  `@android:` values with literals, such as `#00000000`; the multiplatform
  parser may otherwise degrade at runtime without a build failure.

### Logged-out Ambient launch exception

Unauthenticated server entry and login use `AmbientLaunchTokens` and bundled
launch artwork, independent of `LocalJellyfinPalette` and
`LocalAppBackgroundBrush`. `AmbientLaunchScaffold` paints the full-bleed area;
only interactive content receives safe-area or overscan padding. Shared and TV
logged-out roots install this scaffold. This exception does not extend to
logged-in screens.

When discovery is unavailable, both logged-out layouts omit discovery and Scan
again. Expanded layout shows the manual-entry heading; Compact shows no heading.

### Logged-in Android TV Settings backdrop exception

The TV shell's drawer underlay paints one static `launch_backdrop_landscape`
layer across the physical bounds. `LocalAppBackgroundBrush` is the fallback,
followed by the image, a uniform dim, and palette-derived readability scrims.
`TvSettingsScreen` stays transparent; the collapsed rail exposes the backdrop,
while the expanded drawer remains opaque.

`rememberSettingsBackdropImage` decodes once per process off Main. Re-entry
reuses the bitmap, and the brush remains visible until decode completes. The
backdrop never uses `AmbientLaunchTokens`, server artwork,
`AmbientColorExtractor`, or focused-setting state. Theme changes update its
brush and scrims without resetting Settings scroll or focus.

## Product Layout

- Favor useful media browsing. Artwork, progress, watched state, availability,
  and selected-item metadata should be visible before opening Detail when data
  exists. Avoid nested cards, unrelated decoration, and hidden core media state.
- Shared shells enable edge-to-edge. Shared `SafeAreaContent` consumes safe
  drawing insets; Android TV uses `TvSafeAreaContent` for fixed overscan.
  Full-bleed hero and player content applies safe drawing to interactive
  elements. Native tvOS owns SwiftUI/AVKit layout; see
  [Platform Strategy](architecture.md#platform-strategy). Every platform keeps
  controls clear of system bars, cutouts, and home indicators.
- Android mobile playback hides status/navigation bars for the whole player route,
  including Loading, errors, controls, and PiP transitions. Resume/PiP exit
  reapplies immersive mode; disposal restores prior bar visibility and behavior.
  On iOS, the player route controls status-bar and home-indicator preferences
  through the existing per-window SwiftUI host. Leaving playback restores normal
  chrome; system gestures retain their native behavior.
- Text must fit across phone, TV, and desktop. Mobile controls require content
  descriptions, screen-reader labels, scalable text, contrast, and suitable
  touch targets. Avoid tiny and hover-only controls.
- Diagnostics-upload consent text must render in full at larger font scales and
  in longer translations. Shared Settings places supporting copy in its owning
  row and merged spoken label; TV places it below the dialog title. Buffer size,
  preference errors, and upload results remain status. Never expose a stored
  secret as a row value or accessibility label; show only whether it is stored,
  and keep editing masked.
- Movie, concrete-episode, series, and season Detail show Version only for at
  least two valid, distinct sources. Use the nonblank server name or localized
  `Version N` in stable server order. Shared touch uses `TouchTrackDropdown`;
  TV follows the [D-pad contract](tv-ux-behaviors.md). Selection updates the
  marker and every visible source-owned field together. Zero or one source adds
  no disabled control.
- Movie and concrete-episode subtitle pickers include OpenSubtitles search after
  Off, Jellyfin tracks, and installed assets. Results expose release name,
  language, format, hearing-impaired/forced flags, trusted status, rating,
  downloads, availability, progress, errors, and quota/reset data so visible
  facts match ranking. Installation selects the durable local asset for Play.
  Its acknowledgement applies only while owner, account, item, source, and
  explicit selection revision still match, even after dismissal and reopen.
  Dismissal remains available during installation. Detail supports switching,
  replacement, deletion, and sync retry; the player lists installed assets but
  never starts search.
- <a name="related-shelves"></a>Related Detail content renders at most four
  repository-projected groups (`RELATED_GROUP_DISPLAY_LIMIT`) in Cast-first,
  priority-gated order and hides empty groups. Focus and scroll follow rendered
  order. Episode keeps Next Up then Similar; Series keeps its flattened Related
  shelf. [Data playback](data-playback.md#related-shelves) owns source order;
  [TV UX](tv-ux-behaviors.md#related-shelf-focus) owns D-pad behavior.
- UI adapts its structure instead of stretching the phone layout. Shared shells
  resolve `WindowWidthTier` once from actual width: Compact below 600 dp,
  Medium 600-839, Expanded 840-1239, and XLarge 1240+. Compact uses bottom
  navigation. Medium and Expanded use the shared screens, persistent rail,
  capped content width, adaptive padding, and wider heroes. XLarge uses the full
  remaining width and shared TV-style composition without D-pad behavior; Home
  starts with shelves and omits the touch featured carousel.
- Shared Settings uses one to four equal staggered columns after subtracting
  horizontal padding, with a 320 dp minimum and the shared gap. It fills the
  available pane. Detail, Series, and Season use shared adaptive TV-style bodies.
  Person stays stacked on Compact and uses a portrait-or-initial beside bounded
  name/overview from Medium upward, aligned to adaptive horizontal padding.
- Shared UI reads explicit platform capability locals for desktop-only input,
  fullscreen, resize, and transport behavior. Common/mobile defaults are off;
  common composables do not infer the platform through OS checks.
- Desktop popups, menus, and dialogs use the merged Compose canvas. Do not
  restore platform-window layers or per-surface window workarounds.
- Shared Settings switch rows omit redundant visible On/Off supporting values,
  retaining explanatory descriptions, the spoken value, and one toggle target.
  Non-switch rows retain their selected/effective values.
- Shared Detail IMDb/TMDB chips center their label and decorative external-link
  icon in the touch target. Non-Compact touch layouts center the link group;
  Compact placement and D-pad exclusion remain unchanged.
- Shared Settings retains Back and title but omits its self-navigation shortcut.
  Other shared screens retain Settings.
- The adaptive rail reuses the bottom-bar destination model. On Medium+ it is
  persistent, toggles between icons-only and icons-with-titles, consumes real
  layout width, and does not auto-collapse on navigation, scrim tap, pointer
  exit, or Back. Rail width consumes start-side safe drawing before content
  padding is calculated, preventing duplicate landscape-cutout insets.

### Downloads navigation and presentation

- Downloads is the fifth shared destination after Home, Library, Discover, and
  Find when the effective session permission allows downloads. Compact Android
  and iOS use the bottom bar; Medium+ shared layouts use the rail. TV behavior
  is defined in [TV Downloads](tv-ux-behaviors.md#downloads).
- Movie and concrete-episode Detail show Download only with permission. The
  dialog starts at Original and lists only valid Fixed choices and track
  confirmations from the [download contract](data-playback.md#downloads-and-offline).
  Review validates the selection and shows the server estimate; Start queues it.
  Preview/enqueue errors replace the dialog content with a reason-specific
  failure. Allocation stays in Settings and Downloads. Normal Play remains
  remote; completed Detail Download and Play on a Completed row are the only
  entrances to offline playback while the feature is enabled.
- The account-scoped list keys rows/actions by `DownloadId`, preserving
  Completed, Downloading/Finalizing, Queued, Paused/Blocked, and Failed states.
  Queued rows offer Cancel and advance through one transfer slot; other states
  expose only valid Play/Resume, Pause, Retry, Cancel, or Delete actions. Delete
  requires confirmation and stays disabled while the exact artifact is leased;
  a stale request returns `ArtifactInUse`. Any paused row adds a Resume-all
  interruption notice. Bottom content padding clears either system navigation
  or the Compact bottom bar, and horizontal padding follows adaptive safe area.
- Settings and Downloads expose device allocation, physical bytes, reservations,
  safe remaining capacity, over-allocation, current-account bytes, and one
  anonymous aggregate for other accounts. They never expose another account's
  identity or titles, promise cleanup, or show raw byte/bit units. Allocation is
  edited in whole GB; Manage opens Downloads. The section is absent without
  permission.
- Usage refreshes immediately and through one conflated worker that completes
  each I/O call and permits a trailing request after 500 ms; continuous
  checkpoints must not require quiet time. One sequential collector computes
  sections on the injected work dispatcher and publishes records and sections
  atomically without replacing command state.

### Player playback notices

- Playback warnings are beta, off by default, and were disabled for existing
  installs by a data migration. Server-scoped `playbackWarningsEnabled`
  suppresses advisory health and actionable recovery notices from the next
  playback start. Automatic bounded recovery still runs, and fatal unplayable
  errors remain visible.
- Notices use translucent black/white debug-overlay chrome. Existing
  audio/subtitle/fallback and timed kept-playback banners keep their theme.
  Failed target planning is distinct from successful construction fallback.
- The shared Player backend picker appears for eligible remote content whenever
  policy has a non-current concrete target, including an all-unavailable list.
  It omits Auto, disables current/unavailable rows, is absent offline, and is
  disabled while switching. Labels come from resources. The picker closes after
  replacement or failure before teardown.
- The Compact buffering spinner and stroke are twice the normal size through
  player-specific tokens; shared non-player progress tokens do not change.
- Health and recovery notices are durable player state. They persist until
  dismissal, replacement, session/item end, or an explicit lifecycle rule.
  Shared Compose anchors them at the bottom, using safe insets with hidden
  controls and measured control reserve while visible. Width and wrapping stay
  readable; Compact puts text and wrapping actions on separate rows.
- `PlaybackActionNotice` defines allowed semantic actions. UI forwards them to
  the ViewModel/presenter and never infers replans, mutates policy, or persists
  settings. The closed set is Auto, Keep current quality, Try higher, Choose
  lower quality, Try Original, retry, Settings, close, and dismiss. Successful
  automatic quality recovery offers exactly Keep current quality, Try higher,
  Choose lower quality, and Dismiss; Choose lower opens the active quality
  picker. Settings appears only when explicitly allowed. PiP hides the
  interactive surface and defers stream-changing actions. Dismiss remains
  available wherever a notice is shown.
- `LocalInputDiagnosticsSink` is an optional no-op-by-default platform seam. It
  accepts closed enums and numeric coordinates, never localized labels. Observe
  `PressInteraction` through `InteractionSource`, outside the pointer chain.
  Release/Cancel have no coordinate. Compare coordinates only after normalizing
  both spaces to the same window origin and unit.
- The debug overlay is independent of notices and excludes credentials, URLs,
  identities, paths, and raw native errors. `playerDebugSections` is the sole
  shared/TV projection for stable rows, labels, unavailable values, and
  emphasis. Playback rows are Backend, Play method, Transcode reasons, optional
  Container, Video, Audio, Subtitle render/styleability, launch/native first
  frame, rebuffers, audio underruns, and Status. Runtime rows are Decoder,
  Runtime format, dropped frames, buffer policy, target/allocated, buffered
  ahead, and bandwidth estimate. Policy rows are source bitrate, request cap and
  origin, quality policy, capability result, first video output, and effective
  transcode cap. With no playback info it shows only
  `Playback info: Unavailable` and live Status. Richer modeled facts, including
  transition readiness, stay outside the panel.
- Keep notice/action lists stable across playback and diagnostics ticks. Read
  live values at leaves and drive placement from narrow stable projections.

## TV Layout

- Android TV geometry, focus, remote input, drawer behavior, and screen details
  are owned by [TV UX](tv-ux-behaviors.md). TV surfaces support D-pad, Back,
  Select, and Play/Pause with an unmistakable focus treatment.
- Android TV and non-Compact shared Detail/Series/Season use one common adaptive
  implementation selected by `LocalDetailInteractionMode`. `Dpad` owns focus
  requesters/groups, no-auto-scroll outer sections, Mario shelves, and season
  tab routing. `Touch` owns tap/click, desktop scroll input, a visible hero Back
  button, and tap-to-select episode metadata. Compact Detail/Series keep phone
  bodies with inline season chips and no Season route.
- Desktop controls may use the TV-style transport arrangement only with the
  desktop capability enabled at Expanded/XLarge. Compact uses the horizontal
  shared strip.
- Hide controls that cannot act: Queue without a playlist and Quality without
  rungs. A Library Shuffle All movie queue is a playlist. Disabled styling is
  reserved for a temporarily unavailable action.
- Local-text subtitles use independent renderer bases, with the user size
  preference applied separately: mobile Media3 uses 16 sp, Android mpv 41.25
  native scaled pixels, and desktop mpv 28.5 native scaled pixels. Android TV
  Media3 retains its fractional base. Mobile Android raises subtitles by 160 dp
  plus the safe bottom inset while controls/pickers need clearance, returning
  to zero extra inset when hidden or in PiP. The reserve includes a 16 dp gap
  above the existing 144 dp controls estimate; TV retains its own geometry.
- The player top bar shows title and year plus series/episode metadata; codec and
  media-version facts stay in debug UI.
- The debug card uses 60% by 60% on desktop. Compact removes the 320 dp cap and
  fills player width. Values wrap without ellipsis; bitrate uses Mbps.
- <a name="player-overlay-layers"></a>Shared Compose and TV players use
  `PlayerOverlayLayer` in video -> debug -> chrome -> popup -> modal order.
  Debug alone uses its layer; controls, metadata, Skip, and Up Next use chrome;
  passive banners, notices, and status glyphs use popup; pickers, menus, and fatal
  errors use modal.
- Seek and desktop volume sliders retain separate thumb sizes: seek 18/22 dp,
  volume 12/16 dp at rest/active.
- Shared Compose and TV Favorite/Watched actions share an outlined button with
  coral active icon. Native tvOS owns SwiftUI buttons.
- Key-input tests assert focus and do not substitute pointer clicks.
- TV Settings is one vertical stack of seven fully composed horizontal category
  rows with no separate page title. Each row owns scroll state and Mario
  bring-into-view; `TvSettingsTileId` owns inventory/order. Fixed-size tiles use
  a focusable circular glyph surface followed by stationary centered title and
  value. Border, glow, and optional zoom affect only the circle, with enough
  reserve to avoid clipping. The vertical progress indicator is non-focusable.
- TV Settings chrome is palette-driven: cyan focus/selection, error for
  destructive confirmations, and the launch/login glow at 4 dp spread and 14%
  accent opacity. Focus zoom is 1.1 only when enabled. Theme choices use shared
  swatches; geometry lives in `TvDimens`, text in TV resources, and glyphs in
  the shared Settings vector set.

## Composable Architecture

- Split state collection/effects from plain immutable content and callbacks
  where practical. Screen files own entry points and orchestration; place
  reusable chrome, rows, pickers, heroes, and widgets in accurately named
  sibling files or an established shared package.
- Settings row classification uses a pure exhaustive `when`; rows without
  actions expose no click semantics.
- Keep cohesive production UI files near 500 lines when a split clarifies
  ownership. Do not split a cohesive component or create forwarding wrappers
  solely to hit the target. Search existing `component/`, `screen/`, and
  `theme/` packages before adding reusable UI.

## State And Performance

- Filtering, sorting, grouping, search ranking, row packing, and display
  projection belong in domain/ViewModels. Precompute display rows; collect
  mode-specific flows only in the active branch.
- Library, Collection, and Person keep loaded cards and offset after a page
  error. Automatic viewport/focus/restore paging stops at the error latch;
  deliberate Retry clears it and retries the failed offset. Initial failures
  retain their first-page behavior.
- Library actions are collection-aware. Movies expose Shuffle All and Bitrate;
  shows expose Last Episode Added. Shuffle respects filters and reports
  empty/failure through a passive snackbar; repeating it retries. Do not add
  client-side date-favorited sorting because Jellyfin exposes no timestamp.
- Library is a two-level hub. A content-sized top-bar dropdown selects and
  persists a library per server/user, falling back to and re-persisting the
  first available library. Movies/shows default to Recommended and also expose
  Library; other types expose Library only. Discover owns Genres/Collections.
  Each inner view retains its scroll state and creates/loads its owner only when
  selected. The Detail-style pill selector reserves a 16 dp content gap.
- Server-backed tab roots silently refresh on re-entry while preserving visible
  content and deduplicating in-flight work. `OnResumeEffect` observes the
  current lifecycle owner, invokes the latest callback on actual `ON_RESUME`,
  and removes its observer. New browse loads/filters invalidate stale refresh
  results.
- Shared Library has no TV grid hero. `Remember last library view`, enabled by
  default, persists per server/user/library and applies on next entry. Grid/list
  modes use an end-edge overlay scrollbar: 48 dp interaction lane, 96 by 8 dp
  thumb, no visible track, proportional lazy item/row progress, drag/jump input,
  and accessibility actions. Content draws beneath it. It is not an alphabetical
  index; TV does not use it.
- Card images decode near draw size from scaled tile tokens and density,
  quantized to shared buckets and capped at the canonical maximum. Card image
  descriptors keep request and decode geometry aligned.
- Collect state at the lowest practical scope. Use narrow `LaunchedEffect`
  keys, `snapshotFlow` for hot state, and `derivedStateOf` around composition
  reads of `layoutInfo`.
- Move hot scroll/animation/gesture reads into layout/draw lambdas such as
  `offset`, `graphicsLayer`, `drawBehind`, or `drawWithCache`. Prefer a leaf
  provider over passing every-frame values through parents. A conditional read
  that is false removes the subscription; effects may read the latest value in
  their untracked body.
- Player position is collected only by leaves that draw it. Controls receive a
  distinct boolean projection. TV focus identity follows the same rule and is
  read only where needed, including entry-only reads while focus is elsewhere.
  Keep the seek-position mirror in a `LaunchedEffect` inside the seek section;
  writing it during composition can regress seek release on iOS.
- Suppress inactivity timeout for the full `isScrollInProgress` interval; do not
  signal keep-alive from each frame or rely on start/stop pulses that can expire
  during a long drag.
- Cache repeated draw geometry and batch it into one operation. Remove shapes
  that map to an already-used pixel; seek chapter ticks follow this rule.
- Coalesce continuous gestures inside their handler. Volume publishes on a
  bounded cadence and immediately on completion; keys/remotes bypass
  coalescing so repeated absolute steps do not plateau. During a slider drag,
  retain the local value instead of adopting lagging published volume. Mute
  toggles and programmatic changes also bypass gesture coalescing.
- Lazy keys are stable and unique within one list. Duplicate IDs in one list
  require a section or position qualifier; identical IDs in separate lists do
  not. Cross-list focus/selection/scroll identity uses `(container, item)`.
  Do not alter already-unique keys, which resets remembered state and placement
  animation.
- Use lazy content types for materially different row shapes. Emit repeated
  content as keyed lazy items instead of a single item containing a loop when
  virtualization matters.

## Previews

- Preview plain content with sample data and no network, database, DI, player,
  or platform service. Use null image URLs or local assets unless image loading
  is the subject.
- IDE previews live in Android source sets: shared previews in
  `shared-ui/src/androidMain`, TV previews in the TV app.
- Add/update previews for materially changed screens or reusable visuals across
  relevant Compact, landscape, Medium, Expanded, XLarge, and TV dimensions.

## Native tvOS screens

The SwiftUI shell uses native `TabView` and `NavigationStack` navigation, with
sidebar tabs on tvOS 18 and standard tabs on tvOS 17, ordered Home, Favorites,
Libraries, Search, Downloads, and Settings. Favorites has its own navigation
history and reuses the bounded View All grid. View All refresh keeps cards
mounted, reports refresh failure inline, and restores surviving focus; its
newest re-entry refresh replaces an older request. Its playable cards use the
same home variant as Search. System Back pops the
current destination. Browsing state follows the
[shared account lifecycle](architecture.md#account-boundary-and-lifecycle).
App-global appearance watches live outside the account-keyed subtree: Ocean,
Midnight and Ember colors and Small/Medium/Large card sizes update browsing and
settings through a native environment. Resizing preserves aspect ratios, clipped
viewports, focus reserve and stable media identity; player video remains black.

Home presents Continue Watching, Favorites, Next Up, and Recently Added in
that order. Each ribbon loads and retries independently; successful empty
ribbons collapse. Re-entry refresh retains visible cards. The cinematic hero
stays above the clipped vertical shelf viewport, follows settled media focus,
and preserves its last item while controls have focus. Artwork has stable
fallbacks and respects Reduce Motion.

Libraries opens a collection-specific hub with Recommended and Library views
where supported. Recommendation sections load and retry independently. The
Library view retains a paged grid, collection-specific sort choices, and draft
filters with Apply, Reset, and Cancel. Sort and the optional last inner view use
shared preferences. Paging tracks consumed server offsets independently of
card deduplication; re-entry refresh is bounded to 200 consumed items. A newer
query or page invalidates older refresh results. The grid clips beneath its
controls and the fixed hero; horizontal ribbons reserve room for focus scaling.

Media focus identity includes its ribbon and item, so the same title can appear
in multiple ribbons. Return navigation reveals the saved target before restoring
focus, falling back within that ribbon when the item disappears. Select opens
detail; Play/Pause on a movie or episode opens playback at its
resume position. View All uses the shared bounded ribbon query. Swift renders
typed presenter state and owns native focus; business projection remains in
Kotlin. Android-specific focus algorithms are owned by the TV UX guide.

Item detail presents readable metadata, source-qualified version/audio/subtitle
choices, media information, people, and related titles. Play, Resume, and
Restart follow the shared resume decision. Watched applies to movies and
episodes; an explicit change clears the launch bookmark, and a failed change
restores the confirmed watched status and progress. Series exposes favorite.
Explicit track choices travel with the
playback route and are validated against its original item and source. Local
subtitle handoff keeps Play, Restart, and Download mounted but disabled until
selection settles; failures require a new subtitle or source choice. A person
credited in both Cast and Crew remains in both sections, with distinct role
labels preserved within each section.

Series opens seasons and Next Up; an explicit season keeps precedence over
Next Up. Account-qualified final Stop settlement refreshes retained detail,
relevant episodes and Next Up, including when settlement follows the initial
return refresh. Refresh preserves visible content and explicit choices.
Episode Select opens detail, while Play/Pause and row actions target
that episode. Season errors and item-action failures remain visible and
retryable. Person links open a header and paged Movies/Shows filmography.
Detail headers scroll with content; the fixed browsing hero is specific to
Home and Libraries.

Search uses the system field and keyboard for the full Find query: text/year,
person, genre, runtime, and watched status. Person and filter-only requests work
without free text; keyboard edits clear a person selection while retaining
questionnaire filters. All, Movies, Shows, and Episodes select cached result
sections. Query, filters, results, focus, and navigation survive detail/player
return and tab changes. Recent queries are account-scoped and recorded on
submit, recent selection, or person selection; filter edits never add blank
recents. Failed searches retain useful results with Retry.

Settings uses native forms and navigation-link pickers for supported playback,
language, segment, browsing, appearance, subtitles, diagnostics, and account
controls. A failed playback-preference load shows Retry and keeps only playback,
language, and segment controls disabled until a successful read; recovery retains
the last confirmed values. Remember Library Tab uses the
shared preference; autoplay-next controls automatic advancement while keeping
manual Next available. Delay and still-watching preferences use the shared
playback writer. Diagnostics disclosure and open-source notices remain
reachable. Account information and Manage Accounts show each configured server
URL, including its base path. Account switching and confirmed removal use shared Actions; removal
shows the captured account's download count/bytes, refreshes stale previews,
blocks leased artifacts, and releases canceled previews. Native dismissal cannot
cancel a consumed confirmation; Cancel and Back still release an unconfirmed
preview. Add Account presents
the same login flow, with cancellation preserving the session.

Login keeps manual server entry available alongside capability-gated nearby
discovery. Sign-in presents Quick Connect with a visible code and a password
alternative. Native Back from sign-in cancels its work, clears password input,
and returns to server entry while retaining discovered rows. An idle scan offers
Search Again with or without results; discovery failure has an explicit Retry
and never blocks manual entry. Root Back and keyboard
editing retain system behavior; shared session state owns successful routing.

Player components separate the installed native host, transport and panels.
Queue offers the current item, direct selection, previous/next and shuffle.
Next-up offers identity/artwork, Play Now and Dismiss; completion countdown and
still-watching semantics are owned by
[data-playback.md](data-playback.md#tvos-native-player). Modal precedence is
action/error, still-watching, a user-opened panel, then next-up. Back closes the
topmost panel and returns focus to its invoker; unobscured Back closes playback.
Progress updates do not steal focus or rebuild AVKit menus. Controls expose
speed, supported subtitle style, AVKit video sizing and sanitized diagnostics.
Timing and VLC video sizing are explicitly unavailable.

OpenSubtitles settings provide a masked consumer-key editor, result preference
and confirmed local-asset clearing. Movie/episode detail and online playback
open account/item/source-qualified search. Results show loading, empty, failure,
unsupported, quota and installation states; local rows offer Select, Delete and
Retry Sync. Off clears selection. Offline playback has no remote subtitle-search
entry or arbitrary file import.

Downloads has its own tab and navigation path. Movie/episode detail previews the
current version and track selection for Original or supported converted quality
before enqueue. The local list groups completed, active, queued, paused and
failed records and provides guarded playback, pause/resume/retry, Resume All,
explicit Resume Queued Downloads with pending and retryable failure states,
Cancel/Delete and storage allocation controls. Destructive actions confirm and
respect active leases. Retained-account access survives server failure; the UI
explains Apple TV's reclaimable storage and app-active transfer limit. The
[download contract](data-playback.md#downloads-and-offline) owns those rules.

## Why

- Subtitle bases belong to each renderer so native defaults and user preferences
  can change independently. Mobile Media3 uses sp so control clearance does not
  also shrink its text. Immersive chrome follows route lifetime rather than
  loaded content, preventing bars from returning during loading or errors.
- Switches already show state; their supporting text explains behavior instead.
  External-link icons make the browser handoff visible without adding another
  action or changing the destination.

- Native tvOS keeps SwiftUI state adapters beside their feature views and owns
  native focus while shared Kotlin presenters own business state. Qualified routes
  protect track choices; settlement refresh and paired watched/progress rollback
  preserve retained detail state.
- OpenSubtitles acknowledgement carries owner identity and selection revision
  because installation may finish after dismissal or a newer selection.
- Overlay layers are explicit because composition order did not keep notices,
  controls, diagnostics, and modals in a consistent interactive order.
- Settings columns use a deterministic kernel because adaptive minimum-width
  cells produced narrow layouts on wide windows.
- Tile size is a single scale so aspect ratios and theme dimensions retain one
  owner; TV remains conservative because overscan and focus zoom also consume
  space.
- Downloads is permission-gated top-level navigation because durable transfers,
  storage, failures, and destructive actions need a visible owner. Normal Play
  remains remote so the active artifact is explicit.
- Related shelf count and order stay in shared model/data owners so shared and
  TV presentation cannot drift.
- Library selection persists through the existing server-scoped preference
  boundary; UI does not write stores. Separate inner-view owners preserve
  context, paging, and scroll state.
- Playback-warning suppression changes presentation only. Recovery policy and
  fatal failure reporting must remain active.
- Player hot state is read at leaves because parent reads recompose unrelated
  controls and previews. Handler-local coalescing and cached draw geometry bound
  work without changing semantic state.
