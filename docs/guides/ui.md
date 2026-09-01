# UI And Compose Rules

Load this for Compose UI, screens, TV surfaces, previews, theme work, or
recomposition-sensitive changes.

## Edge Insets: contentPadding, Not Margins

Screen edge spacing (overscan on TV, safe-area/cutout on mobile, the "gutter"
around content) must be applied as **`contentPadding` on the scrollable**
(`LazyColumn`/`LazyRow`/`LazyVerticalGrid`), NOT as an outer
`Modifier.padding()` margin around the list. The scrollable should fill to the
physical edge so content scrolls under the inset and rests inset — like
RecyclerView `clipToPadding=false` + `contentPadding`. An outer margin clips the
first/last item and makes content stop at a padded box instead of the edge. This
applies to ALL edges (top/bottom/start/end) on every screen. Merge edge insets
with any existing contentPadding (e.g. a grid's focus-scale reserve) rather than
replacing it. Non-scrolling content may keep a padding, but align it with the
list's contentPadding so nothing shifts, and never let an outer margin clip a
scrollable.

## Theme And Resources

- Use Material3 and platform-appropriate TV components where available.
- The app theme is dark-only with selectable palettes. Ocean is the original OTT
  case-study navy/cyan theme: app-wide vertical navy gradient
  (`JellyfinBackgroundBrush`, #12293D→#081420), one cyan accent (#22B8D4) for
  CTAs/selection/ratings/progress, navy surfaces, white primary text with
  blue-gray secondary. Midnight is the charcoal/violet alternative. Ember is the
  black/white/orange alternative. Full-screen roots read
  `LocalAppBackgroundBrush.current`; `SafeAreaContent` draws it behind system
  bars. Shared/mobile UI colors come from `MaterialTheme.colorScheme`; TV chrome
  colors come from `LocalJellyfinPalette.current`, whose palette values live in
  `JellyScopeTheme`. Theme pickers show palette swatches through
  `themeSwatchColors`: mobile/desktop use a Material3 exposed dropdown, while TV
  uses a D-pad focusable picker list. Never hardcode screen-local colors.
  Android TV Material components opt into a TV-module theme adapter scoped to
  the migrated component. The adapter maps `LocalJellyfinPalette` into TV
  Material color roles; do not install a second Material theme around the entire
  TV app or let stock TV defaults bypass Ocean, Midnight, and Ember.
  Hero-enabled bespoke TV browse surfaces and detail/series/season surfaces use
  the artwork-derived ambient vertical gradient on their supported platforms.
  Shared touch/pointer Home uses the selected theme background; it must not
  issue a separate ambient-artwork request or derive a Home-only color.
  Non-Android extraction samples the decoded Skia image off the UI thread and
  caches colors by item ID; unavailable artwork or extraction falls back to the
  selected theme brush. Screens must NOT paint their own full-bleed background
  outside the theme brush path; the player is the black-background exception,
  and logged-in Settings is the palette-driven backdrop-layer exception.
  Settings may compose its `SettingsBackdrop` over the theme brush using the
  selected `LocalJellyfinPalette` and shared launch artwork; it must remain a
  controlled themed layer rather than a screen-local replacement background.
- Put theme colors, typography, shapes, and dimensions in shared theme files.
- Source every `dp` value from the module's dimens object (`TvDimens` on TV,
  shared theme dimensions elsewhere) whenever the value is reused, defines
  layout geometry (widths, heights, rail/hero/card sizes, screen padding), or is
  likely to be tuned again. Inline literals are allowed only for genuine
  one-offs such as 0-2dp hairline spacing, preview containers, or a padding used
  in exactly one place with no geometric meaning beyond local breathing room.
  When in doubt, add a named dimension.
- Android TV geometry is calibrated for 1080p at 320dpi (2px per dp) against
  the validated TV geometry: minimal screen margins (~16dp), app-owned TV
  navigation drawer host with narrow drawer rows, ~116dp poster cards, 26-28sp
  headlines, 14sp body. Re-tune `TvDimens`, not call sites, except for
  dimensions owned by Material TV components.
- Media tile dimensions must be consumed through the shared `tileScaled()`
  helper, which reads `LocalTileScale`; do not consume raw card constants from
  `Dimensions`, `DetailDimens`, or `TvDimens`. Shared mobile/desktop surfaces
  resolve the scale from `WindowWidthTier` and `TileSizeId`, while Android TV
  provides the conservative `tvTileScaleFor` row. Keep TV overscan, focus
  chrome, spacing, and other non-tile layout constants unscaled.
- Use string resources for all user-visible strings.
- Use icons from the project icon system. Settings owns its 30 approved glyphs
  as one shared set of lazily constructed, code-native `ImageVector` values
  translated from the canonical SVG geometry. Shared and Android TV settings
  render those same vectors. Do not reintroduce runtime XML loading, path-string
  parsing, a second TV mapping, PNGs, or Material-icon substitutions; the SVGs
  and contact sheet in the Settings design assets remain the artwork source of
  truth.
- Keep UI text, spacing, and behavior consistent with existing screens.
- Use Compose Multiplatform resources from shared code where possible. Platform
  resource APIs should stay in platform source sets.
- A vector drawable ported into `shared-ui/src/commonMain/composeResources/`
  must carry no `@android:` framework references: rewrite values such as
  `android:fillColor="@android:color/transparent"` to literals (`#00000000`).
  The Compose Multiplatform vector parser cannot be assumed to resolve framework
  references, and an unresolved value degrades at render time instead of failing
  the build, which makes the defect very hard to attribute back to the port.

### Logged-out Ambient launch exception

The unauthenticated server-entry and login flow is the one scoped exception to
the app-wide theme rule. Its Ambient launch composables use
`AmbientLaunchTokens` and bundled launch artwork, never `LocalJellyfinPalette`
or `LocalAppBackgroundBrush`, so selecting Ocean, Midnight, or Ember cannot
affect the launch palette. `AmbientLaunchScaffold` owns the full-bleed backdrop
behind system bars and TV overscan; only the interactive content receives
safe-area or overscan padding. The shared and TV logged-out roots install that
scaffold; each launch-screen reskin must use the fixed components rather than
reintroducing theme values. This exception is limited to the logged-out launch
flow and does not permit themed or logged-in screens to add screen-local
backgrounds or colors.

When server-entry discovery is unavailable, compact and expanded logged-out
layouts omit discovery presentation and the Scan again control. The expanded
layout uses the direct manual-entry heading; compact adds no heading.

### Logged-in Android TV Settings backdrop exception

Android TV Settings is the scoped logged-in exception to the full-bleed theme
rule. The shell-owned drawer underlay, not `TvSettingsScreen`, paints one static
`launch_backdrop_landscape` layer across the physical TV bounds: the normal
`LocalAppBackgroundBrush` remains the fallback base, then the image, a uniform
dim, and directional readability scrims are derived from `LocalJellyfinPalette`.
The Settings route content stays transparent so the image is neither decoded nor
painted a second time. The bundled backdrop decodes once per process OFF the
main thread (shared `rememberSettingsBackdropImage`); the brush base paints
until the decode lands, and re-entries reuse the cached bitmap synchronously.
The backdrop remains visible behind the collapsed transparent rail; the
expanded drawer keeps its opaque themed surface. It must not use
`AmbientLaunchTokens`, server artwork, `AmbientColorExtractor`, or
focused-setting state. A live theme selection updates its brush and scrims
without resetting Settings scroll or focus.

## Product Layout

- Favor media-browsing utility over decorative layouts.
- Posters, backdrops, progress, and selected-item metadata should carry the
  visual weight.
- Progress, watched state, partially watched state, and unavailable media state
  should be visible before opening details when the data is available.
- Mobile UI must include content descriptions, screen-reader labels for
  controls, scalable text behavior, contrast checks, and reasonable touch
  targets.
- The app is edge-to-edge on every platform: platform shells enable edge-to-edge
  (Android `enableEdgeToEdge()`, iOS hosts ignore safe areas) and route all
  screens through the shared `SafeAreaContent` wrapper so system bars, display
  cutouts, and home indicators never clip content. Screens that intentionally
  draw behind system bars (player, hero imagery) must consume
  `WindowInsets.safeDrawing` themselves for interactive elements.
- Text must fit its container on mobile, TV, and desktop.
- The diagnostics upload consent disclosure is what the user is consenting to,
  so it must render in full: any `maxLines` cap has to leave enough headroom to
  wrap the whole string, never to ellipsize it. The tail a tight cap removes is
  the sentence stating that log lines are the only thing omitted when collection
  is off. Size that headroom for larger system font scales and longer
  translations, not for today's English string at today's dialog width.
- Do not add nested cards or unrelated decorative surfaces.
- Do not hide core media state until a detail screen.
- Movie, concrete-episode, series, and season detail show a Version control only
  when at least two valid, distinct media sources are available. A nonblank
  server name is the label; otherwise presentation supplies localized
  `Version N` labels in stable server order. Touch layouts reuse
  `TouchTrackDropdown`; D-pad behavior is owned by
  [`tv-ux-behaviors.md`](tv-ux-behaviors.md). Selecting a row updates the
  current-selection marker and all visible source-owned detail fields together;
  zero/one-source items retain the existing layout without a disabled control.
- Movie and concrete episode detail subtitle pickers include OpenSubtitles
  search alongside Off, Jellyfin tracks, and installed local assets; search is
  not a separate floating detail action. The search dialog shows each result's
  release name, language, format, hearing-impaired and forced indicators,
  trusted status, rating, and download count — the picker must surface what the
  ranking sorts on — plus progress, errors, unavailable multi-file/format rows,
  and returned quota/reset information. Installing a
  result selects its durable local asset for the next Play; the same detail
  picker permits switching back to Jellyfin subtitles, replacing the selection,
  deleting the selected download, and retrying an unconfirmed Jellyfin sync. The
  player picker lists installed local assets but never initiates search.
- <a name="related-shelves"></a>Related detail content is a multi-shelf
  projection capped at the shared `RELATED_GROUP_DISPLAY_LIMIT` of four.
  Shared Detail consumes the repository's Cast-first, priority-gated groups
  and hides empty shelves; its focus containers and scroll hooks follow the
  rendered order. Episode detail preserves Next Up then Similar, while Series
  detail keeps its separate single flattened Related shelf. Repository source
  selection and publication order are owned by
  [`data-playback.md`](data-playback.md#related-shelves); Android TV focus and
  scroll behavior is owned by [`tv-ux-behaviors.md`](tv-ux-behaviors.md#related-shelves).
- Use adaptive layout structure for compact, medium, expanded, TV, and desktop
  surfaces instead of stretching one phone layout everywhere.
- Shared UI that needs desktop-only input, fullscreen, resize, or transport
  behavior must read explicit platform capability composition locals supplied by
  the platform entry point. Do not infer desktop/mobile from OS checks inside
  `commonMain` composables. Default common/mobile capabilities are off.
- Desktop popups, menus, and dialogs use Compose's default merged-canvas
  layers. Do not reintroduce a platform window-layers flag or per-surface
  popup-window workarounds.
- Shared mobile/desktop UI resolves `WindowWidthTier` once near the logged-in
  shell from actual `BoxWithConstraints` width, using Compact < 600dp, Medium
  600-839dp, Expanded 840-1239dp, and XLarge >= 1240dp. Compact keeps the bottom
  navigation bar and current phone geometry. Medium and Expanded use the shared
  screens with a persistent left rail, capped content width, adaptive horizontal
  padding, and wider hero treatment. XLarge keeps the rail but uses the full
  remaining window width and the shared TV-style full-width composition; it must
  never center the app in a narrow content pane. The XLarge shared Home starts
  directly with the TV-style media shelves and does not render the touch-only
  featured carousel. Shared Settings keeps its grouped TV-style sections and
  fills the padded content pane with one to four equal-width staggered columns:
  measure the pane, subtract horizontal content padding before choosing the
  count, preserve a 320dp minimum column width and the shared column gap, and
  allow the resulting columns to consume the remaining width instead of
  retaining a capped trailing gutter. Detail/series/season continue through the shared
  adaptive TV-style bodies. Person is a screen-specific exception: Compact
  retains its full-width stacked backdrop, name, and overview, while Medium,
  Expanded, and XLarge use a portrait-or-initial header beside a bounded
  name/overview column. The wider Person header must use the shared adaptive
  horizontal content padding so both collapsed and expanded navigation-rail
  widths remain valid. Input remains native to the platform, so touch/pointer
  XLarge surfaces do not acquire D-pad behavior.
- Shared Settings keeps Back and its title in the top bar but omits the Settings
  shortcut because self-navigation is redundant. Other shared screens retain
  their Settings shortcut.
- The shared adaptive rail reuses the top-level destination model from the
  bottom bar. It is persistent on Medium+ widths, has a top hamburger toggle,
  and supports Collapsed (icons only) and Expanded (icons plus titles) states.
  The logged-in shell reserves the active rail width for content; expanded rail
  labels are not an overlay and the rail does not auto-collapse on destination
  selection, scrim tap, pointer exit, or system back. Rail-framed content also
  treats the active rail width as consuming start-side horizontal safe drawing
  before computing screen content padding, so landscape cutouts are not added
  again inside rows and grids.

### Downloads navigation and presentation

- Downloads is the fifth destination in the shared top-level model, after Home,
  Library, Discover, and Find, when the session's effective content-download
  permission is enabled. Android mobile and iOS compact layouts render it in the
  bottom bar; medium-and-larger shared layouts, including JVM desktop, render
  the same destination in the adaptive rail. Android TV's drawer and D-pad
  behavior are owned by
  [`tv-ux-behaviors.md`](tv-ux-behaviors.md#downloads).
- Movie and concrete Episode details expose Download in the primary action row
  only when that effective permission is enabled. The request dialog starts at
  Original and presents only the valid per-request Fixed choices and track
  confirmations owned by [the data contract](data-playback.md#downloads-and-offline).
  Review download size validates the selection and shows its server-derived
  estimate; Start download then queues it. Preview and enqueue failures replace
  the request content with a concise reason-specific error. Allocation changes
  stay in Settings and Downloads instead of appearing inside the detail dialog.
  The normal Play action remains remote; the completed detail Download entry and
  Play on a Completed Downloads row are the only explicit entrances to the
  offline route while Downloads is enabled.
- The Downloads screen is current-account scoped and keys every list row and
  action by stable `DownloadId`, never by lazy position. Non-empty Completed,
  Downloading (including Finalizing), Queued, Paused/Blocked, and Failed
  sections preserve every row's exact state. Queued rows advance automatically
  through the single transfer slot and expose only Cancel; other states expose
  only their valid Play/Resume, Pause, Retry, Cancel, or Delete action. Delete asks for
  confirmation; while that exact artifact is leased for playback, Delete stays
  disabled and a stale deletion request returns `ArtifactInUse` instead of
  removing the row. When at least one row is Paused, an interruption notice
  offers one Resume action for the data contract's current-account resume-all
  command. The scrollable's bottom `contentPadding` reserves the larger
  of the system navigation inset and the compact shell's bottom-bar clearance,
  so the final action row can rest fully above either overlay without adding a
  permanent desktop or rail-layout gap.
- When the effective permission is enabled, Settings and the Downloads usage
  card present the device-wide allocation, total physical bytes, outstanding
  reservations, safe remaining capacity, and over-allocation state, plus
  current-account bytes and one opaque aggregate for all other accounts. They
  never expose another account's title or identity. Allocation is edited in
  whole GB and Manage opens the same Downloads destination; storage and bitrate
  values use familiar MB, GB, and Mbps labels instead of raw byte/bit units. No UI promises
  eviction or automatic cleanup. When it is disabled, Settings omits the
  Downloads section entirely.

### Player playback notices

- Playback warnings are user-controllable and OFF by default (the setting
  is labeled beta; a data-only migration also switched existing installs
  off). A server-scoped
  `playbackWarningsEnabled` preference suppresses **both** the advisory
  health banners and the actionable recovery notices. It is presentation-only:
  automatic recovery still performs its bounded compatibility replan and
  quality reduction silently. It takes effect from the next playback start,
  matching the existing per-start preferences snapshot, and it never suppresses
  the fatal player-error dialog, which reports a stream that cannot play at all.
- Playback notices use the debug-overlay surface treatment (translucent black with
  white content) rather than an opaque themed surface, so they read as player
  overlay rather than app chrome. The audio/subtitle/backend-fallback and timed
  "current playback was kept" banners keep their themed treatment; failed target
  planning is distinct from a successful construction fallback.
- The shared player control strip exposes a **Player backend** picker for
  eligible remote content whenever policy has a non-current concrete target,
  including when every alternate is unavailable so the user can inspect the
  disabled explanations. `Auto` is absent, current and unavailable rows are
  disabled, and no label is hardcoded in a shell. The control is absent for
  offline content and disabled rather than rendered as a no-op while a
  session-local switch is in progress; the ViewModel closes the picker on either
  committed replacement or pre-teardown failure.
- The buffering spinner doubles in size on a Compact width tier; its stroke scales
  with it. Size tokens are player-specific and must not change the shared
  `progressIndicatorSize` used by non-player surfaces.
- Playback-health and recovery notices are persistent state, not transient
  snackbars. They remain visible until the user dismisses them, a recovery
  result replaces them, the item/session ends, or an explicit lifecycle rule
  suppresses presentation.
- Shared Compose places the notice at the **bottom** of the player. It uses safe
  drawing/overscan insets when controls are hidden and reserves the measured
  controls region when they are visible, so it stays above transport chrome
  rather than underneath a top bar or controls. The notice has a bounded width,
  readable wrapping, an accessible summary, and visible action labels; it never
  relies on hover alone. On Compact/portrait surfaces the title/message owns its
  own full-width row and actions wrap on a separate row; a single horizontal
  title-plus-actions line is prohibited because it clips the message and
  produces unusable button geometry on phones.
- `PlaybackActionNotice` is semantic UI state. UI maps its allowed actions
  (accept Auto, Keep current quality, Try higher, Choose lower quality, Try
  Original, retry, close, dismiss) back to the owning ViewModel/presenter.
  Settings is available only when a notice explicitly includes that action.
  A successful automatic quality recovery offers exactly Keep current quality,
  Try higher quality, Choose lower quality, and Dismiss; Choose lower quality
  opens the current player's quality picker, and none of these choices persist
  beyond the playback. Composables do not infer a replan from wording, mutate
  policy, or write persistence directly.
- PiP suppresses the interactive surface and defers stream-changing action until
  it is safe to present; it does not discard or misrepresent the underlying
  measurement. Dismiss must be available wherever a notice is rendered.
- Shared components may report input diagnostics through `LocalInputDiagnosticsSink`,
  whose default implementation is a no-op. Only a platform that provides a real sink
  pays anything, so `commonMain` never depends on a platform module. Its API accepts
  closed target/event enums and numeric coordinates only — never user-facing strings,
  because a component's `label` is localized and localized text must not reach
  diagnostics. Observation must not join the pointer-event chain: collect
  `PressInteraction` through an `InteractionSource` rather than `pointerInput`, or the
  probe can perturb the behavior it measures. `PressInteraction.Release`/`Cancel`
  carry no position, so a release coordinate must never be inferred from the
  originating press. Button-local and platform component-local coordinates are only
  comparable after both are normalized to the same window origin and unit; when they
  cannot be, report the spaces separately and draw no offset conclusion.
- The debug overlay is developer data, independent of the notice. It must show
  explicit no-limit/disabled/unsupported values for visible facts and never
  expose credentials, stream URLs, identities, paths, or raw native errors.
  `playerDebugSections` is the one compact generic projection for shared and TV
  surfaces; it owns the stable section/row order, developer labels, unavailable
  markers, and emphasis. Its visible rows are Playback — Backend, Play method,
  Transcode reasons, optional nonblank Container, Video, Audio, Subtitle render,
  Subtitle styleable, Launch / native first frame, Rebuffers, Audio underruns,
  Status; Runtime — Decoder, Runtime format, Dropped frames, Buffer policy,
  Target / allocated, Buffered ahead, Bandwidth estimate; Policy — Source
  bitrate, Request cap, Cap origin, Quality policy, Capability result, First
  video output, Effective transcode cap.
  With no playback info it returns only `Playback info: Unavailable` and live
  `Status`. Richer modeled diagnostic and policy facts, including transition
  readiness, are deliberately not overlay rows.
- Keep notice/action lists stable across position and diagnostics ticks. Collect
  hot playback values at leaves; controls-reserve and notice placement should
  be driven by narrow stable projections rather than rebuilding the player tree.

## TV Layout

- TV surfaces must work with D-pad, back, select, and play/pause.
- Focus must be visually unmistakable from TV distance.
- Android TV screen geometry, drawer ownership, focus traversal, remote input,
  and screen-specific behavior are owned by `docs/guides/tv-ux-behaviors.md`.
- Detail, series, and season surfaces for Android TV and non-Compact shared
  widths render through one shared `commonMain` adaptive implementation gated by
  `LocalDetailInteractionMode` (`Dpad` or `Touch`). Compact detail/series keep
  their phone bodies with inline season chips and no season route. D-pad mode
  owns focus requesters, `focusRestorer`/`focusGroup`, no-auto-scroll outer
  sections, Mario shelf scrolling, and season-tab to episode-strip key routing;
  Touch mode uses plain click/tap behavior, desktop scroll input, a visible hero
  back button, and tap-to-select episode metadata before playback.
- Desktop player controls may reuse the TV-style transport arrangement only when
  the desktop platform capability is enabled and the shared width tier is
  Expanded or XLarge. Compact shared/mobile layout remains the existing
  horizontally scrollable control strip.
- A player control that cannot act on the current item is **hidden, not
  disabled**: a movie has no queue, so a permanently dead Queue button reads as
  broken chrome rather than as a state. Same for Quality with no rungs. Reserve
  disabled styling for something momentarily unavailable that will become
  available (e.g. an in-flight toggle), not for a capability the item lacks.
- The player top bar carries title · year plus a series/episode line for
  episodes. It does not carry media-version/codec badges — that is developer
  detail, owned by the debug overlay, and it crowded the title on narrow
  windows.
- The debug overlay is a compact card by default; on desktop it is sized as a
  fraction of the player surface (60% x 60%); on a Compact width tier it drops the
  320dp cap and fills the player width, because that card truncated most rows on a
  phone. Its rows wrap to as many lines as the value needs and are never
  ellipsized — a truncated capability or recovery reason is the row a reader most
  needs. Bitrate rows read in Mbps.
- Every player surface uses the explicit order **video → debug → chrome → popup
  → modal** from the shared `PlayerOverlayLayer` contract. Composition order
  alone is not a drawing contract. Only the debug panel uses the debug layer,
  keeping controls and metadata interactive above developer data;
  notices/status remain popups above chrome, while pickers, menus, and fatal
  errors remain modal. A warning the user must act on still outranks both
  chrome and developer data.
- Player sliders use caller-owned thumb sizes: the seek bar uses 18 dp at rest
  and 22 dp while pressed/dragged so it remains visually taller than chapter
  ticks; the desktop volume slider retains its 12 dp resting and 16 dp active
  sizes. Do not collapse them back into one shared size pair.
- Shared player behavior is owned by `docs/guides/data-playback.md`; Android TV
  player overrides and remote behavior are owned by
  `docs/guides/tv-ux-behaviors.md`.
- Favorite and watched actions use one shared button treatment across platforms
  and across detail/series/player surfaces: an outlined button with the coral
  accent icon when active (favorited / watched), and the plain treatment
  otherwise. Do not fork per-surface favorite/watched button styles.
- Avoid tiny controls and hover-only affordances.
- Tests for TV/keyboard surfaces should send key input and assert focus, not
  rely on pointer clicks alone.
- Android TV Settings is one vertically scrolling stack of seven fully composed,
  horizontally scrollable category rows without a separate page title; the
  Account section label begins the content. The page owns vertical scroll while
  every category owns an independent horizontal scroll state and theme-aware
  Mario bring-into-view policy, so future tiles can extend beyond the viewport
  without changing deterministic D-pad traversal. Account, Appearance, Playback,
  Skip segments, Device playback, and Services & About each contain five
  fixed-size tiles with wider spacing; Diagnostics is the seventh row and
  contains Collect diagnostic logs, Send diagnostics to server, Verbose system
  logging, and Show playback info at start. Each tile has
  a focusable circular surface holding only the centered supplied SVG-derived
  glyph, followed by centered title and current-value metadata below it. Focus
  border, glow, and optional zoom affect only the circle; metadata remains
  stationary and starts after a dedicated gap that clears the focused circle's
  scale and glow. Horizontal overscan and focus reserve belong inside each row's
  scrollable content. The nonfocusable side indicator is derived directly from
  continuous vertical scroll progress.
- Settings tiles use palette-driven translucent chrome: `palette.cyan` is the
  focus and selected accent, destructive confirmations use the error color, and
  the launch/login focus-glow treatment is reused with the active theme accent
  (4dp spread at 14% opacity). Settings tiles scale to `1.1f` only when the
  existing Focused-card zoom preference is enabled. Theme picker choices show
  their `themeSwatchColors` palette. Tile grid and dialog geometry belongs in
  `TvDimens`; all user-facing text belongs in Android TV resources. The 30
  canonical SVG glyphs remain with the Settings design assets; Android TV
  consumes the same lazily built code-native `ImageVector` values as shared
  Settings. The shell tints those vectors in code without runtime XML resource
  parsing, PNG, or Material-icon substitutions.

## Composable Architecture

- One composable should represent one UI component.
- Screens should use a state-holder/plain-UI split where practical. The
  state-holder composable collects ViewModel/component state and effects; the
  plain UI composable receives immutable state and callbacks.
- Settings rows are classified by a pure kernel with an exhaustive `when`; a
  row with no action exposes no click semantics.
- A stored secret is never a settings row's value. A row value is rendered on
  screen **and** read aloud through the row's accessibility description, so a
  secret-bearing setting reports only whether a value is stored; the secret
  itself never leaves its edit dialog, where it stays masked. This holds for
  every secret-bearing setting rendered through the shared row vocabulary, and
  the accessibility half of the exposure is invisible in a screenshot review.
- Screen files should own screen entry points, state collection, and high-level
  orchestration. Reusable components, chrome, picker menus, rows, hero sections,
  and support widgets should live in accurately named sibling files in the same
  source set/package unless there is a clear reuse boundary elsewhere.
- Large production UI files should be split by cohesive responsibility around a
  500-line target. Treat the target as guidance, not a hard rule: keep a
  cohesive component together when splitting would obscure ownership or require
  behavior changes.
- Before creating reusable UI, check existing `component/`, `screen/`, and
  `theme/` packages.

## State And Performance

- Do not transform data in composables. Filtering, sorting, mapping, grouping,
  search ranking, row packing, and media-state projection belong in UseCases or
  ViewModels.
- Library browse actions are collection-aware. Movie libraries expose a separate
  Shuffle All action (accessible icon at compact touch widths; labeled action at
  wider touch/desktop widths), while movie bitrate and show last-episode-added
  sorts appear only for their matching collection type. Shuffle respects active
  filters and reports empty/unavailable results through a passive snackbar;
  pressing the same action retries.
- Do not add a client-side "date favorited" sort. Favorite ordering uses only
  server-provided sort fields; revisit only if the server gains a favorited
  timestamp.
- The Library tab is a two-level hub: the outer selector is a dropdown in the
  top bar (replacing the screen title) and the inner selector chooses a view.
  The selected library is durable per server and user, restored on load, and
  falls back to the first library — re-persisting the correction — when the
  stored one is gone. `AppTopBar` takes an optional `titleContent` slot for that
  dropdown; every other caller keeps the plain title. The dropdown is
  content-sized, not bar-width: label and chevron sit adjacent with a small gap
  and both centre vertically, so the chevron reads as part of the title rather
  than a detached control at the far edge, and a long library name ellipsizes
  instead of pushing the chevron off-screen. Movies expose Recommended,
  Library, Genres, and Collections; shows expose Recommended, Library, and
  Genres; other library types expose Library only. Recommended is the default
  for movie/show libraries. Each inner view preserves its own scroll state and
  creates/loads its data owner only after the view is selected. The inner
  selector uses the same pill geometry and minimum touch target as Detail
  actions, with filled-selected and outlined-unselected semantics. Its measured
  top stack reserves a 16dp gap before either Recommended or Library content.
- Server-backed tab roots refresh silently whenever they re-enter: visible
  content stays in place while the request runs, a refresh failure leaves that
  content unchanged, and repeated entries dedupe in-flight work. The entry
  composable that owns each ViewModel installs `OnResumeEffect`; a scoped inner
  browse ViewModel owns its own effect. The helper observes the current
  lifecycle owner, invokes the latest callback only for an actual `ON_RESUME`,
  and removes its sole observer on disposal. Newer browse loads or filters
  invalidate older refresh results before they commit.
- Shared mobile, touch, and desktop Library views do not render the TV grid
  hero. Appearance exposes `Remember last library view`, enabled by default;
  when enabled, the selected inner view is persisted per server, user, and
  library. Disabling it affects the next library entry and does not move an
  active screen. Shared Library grid and list browse modes expose a
  position-proportional end-edge scrollbar with a 48dp touch/pointer overlay
  lane that content draws beneath, a fixed 96dp by 8dp thumb, no secondary
  visible track, continuous leading-item/row scroll progress, drag/track-jump
  input, and accessibility progress actions. It maps to lazy item/row
  positions and is deliberately not an alphabetical index or fast scroller.
  Android TV keeps its separate D-pad library behavior and does not use this
  scrollbar.
- Do not compute per-row display values in composition. Precompute display rows
  in ViewModels so scrolling and unrelated recomposition do not repeat the work.
- Card images decode at the size they are drawn: list/grid cards derive decode
  size from the resolved tile token times density, quantize to shared buckets,
  and clamp to the canonical ceiling; cards carry an image descriptor so
  request and decode agree.
- Pass `StateFlow` to children when useful and collect at the lowest practical
  scope.
- Collect mode-specific flows only inside the active UI branch.
- Keep `LaunchedEffect` keys narrow and intentional.
- Use `snapshotFlow` for frequently changing state such as scroll position.
- Wrap composition-time `layoutInfo` reads in `remember { derivedStateOf { ... }
  }`.
- Defer hot state reads for scroll, animation, drag, and gestures into layout or
  draw phases when possible. Prefer lambda modifiers such as `Modifier.offset {
  }`, `Modifier.graphicsLayer { }`, `Modifier.drawBehind { }`, or
  `Modifier.drawWithCache { }` over value modifiers fed by every-frame state.
- Do not pass every-frame values through parent composable parameters when a
  lambda provider can keep the hot read at the leaf that renders it.
- Collect live playback state in the leaves that render it, never at the scope
  that hosts them. The shared player overlay collects position only inside its
  seek section, and hands the control strip a `distinctUntilChanged` projection
  of the one boolean it needs, so a tick cannot recompose the buttons. The same
  rule applies to focus identity on Android TV: read it only where it is used,
  and — where it is only needed on entry — only while focus is elsewhere, so a
  D-pad move has no reader to invalidate.
- A read is a subscription. Guarding a composition read behind a condition that
  is false on the hot path removes the subscription entirely, which is stronger
  than making the read cheap. Keep the same value available to effects by reading
  it inside the effect body, where reads are untracked and always current.
- Do not signal an inactivity/keep-alive channel from a per-frame source such as
  a scroll position. Suppress the timeout for the duration of the interaction
  (derive from `isScrollInProgress`) and let it re-arm when the interaction ends.
  Plain start/stop signalling is not enough on its own: an interaction longer
  than the timeout would hide the surface under the user's finger.
- Repeated per-item geometry in a draw lambda is a per-frame cost proportional to
  the item count. Build it once with `Modifier.drawWithCache` and submit it as one
  operation (`drawPoints`, a single `Path`) rather than one call per item, and drop
  geometry that resolves to the same pixel as something already drawn — the seek
  bar's chapter ticks do all three.
- Coalesce a continuous gesture at its own handler, never on the shared
  publication path. The volume slider publishes on a bounded cadence from its
  movement handler and immediately from its completion handler; keyboard and
  remote steps bypass it entirely, because each repeat derives its next absolute
  value from the last published one and would otherwise plateau.
- Lazy-list keys and item identity (`LazyRow`/`LazyColumn`/`LazyVerticalGrid`):
  - A key must be stable and unique **within its own list** — usually `key = {
    it.id }`. Compose scopes keys per list, so the same asset id in two
    *separate* lists (e.g. the same title in the Continue Watching row and the
    Favorites row) is NOT a collision and needs no cross-list prefix.
  - When a **single** list can legitimately contain the same asset more than
    once — a multi-section result list rendered as one list, or any repeatable
    queue — the id alone is not unique within that list and Compose throws "two
    items with the same key". Qualify the key with its section or position, e.g.
    `key = { "$section-${it.id}" }` (see `FindScreen` result rows) or `key = {
    index, it -> "${it.id}#$index" }`. (The playback queue instead de-duplicates
    ids up front, so its `key = { it.id }` stays unique.)
  - Identity that spans lists — focus restore/rescue, current selection, saved
    scroll position, "is this item still present" checks — must be keyed on
    **`(container, item)`**, never the item id alone. The same asset in two rows
    is two distinct on-screen entities; collapsing them to one id is what let
    Home focus jump between ribbons / strand on navigation chrome. This is a
    logic rule, independent of the view keys above (see
    docs/guides/tv-ux-behaviors.md).
  - Do not blanket-prefix already-unique single-list keys: changing a stable key
    resets that item's remembered state and item-placement animation for no
    benefit.
- Use lazy item content types when a list mixes substantially different row
  shapes and reuse matters.
- Do not wrap large repeated rows inside one lazy item with `Column {
  items.forEach { ... } }`; emit keyed lazy `items(...)` where virtualization
  matters.

## Previews

- Keep previews focused on content composables with sample data.
- Preview code should not trigger network, database, Koin, or platform-only
  behavior.
- Put IDE-renderable Compose previews in Android source sets, not `commonMain`.
  Shared UI previews live under `shared-ui/src/androidMain`; Android TV previews
  live in the TV app's Android source set. This matches the source set Android
  Studio has rendered reliably for this project.
- Add or update previews whenever adding or materially changing screen UI or a
  reusable visual component. Cover the relevant adaptive dimensions: compact
  phone, phone landscape, foldable/medium, tablet/expanded, desktop/xlarge, and
  Android TV dimensions for D-pad/TV-specific UI.
- Use preview-only sample data with null image URLs or local/static assets
  unless the preview intentionally validates image rendering. Never require
  Koin, network, database, players, or real platform services to render a
  preview.

## Why

Rationale for rules this guide states: the choice, the reason, and what was
rejected. An entry is deleted when its rule changes.

### Theme, launch, and layout

- **Unavailable discovery is omitted from logged-out presentation.** The
  server-entry layout has no result, spinner, empty state, or retry affordance
  to render when its capability is unavailable, so the direct URL form remains
  the only visible path. Rejected: disabled discovery chrome or an explanatory
  launch-flow state.
- **One shared set of explicit player z-layers, not composition order.** Relying on emission
  order let a fallback banner render under the controls, while sharing the
  popup layer put developer diagnostics above actionable chrome. One
  `PlayerOverlayLayer` contract keeps the video/debug/chrome/popup/modal order
  identical across shared and TV players, controls usable above debug data, and
  notices above controls. Rejected: fixing only one banner, retaining shell-local
  numeric copies, hiding controls for passive content, or treating every overlay
  as the same popup class.
- **Column counts come from a pure deterministic kernel.** Adaptive grid cells
  guarantee only a *minimum* cell width and distribute leftover space
  unpredictably — that produced one narrow ribbon of settings on a wide
  desktop window. A pure kernel also puts the rule under test. Rejected:
  adaptive cells.
- **Tile size is one scale factor, not a dimension table.** A multiplier
  through a CompositionLocal keeps the dimensions file the single source of
  truth and lets aspect ratios follow automatically; the phone baseline
  resolves to exactly 1.0 so phone layouts are unchanged by construction. TV
  keeps a deliberately conservative table because overscan, row reveal, and
  focus zoom stack on top of card size. Rejected: a hand-tuned dp table per
  slot (12+ tuples growing with every dimension) and column-count-driven grids
  (break fixed-width card assumptions).
- **The launch flow is theme-independent; Settings is palette-driven.** Launch
  composables never read the app palette, so the logged-out identity is
  constant across themes — the deliberate opposite of Settings. Launch artwork
  is bundled and pre-processed (no runtime blur cost) and never real library
  artwork, because the user is unauthenticated at that point.
- **Settings row classification is exhaustive by construction.** A row added
  later cannot compile without being classified, and a row with no action
  exposes no click semantics, so assistive tech never announces a destination
  that does not exist.
- **Settings glyphs are one shared code-native vector set.** Parsing 30 XML
  resources serially delayed the first Settings open, while separate shared/TV
  mappings let identical artwork drift. Lazily constructed `ImageVector` paths
  keep the canonical geometry, build only the glyphs used, and give both
  surfaces one owner. Runtime path parsing, duplicated vector drawables,
  Material substitutions, and an icon registry/cache service were rejected.
- **Version reuses the existing detail-picker vocabulary.** It is another
  bounded source choice, so touch dropdowns and D-pad modal controls already
  provide selection, accessibility, and focus behavior. Multi-option chips, a
  new picker architecture, and a disabled single-source action were rejected;
  they add layout and navigation states without adding a user choice.
- **Downloads is explicit top-level navigation, conditional on the effective
  session permission, not an invisible Play preference.** A dedicated
  destination gives durable transfer state, storage, failures, and destructive
  actions a discoverable owner when the user is allowed to use it. Silent local
  substitution from ordinary Play was rejected because it hides which artifact
  and progress source the user is using; a second Downloads settings screen was
  rejected because Manage can route to the same stable list and actions. The
  temporary false projection hides the shared navigation, Detail actions, and
  Settings section without deleting the dormant route or screen contracts. The
  two-step size review keeps the server-derived estimate visible before queueing,
  while reason-specific failures explain the blocked action without turning a
  media-detail dialog into a second allocation editor. The shell passes its
  bottom-bar clearance into the Downloads scrollable because system navigation
  insets alone cannot keep the final action row above app-owned navigation. A
  conditional interruption notice makes explicit recovery discoverable without
  relabeling quota-blocked or failed work as resumable.
- **Focus-scale room is reserved in the clipping container's
  `contentPadding`.** Lazy layouts clip by default, so a focused item's scale
  and border must be paid for by the list itself — never by outer margins.
  Full-width rows use a smaller focus scale because the default overflows far
  enough to force an inset that visibly misaligns rows from their heading.
- **The Settings backdrop decodes off Main.** Bundled-resource decoding is not
  UI work, so the image uses a process-cached off-Main helper. Rejected: adding
  an image-loading library for one bundled resource; it adds a dependency
  without changing the decode ownership or cache lifetime.
- **Desktop popups and dialogs stay on the merged canvas.** With the player
  in-scene, separate popup windows can flash or miss their first paint;
  removing the platform window-layer flag keeps sort/picker/dialog surfaces in
  the main redraw path. Rejected: per-surface workarounds (anchor nesting,
  tooltip wrappers) — those were secondary aggravators, not the cause.
- **Touch/pointer surfaces favor usable pane geometry and explicit interaction
  state over decorative residue.** Settings fills its padded pane with one to
  four equal-width columns; shared Home uses the selected theme background
  rather than a separate ambient-artwork request; the Library inner selector
  shares Detail pill geometry; the Settings top bar omits its redundant
  self-navigation action. Diagnostic uploads follow the same truth-first rule:
  the current normalized backend preference labels the capability snapshot,
  and an older failure contributes metadata only when its backend matches.
  Rejected: a capped Settings gutter, a low-contrast secondary scrollbar
  track, and relabeling a stale failure snapshot as the newly selected
  backend.

### Related shelves

- **The shared four-shelf cap lives beside `RelatedGroup`.** A single model
  constant keeps Shared Detail and tvOS aligned while the repository remains
  the owner of source order and deduplication. The Cast-first order makes
  sparse metadata useful without letting a fast low-priority request reorder
  the page. Episode Next Up/Similar and Series' flattened shelf remain explicit
  exceptions. Rejected: a UI-only maximum, completion-order publication, and a
  Director job that has no current consumer contract.

### Library and browse

- **The Library outer selector is a top-bar dropdown with a durable
  per-account selection.** A static title plus tab row spent the whole top bar
  on a label, and the selection lived in `rememberSaveable` so cold start
  always landed on the first library. The top bar gained an optional
  title-content slot rather than a fork; the choice persists through the
  existing server-scoped view-preferences store, written through a conflated
  single-consumer writer because rapid taps otherwise give no ordering
  guarantee. Rejected: a new store for one value, keeping the tab row
  alongside the dropdown, and persisting straight from the UI (a store write
  above the ViewModel boundary).
- **Cross-library Genres and Collections tabs were retired.** Discover already
  covered cross-library browsing, and their results spanned every library
  rather than the one being browsed; genre and collection browsing is scoped
  inside the selected library instead.
- **Library content draws beneath the overlay scrollbar instead of reserving
  its interaction lane.** The scrollbar is an overlay control, so short
  content that does not render it no longer loses usable width, while
  visible-track and thumb interaction stay in the same end-edge lane.
  Rejected: a permanent reserved lane, and shrinking the interactive lane to
  the visible thumb width.
- **Scrollbar overflow controls composition while position is consumed in
  placement, semantics, and gesture phases.** A stable metrics provider keeps
  the latest position at the leaf that needs it while `hasMoreContent` alone
  controls whether the overlay exists. Rejected: changing only the offset
  modifier while a parent still reads and passes full metrics, because that
  composition read would continue invalidating the whole scrollbar and leave
  gesture geometry tied to a composed snapshot.
- **No client-side "date favorited" sort.** The server exposes no favorited
  timestamp and its sort options offer only a boolean grouping; the app
  stamping its own timestamps could only order items favorited in this app
  after the feature shipped, with an arbitrary fallback for everything else —
  rejected as brittle app-facing logic.
- **Card images decode at the size they are drawn, not a fixed canonical
  size.** Fixed-size decoding optimized for one cache entry shared between
  lists and detail, but usage is lopsided the other way: users scroll hundreds
  of posters and open a handful. Sizes quantize to buckets so distinct decode
  sizes stay few — per-item measured widths would fragment the cache and make
  scrolling worse — and results clamp to the old canonical size so no surface
  decodes more than before. Rejected: keeping the canonical size, per-item
  measured widths, and changing wide cards' decode aspect (silently alters
  cropping).
- **Each Library Recommended shelf is its own keyed lazy item.** One `item`
  containing a whole section's shelves forced composing and measuring them
  together and lost per-shelf scroll state. Keys compose section name with row
  key because the row key is only unique within its section — a uniqueness
  requirement, not a blanket prefix.

### Player notices and warnings

- **The backend picker is a session control, not Settings in disguise.** Showing
  policy and availability truth together keeps known disabled targets available
  for inspection without rewriting a stored preference; the distinct failure
  notice assures the user that planning preserved playback. Rejected: hiding
  unavailable choices, exposing `Auto`, an enabled-looking offline or
  in-progress no-op, optimistic teardown, a persisted write from player
  controls, and reusing the fallback success copy for target-planning failure.

- **One compact debug projection is shared by every player shell.** Central
  ownership prevents label, formatting, privacy, unavailable-state, and row-order
  drift without treating every modeled diagnostic as suitable for a live
  in-player panel. Shell-local filters/copies and a second expanded desktop
  projection were rejected because they recreate the drift the shared
  projection exists to prevent; rendering the complete diagnostic inventory was
  rejected because it crowds playback and reintroduces hot/noisy rows.
- **Playback warnings ship OFF by default, labeled beta.** The presentation is
  not yet trusted for stable, so the default is OFF with a data-only migration
  switching existing installs off too; automatic recovery still runs silently
  and the fatal-error dialog is never suppressed. Rejected: flipping only the
  model default (a no-op for any install that ever wrote a preference row) and
  removing the feature.
- **Warning suppression is presentation-only, scoped to the next playback
  start.** The preference hides advisory banners and actionable recovery
  notices while automatic recovery continues its bounded replan silently; both
  notice producers route through one preference-aware setter so neither can
  bypass the gate. Rejected: gating advisory banners only, gating the recovery
  itself (turns a presentation preference into an adaptive-playback kill
  switch — a first attempt did exactly that before review caught it), a
  mid-playback observable contract (the player deliberately snapshots
  preferences once per start), and gating the fatal-error dialog (it reports
  an unplayable stream, not a warning).

### Hot paths and recomposition

- **Live playback state is collected in the player's leaves.** Column-scope
  collection recomposed the trickplay preview, seek bar, time row, and whole
  control strip every tick when only the seek section renders position.
  Rejected: passing whole playback state and relying on strong skipping (the
  state changes every tick, nothing skips), and hoisting the collection to the
  ViewModel (it is already there; the problem was the read site).
- **Chapter selection follows chapter boundaries.** The picker derives its
  selection through `chapterIndexFlow`, which maps `currentChapterIndex` and
  applies `distinctUntilChanged`; TV takes its initial focus index once when
  the chapter menu opens, while later playback emissions update selection only.
  Rejected: recomputing selection from every playback tick and moving initial
  focus whenever playback advances.
- **Each paged grid has one approach-end observer.** `LoadMoreOnApproachEnd`
  owns the viewport threshold for a grid; cards do not install per-card effects
  that duplicate requests when item identity or list size changes. D-pad focus
  navigation may still request load-more explicitly as an interaction policy.
  Rejected: one observer per card, which multiplies callbacks and couples
  pagination to item composition.
- **The seek-position mirror stays a `LaunchedEffect`.** With collection
  narrowed to the seek section, the per-tick effect restarts only where the
  position is rendered. Moving the write into composition risks a one-frame
  seek-release regression on iOS without removing a demonstrated bottleneck.
  Rejected: an in-composition assignment and a snapshot-flow variant (the same
  trade with extra machinery).
- **Controls auto-hide is suppressed for the duration of a strip scroll, not
  kept alive per scroll position.** Keep-alive fired on every frame of a
  fling, touching state on the hot scroll path; the strip reports
  `isScrollInProgress` and the timer is not armed while true, re-arming from
  scratch on scroll end. Rejected: plain start/stop signalling (hides controls
  mid-drag past the timeout) and coalescing keep-alive to a heartbeat (reduces
  the per-frame write rather than removing it).
- **Volume is coalesced at the slider's movement handler, never on the
  publication path.** A drag republished the entire player content per percent
  step. Mute toggles, key steps, and programmatic changes bypass coalescing —
  load-bearing, because a held volume key derives each next value from the
  last published one, so delaying publication globally would plateau the ramp.
  The slider stops adopting the published value while dragged, since a
  coalesced publish trails the finger. Rejected: a global trailing-edge
  coalescer, a second volume source of truth, and a separate volume StateFlow.
- **Chapter ticks batch into one draw op, dropping ticks that overdraw each
  other.** One draw call per chapter meant hundreds of ops plus per-frame
  fraction arithmetic on auto-chaptered assets; geometry builds once per
  chapters/duration/size in `drawWithCache` and paints via a single points
  call, and same-pixel-column ticks are dropped — invisible by construction,
  bounding work by bar width rather than chapter count. Rejected: hoisting
  only the arithmetic (op count stays proportional to chapters) and a minimum
  tick spacing (changes what the bar shows — a UX decision, not a performance
  one).
