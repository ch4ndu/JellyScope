# Kids Viewing Engineering

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Kids Account Playback

- `Session.maxParentalRating` and its stored projection retain Jellyfin's nullable
  `MaxParentalRating` from the shared password/Quick Connect mapper. Values 0..7
  enable the mobile Kids presentation; null, negative, and higher values use
  normal playback. The US TV-Y7 and TV-Y7-FV limits both use score 7. This selects
  presentation only: library assignment and content restrictions remain server
  responsibilities, and `EnableContentDownloading` remains independent.
- On mobile account-subtree entry, one best-effort current-user read captures
  the session and boundary epoch and validates response identity. Failure keeps
  the saved rating; successful null clears it. The guarded same-boundary commit
  is owned by [account lifecycle](accounts-and-persistence.md#account-boundary-and-lifecycle).
  There is no polling. Eligibility is captured at the next Player route entry,
  so a refresh never changes an active video's layout. Old envelopes default to
  null offline until a successful refresh or login.
- The catalogue uses one captured account/session/epoch across accessible real
  video libraries, excluding Music and system views. Bounded recursive pages
  request only Movie/Episode card metadata, use supported SortName ascending
  order, terminate from raw page size, and deduplicate IDs. Concurrent changes
  or equal sort names remain best effort under server pagination. Any failure
  discards the partial load. The existing `DiscoveryCache` stores only completed
  successful catalogues in RAM under a distinct key and retains account cleanup;
  callers also reject stale returns after loading. No Jellyfin recommendation,
  Similar, Suggestions, Next Up, or random endpoint feeds this page.
- The watch state owner shuffles locally off Main, keeps current items in the
  pool while excluding them from displayed rows, and preserves order through
  playback and layout changes. The watch page has one shuffled list, without a
  separate watch-history shelf or additional history requests. Only
  artifact-unavailable errors remove a local card; decoder or backend failures
  do not invalidate its files.
- Explicit offline watch entries make no catalogue/history requests. Their pool
  contains current-account Completed Movie/Episode records whose artifacts pass
  `GetOfflinePlaybackPlanUseCase`; missing or changed generations invalidate
  qualification, and explicit retry rechecks. Playback always revalidates a tap.
  The newest valid download per media item supplies metadata, and surviving cards
  retain their order across progress updates. Snapshots contain no artwork. A failed remote
  catalogue can expose the same qualified local fallback, gated by the existing
  download permission; no connectivity monitor or remote fallback is added.

Presentation belongs to [Kids watch page](#kids-watch-page); direct selection,
completion, and from-beginning replay belong to [single-asset playback](#single-asset-kids-playback).

## Single-asset Kids playback

`PlayerLaunchPolicy` defaults to normal queue playback. A qualifying mobile
route selects `KidsSingleAsset`: incoming queues, episode derivation, playlist
metadata, Next/Previous/Shuffle, Up Next, automatic advance, and still-watching
countdowns are suppressed. Recommendation cards never become queue entries.
Completion settles reporting and keeps the selected asset displayed for Replay.

Direct selection retains one PlayerViewModel/controller owner and the existing
release-first installation path. The accepted target owns item ID, optional
DownloadId, and launch identity. Initial loading, selection, and retry share the
cancellable launch owner; selection invalidates outgoing seek/plan/source/track
state and settles reporting before installing the new target. Refused controller
installation conflicts leave selection unchanged, and stale completions cannot
replace the latest accepted target. Retry uses that current target; constructor
source fallback is limited to the initial target before manual selection.
Retry reuses a controller position only after the current selected target has
actually played; earlier failures preserve that target's launch position.

Remote recommendation cards begin at zero; ordinary local cards use saved local
resume. Replay explicitly starts from the beginning, including
a zero effective local-resume input when building an offline plan. Replay does not
erase durable progress to construct that plan. A pending from-beginning launch
keeps that intent through Retry until the matching asset actually starts playing;
a different target or disposal clears it. Meaningful current source/track
choices are preserved for replay. Account/catalogue ownership belongs to
[Kids account playback](#kids-account-playback), and layout/input
to [Kids watch page](#kids-watch-page).

## Kids watch page

- Eligibility and account freshness follow [Kids account playback](#kids-account-playback).
  The presentation is captured once per player route on Android/iOS phones and
  tablets; TV and desktop keep normal playback. There is no app toggle, Kids
  home redesign, parent approval, or viewing timer.
- The [selected touch mockups](../design/kids-mode/player-mockups/README.md)
  define video placement and control hierarchy. Recommendations use the vertical
  grid described below, superseding the mockup carousels and featured first card.
  Portrait uses the full available video width with no horizontal outer padding,
  above the recommendation grid.
  Landscape uses a left-aligned player and a fixed, normally scrolling right
  recommendation region; this follows window orientation, not the 840 dp tier.
  The 16:9 viewport uses Fit to preserve source aspect, never stretching into
  a column. Fullscreen/PiP fit and
  center that viewport in the available canvas without replacing its native owner.
  Portrait and landscape fullscreen entry/exit animate the same 16:9 viewport
  over 250 ms; recommendations slide and fade out, then return on exit. Rotation
  uses 500 ms to resize the video around the destination pane center while
  recommendations move to their new region beneath the video layer. Old screen
  coordinates never drive the rotating video's center. Center and size have
  separate animation state, so a fullscreen toggle during rotation continues
  from the visible center. The native player stays
  installed. Safe spacing follows system-bar changes; animation and drag values are
  read during layout/placement. Rapid toggles retarget the current animation.
  PiP uses direct geometry. Geometry changes never replan or restart playback.
- Inline layout consumes the top safe-drawing inset once above the video.
  Cutout/navigation insets protect overlaid controls and recommendation content,
  without becoming horizontal video margins. Landscape sizing leaves the bottom
  system area clear. Fullscreen retains native bar hiding and safe control insets.
- The current title is compact and single-line below the portrait video, and
  below the landscape video when vertical space permits. One More to watch
  heading covers online or offline recommendations. A single shuffled pool
  is projected off Main into one vertically scrolling adaptive grid on all phone
  and tablet orientations. Columns adapt to the available recommendation width
  and tile-size preference; every asset occupies one equal-width cell. There are
  no horizontal ribbons or special first-card spans. Every card uses 16:9 artwork,
  a one-line ellipsized title, and an optional
  duration badge when metadata exists. No subtitle/metadata row adds tile height.
  The current asset is excluded; there is no history shelf, inferred recommendation
  category, or autoplay queue. Scroll positions survive orientation/fullscreen.
- Initial preparation and buffering show a centered compact loading indicator,
  including when controls are hidden. Play/Pause/Replay replaces the indicator
  only after preparation or buffering ends; terminal failures use the shared typed
  error/recovery presentation without a Play action. PiP omits the custom center
  control. Media-button toggles use the shared play-intent and Replay policy.
  Centered play/pause or Replay and top Back/options overlay the video. Elapsed
  time, seek, duration, and fullscreen share one compact bottom row. The primary
  button uses the active theme accent; timestamps retain contrast on bright video.
  All buttons retain 48 dp or larger targets. Audio, Subtitles, Quality, and
  recovery use the existing UI; open options suspend auto-hide. Inline gestures
  do not intercept list scrolling. Existing loading/error/retry and permitted
  Downloads actions remain available. No social or duplicate below-video controls
  are added. Audio-unavailable, subtitle, and backend notices use existing shared
  banners and timeout constants; the audio warning can be dismissed and returns
  on control interaction, with a glyph while controls are hidden. Gesture HUDs
  expire using the shared timeout.
- Dragging the inline video upward enters fullscreen; dragging the fullscreen
  video downward exits it. Either direction follows the finger up to 50% of the
  watch screen height, allowing the video to move off-screen. The release
  threshold in both directions is 20% of the measured video viewport height at
  pointer-down, fixed for that gesture even if its size changes. Releasing at or
  beyond that distance in the active direction commits the animated transition;
  a shorter or cancelled drag animates back. Reversing before release uses the final
  displacement. The gesture starts on the video, not the recommendation grid;
  inline taps still toggle controls without double-tap seek or hold speed.
  Rotation/PiP or an external fullscreen change cancel the drag;
  system gesture edges and overlaid controls keep their input ownership. Kids
  disables brightness/volume swipes on both sides without acquiring the native
  brightness controller. Fullscreen tap, double-tap seek, and hold speed remain.
- Back returns within or closes a picker, then exits fullscreen, then stops and
  pops the watch route. It has no extra hide-controls step. Background/PiP close
  uses the explicit route stop path. Permitted Downloads navigation also stops
  and pops the player before opening the existing destination.
- Offline cards use saved snapshot metadata and artwork. Legacy downloads and
  missing images use placeholders; cached remote thumbnails do not imply offline
  availability. Download permission and artifact qualification remain governed by the [download contract](downloads.md#downloads-and-offline).

## Why

### Kids catalogue and history

- Curated membership is a catalogue, not a queue or recommendation service.
  Account/epoch checks contain async results; fresh UserData avoids freezing
  history in RAM.
- Offline availability means a qualified completed artifact, not cached remote
  metadata. Generation memos reduce file work, while retry and playback revalidate.

- **Single-asset choice needs explicit target ownership.** A one-item queue alone
  would still allow episode derivation, stale retry inputs, and route-closing
  completion. Kids uses a distinct launch policy while retaining the existing
  planner, reporting, artifact leases, and native installation owner.

- Kids recommendations are discovery views, not queues. One retained
  player and explicit selection avoid autoplay while preserving responsive
  layout and fullscreen continuity. Animation keeps the live surface; rotation
  uses the destination pane center and longer timing, while fullscreen keeps its
  shorter timing. A video-height release threshold makes drag transitions
  reversible and reachable in portrait.
