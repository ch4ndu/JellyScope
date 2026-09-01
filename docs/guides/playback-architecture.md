# Playback Architecture

This guide owns JellyScope's playback decision pipeline and the platform/backend
variation around it. The wire contract, persistence, tracks, diagnostics, and
runtime detail live in [data-playback.md](data-playback.md). The durable product
choices and their rejected alternatives are recorded in the [Why](#why) section
at the end of this guide.

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
  planning or explicit-intent failure closes the picker and presents the timed
  "current playback was kept" notice without a Stop or teardown. For the final
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
generation-bound opaque artifact reference. Every accepting controller must
acquire the trusted local artifact lease before resolving the resource; the
offline branch never enters a remote URL or credential-attachment path, and
deletion must refuse that leased generation.

Android and JVM keep the normally resolved concrete backend for both artifact
kinds. iOS selects VLCKit for every offline session, never writes that
session-only choice to preferences, and makes the backend required. A missing,
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
which remains the only executor and ordered settlement publisher. The Compose
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

Do not collapse the two renderings, and do not add an `initialAudioGate` field to a
controller that does not already have one — only Media3, AVPlayer, and mpv read the flag
after prepare. The audio-gate defect class recurred once per copy of this table,
which is why it now has exactly one home and its own host tests. `PlaybackInterruptionIntent`
follows the same rule from the other direction: it is `commonMain` with Apple-only
consumers, because Android uses audio focus rather than interruption callbacks — do not
wire it into the Android controllers by analogy.

### Android mobile

The concrete Android choices are ExoPlayer/Media3, mpv, and LibVLC. ExoPlayer
is the default and `Auto` normalizes to it; the Android backend policy owns the
visible order ExoPlayer, mpv, LibVLC (beta), which both Settings shells consume.
Media3 derives video decoder facts from MediaCodec and the active display path.
For each codec it keeps resolution, throughput, profiles, levels, bit depth,
acceleration class, and ordinary/secure eligibility on one candidate until it
selects the preferred ordinary decoder; finite limits and constraints are then
projected from that same candidate. Its audio decode list combines the platform
probe with the process-cached, fail-closed E-AC-3 result from JellyScope's bundled
Media3 FFmpeg extension. Decode-input channel ceilings remain per codec, while
the active route separately supplies PCM-output and encoded-passthrough facts.
LibVLC uses its pinned engine/runtime facts; MediaCodec ceilings and Media3
FFmpeg support do not become LibVLC claims. mpv uses its separate pinned
capability declaration and does not inherit Media3 or LibVLC claims. All three
attach the source that owns each decode and finite-limit claim; pre-29 name
classification and unclassified platform results retain those weaker identities
instead of becoming hardware-probe evidence. All three map the same immutable
plan, share policy/recovery logic, and retain their own native lifecycle,
audio, subtitle, surface, and error mappings. A native
create/init/decoder/unsupported-media failure on an alternate backend takes
exactly one fresh ExoPlayer-qualified replan at the last confirmed position;
`docs/guides/data-playback.md` owns that fallback contract. Media3 emits native first-frame
evidence, LibVLC emits a positive displayed-picture-counter observation, and
Android mpv currently declares first-video-output measurement unsupported;
surface attachment is never treated as displayed output. Android LibVLC
additionally applies its pinned dav1d resource policy at the native-media
boundary. That policy is self-scoped to dav1d by module ownership and remains
separate from source compatibility and quality decisions.

Android mpv's capability matrix is deliberately conservative: it advertises
the pinned FFmpeg/container/audio families, up to eight audio channels, and
text/bitmap subtitle delivery, but no HDR, Dolby Vision, audio passthrough, or
display-derived ceiling. It uses Android/OpenGL ES with a 10-second cache
target and 64 MiB forward and 16 MiB backward byte caps. Mobile uses `gpu-next`
with `mediacodec-copy`, emulators use `gpu-next` with software decode, and the
TV baseline uses classic `gpu` with zero-copy `mediacodec` and the
fast profile. TV pins `gpu` because the supported TV set includes drivers that
reject the external-sampler path used by `gpu-next`; the copy and software
classes do not import external samplers and keep `gpu-next`. The TV copy path is
rejected because it cannot guarantee real-time presentation when audio advances
ahead of video. Direct `mediacodec_embed` presentation is not an eligible fallback:
it bypasses the failing GPU-import path only by giving up mpv subtitle rendering,
which is a required backend behavior.

Android mpv presentation uses a fresh project-owned SurfaceView host for each
Compose view instance. Surface callbacks and release are owner-qualified so an
outgoing view cannot detach a newer replacement. Each valid attach enables the
native window, publishes the current `android-surface-size`, and reapplies that
size after layout/rotation changes. Same-host prepares retain that engine-bound
surface and replace only the media; they never reattach the identical handle
while a quality replan is dispatching. Detach disables the native window
without destroying the player. On TV, surface teardown blocks (bounded) inside
`surfaceDestroyed` until mpv's queued surface work drains. Sanitized lifecycle diagnostics include surface
attachment state, dimensions, retained attachment, size changes, detach, and
ignored stale callbacks, but never a surface handle or media identity.

### Android TV

Android TV uses the Android planners/controllers but owns its D-pad, native
surface, and focus shell; unlike Android mobile, it does not own a MediaSession.
The Android domain policy supplies the backend order ExoPlayer, mpv, LibVLC
(beta) and the ExoPlayer default. The server-scoped backend dialog consumes
that policy projection directly; mpv is an ordinary explicit opt-in and the
durable choice applies to the next playback session. The player overlay also
uses the same projection for a session-only live switch. ExoPlayer, LibVLC, and mpv retain
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
ready-for-display bridge from its native presentation surface. VLCKit reports a
positive displayed-picture counter. AVPlayer/VideoToolbox capability facts and
VLCKit engine facts are separate: one backend's hardware probe cannot erase a
codec from the other. Capability provenance likewise keeps the AV1 hardware
probe, static codec declarations, documented finite ceilings, and unknown
VLCKit limits distinct. Both receive the shared policy/notice model, while
Apple audio session, PiP, native track mapping, and authenticated URL handling
stay in Apple-owned code.

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

### tvOS / AVKit

tvOS is native SwiftUI/AVKit over the shared Kotlin presenter, not Compose.
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

## 6. Verification boundary

Automated tests cover typed-policy normalization, schema migration, exact
request/profile encoding, planner permissions, bounded recovery state,
generation-safe output observations, durable presenter/ViewModel state,
and diagnostics. They do not prove real decoder behavior,
Jellyfin's forced-transcode arithmetic, native presentation, or remote/PiP
interaction. Current platform validation gaps and evidence status — including the
explicitly pending physical iPhone validation — are owned by the internal
`.local/KNOWN-ISSUES.md` ledger. The repeatable Android device and runtime
measurement procedure lives with its Android Media3 load-control and
playback-performance rows.
The project-owned Android `android-libmpv` wrapper, its pinned AAR build input,
ABI/native inventory, license assets, and corresponding-source route are owned by the
[Android native dependency runbook](../operations/android-native-dependencies.md);
package inspection and native coexistence remain release-gate evidence rather
than host-test evidence. Physical playback evidence follows the
minified-release-build rule in `docs/guides/workflow.md`.

## Why

Durable product choices and their rejected alternatives. Each entry explains a
rule the body above states; the body remains authoritative for the rule itself.

- **An explicit backend switch is a guarded replacement, not a preference
  write or recovery path.** Planning before teardown means a bad target leaves
  proven playback and reporting intact; plan/reporting authority and the
  release-first installation preserve one controller owner after a switch has
  committed. Reusing Android's automatic health fallback would conflate a user
  choice with decoder recovery and hardcode ExoPlayer. Reusing runtime caps or
  health facts would turn an outgoing-controller observation into target
  capability. Retrying construction beyond the one concrete platform default,
  retaining a second controller while preparing it, or adding a recovery state
  machine were rejected because each obscures the one authoritative
  controller/session.

- **Player quality is session-only, resolved by one precedence chain.** A
  fresh playback resolves its quality as: explicit choice made in the current
  player session, else the per-server VLC-family Fixed default when the
  concrete backend is LibVLC/VLCKit, else the per-server general default.
  Durable per-item/source quality persistence was rejected: it made the
  effective value unpredictable and let stale experiments silently override
  current settings (durable audio selection stays source-keyed — track choice
  is a stable per-title fact, unlike quality experiments). Applying the VLC
  value only after a failed first attempt was rejected: it cannot prevent the
  bad first attempt, a bitrate-only second request has different semantics
  from the visible quality ladder, and the setting exists precisely so a user
  can pick a known-working default before playback; it is therefore a normal
  Fixed policy on the initial request, never a decoder capability or learned
  limit. The most recent explicit user decision wins, so the VLC default never
  overrides an explicit in-player Original. Inherited Auto is not explicit
  Auto: the settings default governs the initial request, but only a
  deliberate in-player Auto choice authorizes an automatic downgrade — a
  configured default must never silently change streams. Fixed never
  auto-lowers: it is an explicit value, and only Auto grants quality recovery.
  Exhausted-recovery notices open the in-player lower-quality picker rather
  than routing to Settings, because the player already owns those choices.

- **Original failure remains a user decision for every recovery trigger.**
  Decoder failure, unsupported media, missing video output, cumulative
  buffering, repeated stalls, and dropped frames all preserve the selected
  source and return the same `OriginalPlaybackFailed` prompt. Granting one of
  those triggers an exception would make Original's no-stream-change promise
  dependent on implementation detail; a budget, runtime cap, or hidden replan
  was therefore rejected. The user can explicitly accept Auto, open Playback
  Settings, dismiss, or close instead.

- **Offline playback is a separate trusted-local resolution branch.** Sending
  an offline request through the remote planner, putting a raw path in
  `PlaybackPlan`, or silently replacing ordinary Play with a local copy was
  rejected because each can contact the server, bypass account/generation
  authority, or expose filesystem identity. The opaque reference plus
  controller-held lease keeps resolution and deletion coherent. iOS requires
  session-only VLCKit for every offline artifact so one local playback route
  has one lease-aware native owner; automatic AVPlayer fallback would bypass
  that ownership decision and reproduce backend-dependent offline failures.

- **No client limit is an honest wire sentinel, never a finite stand-in.** A
  finite "unlimited" guard (for example 100 or 120 Mbps standing in for Auto
  or Original) was rejected because a finite value is a client limiter that
  can cause the very transcode it claims not to request; simply omitting the
  bitrate fields was equally rejected, because an omitted field is a
  server-side default cap, not neutrality. The honest no-client-limit wire
  sentinel replaced both. A working bitrate tied to one
  device/server/content combination can never become a platform-wide ceiling —
  the VLC default stays exact,
  user-owned, and tunable by trial and error. Also rejected: erasing backend
  capability differences behind one shared claim set, and duplicating policy
  orchestration in every platform shell.

- **Session recovery shares decisions, and PiP retains the decisive cause.**
  Activation-first precedence, exact-target eligibility, one-shot budgets, and
  typed causes are identical across Compose and tvOS, so keeping shell-local
  booleans let the paths drift. Strongest-wins prevents a weak earlier signal
  from hiding a later decoder or unsupported failure, while equal severity keeps
  the first for deterministic stability. Latest-wins was rejected because a
  later weak signal can erase stronger evidence; unconditional first-wins was
  rejected because a weak early signal can hide stronger later evidence;
  replaying or queueing all triggers was rejected because one PiP exit could
  cause multiple prompts or replans. Clearing on launch or retry keeps stale
  evidence from crossing item or session authority. Original and inherited Auto
  remain manual because the winner still enters the existing coordinator. A pure
  immutable reducer gives shared decisions one owner while each shell retains
  jobs, notices, diagnostics, stale-work cancellation, and native commands.
  Rejected: a universal player base class or stateful mega-kernel, which would
  move platform lifecycle and presentation ownership into common code.

- **Reporting coordination is shared above one ordered executor.** Start
  success/pending state, ready-state retry, progress eligibility, periodic
  sampling, edge mapping, and Stop suppression are identical across Compose
  and tvOS, so retaining them in both shells made ordering fixes incomplete by
  construction. Compose supplies a projection stable across controller
  replacement, while tvOS creates its coordinator after concrete installation
  so that controller flow is stable for the coordinator lifetime; both delegate
  every request to `PlaybackReportingQueue`. Eager native-controller or
  coordinator allocation before `start()` was rejected because it performs
  resource and callback work before presentation authority exists, and Swift's
  former immutable player snapshot could not observe delayed installation. A
  second queue or an immutable event helper that left timer ownership in each
  shell was rejected.
  Completion, navigation, controller commands, and general previous-status
  state remain shell-owned because those semantics are not reporting policy.

- **Compose serializes delayed controller installation.** A current launch
  waits for an earlier installer and rechecks its generation and item authority
  before replacement work. Drop-on-busy was rejected because a stale installer
  could make the current launch terminate without an installed controller.

- **Same-plan Retry shares selection validation, not capture or native replay.**
  Exact target matching and the three subtitle outcomes are identical across
  controllers, but nullable local state is not: three controllers already track
  explicit Off and four do not. Controllers therefore classify their existing
  vocabulary before calling the pure policy; the policy never infers Off from
  null. Rejected: adding a shared mutable retry holder, teaching ambiguous
  controllers a new Off bit, or moving assets, timing, play intent, engine setup,
  and native command order into common code.

- **Android backend policy and capability facts stay concrete-backend owned.**
  Auto normalizes to ExoPlayer; the durable initial concrete backend is selected
  before PlaybackInfo and remains authoritative across queue items unless the
  user makes an explicit session-only switch, with mpv and LibVLC (beta) as
  explicit alternates. TV exposes mpv as an explicit alternate, while
  availability and native readiness remain typed runtime concerns. MediaCodec
  candidates stay coherent through selection because combining the largest
  software-decoder box with a hardware decoder's profile/throughput facts
  describes no real path. Media3's bundled FFmpeg E-AC-3 result augments only
  Media3 because an extension renderer is not evidence for mpv or LibVLC.
  Closed provenance preserves the distinction between authoritative platform
  flags, legacy name classification, runtime extension support, pinned engine
  declarations, and static/unknown fallbacks; calling every accepted candidate
  a hardware probe was rejected because it would make the diagnostic stronger
  than the source fact.
  Rejected: making mpv the default, hiding it behind a runtime check, enabling
  it through a second mutable TV feature flag, treating host/emulator success
  as native presentation evidence, flattening unrelated decoders into one
  synthetic capability, or sharing Media3 extension support across backends.

- **`mediacodec-copy` is rejected for Android TV mpv; zero-copy `mediacodec`
  is the baseline.** The copy path can advance video behind audio, so a small
  dropped-frame count is not evidence of healthy presentation when the video
  clock itself is lagging. Zero-copy has its own defect but remains the less
  broken baseline. Rejected: shipping the copy path because its counter looks
  better, waiting for video to catch audio, lowering quality when a lower-rate
  decode is healthy, disabling frame dropping and accepting drift, or treating
  refresh-rate matching as the fix.

- **Android TV mpv's classic `gpu` renderer applies TV-wide — a deliberate
  product call, not a defect record.** One TV rendering configuration avoids a
  per-SoC table for the external-sampler compatibility path. Scoping the
  fallback by renderer string was rejected because it adds detection complexity
  without a separate supported configuration contract. If a supported TV later shows an mpv
  rendering regression, revisit the scope of this call rather than treating
  the TV-wide setting as an oversight.

- **Android TV surface teardown synchronizes with mpv's native queue.** The
  framework disconnects the Surface the moment `surfaceDestroyed` returns, and
  mpv's video output racing that disconnect can deadlock the GPU driver against
  main-thread buffer teardown. Relying on asynchronous detach ordering was
  rejected: a callback can be ignored as stale exactly when the detach is still
  queued.

- **Desktop keeps backend-owned provenance; macOS LibVLC has one scoped product
  envelope.** A demonstrated clean 4K/60 path and failing 8K/60 output justify
  a conservative macOS-LibVLC Standard policy, not a decoder claim or a desktop
  ceiling. Width/height participate in server negotiation while proportional
  frame-area throughput stays in local source-copy preflight; a blanket 60 fps
  condition was rejected because lower-resolution high-frame-rate sources can
  fit the same throughput. The existing Unrestricted choice removes only the
  marked envelope so a user can deliberately attempt the source unchanged.
  Separate snapshots keep that correction from leaking into mpv or non-macOS
  LibVLC. Also rejected: one global desktop cap, guessed machine tables,
  window/screen-derived limits, learned limits, and changing Original's
  no-stream-change promise. The macOS mpv runtime supply and loading rationale
  are owned by `architecture.md`.

- **iOS VLCKit transition and PiP evidence stay native and identity-bound.**
  Prepare, deferred resume, and seek retain the requested target until target
  arrival, later clock progress, and two fresh displayed-picture advances
  agree; a generation-local transition sequence prevents stale settlement.
  Target arrival alone was rejected because audio/clock progress can precede
  fresh video. A continuous whole-session cadence detector was rejected because
  frame dropping and variable cadence are not frozen-output evidence. PiP uses
  VLCKit's public drawable/media/window protocols and binds a lazily created
  surface to the active prepare generation. Private layer traversal and a
  project-owned sample-buffer controller were rejected because they duplicate
  native ownership and depend on engine internals.

- **iOS uses one conservative input envelope across AVPlayer and VLCKit.** The
  existing 4K30 frame-area and throughput bound is an app-supported iOS input
  policy, so applying it before either backend prevents 4K60 and 8K60 sources
  from reaching a route that cannot sustain them under the default **Standard**
  setting. **Unrestricted (experimental)** removes only this product envelope
  from server negotiation and local source-copy preflight for both backends;
  backend codec/container/range constraints, explicit quality choices, and
  transition-scoped output qualification remain active. It does not make AVPlayer's
  hardware probe a VLCKit codec fact: each backend retains its own codecs and
  provenance, and VLCKit's finite Standard bound remains a static declaration.
  Per-model tables were rejected because they turn a production safety policy
  into an incomplete hardware catalog; a fully custom device-profile editor was
  rejected because the required future-device escape hatch needs one bounded
  policy choice, not user-authored decoder claims. Leaving VLCKit unbounded by
  default was rejected because it waits for visible output failure before
  requesting a compatible stream.

- **Android TV binds the shared Actionable health contract; desktop and tvOS
  stay Advisory.** Qualified Auto dropped-frame evidence on TV executes the
  same evaluator thresholds, canonical lower-rung selection, one-shot session
  budget, and replan path as mobile. Presentation policy is selected at each
  app root, never on `PlayerController` — leaving it on the controller would
  couple native measurement capability to shell UX and make Android mobile
  and Android TV indistinguishable. Rejected: a TV-specific measurement or
  threshold, a second recovery coordinator, new playback state for the
  overlay, one Android-wide policy (it removes the mobile safe action or puts
  mobile-only actions on TV), and treating the VLC budget as selected quality.

- **Android LibVLC bounds dav1d frame delay at one frame per media; the bound
  is backend readiness, never playback policy.** A very-high-resolution AV1
  baseline reached near-kill memory before the health window could recover;
  the option is owned by dav1d and inert when another decoder opens, and it
  changes no plan, codec, resolution, bitrate, or device rule. The value is
  experiment-selected, not generalized. Rejected: a global AV1/resolution/
  bitrate cap (limited combination evidence cannot define capability),
  low-RAM/model gates (proxies for a native allocation failure), a
  user-visible decoder-thread setting (implementation detail without a user
  contract), and hidden preflight transcode or automatic backend switch
  (changes the user's decision).

- **The initial audio-activation table has one home because copies kept
  regressing.** The DirectPlay audio gate once existed as six copies in three
  shapes, and its defect class recurred per copy; the fix was consolidation
  into one common function with two explicit adopters, not another patched
  copy. Because no host test can construct the riskiest controller, the
  rendering is covered by testing the adopters directly.
