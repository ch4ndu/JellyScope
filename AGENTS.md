# JellyScope Project Instructions

JellyScope is a Kotlin Multiplatform and Compose Multiplatform Jellyfin client.
Read the current implementation and only the guides relevant to the task.
[`docs/README.md`](docs/README.md) maps the complete documentation set.

## Contract Routing

| Area | Guide |
| --- | --- |
| Layers, state/events, source sets, DI, threading, platform bridges | [`architecture.md`](docs/engineering/architecture.md) |
| Shared playback pipeline and backend ownership | [`playback-architecture.md`](docs/engineering/playback-architecture.md) |
| Scope, coordination, code clarity, tests, verification, documentation | [`workflow.md`](docs/engineering/workflow.md) |
| Code and architecture review | [`audit.md`](docs/engineering/audit.md) |
| Compose, layout, resources, accessibility, previews | [`ui.md`](docs/engineering/ui.md) |
| Compose correctness and performance review | [`compose-performance-audit.md`](docs/engineering/compose-performance-audit.md) |
| Android TV focus, navigation, remote/D-pad behavior | [`tv-ux-behaviors.md`](docs/engineering/tv-ux-behaviors.md) |
| API and caches | [`api-and-caching.md`](docs/engineering/api-and-caching.md) |
| Accounts, credentials and settings | [`accounts-and-persistence.md`](docs/engineering/accounts-and-persistence.md) |
| Playback policy and runtime | [`playback-policy.md`](docs/engineering/playback-policy.md), [`playback-runtime.md`](docs/engineering/playback-runtime.md) |
| Downloads and execution | [`downloads.md`](docs/engineering/downloads.md), [`download-execution.md`](docs/engineering/download-execution.md) |
| Subtitles and diagnostic privacy | [`subtitles.md`](docs/engineering/subtitles.md), [`diagnostics.md`](docs/engineering/diagnostics.md) |
| Release and signing | [`RELEASE.md`](docs/RELEASE.md) |
| Android native dependencies and source obligations | [`android-native-dependencies.md`](docs/operations/android-native-dependencies.md) |
| Licensing, distribution, and product identity | [`licensing-and-distribution.md`](docs/operations/licensing-and-distribution.md) |

## Required Gates

All implementation follows the [development workflow](docs/engineering/workflow.md),
including its scope-expansion and delegation approval rules, single integration
responsibility, selective-test policy, and limits on Git/release authority.
Completion requires its [final candidate verification](docs/engineering/workflow.md#7-verify-the-final-candidate)
and [documentation update rule](docs/engineering/workflow.md#8-maintain-documentation),
including its [concise writing guidance](docs/engineering/workflow.md#concise-useful-documentation).
