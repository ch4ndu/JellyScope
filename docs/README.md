# Documentation Map

Use this map to find each document's scope. The
[development workflow](guides/workflow.md#8-maintain-documentation) owns
documentation maintenance and single-owner rules.

## Sources Of Truth

| File | Owns |
| --- | --- |
| [`../README.md`](../README.md) | Project overview, feature summary, supported platforms, and starting points |
| [`USAGE.md`](USAGE.md) | Feature guide: accounts, browsing, queues, versions, downloads, playback, subtitles, PiP, and diagnostics |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | Development entry point and change requirements |
| [`../CHANGELOG.md`](../CHANGELOG.md) | What shipped in which release |
| [Project instructions](../AGENTS.md) | Contract routing and required gates |
| [`BUILD.md`](BUILD.md) | Toolchain, per-platform build commands, installation and upgrades, verification baseline, module map |
| [`RELEASE.md`](RELEASE.md) | Release runbook: Android and Apple signing setup, release builds, smoke-test checklist |
| [`guides/architecture.md`](guides/architecture.md) | Layers, state/events, source layout, and platform boundaries |
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

The overview and feature guide describe application behavior; engineering
contracts and development process belong in the guides.
