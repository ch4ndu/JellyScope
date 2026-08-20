# Documentation Map

Each fact lives in exactly one place. Change it at its owner and link to it —
never copy another document's rules into a second file.

## Sources Of Truth

| File | Owns |
| --- | --- |
| [`../README.md`](../README.md) | User-facing capabilities per platform: what the app does, players, formats, subtitles |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | Contributor entry point: build pointers, code conventions, PR expectations |
| [`../CHANGELOG.md`](../CHANGELOG.md) | What shipped in which release |
| [`../AGENTS.md`](../AGENTS.md) | Agent instruction router, always-on engineering rules, and the documentation update rule |
| [`BUILD.md`](BUILD.md) | Toolchain, per-platform build commands, verification baseline, module map |
| [`RELEASE.md`](RELEASE.md) | Release runbook: Android and Apple signing setup, release builds, smoke-test checklist |
| [`guides/architecture.md`](guides/architecture.md) | Layers, state/events, source layout, platform boundaries, known duplication |
| [`guides/playback-architecture.md`](guides/playback-architecture.md) | Shared playback decision pipeline and platform/backend ownership |
| [`guides/data-playback.md`](guides/data-playback.md) | Jellyfin API, auth, cache, settings, playback planning and behavior, player UX, probe-verified server behavior |
| [`guides/ui.md`](guides/ui.md) | Shared UI, adaptive layout, accessibility, resources, previews |
| [`guides/tv-ux-behaviors.md`](guides/tv-ux-behaviors.md) | TV focus, remote input, navigation, screen behavior |
| [`guides/workflow.md`](guides/workflow.md) | Step-by-step development, testing, review, verification, and documentation workflow |
| [`guides/audit.md`](guides/audit.md) | Architecture, correctness, code-quality, performance audit workflow |
| [`guides/compose-performance-audit.md`](guides/compose-performance-audit.md) | Compose performance and correctness audit method |
| [`operations/android-native-dependencies.md`](operations/android-native-dependencies.md) | Android native dependency pins, packaged-binary verification, licenses, corresponding-source obligations |
| [`operations/licensing-and-distribution.md`](operations/licensing-and-distribution.md) | Project-license direction, platform-distribution obligations, source availability, trademarks, and migration gate |
| `design/**` | Published brand/icon asset provenance and retained design assets |

Rationale — why a rule is what it is, and what was rejected — lives in the
`## Why` section of the guide that owns the rule, never in a separate log.

## Reader Contract

The root `README.md` describes JellyScope's features, supported platforms, and
playback capabilities. Implementation details and internal terminology do not
belong there. `CONTRIBUTING.md` explains how to contribute,
`guides/workflow.md` covers the development process, and `BUILD.md` explains how
to build and run the apps. The remaining guides contain the engineering rules
for their areas.
