# Shared Player Interaction Contracts

This contract covers the shared Android mobile, iOS and desktop player.
Platform tags: **[all]** shared; **[touch]** mobile and desktop pointer;
**[ios]** iOS; **[mobile]** Android mobile and iOS phones. Android TV-specific
input lives in [TV screen behavior](tv-screen-behavior.md#player).

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Transcode seeking and completion

- Seeking a transcode stream to a position outside the produced window
  (`[plan.startPositionMs, bufferedPositionMs]`) restarts the transcode at the
  target (re-plan) instead of an in-stream seek. This is gated by the
  `PlayerController.transcodeSeekRestartsStream` capability (default false; true
  only for AVPlayer, which wedges when a not-yet-produced HLS segment times out)
  so Android/desktop seek behavior is unchanged. Direct-play and in-window seeks
  stay in-stream on every platform. The re-plan reuses the quality/track-change
  path and its default request policy, so a seek after a forced subtitle
  fallback may return to DirectPlay and repeat the native subtitle failure and
  one-shot fallback; this is the accepted transcode-seek behavior. It is guarded
  against concurrent restarts.
- End-of-playback is a one-shot: `PlayerViewModel` emits `playbackEnded` only at
  a true end-of-queue `Completed`, and the shared player closes once through a
  single guarded path; mid-queue `Completed` auto-advances instead.

## Autoplay, queues, and timing controls

- `PlaybackPreferences.autoPlayNext` defaults to enabled with a shared 10-second
  delay. The persisted delay is normalized to 0–60 seconds. Disabled autoplay
  never starts a countdown but keeps manual Play Next available; a zero delay
  advances once immediately. The active queue snapshots this policy with its
  item, queue identity, and playback generation so a stale Completed emission
  cannot advance a replacement queue.
- `PlaybackPreferences.stillWatchingPrompt` defaults to enabled and gates the
  still-watching safety prompt, which otherwise interrupts after three
  consecutive automatic advances. When disabled the gate is skipped entirely and
  the consecutive-advance counter is not kept, so re-enabling it starts a fresh
  count rather than prompting on the next advance. Preferences resolve once per
  playback start, so a mid-session change applies from the next start. Resume is
  unconditional: there is no start-over preference, and the per-launch Start
  over action remains the only way to ignore a stored position.
- A queue switch request reports whether it was **accepted**: `playNext` returns
  false for no next item, a stale auto-advance generation, or the still-watching
  gate taking over. The Up Next affordance may only dismiss itself (and the
  overlay may only hide its controls) on an accepted switch — dismissing on a
  refusal strands the user on a finished item with no visible way to continue.
- Timing offsets are reached from a single **Offset** entry in the
  audio/subtitle track picker (value shown on that entry only when non-zero);
  the offset panel leads with the cumulative value, and closing it returns to
  the parent track picker rather than dismissing to the player.
- A backend reporting subtitle timing as Supported is **not** a reason to show
  the subtitle control: the Android backends report Supported unconditionally,
  and a subtitle delay with no subtitle track has no observable
  effect. Gate the subtitle button and its picker auto-close on an actual
  selectable track or local asset (`hasSubtitlePickerChoice`), never on timing
  support. Desktop mpv implements both-sign audio and subtitle timing through
  `audio-delay` and `sub-delay`; it retains normalized state while an engine is
  absent and reapplies both values on engine construction and before `loadfile`.
  Desktop LibVLC still exposes no `PlayerTimingController`, so Offset remains
  hidden for that backend.

## Controls overlay

- **[all]** Controls auto-hide after 4s while playing. Every control, seek,
  picker, gesture, pointer-move, or controls-scroll interaction resets the
  timer. Loading, buffering, and paused transitions reveal controls. BACK
  dismisses a visible picker first, then visible controls even while paused; a
  subsequent BACK leaves the player.
- **[all]** Only POINTER-device movement reveals a hidden overlay; touch
  movement only keeps an already-visible overlay alive. This pointer-type gate
  prevents Android back gestures from revealing controls and preserves the tap
  toggle's single owner.
- **[touch]** A centre tap toggles the controls immediately — the centre zone
  has no double-tap gesture, so it never needed to wait out the double-tap
  window — and a second centre tap inside that window is swallowed so the
  overlay cannot flicker. Side taps keep the delay because a second tap there
  seeks. The decision is the pure `playerTapAction` policy.
- **[touch]** The edges where the player ignores its own taps and swipes are the
  existing screen-size ratios OR the device's real `WindowInsets.systemGestures`
  insets, whichever is larger, so back-gesture and home-indicator bands are
  honored as the device reports them. On desktop those insets are zero; on iOS
  they are the root view's layout margins, which the ratios already exceed at
  realistic sizes.
- **[all]** Buffering shows a plain spinner without a scrim. Controllers report
  seek stalls as Buffering; desktop does so immediately after `seekTo` and while
  mpv reports `seeking`.
- **[touch]** Double-tap left/right seeks and long-press temporarily boosts
  speed, each with a transient HUD. Normal-player vertical swipes adjust
  brightness/volume on **[android]** only — iOS and desktop install a no-op gesture controller whose
  brightness/volume updates return no value, and the shared gesture code
  silently skips the HUD when there is no value to show, so the swipe is inert
  rather than broken. Android captures the exact window brightness value when
  the player takes ownership. Each swipe begins from the current window
  override, or from the clamped system brightness setting when no override is
  active. Player disposal restores the exact ownership-time value rather than
  forcing the no-override sentinel. Meter labels render one percent sign with
  the shared resource formatter. Kids disables both adjustments and uses its
  [fullscreen drag interaction](kids-viewing.md#kids-watch-page) instead.
- **[desktop]** Window-level capability bridges deliver Space, Left/Right, Esc,
  fullscreen double-click, and cursor hide/restore even when inner controls own
  focus. While the player is fullscreen, pointer inactivity hides the cursor
  after the configured delay regardless of play/pause state; pointer movement
  reveals it and restarts the delay. Pickers keep the cursor visible. The root
  focus shortcut path is used only without the bridge. The desktop cursor bridge
  must apply visibility to the embedded Compose AWT component tree, not only the
  outer window, because child components can own the effective pointer cursor.
  On macOS, controls are composed only in the parent scene. A
  `BlendMode.Clear` cutout exposes the native video through that scene, and a
  1dp `SwingPanel` supplies only the AWT peer anchor while Compose layout drives
  the real native extent. mpv and LibVLC views stay below Compose through
  negative layer `zPosition` and return nil from AppKit `hitTest:`, leaving
  hover, click, double-click, and control hit testing on the normal
  AWT-to-Compose path. mpv uses OpenGL swap interval zero so presentation does
  not block its render executor on display refresh.
- **[all]** Picker sheets are bottom-centered and capped on wide windows. Use a
  dark flat-row panel with title divider and selected checkmarks, not full-width
  chip grids.
- **[all]** Up Next and the bottom controls share one bottom layout stack. When
  controls are visible, Up Next sits above their actual composed height; when
  controls are hidden, it returns to the bottom safe area.
- **[all]** Up Next can be previewed during the end window, but automatic
  countdown and queue advancement begin only after the current item reports
  `Completed`. Not Now dismisses only the Up Next card (and cancels its
  countdown for that item); the player stays open and BACK remains the exit
  path. The shared contract is only an immutable dismissal identity derived
  from the target item, queue index, and autoplay countdown key; shared and TV
  surfaces retain their own local mutable dismissal/focus state. A new target,
  queue position, or playback generation changes the identity and re-arms the
  card.
- **[all]** Hide Chapters when there are none. The selected chapter is the last
  timestamp not after the current position; before the first timestamp none is
  selected. An open chapter picker reads the live playback position so selection
  advances across chapter timestamps without reopening the picker.

## Seek bar

- **[all]** Taps/scrubs commit on release (`onValueChangeFinished`), not
  continuously.
- **[Android LibVLC]** `Event.Buffering` is a cache-fill percentage over a
  dynamic native duration, not milliseconds. Keep `bufferedPositionMs` at the
  playhead and draw no secondary track; the raw percentage is diagnostic-only.
  Never substitute a `--network-caching` estimate.
- **[all]** Draw buffered progress and show a trickplay thumbnail only while a
  user-driven scrub or pending seek target is active, and only after that
  target's current sprite sheet loads successfully. Merely focusing the seek bar
  or displaying the live playback position never requests or shows a preview.
  Missing or invalid metadata, loading, and image failure render no preview
  chrome; a later scrub session or tile identity may retry the request. A
  successful sprite is drawn at its full tile-grid extent through a clipped
  one-thumbnail viewport; parent layout constraints must not shrink the sheet
  before applying the crop. Release diagnostics distinguish unavailable
  metadata, loading, success, failure type, decoded dimensions/source, and crop
  coordinates without media, account, server, URL, or credential identity.
- **[tv]** A committed seek retains its exact target thumbnail until the
  player's resulting loading/buffering recovery ends. If the target was already
  buffered and no recovery transition begins, a short bounded handoff clears
  the retained preview. The thumbnail is horizontally centered over the target
  where space permits and clamps within the seek-bar width near either edge. A
  shallow pointer uses three quarters of a player option button as its maximum
  width and bends from the clamped thumbnail toward the exact seek position.
  Its base narrows near a hard endpoint so the edge-facing side remains compact
  instead of curling into a broad hook, without materially increasing the scrub
  section's height. When recovery releases the target, the loaded preview fades
  out while the lane collapses toward the scrubber over a short transition.
  Non-null target changes share one animation identity so held D-pad steps move
  immediately; an item identity change disposes the old animation immediately.

## Hold-to-seek (TV D-pad/media keys, desktop keyboard)

- Held seek keys accumulate a UI-local **pending target** (`HoldSeekAggregator`)
  instead of committing per keypress: the initial tap advances by 10 s and
  every second repeat advances the target with a duration-scaled step curve
  (base 10 s until the ramp threshold, then geometric growth capped by content
  duration; unknown duration never accelerates), while skipped repeats remain
  activity for the watchdog. The seek bar, position label, and trickplay
  thumbnail follow the pending target during the hold. Exactly one `seekTo`
  commits on key-up, producing one `TimeUpdate` report.
- Timing lives in `HoldSeekTimers`: a two-stage inactivity watchdog (600 ms
  grace before the first key repeat — Android's first repeat arrives at ~400 ms
  — then 300 ms of repeat silence) final-commits lost key-ups and terminates the
  session (no cadence tick can follow); a session-keyed 2 s cadence commits
  mid-hold catch-ups with the ramp retained and the session re-anchored at the
  committed target.
- Direction reversal rebases from the pending target and resets the ramp.
  Transport keys (play/pause/SELECT/Space) commit the pending target before
  acting; BACK/Escape cancels it without seeking.
- Every hold session is **item-bound**: `HoldSeekController` captures the
  published `playbackItemId` (read synchronously from the ViewModel state, not
  the composed snapshot, which lags a frame) when the session starts, refuses to
  start while identity is unstable (null), and drops any final, watchdog, or
  cadence commit whose identity no longer matches — so a key-up or watchdog
  racing a queue switch can never seek the replacement item.
  `Content.playbackItemId` goes **null synchronously in `startQueueSwitch`**
  before planning suspends; only the playback-start `installPlan` restores it
  (`stabilizesQueueSwitch = true`) — a replan re-prepares the same item and
  never stabilizes switch identity, and `startQueueSwitch` cancels any in-flight
  `replanJob` so a stale quality/track plan cannot install mid-switch. Leaving
  `Content` or disposing the player cancels the pending state too.
- **[desktop]** Pointer scrubbing owns the pending state: an active slider press
  (`pointerScrubActive`, hoisted from `SeekSlider` press/release/ cancel
  interactions) makes keyboard seek keys inert, and a pointer press mid-hold
  cancels the keyboard session without committing. The window key bridge reveals
  and keeps controls alive from the first LEFT/RIGHT key-down so the pending
  target is visible during the hold.

## Segment skip policies

- Each known media-segment type (Intro, Outro, Recap, Preview, Commercial) has a
  per-server `SegmentSkipPolicy` in `PlaybackPreferences`
  (`AutoSkip`/`Ask`/`Ignore`, default Ask; unknown persisted names fall back to
  Ask). Unknown segment types are always Ignore. Preferences resolve once per
  playback start; mid-session settings changes apply from the next start.
- `Ask` surfaces the segment through the derived `Content.skipPromptSegment`;
  both players render the Skip button from it while `currentSegment` remains the
  unfiltered truth (the Outro-based Up Next window logic is unaffected by
  policy).
- `AutoSkip` seeks in-item to the segment end through the normal `seekTo` path,
  **once per segment identity (type+start+end) per item playback session**, only
  while `Playing`: resuming or seeking into an auto segment skips it once;
  seeking back into an already-skipped segment never re-yanks; the session set
  clears on each playback start (including queue advancement). Auto-skip never
  calls `playNext`, never opens Up Next early, and cannot bypass the Still
  Watching gate — completion always arrives through the normal path.

## Transcode seeking

- **[all]** Seeking within `[transcode start, buffered-ahead]` is an in-stream
  seek. Direct-play seeks are always in-stream.
- **[ios]** Seeking outside the produced transcode window re-plans at the target
  because AVPlayer otherwise requests an unavailable HLS segment and can wedge.
  `transcodeSeekRestartsStream` gates this to AVPlayer; concurrent restarts
  share one cancellable re-plan job.

## End of playback

- Normal queue playback emits `playbackEnded` and closes exactly once at its
  true end; mid-queue completion advances instead. Episode playback without a
  usable explicit queue derives a chronological season/episode queue for Up
  Next, while explicit queues remain authoritative.
- Mobile [Kids single-asset playback](kids-viewing.md#single-asset-kids-playback)
  suppresses queue derivation and automatic advance, keeps the completed asset
  displayed, and waits for explicit Replay or another card selection.
- **[all]** Failed/aborted playback that never started must not clear Continue
  Watching progress.

- **[all]** The player quality picker keeps **Use playback default** and
  explicit **Auto** as separate rows even when they resolve to the same policy:
  the first clears the current-playback choice and displays a second line naming
  the effective value and its general/VLC settings source; the second selects
  Auto for this playback and is the only Auto row that authorizes automatic
  quality lowering from health evidence. An inherited Auto default instead
  presents manual in-player choices. Their pure lazy-list identities are therefore distinct: the
  inherited-default action uses a dedicated sentinel, while explicit Auto,
  Original, canonical Fixed, and off-ladder Custom Fixed rows derive identity
  from mode and bitrate. This identity does not change row ordering, selection,
  replanning or picker dismissal behavior. No player quality row persists by
  asset; a fresh playback starts from settings again.

- **[shared/Android TV]** Audio is available with at least two real tracks or
  supported audio timing, preserving Offset access for a single track. Subtitle
  remains available with one real track because Off is an additional selectable
  state.

## Why

### Player UX

- **Android brightness borrows window state.** Ownership captures the exact
  restore value, while each gesture begins from the live override so repeated
  swipes remain continuous.
- **Seek policy stays evidence-based.** Approximate constant-bitrate seeking is
  disabled without a demonstrated need, and hold-to-seek velocity is reduced by
  gating repeat advancement rather than changing tap granularity.
- **Playback speed is a system-publication edge.** A speed-only change updates
  transport rate even when metadata and position are unchanged; paused playback
  still publishes a zero rate.
- **Overlay and navigation actions keep their existing scope.** Pointer movement
  reveals desktop controls; Back retains its platform exit behavior. **Not now**
  dismisses only the current Up Next identity, and new item/queue/generation
  state re-arms it.
- **Settings expose only effective behavior.** The contradictory resume override
  was removed, while Still Watching remains independent from Autoplay next.
  Inactive controls are hidden; audio offset remains available with one audio
  track because A/V synchronization is still meaningful.
