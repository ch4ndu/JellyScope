# Desktop Playback

Engineering contract. See the [documentation map](../README.md) for related
owners and the [user guides](../USAGE.md) for usage instructions.

## Desktop recovery and system integration

- Desktop mpv completion readiness is bound to the current prepare generation
  and replacement playlist entry. Every prepare increments the generation and
  disarms completion in one lifecycle-locked transition before `loadfile`; only
  matching `START_FILE` and `FILE_LOADED` events arm EOF for a resolved entry.
  Each poll revalidates its generation-tagged readiness observation under the
  same lock before setting `loaded`, applying selections, or publishing playback
  state. Stop, release, load failure, engine replacement, and prepare
  supersession invalidate readiness. If mpv cannot expose the replacement entry
  ID, the controller stays disarmed until it observes an explicit
  `eof-reached=false`; an unavailable/null read is not evidence that the stale
  latch cleared. It then permits a later EOF and emits one sanitized fallback
  diagnostic containing only the allowlisted reason and candidate count.

- Desktop VLC publishes audio/subtitle activation through the **shared**
  `AudioActivationConfirmation`/`SubtitleActivationConfirmation` kernels, as mpv
  does. Its VLC-specific retry loop (10 x 250 ms) still *drives* selection —
  elementary streams exist only during playback — but the state transitions and
  the single non-extending three-second deadline belong to the kernels, so
  repeated `TracksChanged` cannot restart the deadline and an exhausted attempt
  cannot overwrite a newer `Active` (exhaustion publishes through the kernels'
  still-pending guard). Mapping diagnostics use the allowlisted fixed grammar
  rather than free-form lines. A slave-attached plan target still auto-confirms
  on the `Playing` event, and `stop()`/`release()` clear both confirmations so an
  armed deadline cannot fire over a stopped session.
- Desktop playback probes use one JVM `DesktopPlaybackProbe` gate and typed
  Kermit sink across mpv, LibVLC, native surfaces, hierarchy, and input/window
  sources. The gate is enabled dynamically by **Collect diagnostic logs** or
  forced on by the compatible `jellyscope.desktop.playbackProbeLog=true` JVM
  property; changing Settings takes effect without restarting, while the
  OpenGL presentation force remains CLI-only. Event sources stay installed for
  their application/window lifetime and gate each callback before emission.
  Records contain only closed enums, booleans, bounded numbers/lists, and
  normalized technical tokens—never track labels or media identity. The
  dedicated `JellyScopePlaybackProbe` scrubber schema requires the exact fields
  for each event and rejects unknown, duplicate, malformed, credential-like,
  path/URL, or free-form values before the bounded safe-history store.
- Desktop native events and poll publications are drained by ONE serialized
  consumer (an unlimited `Channel` on the state scope). libvlc's callback thread
  only `trySend`s, so it never blocks and its arrival order is preserved, and
  the end-of-stream evidence plus the seek pin therefore have a single writer.
- mpv teardown is asynchronous on EVERY presentation, including the software
  path used by Windows and Linux, so closing the player never runs
  `mpv_terminate_destroy` on the caller thread. Native commands hold a
  refcounted engine lease taken atomically with the context read; teardown nulls
  the context under the same lock and waits (bounded) for outstanding leases
  before freeing. A lease that outlives the wait defers destruction until the
  count reaches zero — the OpenGL surface stays open and detach callbacks stay
  queued until that real destroy completes, then report `Terminated`. Permanent
  quarantine remains reserved for an undrained render executor. Moving `sub-add`
  off the lifecycle lock removes the app-side stall; `mpv_command` is still
  serialized inside libmpv, so a slow sidecar fetch can delay a concurrent
  native command.
- mpv `END_FILE reason=ERROR` for the current playlist entry surfaces as
  `Failed` (handled BEFORE the pending-subtitle early return; stale entries,
  STOP, and REDIRECT never fail playback). Classification is native-code first —
  `MPV_ERROR_LOADING_FAILED (-13)` → Network (feeds the shared one-shot retry),
  `NOTHING_TO_PLAY (-16)`/`UNKNOWN_FORMAT (-17)`/ `UNSUPPORTED (-18)` →
  UnsupportedMedia — then the keyword classifier over `mpv_error_string`. This
  is what engages the shared recovery ladder and network retry on desktop.
- Buffering derivation includes `core-idle` (gated on loaded/not-paused/not-
  completed) alongside `paused-for-cache` and `seeking`.
- `track-auto-selection=no` is a required init option: mpv never auto-picks
  tracks. On `FILE_LOADED` the controller performs deferred default selection
  (first video track's selector id → `vid`; resolved pending audio target, else
  first embedded audio → `aid`; `sid` stays off until selected). The explicit
  selection paths remain authoritative and overwrite defaults.
- Init options are split REQUIRED (abort init on failure) vs BEST_EFFORT
  (`tone-mapping=bt.2390`, `target-peak=auto` for the software render path;
  rejected options log one sanitized diagnostic and continue). A desktop-only
  Settings caption under the HDR picker (`isDesktopHdrToneMapNoticeVisible()`)
  explains mpv's software tone mapping and LibVLC's server HDR-to-SDR
  conversion; neither necessarily matches native HDR presentation.
- `DesktopDisplaySleep` (jvmMain, JNA → IOKit `IOPMAssertionCreateWithName`
  PreventUserIdleDisplaySleep) holds one idempotent assertion while status is
  Playing or Buffering, released on pause/stop and always on dispose; no-op off
  macOS. mpv cannot inhibit sleep itself (`vo=libmpv` has no window).
- In-app volume/mute (desktop only): `PlayerVolumeController` side-interface
  (commonMain contract, implemented by **both** desktop controllers —
  `MpvPlayerController` and `DesktopLibVlcPlayerController`; clamped 0-100).
  `Content.volumeControl` is null on platforms without the interface, hiding the
  desktop-style controls (mute button + adjacent slider in the desktop
  control cluster) and making ArrowUp/ArrowDown/M keys unconsumed.
- **Desktop volume persistence — the write bound lives in the store, not the
  controller.** `setVolume`/`setMuted` publish to `_volumeState` and submit to
  `DesktopPlayerVolumeStore` immediately on both backends, with no controller-side
  timer. `JvmFileVolumeStore` passes changed values and failed-write retries to its
  single `SerializedLatestValueWriter`, which restarts a **500 ms trailing
  debounce** on an injectable **monotonic** clock for each submission it receives.
  - `submit` suppresses a state equal to `latestState`, preventing held input at
    the 0/100 clamp from starving the quiet window. A failed write revokes that
    suppression for one retry; suppression is never based on the last persisted
    value.
  - There is exactly one writer and no flush, drain, direct write, or close-path
    drain. This preserves serialized ordering.
  - App state is the only volume truth and flows app → engine, including when an
    engine is newly attached. Native poll readback must never publish `volume` or
    `mute` into `_volumeState` because it can overwrite newer user intent.
  - The accepted loss is a change made within 500 ms of quitting the app on
    either desktop backend. Closing the player while the app stays open persists
    normally via the in-process `latestState`.

  This is a documented controller-owned persistence exception to the
  UseCase/Action write rule; the writer runs `NonCancellable` on IO.
- macOS Now Playing loads MediaPlayer.framework before Objective-C lookup and
  resolves/dereferences its exported `MPMediaItemPropertyTitle`,
  `MPMediaItemPropertyArtist`, `MPMediaItemPropertyPlaybackDuration`,
  `MPNowPlayingInfoPropertyElapsedPlaybackTime`, and
  `MPNowPlayingInfoPropertyPlaybackRate` globals; the publication dictionary
  uses only those framework-owned key objects. The bridge retains its
  per-signature `objc_msgSend` bindings and main-queue publication through the
  `_dispatch_main_q` global symbol (`dispatch_get_main_queue` is a macro).
  Command-target registration remains process-lifetime, while per-session
  gating and ViewModel command routing preserve transcode-seek rules. Load,
  install, asynchronous publish, and clear are separate failure boundaries;
  each degrades to no-op playback behavior and emits only the closed event plus
  sanitized exception class through `formatSafeFailureDiagnostic`, never a
  message, cause, or stack.

## Desktop player presentation

- The frame-ready, double-buffered software Render API bridge remains
  the Windows/Linux presentation and the automatic macOS compatibility
  fallback. It is not a high-resolution acceptance path.
- macOS mpv uses an app-owned IOSurface Render API surface by default. A private
  CGL context renders into a three-buffer IOSurface swapchain, and the render
  executor publishes each completed surface through a disabled-actions
  `CATransaction` on an app-owned sublayer. The host falls back to the
  generation-scoped `NSOpenGLView` surface when IOSurface construction or its
  FBO self-check fails, then to the software bridge when the engine cannot
  initialize. The IOSurface route requests `videotoolbox-copy`; OpenGL and
  non-macOS routes retain mpv's `auto-safe` policy. Neither native path supplies
  `wid` or `gpu-context=macvk`.
- The app-owned IOSurface removes the shared-context presentation bottleneck,
  but libmpv's OpenGL-only Render API still has a high-resolution and pointer-
  movement limitation on macOS. Keep this limitation explicit until a replacement
  demonstrates continuous embedded presentation under sustained pointer input.
- Settings → Playback → Default player exposes independent macOS `mpv` and
  `LibVLC` choices for the next playback session. LibVLC is selectable
  only when the cached file-based runtime check finds a usable VLC layout; the
  check does not create a native engine, and a VLC install appearing after the
  cache is evaluated needs an app restart before the Settings listing changes.
  VLC 3 version validation remains at engine creation; an incompatible runtime
  follows the existing backend-fallback notice. Its native AppKit child surface
  does not share mpv surface contracts or the mpv OpenGL limitation, so it may
  be worth trying when continuous pointer movement degrades mpv. The Standard
  [compatibility envelope](playback-policy.md#1-product-policy-and-invariants) bounds LibVLC's independent
  very-high-resolution input risk; Unrestricted permits a deliberate unchanged
  attempt, so LibVLC is still not a universal fallback.
- `DesktopVlcRuntimeDiscovery` checks for `lib/libvlc.dylib`,
  `lib/libvlccore.dylib`, and a `plugins/` directory. The bundled root is
  preferred; development runs may use `/Applications/VLC.app`, and engine
  creation validates VLC 3 compatibility. [BUILD.md](../BUILD.md#desktop-jvm)
  owns fetch/cache/offline behavior, the [release runbook](../RELEASE.md#manual-macos-stages)
  owns packaging, signing, and verification, and
  [licensing and distribution](../operations/licensing-and-distribution.md#desktop)
  owns provenance and source obligations.
- Desktop player hosts and native video surfaces start black. For a non-zero
  LibVLC start, the controller supplies a per-media `:start-time` input option
  before playback and retains its bounded post-start seek as a compatibility
  fallback. The native view stays transparent, playback status stays
  `Buffering`, and native audio stays suppressed until the clock is near the
  requested position and at least two pictures beyond the post-seek baseline
  have been displayed. One picture is not sufficient because cold decoder
  startup can leave that opening picture frozen while the audio clock advances.
  The shared buffering indicator therefore covers decoder startup or fallback-seek work
  instead of exposing a stale opening frame while audio advances. Once ready,
  the surface becomes visible and the controller restores the user's actual
  mute state.
- The shared controls render only in the full-player parent Compose scene above
  either native surface; there is no desktop controls window or synchronization
  lifecycle. Very-high-resolution desktop playback limits are backend-specific:
  mpv can accumulate output drops under continuous system pointer movement even
  with IOSurface presentation, while LibVLC can independently lose output
  pictures on streams beyond sustainable client capacity. Neither backend has a
  universal very-high-resolution acceptance claim.

- Optional development launch flags make this baseline repeatable for a stored
  authenticated session. `jellyscope.desktop.initialPlaybackItemId` opens the
  normal player route. `jellyscope.desktop.initialDetailItemId`, or the
  `jellyscope.desktop.deepLink` property containing the canonical
  `jellyscope://details/<itemId>` URI, opens the normal item-detail route; an
  explicit detail target wins if both a detail and player target are supplied.
  Packaged macOS apps also register `jellyscope` and handle that URI while the
  app is running through the platform open-URI callback.
  The legacy `jellyscope.desktop.playbackProbeLog=true` launch property is the
  force-on compatibility input for the same Settings-backed typed probe above;
  it does not restore the former stdout/free-form path. The Compose `hotRun` task
  must receive the same macOS interop/layer module-open arguments as normal
  desktop launch plus the development native-library path; otherwise the
  AppKit surface cannot attach and VLC remains pre-play with black video and
  suppressed audio. Native bridge or JVM-argument changes still require a fresh
  Hot Reload process because class reload cannot retrofit process launch
  options. `jellyscope.desktop.forceMpvOpenGlSurface=true` is a development-only
  presentation force that skips the macOS IOSurface attempt and exercises the
  mpv OpenGL surface; it has no planning, credential, backend-policy, or
  default-navigation effect. None of these flags changes planning, credentials,
  backend policy, or default navigation.

## Desktop

Desktop defaults to mpv and offers LibVLC when its runtime is available. mpv and
LibVLC use separately owned immutable declarations and capability snapshots.
mpv marks its claims as pinned-engine evidence; LibVLC's base declarations stay
static with unknown finite limits. On macOS only, Standard compatibility
intersects every locally advertised LibVLC video codec with one app-owned,
user-overridable input envelope: 3840x2160, 8,294,400 pixels per frame, and
497,664,000 pixels per second. Width and height shape the server profile;
frame-area and throughput remain local source-copy preflight facts because the
wire profile cannot express them. Unrestricted removes only this marked product
envelope. It does not broaden codec, container, HDR, audio, output, or
explicit-quality facts, and it never affects mpv or non-macOS LibVLC.

Auto and eligible Fixed retain the existing single forced-re-encode recovery
when source-copy preflight refuses the source. Original never takes that
stream-changing request and fails closed before native prepare. Neither window
nor screen size is a decode limit. mpv's software path reports an app-published
frame; its production macOS OpenGL path explicitly reports first-output
measurement unsupported until it has a trustworthy compositor-presentation
callback. Desktop LibVLC reports a positive displayed-picture counter.
Render/presentation telemetry remains diagnostic evidence, not a capability or
quality policy. Desktop presents the same persistent actionable notice contract
through shared Compose, with its pinned runtime supply, surface, and lifecycle
ownership left intact.

## Desktop libmpv boundary

macOS loads only the pinned bundled mpv closure; Linux uses system lookup and
Windows packages supply `mpv-2.dll`. Apple Silicon packages also bundle the
audited VLC runtime. Acquisition and offline behavior belong to
[BUILD](../BUILD.md#desktop-jvm), and signing, provenance, and package checks to
the [release runbook](../RELEASE.md#manual-macos-stages). Android mpv is a
separate integration.

Desktop sets `LC_NUMERIC=C` before using the loaded libmpv interface, and
release shrinking preserves runtime-loaded providers and native entry points.

Desktop mpv exposes a JVM-only presentation capability. The primary macOS path
uses a generation-scoped app-owned `NSView`, CGL context, and three-buffer
IOSurface swapchain; it falls back to app-owned OpenGL, then two-buffer Skia
software rendering before item load. Core owns libmpv callbacks/context and one
render executor. Initialization and release serialize under `engineLock`, joins
stay outside it, and detach completes only after callbacks and context stop. No
path supplies `wid` or lets mpv create or focus a window.

Software fallback renders into two UI-owned BGRA buffers off Main, publishes a
complete front buffer on Main, and waits one frame before reusing the prior
buffer.

Compose owns controls and input. A clear cutout exposes the native view below
Compose; the peer view rejects hit testing. Desktop LibVLC remains an independent
controller/AppKit surface, never an mpv render target. Native handles, callbacks,
Skia types, and generations stay out of common contracts. Backend-specific
presentation limits and runtime checks are owned by
[desktop presentation](#desktop-player-presentation); package success
does not establish real playback behavior.

- **[desktop]** Subtitle vertical placement is backend-specific. Without bottom
  chrome, mpv uses its native `34` scaled-pixel `sub-margin-y`; controls or a
  bottom picker add `146` for a total of `180`, while PiP stays at `34`. This
  live setter does not reprepare or change selection/style. Desktop LibVLC keeps
  native placement because VLC 3.0.23 exposes only a construction-time integer
  margin before video dimensions exist; do not restore one fixed pixel override
  without per-media geometry. Android placement is owned by
  [UI](ui.md#tv-layout), and iOS ignores this surface input.

- **[desktop]** mpv uses the independent base and user size preference defined in
  the [subtitle presentation policy](ui.md#tv-layout). It resolves non-external `track-list`
  entries to actual selector ids, confirms embedded selection by `aid`/`sid`,
  and confirms external selection by request-specific track title.

- **[desktop]** `loadfile replace` acceptance is not subtitle readiness.
  External/local `sub-add` waits for the matching lifetime-unique playlist
  entry's start-file then file-loaded events. One background event consumer
  correlates playlist ID plus prepare generation; newer prepare, stop/release,
  matching end/load failure, or queue overflow invalidates pending work.

- **[desktop]** Every mpv `loadfile` explicitly sets its resume offset; a
  zero-start item first resets the persistent `start` option to `none`, and a
  failed reset aborts the load rather than inheriting stale state.

- **[desktop]** Content scale opens a Fit/Crop picker; Crop maps to mpv
  fill/crop.

## Why

- **Desktop mpv trust derives from the JVM and fails closed.** The native engine
  does not inherit the JVM trust store automatically, so an exported PEM and its
  required native option are construction prerequisites. The insecure exception
  remains account/server-scoped and cannot weaken another session.

- **Timing and subtitle placement remain backend-owned.** Desktop mpv maps the
  shared offset contract to native delay properties and applies its transient
  subtitle margin from chrome state. Desktop LibVLC keeps native placement and
  no timing support until equivalent evidence exists.

- **Completion is generation-qualified.** Desktop mpv's sticky EOF and Android
  LibVLC's trailing `Stopped` event can otherwise complete the wrong media or
  cancel valid completion. Entry/generation readiness and playback-derived
  progress distinguish completion from seek/resume state.

- **Desktop volume coalescing stays inside its store.** That boundary guarantees
  write ordering; quitting may still lose a change made within the documented
  500 ms debounce window.

- **Desktop presentation keeps platform-native ownership.** macOS IOSurface
  copy-back avoids the observed startup flash while retaining the embedded
  presenter; OpenGL and software paths remain explicit fallbacks. Now Playing
  uses exported MediaPlayer key objects because matching string values are not
  framework identity.

### Desktop playback embedding

- macOS mpv uses IOSurface, app-owned OpenGL, then software fallback; `wid` is
  rejected because it opens a separate player window. Compose remains the sole
  controls/input scene.
- Engine-lock and deferred-lifetime discipline keep teardown out of render
  callbacks. Runtime availability is a cheap file check; engine creation still
  validates versions. Pinned packaged inventories prevent machine libraries from
  changing the native graph.
