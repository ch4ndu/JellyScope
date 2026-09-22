# Playback Architecture

This guide owns JellyScope's playback decision pipeline and the platform/backend
variation around it. The wire contract, persistence, tracks, diagnostics, and
runtime detail live in [data-playback.md](data-playback.md).

## 1. Product policy and invariants

JellyScope is DirectPlay-first. A request is governed by two independent
inputs, which must never be relabelled as one another:

1. **Concrete-backend compatibility facts** describe what the selected decoder
   can truthfully accept: codec, container, profile, bit depth, range,
   dimensions, throughput, subtitle delivery, and audio/output facts.
2. **The user's resolved quality policy** is `Auto`, `Original`, or
   `Fixed(exact bitrate)`. Resolution order is an explicit choice in the current
   playback, then the per-server VLC-family default when a VLC backend is active,
   then the general per-server playback default.

The following rules are non-negotiable:

- Decoder capability comes from an active decoder/platform probe, a pinned
  engine declaration, or platform-vendor documentation. A deliberately
  conservative product input envelope may intersect more than one backend when
  it is owned and labelled as app policy rather than decoder evidence. A screen,
  window, monitor, and JellyScope's limited device/server/content measurements
  never create a platform-wide decoder claim.
- Every advertised codec carries closed decode and finite-limit provenance.
  Composed claims may retain multiple sources; absent or weaker knowledge stays
  explicit as `Unknown`, and static fallbacks are never relabelled as probe
  results. Provenance explains an existing capability but does not alter it or
  become a Jellyfin wire field.
- A shared capability object is permitted for more than one backend only when
  every claim is accurate for each of those backends. Backend-specific objects
  are an accuracy choice, not an architectural goal.
- Unknown resolution capability means unknown, not unsupported. A finite low
  bound remains a usable bound; only absence of a decoder makes a codec
  unusable. Source-relative preflight decides whether the particular source can
  be copied.
- DirectPlay and DirectStream both undergo source-copy preflight. DirectStream
  remains preferred to full Transcode when a server-side remux is sufficient.
- Display geometry is presentation state only. Fullscreen, resize, external
  display, and monitor changes never initiate a quality replan.
- The player controller receives an immutable `PlaybackPlan`; it maps that plan
  to native APIs and reports native facts. It never fetches Jellyfin metadata or
  selects a stream strategy itself.
- Backend readiness is a controller-owned execution concern applied only after
  the immutable plan is installed. Native resource configuration may make an
  already-selected backend safer to execute, but it cannot change DirectPlay
  eligibility, the user quality policy, a device profile, or the wire request.
- A remote current playback may take one explicit, session-local concrete-backend
  switch. The ViewModel projects `PlayerBackendPolicy` in policy order, excludes
  `Auto`, leaves known unavailable choices visible but disabled, and never writes
  the persisted backend preference. It first builds and validates a clean
  target-qualified plan while the healthy controller remains installed. The
  switch captures explicit source/audio/subtitle/quality intent, play/pause,
  speed, subtitle style, resize mode, queue identity, and installed-plan/
  reporting authority; it never carries outgoing decoder, health, dropped-frame,
  recovery, or runtime bitrate facts into the target request. A preflight
  planning or explicit-intent failure closes the picker and presents a persistent,
  dismissible notice with the rejection reason and confirmation that current
  playback was kept, without a Stop or teardown. For the final
  commit, the serialized installer revalidates authority, pauses an originally
  playing outgoing controller, refreshes confirmed position, and builds a final
  target-qualified plan while the controller and reporting session remain
  installed; a final planning or explicit-intent failure restores its prior
  play/pause intent and keeps that playback. After the final plan succeeds, the
  release-first installation revalidates authority after its committed
  suspensions and before reporting, prepare, and play/pause. Construction may fall back once to the platform
  concrete default only for that explicit switch; the factory-reported backend
  is authoritative and receives a clean matching plan before prepare. Offline
  sessions, queue changes, stop, disposal, unavailable/current choices, later
  plan/reporting installation, and stale switch generations cannot commit.

### Single-asset Kids playback

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
[Kids account playback](data-playback.md#kids-account-playback), and layout/input
to [Kids watch page](ui.md#kids-watch-page).

### Quality semantics

| Policy | Initial wire request | Automatic stream-changing recovery | Persistence |
| --- | --- | --- | --- |
| `Original` | No client quality/performance limit; DirectPlay/DirectStream enabled | Never. Source-copy incompatibility fails closed; a runtime decoder failure or reliable missing-video-output fact shows a persistent action notice. | Server default, or current playback only when selected in the player. |
| `Auto` | The same no-client-limit, DirectPlay-first request as Original | Auto may take one compatibility replan. Only an explicit in-player Auto choice may take one strictly lower canonical quality recovery; an inherited Playback-default Auto policy instead presents manual in-player choices. | Server default; a runtime recovery cap and player choice never persist. |
| `Fixed` | The exact selected bitrate, plus the canonical rung resolution when applicable | Compatibility recovery may stay inside the selected cap; it never lowers the user's fixed choice. | Server default, VLC-family default, or current playback only. |

`Original` does not hide genuine decoder incompatibility. The device profile may
still cause Jellyfin to choose a compatible stream when authoritative facts
require that. It means that JellyScope adds no finite quality/performance
limiter and does not silently choose a different stream after runtime failure.

In shared Compose playback, a user quality choice is a proposal until a current
replacement plan succeeds. A rejected proposal preserves the installed stream,
last applied quality and its explicit/inherited origin, current pause/play
intent, reporting, tracks, and health/recovery state. It shows the same typed,
dismissible playback-change notice used for rejected backend switches. A later
backend switch inherits the last applied quality, never a rejected proposal.
Planning remains cancellable; the existing installation guard owns outgoing
reporting Stop through replacement installation.
This preservation applies only while the same installed session remains active;
startup, terminal recovery, and native prepare failures retain their normal
failure semantics. A new relevant action or explicit dismissal clears the notice.

`Original` recovery is a single prompt contract. Every trigger below returns
`OriginalPlaybackFailed` with exactly `AcceptAuto`, `OpenPlaybackSettings`,
`Dismiss`, and `Close`; it creates no replan, recovery budget use, or runtime
quality cap.

| Trigger | Evidence class |
| --- | --- |
| `DecoderFailure` | Typed decoder failure |
| `UnsupportedMedia` | Typed unsupported-media failure |
| `NoVideoOutput` | Reliable missing-video-output observation |
| `CumulativeBuffering` | Cumulative buffering evidence |
| `RepeatedStalls` | Repeated-stall evidence |
| `DroppedFrames` | Sustained dropped-frame evidence |

`Auto` starts identically to Original at the quality layer. Its additional
quality-recovery permission is granted only by an explicit in-player Auto
selection for this playback; an inherited Playback-default Auto policy has the
same initial request and collects the same health evidence, but it does not
automatically change streams. Auto may take one compatibility recovery for a
decoder/unsupported failure or trustworthy no-video-output observation. An
explicit in-player Auto may also take one quality recovery for
repeated/cumulative buffering or sustained dropped-frame evidence; inherited
Auto instead presents manual in-player choices. Slow start is guidance only.
Every automatic quality step is strictly downward on the shared canonical
ladder. There is no automatic upshift: stable playback after a lower choice is
not evidence that a higher choice is safe. **Try higher quality** clears the
session runtime cap, records explicit session Auto authority, and replans
uncapped; it preserves the spent automatic budgets so an immediate loop cannot
occur. **Keep current quality** converts
the recovered value to Fixed only for the current playback. Stopping and
reopening the item resolves the defaults again.

A Fixed-policy failure never lowers quality automatically. Its persistent
notice offers the in-player lower-quality picker, an uncapped higher/Original
retry, and dismissal; it does not route to Settings.

Network retry which repeats the same plan, and an already-qualified backend
replacement which preserves the selected policy, remain separate from a
stream-changing quality recovery. A replacement must re-plan from the new
backend's own facts and discard observations belonging to the old controller.

## 2. End-to-end decision pipeline

```text
general settings default + active VLC default + current-playback choice
                 │
                 ▼
        normalized Auto / Original / Fixed
                 │                 concrete selected backend
                 ▼                         │
       explicit bitrate constraint ◄──── capabilities
                 │                         │
                 ├──── build DeviceProfile + PlaybackInfo request
                 │       (no-client-limit sentinel or exact value)
                 ▼
   server response + source-copy preflight: DirectPlay → DirectStream → Transcode
                 │
                 ▼
           immutable PlaybackPlan → native PlayerController
                 │                         │
                 ▼                         ▼
          reporting/tracks          native state and output evidence
                                           │
                                           ▼
                           shared health evaluation + Auto recovery kernel
                                           │
                                           ▼
                         notice / bounded replan / explicit user action
```

1. The ViewModel or tvOS presenter pins the concrete backend, then resolves the
   current-playback choice over the matching backend default. Compose production
   starts with an inert pending controller and, after detail, preferences, and
   any item override are known, constructs only the resolved backend on work
   before Main installation; direct/test injection is concrete unless explicitly
   marked pending. Compose retains release-first replacement and its existing
   ExoPlayer fallback; tvOS performs one delayed AVPlayer
   construction/installation after `start()` on work and before reporting,
   observation, planning, or readiness publication. An arriving current Compose
   launch waits for an earlier installer, then rechecks generation and item
   authority before it can release or construct. A VLC-family
   default is a normal Fixed policy and therefore applies to the first request.
   It also retains whether Auto came from an explicit in-player choice, because
   effective Auto policy alone does not authorize an automatic quality downgrade.
2. Common code normalizes `PlaybackQualityPolicy` and derives a separate
   `PlaybackBitrateConstraint`. Policy identity is never encoded as nullable
   bitrate.
3. The concrete backend's `DeviceProfileProvider` supplies capability facts.
   User quality and the independent user resolution choice may tighten those
   facts; neither can loosen them.
4. The repository creates the first request and device profile. No client limit
   is represented deliberately rather than by omitting fields. The planner
   retains the constraint, cap origin, resolution policy, recovery intent, and
   capability conclusion in the plan/diagnostics.
5. `PlaybackInfoPlanner` combines the server result and local source-copy
   preflight to choose DirectPlay, DirectStream, or Transcode. Original blocks
   the DirectPlay/DirectStream-disabled compatibility ladder; Auto and eligible
   Fixed plans can use it within their stated bounds.
6. The controller installs the plan, reports exact/native track truth and
   native observation facts. It may then apply a backend-owned resource policy
   whose diagnostic identity and bounded parameters are closed values. The
   owner retains lifecycle, position, reporting, and replan authority.
7. The shared health coordinator evaluates sanitized facts; the pure Auto
   recovery coordinator makes a semantic decision, including a typed manual
   choice reason when inherited Auto lacks explicit downgrade authorization.
   The owning ViewModel or presenter executes that decision only when it is
   safe to present its consequences to the user.

### Offline resolution branch

An explicit Download Play route takes a separate branch before the online
pipeline above. It resolves a current-account, Completed, current-generation
record and its persisted snapshot into `StreamMode.Offline`; it does not call
detail, PlaybackInfo, remote image/trickplay/segment, or autoplay resolution,
and ordinary remote Play never substitutes this branch. The plan carries only a
generation-bound opaque artifact reference and a typed package-sidecar choice
when selected, never a fabricated Jellyfin index or raw local path. Every
accepting controller must
acquire the trusted local artifact lease before resolving the resource; the
offline branch never enters a remote URL or credential-attachment path, and
deletion must refuse that leased generation.

Android and JVM keep the normally resolved concrete backend for both artifact
kinds. iOS and tvOS select VLCKit for every offline session, never write that
session-only choice to preferences, and make the backend required. A missing,
wrong, or failing VLCKit controller returns `OfflinePlayerUnavailable`; it does
not prepare AVPlayer, fall back to it, or delete the artifact. The complete
identity, artifact, and progress rules live in
[Downloads And Offline](data-playback.md#downloads-and-offline).

## 3. Wire and persistence boundaries

`PlaybackBitrateConstraint` is internal planner/wire intent, not UI policy:

| Constraint | Meaning and encoding |
| --- | --- |
| `NoClientLimit` | Encode `Int.MAX_VALUE.toLong()` in PlaybackInfo and the DeviceProfile streaming/static fields. Omit `TranscodingProfile.MaxBitrate` and codec `VideoBitrate` conditions. The sentinel is a protocol maximum, never a displayed quality rung. |
| `ExactUserLimit` | The Fixed exact value in request/profile output fields and the required `VideoBitrate` condition. A canonical rung also supplies its resolution box; Custom is bitrate-only. |
| `AutoSessionLimit` | A temporary Auto recovery value encoded like an exact quality output limit. It is held only for the active session and is never silently written to preferences. |

The no-client-limit sentinel exists because omitting these Jellyfin fields lets
the server assume its own finite default. It is not a claim that every decoder
can play every source. A forced-transcode sentinel probe must be recorded in
the server-behavior evidence before release promotion; an unsafe result blocks
promotion rather than authorizing a hidden finite replacement.

Quality has exactly two durable settings and one ephemeral scope:

- `PlaybackPreferences.defaultQualityPolicy` is a local installation's default
  for a Jellyfin server (therefore server + device/app-profile in practice).
- `PlaybackPreferences.vlcTranscodeMaxBitrateBps` is the legacy storage name for
  an optional VLC-family Fixed default on that server. It uses the same
  bitrate-plus-canonical-resolution semantics as any other Fixed quality.
- An in-player choice is held only by the active presenter/ViewModel. It is not
  written to `PlaybackSelection`, process memory, or preferences.

Settings offers Auto, Original, the canonical fixed rungs, and Custom. Player
pickers additionally offer **Use playback default**, whose second line names the
effective inherited policy and whether it comes from the VLC-family or general
Playback setting. It clears only the current-playback choice and must preserve
audio/subtitle choices. Legacy per-source quality columns remain only for Room
schema compatibility and are ignored on read and cleared on write; durable audio
selection remains source keyed. There is no account-wide quality fallback,
per-asset quality memory, or persisted learned capacity.

## 4. Runtime evidence and recovery ownership

`PlayerController` reports native facts through narrow contracts:

- state and runtime diagnostics are `StateFlow` snapshots;
- dropped frames and first-video-output observations are event flows scoped to
  the controller prepare generation;
- iOS VLCKit prepare/resume/seek observations additionally carry a transition
  sequence within that generation, so Started, Presented, and TimedOut settle
  only the exact target transition;
- measurement capabilities state whether a fact is reliable and name its
  evidence class (`NativeFirstOutput`, `DisplayedPictureCounter`,
  `ReadyForDisplayBridge`, or unsupported).

`PlaybackHealthSessionCoordinator` owns monotonic timers, current generation,
first-output deadline, lifecycle exclusions, and sanitized health summaries. A
missing-video-output signal is possible only for video content while playback
is active/progressing, a reliable current-generation measurement is armed, and
the session is outside seek, replan, resume, surface, backend, background, and
PiP-transition exclusions. Audio-only media, pause, stale callbacks, Vout or
surface attachment alone, and unavailable measurements cannot trigger it. The
deadline begins when the player reports active Playing/progress, not when the
request launched. Measurement capability is checked both when the timer is
armed and when it fires, so a controller that reports the evidence unavailable
cannot inherit a stale no-video deadline and force a false fallback.

Android mpv sustained slow-software recovery is separate from optional playback
health signals and warnings. Its dedicated ViewModel state pauses playback and
requires an explicit choice: Switch to ExoPlayer, Keep playing with mpv, or Stop
playback. Back/outside taps cannot dismiss it, and neither warning preferences
nor diagnostic collection can suppress it. Continue consumes the prompt for the
current prepare; a fresh prepare can collect fresh evidence. A confirmed switch
uses the existing explicit backend-switch planner/installer, preserving current
quality policy, origin and runtime cap; Original is not silently relaxed.
Selecting Switch immediately hides the dialog and presents the player loading
spinner while planning/preparing. Planning failure keeps playback paused and
restores the dialog with a failure explanation and explicit retry/continue/stop
choices. Stop/disposal/new playback invalidate
old dialog actions. Thresholds and capture fields live in
[data-playback.md](data-playback.md).

`AutoPlaybackRecoveryCoordinator` is pure common-domain logic: it has no
coroutines, controller references, persistence calls, navigation, or strings.
It owns the two one-shot budgets and produces `CompatibilityReplan`, `LowerTo`,
a user prompt, a recovered notice, or no action. `LowerTo` requires the typed
explicit-session-Auto authorization; inherited Auto receives the typed manual
choice prompt without spending the quality budget. The ViewModel and
`TvPlaybackSessionPresenter` own execution, position preservation, policy
persistence, notice state, and stale-work cancellation. While the Compose
ViewModel is in PiP, its one pending-recovery slot retains the strongest trigger
in this locked order: DecoderFailure, UnsupportedMedia, NoVideoOutput,
CumulativeBuffering, RepeatedStalls, then DroppedFrames. Equal severity keeps
the first trigger. Leaving PiP clears and handles one retained trigger, while a
new item or queue launch and retry clear stale pending work. Measurement remains
lifecycle-safe while recovery presentation is deferred. The one retained trigger
still enters `AutoPlaybackRecoveryCoordinator`; arbitration grants no new
stream-change authority, so Original and inherited Auto keep their existing
manual prompt contracts.

`PlaybackSessionRecoveryPolicy` is the pure common-domain owner for recovery
precedence around controller state. It checks exact audio/subtitle activation
targets before decoder/unsupported failure, then permits a separate one-shot
same-policy network retry. Its immutable generation/item state keeps network,
audio-target, and subtitle-request budgets armed across replans while rejecting
stale items and targets. Every replan decision carries its closed
`PlaybackClientTrigger`. The ViewModel and tvOS presenter translate those
semantic decisions into their own jobs, controller commands, notices,
diagnostics, fatal state, and stale-result checks; native lifecycle ownership
does not move into the policy.

`PlaybackReportingCoordinator` is the per-session-owner common coordinator for
Start readiness and retry, pause/unpause edges, periodic progress sampling, and
Stop/disposal settlement. It composes the existing `PlaybackReportingQueue`,
which owns a serial ingress/local-settlement drain and a serial remote drain.
The domain reporting capability separates local durability from guarded remote
forwarding without exposing the data router to presentation. The Compose
owner supplies its stable shared playback-state projection so controller
replacement cannot strand reporting on an outgoing controller flow. tvOS creates
its reporting coordinator only after concrete installation and uses that
installed controller flow for the session. Both shells retain their general
previous-status, completion, queue, navigation, and native-command semantics.

Diagnostics are allowlisted and backend-qualified. They state policy,
constraint/client limiter, wire representation, capability result/reason,
effective transcode cap, configured VLC default state, recovery intent/result, and
first-video-output evidence/observation. A backend-readiness record may state
the closed resource policy and bounded value that were applied, but must not
claim a native decoder module the backend API cannot expose. Disabled and
no-cap states are rendered explicitly; a protocol sentinel is never presented
as a user quality. Diagnostics never include stream URLs, headers, tokens,
server/user IDs, titles, paths, or raw native errors.

## 5. Platform and backend ownership

Controllers own native verbs and event wiring; they do **not** own the state
decisions those verbs implement. Where a decision is identical across backends it
lives in one tested `commonMain` kernel and every controller renders that one value.

`RetrySelectionPolicy` follows that boundary for same-plan Retry. Each controller
classifies only the state it already owns as subtitle unspecified, explicit Off, or
a concrete selection before its existing prepare path. The policy retains audio,
accepts a concrete subtitle only when its complete activation target still matches
the plan, and returns a tagged restore-plan, reassert-Off, or select decision. Only
Android mpv, Android LibVLC, and desktop LibVLC classify explicit Off; Media3,
AVPlayer, VLCKit, and desktop mpv keep ambiguous null as unspecified. Controllers
still own subtitle assets, position/timing/speed retention, play intent, engine
reinitialization, released checks, native command ordering, and diagnostics.

The initial audio-activation gate is the canonical example. `initialAudioActivationFor(plan)`
returns the whole table: no audio target is `None`; `DirectPlay` awaits native track
mapping and stays pending; every other stream mode — including `Offline`, which has no
server in the path — is already active, because the audio choice is fixed before the
player sees it. Publishing this contract is what lets the shared ViewModel update
installed audio and trigger DirectPlay-disabled recovery, so a controller that skips it
silently loses both.

Two renderings of that one decision exist, and the difference is deliberate:

- `applyInitial` — Media3, AVPlayer, VLCKit, mpv, desktop VLC. A `None` decision
  **clears** the confirmation.
- `applyInitialWithoutClearing` — Android LibVLC only. A `None` decision is a **no-op**,
  because that controller already published `None` from its own state projection and
  clearing here would additionally cancel a timeout it expects to keep. Its call site is
  also guarded on having no pending user audio selection, so a choice made before the
  transition wins over the plan's gate.

AVPlayer's existing held gate guards every native resume entry: public Play,
poll recovery and seek completion. Pending or Unavailable activation cannot
clear it; the matching activation callback releases it, while shared recovery
or explicit failure resolves unavailable audio. VLCKit's start-before-discovery
behavior is unchanged.

Do not collapse the two renderings, and do not add an `initialAudioGate` field to a
controller that does not already have one — only Media3, AVPlayer, and mpv read the flag
after prepare. Keep this decision table in its one shared home with its host tests.
`PlaybackInterruptionIntent`
follows the same rule from the other direction: it is `commonMain` with Apple-only
consumers, because Android uses audio focus rather than interruption callbacks — do not
wire it into the Android controllers by analogy. It owns one captured resume
intent with explicit revocation and one-shot consumption; Apple controllers
distinguish internal interruption pause from public Pause. User-visible policy
is owned by [iOS recovery](data-playback.md#ios-recovery).

### Android mobile

The concrete Android choices, default, normalization, and visible order come from
the [Android backend policy](data-playback.md#backend-selection), which both
Settings shells consume.
Media3 derives video decoder facts from MediaCodec and the active display path.
For each codec it keeps resolution, throughput, profiles, levels, bit depth,
acceleration class, and ordinary/secure eligibility on one candidate until it
selects the preferred ordinary decoder: hardware before unclassified before
software, excluding secure-required candidates. Finite limits and constraints
are projected from that same candidate. Its audio decode list combines the platform
probe with the process-cached, fail-closed E-AC-3 result from JellyScope's bundled
Media3 FFmpeg extension. Decode-input channel ceilings remain per codec, while
the active route separately supplies PCM-output and encoded-passthrough facts.
LibVLC and mpv retain separately pinned codec/audio/subtitle declarations.
For intersecting declared and probed video codecs whose declared resolution
tuple is entirely unknown, LibVLC projects available MediaCodec width, height,
frame-area, and throughput bounds, including partial bounds; mpv requires
complete probed bounds. Existing declared bounds remain unchanged. This
projection does not import Media3's
audio support, profiles, or full capability set into either engine. All three
attach the source that owns each decode and finite-limit claim; pre-29 name
classification and unclassified platform results retain those weaker identities
instead of becoming hardware-probe evidence. All three map the same immutable
plan, share policy/recovery logic, and retain their own native lifecycle,
audio, subtitle, surface, and error mappings. Alternate-backend startup recovery
and the distinct offline construction-fallback boundary are owned by
`docs/guides/data-playback.md`. Media3 emits native first-frame
evidence, LibVLC emits a positive displayed-picture-counter observation, and
Android mpv currently declares first-video-output measurement unsupported;
surface attachment is never treated as displayed output. Android LibVLC
additionally applies its pinned dav1d resource policy at the native-media
boundary. That policy is self-scoped to dav1d by module ownership and remains
separate from source compatibility and quality decisions.

Android mpv deliberately advertises conservative pinned-engine capabilities:
up to eight audio channels and supported text/bitmap subtitles, but no HDR,
Dolby Vision, passthrough, or display-derived ceiling. Mobile uses copy-back,
emulators use software decode, and TV defaults to classic GPU plus zero-copy
MediaCodec because supported TV drivers reject the `gpu-next` external-sampler
path and copy-back cannot guarantee real-time TV presentation. Direct MediaCodec
is an explicit setting, never an automatic fallback, and gives up mpv subtitles
and sizing. Exact cache, output, surface-lifetime, and diagnostic rules belong to
the [Android mpv backend](data-playback.md#android-mpv-backend).

### Android TV

Android TV uses the Android planners/controllers but owns its D-pad, native
surface, and focus shell; unlike Android mobile, it does not own a MediaSession.
Its server-scoped backend dialog and session-only live switch consume the
[shared Android policy projection](data-playback.md#backend-selection) directly.
ExoPlayer, LibVLC, and mpv retain
their backend-specific capability/evidence rules from Android mobile. The TV
notice is a persistent, dismissible bottom action surface above visible
controls; it never steals focus on appearance. Its explicit actions are
D-pad-reachable, and dismissal restores a safe player focus owner. It may
defer an automatic replan while PiP prevents safe interaction. Its app root
uses the shared `Actionable` guidance policy, so qualified Auto health evidence
can take the existing one-shot common recovery path; desktop and tvOS retain
their own Advisory bindings.

### iOS

iOS Compose selects AVPlayer or VLCKit before initial planning and also exposes
the shared explicit remote session switch. AVPlayer uses the narrow
ready-for-display bridge from its native presentation surface. VLCKit uses
best-effort transition counters but does not advertise reliable shared
first-output measurement; see the [iOS recovery contract](data-playback.md#ios-recovery).
AVPlayer/VideoToolbox capability facts and
VLCKit engine facts are separate: one backend's hardware probe cannot erase a
codec from the other. Capability provenance likewise keeps the AV1 hardware
probe, static codec declarations, documented finite ceilings, and unknown
VLCKit limits distinct. Both receive the shared policy/notice model, while
Apple audio session, PiP, native track mapping, and authenticated URL handling
stay in Apple-owned code.

Standard compatibility applies the same app-owned 4K30 frame-area/throughput
input envelope to both AVPlayer and VLCKit. Only finite width/height conditions
reach the server profile; frame area and proportional throughput remain local
source-copy limits. Unrestricted removes only this marked envelope from the
profile and preflight, preserving backend codec, container, range, explicit
quality, and runtime-health constraints. This is product policy, not shared
decoder evidence.

### Desktop

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

### tvOS native playback

tvOS uses native SwiftUI player hosts over shared Kotlin presenters.
`TvPlaybackSessionPresenter` resolves the same typed policy, keeps player
choices session-only, and consumes the same session-recovery and Auto-recovery
kernels as the Compose player. The
AVPlayerViewController host passes a
ready-for-display observation through the narrow controller bridge. SwiftUI
renders persistent dismissible actions above the AVKit controls without
rebuilding transport menus on playback ticks or stealing Siri Remote focus;
the presenter controls any replan. Simulator success is not Apple TV hardware
decode, PiP, remote, or presentation evidence.

tvOS publishes `playerInstalled` only after Main installs the concrete
controller, creates reporting from that controller state flow, and attaches
output observation. Swift re-reads `platformPlayer` from that readiness update
instead of retaining an initialization snapshot. A close before installation
reports nothing and leaves any late candidate to startup ownership for one
release; a close after installation retains final reporting settlement and one
native release.

Online sessions remain AVPlayer-only. `TvOfflinePlaybackPresenter` loads the
account-qualified local download and shared `buildOfflinePlaybackPlan`, requires
VLC, and updates local progress without online planning or reporting. The
MainActor native model watches exactly one presenter and mounts the host for its
installed backend: AVKit online, or a controller-owned VLC drawable with native
transport offline. The Apple engine owns leases and native teardown; Swift never
resolves artifact paths. Queue/countdown and native panel behavior are owned by
[data-playback.md](data-playback.md#tvos-native-player) and
[ui.md](ui.md#native-tvos-screens).

## 6. Verification boundary

Automated tests cover typed-policy normalization, schema migration, exact
request/profile encoding, planner permissions, bounded recovery state,
generation-safe output observations, durable presenter/ViewModel state,
and diagnostics. They do not prove real decoder behavior, Jellyfin's
forced-transcode arithmetic, native presentation, or remote/PiP interaction;
physical iPhone validation remains pending. Android device/runtime evidence must
use a minified release build and the shortest owning-guide procedure. The
project-owned Android wrapper, native inventory, license/source route, package
inspection, and coexistence gates are owned by the
[Android native dependency runbook](../operations/android-native-dependencies.md).

## Why

- **Single-asset choice needs explicit target ownership.** A one-item queue alone
  would still allow episode derivation, stale retry inputs, and route-closing
  completion. Kids uses a distinct launch policy while retaining the existing
  planner, reporting, artifact leases, and native installation owner.

- **Backend switching is a guarded controller replacement.** Planning and
  intent validation happen while current playback and reporting remain
  authoritative, then one release-first installation commits. Persisting the
  switch, importing outgoing health facts, or retaining two live controllers
  would blur session and ownership boundaries.

- **Quality has one precedence chain and no learned capacity.** A current-player
  choice outranks the active VLC-family Fixed default, which outranks the
  general server default. Session scope prevents stale experiments from
  overriding current policy and keeps inherited Auto distinct from explicit
  Auto. A choice commits only after planning succeeds, so rejection or
  cancellation cannot alter the installed stream or its reporting. The
  no-client-limit sentinel is required because both omission and a finite
  stand-in can impose a server cap.

- **Original has one manual failure contract.** Decoder, unsupported-media,
  missing-output, buffering, stall, and dropped-frame triggers all preserve the
  source and present the same actions. Trigger-specific hidden replans would
  make Original's no-stream-change promise depend on implementation detail.

- **Offline playback is a separate trusted-local branch.** Routing it through
  remote planning could contact the server, expose a local path, or bypass
  account and generation authority. Opaque artifact references and leases keep
  resolution and deletion coherent; iOS and tvOS require session-only VLCKit so
  a missing backend fails visibly without changing ownership.

- **Recovery and reporting decisions are shared without moving lifecycle
  ownership.** Common immutable coordinators keep precedence, PiP arbitration,
  budgets, reporting order, and settlement consistent. Jobs, notices, native
  commands, navigation, and stale cancellation stay with each presentation
  owner; a universal stateful player would couple unrelated lifecycles.

- **Controller installation is serialized.** A current launch waits for an
  earlier installer and rechecks generation and item authority. Dropping a
  launch while installation is busy can leave the current item without a
  controller.

- **Same-plan Retry shares validation, not mutable replay state.** The common
  policy validates exact audio/subtitle targets and explicit Off only from the
  vocabulary each controller already owns. Assets, timing, play intent,
  reinitialization, and native command order remain controller-specific.

- **Capability facts remain concrete-backend owned.** Combining unrelated
  decoder maxima, platform probes, static declarations, or extension support
  can describe a nonexistent path. Closed provenance preserves what was measured
  or declared without creating a capability or sharing Media3 FFmpeg support.

- **Android TV mpv uses the selected supported rendering baseline.** Zero-copy
  `mediacodec` avoids the copy path's audio/video drift, and classic `gpu`
  avoids the supported-TV external-sampler failure. GPU remains default; direct
  output is an explicit choice with subtitle/sizing limits. Surface teardown
  synchronizes with mpv because asynchronous detach can race Surface destruction.

- **Desktop and iOS product envelopes are scoped policy.** The macOS LibVLC
  4K60-equivalent and iOS 4K30-equivalent Standard envelopes prevent known
  unsafe inputs without claiming a universal hardware ceiling. Unrestricted
  removes only that envelope; backend facts remain active. Screen geometry,
  guessed machine tables, and sparse model catalogs are not decoder evidence.

- **iOS transition and PiP evidence stays native and identity-bound.** Target
  arrival alone cannot prove fresh video, so bounded transition evidence also
  requires later clock and displayed-picture progress and rejects stale
  generations. VLCKit's public PiP protocols retain engine ownership; private
  traversal or a parallel controller would duplicate it.

- **Health policy is selected by the application surface.** Android TV uses the
  shared Actionable policy, while desktop and tvOS remain Advisory; placing that
  choice on `PlayerController` would conflate measurement support with
  presentation behavior. Android LibVLC's one-frame dav1d delay bound is a
  backend resource safeguard, not a source, quality, or device-profile rule.

- **Initial audio activation has one shared decision table.** DirectPlay waits
  for exact native mapping while other modes begin active, with two deliberate
  renderings for controller state differences. Centralizing the table prevents
  controller copies from drifting without forcing unrelated lifecycle state
  into a common base class.
