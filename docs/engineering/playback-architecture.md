# Playback Architecture

This guide owns the shared decision pipeline and platform ownership map.
Detailed contracts live in the [engineering documentation map](../README.md).

`PlaybackPlan` is the immutable domain-to-player contract. It models DirectPlay,
DirectStream, Transcode and Offline explicitly, and gains a field only with the
capability that consumes it. Platform players map it to native APIs; they do not
fetch Jellyfin metadata or choose a stream mode.

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
is owned by [iOS recovery](apple-playback.md#ios-recovery).

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
