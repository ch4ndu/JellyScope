# Contributing To JellyScope

Thanks for taking a look. [`README.md`](README.md) describes the app for the
people using it; this file is for people changing it.

JellyScope is Kotlin Multiplatform with Compose Multiplatform UI: shared domain
and UI code, with platform shells for Android mobile, Android TV, iOS, macOS
desktop, and Apple TV.

## Where things live

- [`docs/README.md`](docs/README.md) — the documentation map: which file owns
  which facts.
- [`docs/BUILD.md`](docs/BUILD.md) — toolchain, per-platform build commands,
  module layout, and the verification baseline.
- [`docs/guides/workflow.md`](docs/guides/workflow.md) — start here for the
  step-by-step development, testing, review, and verification workflow.
- [`docs/guides/`](docs/guides) — the engineering contracts: architecture, UI,
  TV behavior, data and playback rules, and the rationale (`## Why`) behind
  them.

## Build and run

`./scripts/verify.sh` is the local pre-flight check — lint, unit tests, and the
Android release assemblies. Run it before opening a pull request.

A green build isn't a verified change. Exercise the path you touched on a real
device or simulator: the click, D-pad, keyboard, route, or playback flow. Note
that LibVLC playback is degraded on debug builds, so playback changes need a
release build to evaluate.

## Code conventions

- New JellyScope-owned Kotlin and Gradle files start with the `MPL-2.0` SPDX header.
- Put code in `commonMain` unless it genuinely needs a platform API; platform
  code sits behind an interface or a narrow `expect`/`actual` boundary.
- Keep the layering: repositories → use cases and actions → view models → UI. UI
  doesn't filter, sort, or group data.
- Move non-trivial work off the main dispatcher; keep state emission on it.
- Log through the Kermit facade rather than platform loggers, and never log
  tokens, credentials, credentialed URLs, or raw payloads.
- No `!!`.
- Reuse the existing use cases, composables, strings, icons, and theme
  dimensions before adding new ones.
- ktlint runs in `verify.sh`; otherwise match the surrounding code.

## Playback changes

Playback is the most constrained part of the codebase, and most of its rules
exist because a specific device or server behavior broke. Read
[`docs/guides/data-playback.md`](docs/guides/data-playback.md) before changing
anything there — it records probe-verified Jellyfin server behavior the client
is designed around, and its `## Why` section explains alternatives that were
tried and rejected. Prefer what's recorded there over re-deriving it.

Device profiles are the client's honesty boundary: they should advertise what
the selected player can really decode, neither over-claiming from an unreliable
decoder enumeration nor under-claiming to feel safe.

## Pull requests

- Keep changes scoped to one thing, and say what you verified and on what
  hardware.
- Update [`README.md`](README.md) only when a user-facing capability,
  limitation, or support status changes. The release owner updates
  [`CHANGELOG.md`](CHANGELOG.md) at release time.
- If a fix would trade away existing behavior, say so in the description rather
  than deciding silently.
- Never commit server URLs, credentials, or anything under `.local/`.
- `main` is the only long-lived branch; feature and fix branches are merged and
  deleted.
