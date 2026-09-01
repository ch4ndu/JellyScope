# Changelog

Notable JellyScope changes, newest first.

## 0.1.0-alpha92 — enable resilient offline downloads · week 8 · 2026-08-31

- Movies and individual episodes can be downloaded at Original quality or a selected converted quality from their detail actions, then managed and played from the Downloads screen on Android, Android TV, iOS, and macOS. Transfers remain serial, interrupted work is resumable as a group, and storage allocation uses decimal MB and GB units.
- Fixed-quality downloads accept Jellyfin's direct media playlists and large HLS manifests, preserve authenticated media access through transfer and recovery, reject unsupported artifacts with actionable diagnostics, and recover finalizing packages without turning coroutine cancellation into a false failure.
- Offline playback resolves local artifacts without a server connection, retains local resume progress, forces VLCKit for iOS offline sessions, and reports an unavailable offline backend instead of falling through to AVPlayer. Original and converted artifacts now use bounded, platform-private storage and lifecycle-aware checkpoint recovery.
- Android 13 and newer request notification permission when a download starts without making permission a transfer requirement; allowed notifications expose progress and cancellation. Android TV adds its Downloads route, D-pad-safe actions and dialogs, bottom content clearance, stable focus, and guarded Home-row position updates.
- Automated lint, shared-core and shared-UI host tests, Android TV tests, both minified Android release assemblies, native-payload and Android license-readiness checks, and the iOS Simulator framework link passed. The maintainer reports that the remaining Android mobile and Android TV Downloads acceptance paths passed device validation.

## 0.1.0-alpha91 — harden sessions and Android TV lifecycle · week 7 · 2026-08-26

- Android TV Home now replaces its account-scoped state immediately after a server or account switch, so posters use the current session without requiring an app restart; Android TV and mobile also retire Media3 HTTPS connections off the UI thread, preventing the demo-server player-dismiss crash.
- Saved Jellyfin sessions on Apple platforms and macOS now use app-owned storage without Keychain or Security.framework prompts. The accepted plaintext-at-rest tradeoff is documented as a product constraint rather than a licensing or recurring audit blocker.
- Diagnostic collection is disabled by default when no preference has been saved, sign-in tolerates accidental trailing whitespace, and Android mpv subtitles return to their native bottom margin when player chrome is hidden.
- The README identifies JellyScope as an independent third-party Jellyfin client and records the Jellyfin trademark without implying affiliation or endorsement.
- Automated lint, shared-core and shared-UI host tests, Android TV tests, both minified Android release assemblies, native-payload and Android license-readiness checks, and the iOS Simulator framework link passed. Not device-validated — the demo-server dismissal, account-switch poster, Apple credential-storage, sign-in, diagnostics, and subtitle runtime paths remain unperformed.

## 0.1.0-alpha90 — switch players without leaving playback · week 7 · 2026-08-24

- Android mobile and TV, iPhone, iPad, and macOS can switch to another available playback engine from the video-camera control while watching a video streamed from Jellyfin; the choice applies only to the current session, and unavailable engines remain visible with an explanation.
- JellyScope checks the requested engine against the current media source, audio track, subtitle track, and playback position before replacing the player. If that plan cannot preserve the active choices, the picker closes and the video keeps playing with the current engine.
- Successful switches retain play or pause intent, position, quality, tracks, queue and reporting continuity; Android TV keeps D-pad focus inside the picker and restores it to the invoking control after dismissal or a preserved-playback failure.
- Automated lint, the focused causal test, shared host tests, Android TV tests, both minified Android release assemblies, native-payload and license checks, and iOS framework linkage passed. The maintainer reports that the complete backend-switching flow passed device validation on the targeted surfaces.

## 0.1.0-alpha89 — harden playback capability and startup ownership · week 6 · 2026-08-23

- Android LibVLC now retains every independently measured finite decoder limit instead of discarding useful evidence when another probe field is unavailable, while Android mpv keeps its stricter complete-probe policy.
- Shared Compose players construct only the selected native backend off Main after playback preferences are known, serialize overlapping launch ownership, and release stale or failed candidates exactly once; Apple TV now follows the same delayed-construction and explicit-readiness boundary.
- Desktop mpv retains software HDR tone mapping while desktop LibVLC asks Jellyfin for SDR conversion, Android warms its cached codec probe before playback, PiP keeps the strongest deferred recovery trigger, and Android TV Home no longer observes route-wide focus movement on every D-pad press.
- Automated lint, focused host tests, Android mobile/TV minified release builds, R8/lint, native-payload and license checks, Apple framework linkage, and compile-only Apple TV app assembly passed. Not device-validated — the representative-hardware playback, HDR, PiP, remote, focus, and startup checklist remains open.

## 0.1.0-alpha88 — simplify pinned dependency and native packaging maintenance · week 6 · 2026-08-17

- AndroidX Core, JNA, AtomicFU, Kotlin, Ktor, SQLite, and KSP are updated to their reviewed stable versions while the existing SDK, Media3, native-player, and platform compatibility holdbacks remain unchanged.
- Gradle, Android, iOS, and macOS packaging now trust pinned versions and source revisions from their declared repositories instead of maintaining a second project-owned checksum or file-size allowlist.
- Native and release packaging still enforce required files, ABIs, framework slices, architecture, macOS 13 compatibility, dependency closure, source and license disclosures, clean Git binding, signing, notarization, stapling, and Gatekeeper validation.
- Cross-platform compilation, Apple framework links, the unsigned iOS Simulator app build, checksum-free VLCKit retrieval, and macOS mpv/VLC preparation passed. Not device-validated; clean release artifacts and native package contents are verified by the release gate before tagging.

## 0.1.0-alpha87 — retain macOS release sources and runtime disclosures · week 5 · 2026-08-16

- Apple Silicon macOS release packaging now verifies every resolved desktop JVM group, module, and version against a reviewed license/source inventory, packages that inventory with its notices, and keeps unresolved non-macOS targets explicitly blocked rather than inferring their readiness.
- Each macOS Release DMG is bound to a clean Git revision and retains the exact JellyScope source archive plus the checksum-pinned VLC 3.0.23 source archive beside the binary; the existing pinned IINA/mpv source routes remain unchanged.
- iOS signing can now read a developer's Team ID from an ignored local Xcode configuration, keeping personal team information out of the shared project while preserving automatic device and archive signing.
- Focused metadata, source-cache, dependency-inventory, package-closure, signing, and release-build checks passed. The macOS DMG is Developer ID signed but not notarized or runtime-validated on the macOS 13 floor.

## 0.1.0-alpha86 — bundle a pinned macOS mpv runtime · week 5 · 2026-08-16

- Apple Silicon macOS builds now bundle IINA's checksum-pinned 1.4.0 arm64 mpv runtime and its complete 69-library closure, using one verified cache for development and packaging instead of reading Homebrew, MacPorts, `/usr/local`, or another machine-installed mpv.
- macOS loading now fails closed when the app-bundled `libmpv.2.dylib` is absent; package verification checks the exact runtime within each Compose output variant, and the supported desktop package requires macOS 13 or newer on Apple Silicon.
- GPL-compatible source, attribution, license, and release-metadata routes now cover the pinned IINA/mpv artifact without retaining a separate legal-review blocker for that exact runtime.
- Automated lint, shared host tests, Android TV tests, release builds, license checks, desktop compilation, native-runtime relocation, exact dynamic loading, and packaged mpv/VLC closure checks passed. Not device-validated — interactive macOS 13 playback with machine-installed mpv absent, credentialed signing, and notarization remain open.

## 0.1.0-alpha85 — keep Library chrome with the first TV content row · week 5 · 2026-08-14

- The top-level Android TV Library hub now draws its Recommended/Library tabs and Library count, Shuffle, Sort, and Filter controls only while focus is in that chrome or the first content row; lower rows and the hosted rail hide them without changing the reserved layout or hero geometry.
- Recommended cards, Retry controls, status focus, live ribbon reordering, and the adaptive Library grid now report their current row through stable identities while retaining existing rail entry, D-pad traversal, picker return, reload, and route-restoration behavior.
- Android TV lint, the unchanged unit suite, debug Kotlin compilation, and diff hygiene passed. Not device-validated — rapid row changes, adaptive-column transitions, hosted-rail re-entry, and picker restoration remain source-verified rather than runtime-proven.

## 0.1.0-alpha84 — restore native mpv subtitle placement when controls hide · week 5 · 2026-08-14

- macOS mpv subtitles now use mpv's native 34-scaled-pixel bottom margin when player controls are hidden or playback is in Picture-in-Picture, instead of remaining permanently raised.
- Visible playback controls and bottom pickers temporarily add 146 scaled pixels, retaining the existing 180-pixel total clearance without restarting or replanning playback; desktop LibVLC and other platform players remain unchanged.
- Automated focused subtitle-policy and mpv lifecycle tests, shared-core/shared-UI lint, Android compile, desktop compile, and iOS framework-link checks passed. The macOS SUBRIP auto-hide, picker transition, and LibVLC comparison were not device-validated for this release.

## 0.1.0-alpha83 — temporarily hide and block Downloads · week 5 · 2026-08-14

- Downloads is temporarily unavailable on clean installations: shared mobile, iOS, and desktop navigation, Detail download and offline actions, Settings management, and Android TV drawer, rail, selection, and rendering surfaces are hidden.
- Password and Quick Connect sessions fail closed by projecting Jellyfin's existing content-download permission to false without changing the raw server policy or unrelated permissions such as subtitle management.
- Original and fixed-quality download admission reject the disabled effective permission before API preflight, lease acquisition, enqueue, or platform wake, while the dormant Downloads routes and implementation remain available for a separately validated re-enable.
- Automated shared-core, shared-UI, Android TV, Android mobile/TV compile, desktop compile, and iOS framework-link checks passed. Not device-validated — existing-install compatibility, retained download work, and future re-enable behavior are outside this temporary clean-install release.

## 0.1.0-alpha82 — unify playback overlays and causal diagnostics · week 5 · 2026-08-14

- Android mobile, Compose iOS, desktop, and Android TV now render the same shared playback-information rows; LibVLC cache, effective quality, wire bitrate, and Android-TV-only display rows are hidden without removing their underlying runtime evidence.
- Playback diagnostics now correlate planning, prepare, backend, reporting, track and subtitle-render state, bounded buffer/cache/output health, recovery, and terminal outcomes across shared players and tvOS using closed identity-free values.
- Android TV display-mode decisions are retained as structured diagnostics without mode identifiers, and tvOS records the prepare-scoped controller-failure and recovery chain needed for first-line playback triage.
- Client-log uploads contain only the structured report and scrubber-admitted bounded history; raw Android mpv files and free-form native excerpts remain local and are cleanup-only.
- Automated shared, Android host, Android TV, desktop, iOS, tvOS, release-APK, packaged-resource, native-payload, and license checks passed. Sanitized upload and cross-platform causal-chain smoke checks were not device-validated for this release.

## 0.1.0-alpha81 — harden diagnostics, async state, and UI boundaries · week 5 · 2026-08-13

- Sanitized diagnostics now use closed tags and operation identifiers, persist a bounded identity-free history across restarts in a separate disposable store, retain a minimal previous-run failure marker, and expose matching collect/send controls on shared, Android TV, and tvOS Settings.
- Opting out durably disables collection and deletes retained diagnostic records, while upload and clear operations acknowledge only the exact snapshot they handled so concurrent or recovered records remain intact.
- Settings capability probes, Series episode projection, and tvOS settings/search persistence now keep expensive work off Main and reject stale completions without discarding newer user selections or fresh server metadata.
- Chapter selection updates only at chapter boundaries, Android TV paged grids use one load-more observer, account-scoped success caches reject stale publication, and shared detail feedback removes duplicated layout without changing screen behavior.
- Release-license metadata cleanup now rejects canonical aliases, symlinks, unsafe roots, and unmarked non-empty destinations before deletion, with focused regression coverage included in the broad verification gate.
- Automated shared, Android host, Android TV, JVM, Kotlin/Native link, desktop packaging, release APK, resource, native-payload, and license checks passed. Diagnostic upload, previous-exit capture, playback, focus, and cross-platform runtime checks were not device-validated for this release.

## 0.1.0-alpha80 — add cross-platform offline downloads · week 5 · 2026-08-12

- Android mobile, Android TV, iOS, and macOS can now download individual movies and episodes from Detail, manage them from a dedicated Downloads destination, and play completed items without contacting the server; tvOS remains outside the feature scope.
- Original downloads use exact-source resumable byte ranges, while fixed-quality downloads use a validated finite H.264/AAC HLS package with explicit audio and subtitle selection.
- One device-wide FIFO permits a single active transfer, with pause, resume, retry, cancel, delete, restart recovery, and user-configurable whole-GiB storage allocation that includes partial and reserved work without automatic deletion.
- Offline playback uses generation-bound local artifact leases, preserves local resume and watched state, protects active media from deletion, and requires session-only VLCKit for localized HLS on iOS without changing the saved player preference.
- Download usage remains account-private while showing device total, current-account, and opaque other-account bytes; confirmed account removal durably deletes retained downloads before credentials disappear, while ordinary account switching keeps them.
- Automated host tests, Android TV tests, iOS linking, desktop compilation, release APK assembly, resource packaging, and native-license checks passed. Not device-validated — the Android, Android TV, iOS, macOS, and mobile backup/restore runtime checklist remains open.

## 0.1.0-alpha79 — harden playback compatibility and add media-version selection · week 5 · 2026-08-12

- macOS LibVLC Standard mode now asks Jellyfin to transcode sources beyond a 4K/60-equivalent input envelope, while Unrestricted remains an explicit bypass and Original fails closed when a safe stream cannot be produced.
- Four exact Fire TV models now exclude two demonstrated HEVC Dolby Vision plus HDR10+ combinations from both server negotiation and local video copy, using deterministic host coverage without claiming hardware validation.
- Movie, episode, series, and season details now expose session-only media Version selection, preserving exact source tracks, badges, media info, playback IDs, refresh behavior, focus, and source-scoped local subtitles.
- Shared and Android TV Settings now use one lazy code-native set of 30 existing glyphs instead of runtime XML parsing.
- Apple TV is documented as an unsupported alpha preview, and Windows and Linux as unsupported development targets. Fire TV playback and the new detail interactions were not device-validated; no simulator, emulator, or physical device was run for this release.

## 0.1.0-alpha78 — modernize native auth and compact playback diagnostics · week 5 · 2026-08-11

- Desktop and Android mpv now share the modern comma-free Jellyfin Authorization form, while Android, desktop, and iOS VLC-family backends use guarded `ApiKey` URLs so native media and subtitle requests remain authorized when Jellyfin 12 disables legacy authorization.
- Existing same-origin, redirect, stale-credential clearing, and token-free cross-origin protections remain intact, and the modern forms stay version-agnostic for current Jellyfin servers.
- Every in-player debug overlay now uses the compact playback/runtime/policy field set; unavailable playback information collapses to two useful rows, while Android TV keeps only its five display-mode additions.
- This release does not change DirectPlay, DirectStream, or transcode capability planning. Live Jellyfin 10.11.11/12 native-backend playback and TV visual-fit checks were not device-validated for this release.

## 0.1.0-alpha77 — harden iOS playback and disclose VLCKit beta limits · week 5 · 2026-08-11

- AVPlayer remains the recommended fresh-install iOS default; Standard playback compatibility asks the server for a compatible stream above JellyScope's conservative 4K30 input envelope, while an explicit Unrestricted setting lets future hardware try original sources.
- VLCKit serializes native callbacks before reading player state, preserves known VOD duration across recovery, and uses bounded prepare/resume/seek transitions so unavailable picture counters cannot permanently pin Buffering, the timeline, or natural completion.
- VLCKit Picture in Picture now uses its public drawable and controller protocols with generation-safe lifecycle and transport handling. Settings labels the backend beta and discloses that PiP seeking remains experimental and may close or interrupt playback.
- Bounded, identity-free VLCKit transition and terminal evidence now follows the existing diagnostic collection, scrubber, verbose-system-log, and retention controls so user reports can reconstruct playback state without media or account identity.
- The exact signed iPhone Release candidate DEVICE-VALIDATED resumed transcoded 8K playback through spinner clearance, an advancing timeline, and natural completion back to Details. The broader PiP-seek matrix remains non-blocking beta follow-up.

## 0.1.0-alpha76 — make playback capability evidence explicit · week 5 · 2026-08-11

- Playback diagnostics now identify the closed evidence source behind each advertised codec and finite decode limit without exposing raw decoder names or media identity.
- Android capability evidence distinguishes platform hardware, platform software, legacy codec classification, bundled runtime support, pinned player declarations, documented limits, and unknown facts; substituted Media3 channel ceilings are recorded as documented limits rather than platform probe results.
- Desktop mpv and LibVLC now own independent immutable capability snapshots while preserving every effective codec and limit advertised by alpha75; copied LibVLC claims remain truthfully static or unknown until native validation exists.
- Apple AVPlayer and VLCKit profiles now expose the evidence already supporting their existing claims without changing either profile or its Jellyfin wire representation.
- Automated provider, diagnostics, Android/JVM compile, iOS framework/app build, lint, release-APK, packaging, and native-dependency gates passed. Runtime-only provenance and audible-output checks remain in the local validation backlog.

## 0.1.0-alpha75 — align Android playback capability with the selected player · week 5 · 2026-08-11

- Android Media3 now advertises bundled FFmpeg E-AC-3 decode only when that shipped runtime confirms support, while per-codec decode-input ceilings remain separate from PCM output and encoded passthrough limits.
- Android video capability projection selects one coherent ordinary decoder per codec, preferring hardware without mixing another decoder's resolution, throughput, profile, level, or bit-depth facts.
- Android mpv passes external subtitle titles in the native command's positional title slot and retains exact-title readback before confirming activation.
- Media3 emits a generation-correlated, allowlisted decoder classification so diagnostics can distinguish bundled FFmpeg from the platform path without logging raw decoder names.
- The shared player overlay export no longer collides with Xcode's `DEBUG` macro, restoring Debug iOS framework imports and simulator app builds.
- Shield mpv and Chromecast Media3 DEVICE-VALIDATED the exact 4K HEVC/E-AC-3 asset with embedded SUBRIP selected: both DirectPlayed without subtitle activation errors; audible output remains unverified because both televisions were muted.

## 0.1.0-alpha74 — keep Android TV ambient color with its asset · week 5 · 2026-08-10

- Android TV Home, Recommended, hero-enabled Library, Collection, and the navigation-drawer
  underlay now share one settled-asset ambient owner, rejecting late colors from prior assets.
- Ambient tint retains its current rendered value while the next poster color resolves, then uses
  the established smooth direct crossfade instead of briefly resetting to the default background.
- Still-composed TV cards retain and republish their decoded poster after bounded cache eviction,
  avoiding missing ambient updates without adding persistent storage or another image fetch.
- Identity-free `AmbientColor` diagnostics correlate selection, cache, poster, extraction, and
  stale-result outcomes without admitting those records to uploaded client logs.
- Chromecast Home DEVICE-VALIDATED: 54 settled transitions covered uncached extraction and cached
  revisits with successful color application, and the corrected crossfade was visually approved.
- Contributor guidance now requires the smallest stable regression set and explicit approval before
  introducing permanent UI, integration, server-orchestration, or exhaustive test harnesses.

## 0.1.0-alpha73 — harden shared player architecture · week 5 · 2026-08-10

- Shared Compose and tvOS playback now use the same bounded recovery and reporting coordinators,
  including one-shot same-plan network retry and generation-safe reporting settlement.
- All seven native player controllers now share retained audio/subtitle retry decisions while
  preserving each backend's native lifecycle, track activation, and position behavior.
- Detail, Series, Player, and tvOS resolve one cancellation-safe playback launch context, and
  Settings consumes one domain-owned backend order/default policy instead of platform copies.
- Android mobile and TV share one native surface host; Android TV also keeps the activity window
  awake for the full player route while retaining surface-level screen-on protection.
- Overlay order, bounded debug projection, and resume refresh observation are shared across player
  shells. Automated host tests, release APKs, Apple links, and desktop packaging passed; physical
  runtime validation was not performed for this release.

## 0.1.0-alpha72 — refine player controls and diagnostics · week 4 · 2026-08-09

- Player layers now follow video, debug, controls, notices, then dialogs across
  shared mobile and TV surfaces; the desktop debug panel is 60% of the player,
  and mobile seek thumbs are easier to distinguish from chapter ticks.
- Android playback surfaces keep the display awake for every backend, while TV
  Up Next dismissal keeps the player open and hands focus back to its controls.
- macOS publishes framework-keyed Now Playing metadata, and desktop users can
  enable bounded playback probes from Settings without command-line flags.
- Settings keeps long quality explanations and Cancel reachable, Coming Later
  lists only Downloads & offline, SyncPlay, and Chromecast, and desktop queues
  include season and episode identity.
- Host tests, the iOS simulator framework link, Android mobile/TV release APKs,
  and the macOS app/DMG with packaged mpv and VLC passed. The runtime acceptance
  paths were not device-validated for this release.

## 0.1.0-alpha71 — harden playback boundaries and subtitle ranking · week 4 · 2026-08-09

- Playback credentials now follow a shared origin-and-path policy: Media3
  re-evaluates every redirect hop, AVPlayer applies the policy to initial media
  and subtitle resources, and iOS Release builds have a deterministic gate that
  rejects developer credentials.
- AVPlayer advertises stricter HEVC, frame-rate, and progressive-scan limits;
  desktop mpv now rejects stale completion, poll, prepare, and initialization-
  replay state when a newer item, stop, or release wins.
- Apple Now Playing republishes speed changes, while OpenSubtitles can rank by
  accessibility preference and the selected source's directory-free release
  basename without retaining or sending the full media path.
- Host tests, iOS links, Android mobile/TV release verification, and the macOS
  desktop app/DMG with packaged mpv and VLC passed. No new device runtime check
  was performed; the existing AVFoundation per-redirect limitation remains open.

## 0.1.0-alpha70 — make Android native verification reproducible · week 4 · 2026-08-09

- Android mobile and TV release builds no longer fail exact native-bundle
  verification merely because the project-owned mpv bridge was compiled from
  a different checkout or supported macOS/Linux build host.
- Exact per-ABI bridge verification remains fail-closed, while generated
  binaries no longer expose local repository, Android SDK, or host-toolchain
  paths. This is a build-only change; player runtime behavior is unchanged.

## 0.1.0-alpha69 — consolidate macOS native dispatch and optimize library scrollbars · week 4 · 2026-08-09

- Library scrollbars now keep continuously changing position out of Compose's
  composition phase, reducing scroll-time work while preserving track clicks,
  thumb dragging, accessibility progress, and list/grid behavior.
- macOS AppKit, mpv, LibVLC, and Now Playing bridges now share one lazy,
  signature-safe Objective-C runtime binding while retaining their existing
  framework, selector, callback, and lifecycle ownership.
- macOS and Android mobile scrollbar checks passed, along with the default mpv
  IOSurface, forced OpenGL, and LibVLC surface matrices. The existing macOS Now
  Playing defect remains open; an 8K/60 AV1 LibVLC cold-start and post-seek
  still-frame issue was reproduced in the pre-refactor build and remains open.

## 0.1.0-alpha68 — add iOS VLCKit Picture-in-Picture · week 4 · 2026-08-09

- The selectable iOS VLCKit backend now supports automatic system
  Picture-in-Picture with play, pause, relative skip, restore, and close
  controls while preserving the existing background-close behavior when PiP
  is disabled.
- iOS now uses checksum-pinned unified VLCKit 4 with typed track and buffering
  APIs, safer terminal-event classification, and generation-safe PiP source
  replacement and teardown.
- Not device-validated — the runtime playback and PiP checklist remains open.
  Direct-play VLC subtitle overlays are not included in the PiP video surface;
  burned-in transcode subtitles remain visible.

## 0.1.0-alpha67 — stop macOS mpv startup flashing · week 4 · 2026-08-09

- macOS mpv now uses VideoToolbox copy-back with its IOSurface presenter,
  preventing the repeated black flashes isolated on cold asset starts while
  preserving the existing OpenGL and software fallbacks.
- Playback-probe output distinguishes the requested and resolved hardware-
  decode routes without exposing media or account identity.
- macOS device validation passed the startup-flash cohort and sustained 4K60
  interaction. Same-player queue replacement was unreachable in the tested
  library path, and software-decoded 8K60 AV1 performance is not claimed.

## 1.0.0 (unreleased)

The pending first production release; it is not tagged yet, and the release flow
owns the version bump. JellyScope is a fresh Kotlin Multiplatform and
Compose Multiplatform Jellyfin client; Android TV and Android mobile are the
primary product surfaces, with macOS/desktop and iOS reusing the shared browse
UI over native playback bridges, and an alpha-quality tvOS SwiftUI shell over
the same shared core.

### Platforms

- **Android TV** — TV-native navigation with D-pad focus throughout,
  browse/detail/discovery/settings, TV transport-key routing, and Fire TV
  Watch Next.
- **Android mobile and tablet** — the shared touch app with gestures,
  Picture-in-Picture, MediaSession controls, and adaptive layouts. Requires
  API 25+ (Fire OS 6).
- **Desktop (macOS)** — the shared Compose app with bundled release packaging
  and in-scene player controls over native video surfaces.
- **iOS** — the shared Compose app with persistent session restore, Now
  Playing/lock-screen transport, and PiP.
- **tvOS (alpha)** — a native SwiftUI shell over the shared core: login, home,
  browse, detail, search, settings, and system-player playback. Simulator-
  verified only.

### Playback backends

Every platform maps one immutable, server-authoritative playback plan through
a platform backend — user-selectable where more than one exists:

- **Android**: ExoPlayer/Media3 (default) with the Jellyfin FFmpeg decoder
  extension and decoder fallback; an mpv backend built from a project-owned
  wrapper (API 26+); and LibVLC (beta). Failed mpv starts fall back to a fresh
  ExoPlayer plan resuming from the last confirmed position.
- **Desktop**: a JVM libmpv bridge presenting through an app-owned IOSurface
  swapchain on macOS (with automatic OpenGL and software fallbacks), and an
  optional LibVLC (beta) backend with an audited bundled VLC runtime in macOS
  packages.
- **iOS**: AVPlayer (default) and a unified VLCKit 4 backend, both selectable in
  Settings.
- **tvOS**: the system player (AVPlayerViewController).

### Capability highlights

- **Playback planning and quality policy** — server-authoritative PlaybackInfo
  with backend-qualified device profiles; DirectPlay/DirectStream/Transcode
  selection with subtitle-honest URLs and remux when only audio is
  incompatible; typed Auto/Original/Fixed quality on a shared eight-rung
  ladder where only explicit in-player Auto may lower quality automatically;
  canonical track-identity matching across codec aliases, languages, and
  titles; and streams the device provably cannot decode are never handed to
  it.
- **TV focus architecture** — an app-owned drawer, payload-bearing route
  history, a stable-key route focus coordinator and nested focus kernel,
  hold-to-seek acceleration with pending-target scrubbing, and focus-safe
  dialogs, warnings, and dismissal everywhere.
- **Subtitles** — planned and external subtitle selection with durable intent,
  a distinct durable "Off", OpenSubtitles search/install/cleanup from movie
  and episode detail, sidecar normalization for VLC-family backends, and
  client-side text rendering that never disturbs a transcode.
- **Diagnostics** — structured, allowlisted, sanitized playback diagnostics;
  an in-player playback-info overlay; opt-in bounded log collection with
  user-initiated structured server upload; raw mpv logs stay on-device; and
  passive, dismissible playback-health guidance that never changes playback by
  itself.
- **Multi-account** — canonical server/user identity scoping caches, stores,
  images, playback memory, and Watch Next; serialized account-boundary epochs
  rejecting stale work; all-or-nothing account removal; and deterministic
  logout cleanup.
- **Shared UX** — password and Quick Connect login, network discovery on
  Android and desktop, direct server entry on iOS, adaptive navigation from
  compact phones to full-width TV-style layouts,
  three dark themes, window-tier-aware tile sizing, per-type media-segment
  skip policies, Up Next auto-advance with an optional Still watching gate,
  and a full-pane adaptive Settings surface shared by mobile, desktop, and
  iOS.

## Pre-1.0 alpha development

Sixty-eight alpha version numbers (alpha1–alpha68, July–August 2026) built the
1.0 surface; the closing note records which of them never shipped as their own
release. Grouped by theme rather than by individual version:

- **Playback planning and quality policy** (alpha4–alpha5, alpha9,
  alpha16–alpha19, alpha45–alpha53): playback telemetry; best-first
  codec-profile ordering; an explicit Auto bitrate ceiling (omitting it meant
  a server-side 8 Mbps cap); canonical track identity across
  codec/language/title aliases; truthful audio/HDR advertising with
  fail-closed video-range allowlists; remux preservation; and the final
  quality architecture — per-model decode ceilings, resolution-honest caps,
  the shared eight-rung ladder, DirectPlay-first policy, and session-only
  typed Auto/Original/Fixed semantics with bounded Auto recovery.
- **Android playback backends** (alpha26, alpha31–alpha33, alpha52): selectable
  ExoPlayer/LibVLC with durable source-keyed intent, callback-owned
  PiP/MediaSession, and the FFmpeg decoder extension; Media3 time-floor load
  control; honest dropped-frame warnings; and LibVLC decoder-safety projection
  closing a reproduced hardware-decoder kernel panic.
- **mpv integration** (alpha62–alpha65): Android mpv as a selectable backend
  with a pinned, checksum-verified native bundle; the JNI wrapper rebuilt from
  source in-repository with audited log-hygiene patches; one-shot ExoPlayer
  fallback; Tegra-specific renderer and close-deadlock fixes; and the redacted
  mpv diagnostic-log upload pipeline.
- **Desktop presentation** (alpha25, alpha38–alpha41, alpha47, alpha54,
  alpha59–alpha61, alpha67): mpv recovery/stall hardening, HDR tone-mapping, media
  keys; in-window rendering (the separate-window route was rejected);
  in-scene controls as the sole presentation; the IOSurface swapchain making
  exact-8K playback drop-free at rest; bundled audited VLC packaging; decode
  guardrails with a maximum-resolution setting; volume-persistence and
  player-lifecycle fixes; and IOSurface copy-back for cold-start flashing.
- **Apple platforms** (alpha7–alpha8, alpha23–alpha24, alpha43, alpha45): the
  tvOS SwiftUI shell and its system-player feature pack; the iOS dual player
  (AVPlayer + VLCKit) with the iOS 18 empty-audible-group direct-play fix;
  audio-session, keep-awake, stall-policy, and Now Playing hardening;
  first-device-pass fixes; and iOS decode ceilings.
- **TV focus architecture** (alpha6, alpha10–alpha11, alpha20, alpha30,
  alpha41, alpha53): hold-to-seek acceleration and segment-skip policies;
  click-to-edit TV text fields; focus-scroll composition; chapter-menu and
  dialog focus reveal; and warning dismissal that restores safe player focus.
- **Shared UI and UX** (alpha3, alpha12, alpha27, alpha29–alpha31, alpha34,
  alpha44, alpha58): tile-size scaling; the Ambient launch flow; the touch
  Settings redesign; the per-account library dropdown; measured Compose
  performance work; app icons; and resume-always-resumes semantics.
- **Diagnostics pipeline** (alpha4, alpha17, alpha28, alpha33, alpha53,
  alpha57): debug overlays, allowlist-first sanitized log capture,
  user-submittable server uploads with capability snapshots, and
  credential-free playback-info panels.
- **Hardening** (alpha21, alpha42, alpha49–alpha51, alpha55–alpha58, alpha63,
  alpha66): multi-server account-boundary epochs and logout cleanup; the
  cross-platform audit batch (threading, secret-bearing logs, retry and
  end-of-stream correctness); playback-health evaluation; duplicated player
  logic unified into shared tested kernels; all-or-nothing account removal; and
  credential-store recovery from a permanently failed Android Keystore key,
  which previously left the app silently and permanently signed out with no
  way back in.

alpha13–alpha15 shipped inside alpha16; alpha22 was never released.
