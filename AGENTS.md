# JellyScope Project Instructions

JellyScope is a Kotlin Multiplatform and Compose Multiplatform Jellyfin client.
Read the current implementation and only the guides relevant to the task.
[`docs/README.md`](docs/README.md) maps the complete documentation set.

## Contract Routing

| Area | Guide |
| --- | --- |
| Layers, state/events, source sets, DI, threading, platform bridges | [`architecture.md`](docs/guides/architecture.md) |
| Shared playback pipeline and backend ownership | [`playback-architecture.md`](docs/guides/playback-architecture.md) |
| Scope, coordination, code clarity, tests, verification, documentation | [`workflow.md`](docs/guides/workflow.md) |
| Code and architecture review | [`audit.md`](docs/guides/audit.md) |
| Compose, layout, resources, accessibility, previews | [`ui.md`](docs/guides/ui.md) |
| Compose correctness and performance review | [`compose-performance-audit.md`](docs/guides/compose-performance-audit.md) |
| Android TV focus, navigation, remote/D-pad behavior | [`tv-ux-behaviors.md`](docs/guides/tv-ux-behaviors.md) |
| API, authentication, caches, settings, playback behavior | [`data-playback.md`](docs/guides/data-playback.md) |
| Release and signing | [`RELEASE.md`](docs/RELEASE.md) |
| Android native dependencies and source obligations | [`android-native-dependencies.md`](docs/operations/android-native-dependencies.md) |
| Licensing, distribution, and product identity | [`licensing-and-distribution.md`](docs/operations/licensing-and-distribution.md) |

## Required Gates

All implementation follows the [development workflow](docs/guides/workflow.md),
including its scope-expansion and delegation approval rules, single integration
responsibility, selective-test policy, and limits on Git/release authority.
Completion requires its [final candidate verification](docs/guides/workflow.md#7-verify-the-final-candidate)
and [documentation update rule](docs/guides/workflow.md#8-maintain-documentation).
