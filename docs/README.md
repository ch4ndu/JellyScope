# Documentation Map

## User guides

Start with [Using JellyScope](USAGE.md) for task-based instructions and platform limits.

## Engineering

Each guide owns the detailed contract named below. Link to its owner instead of
copying rules. [Documentation maintenance](engineering/workflow.md#8-maintain-documentation)
owns audience, size and migration policy.

| Guide | Owns |
| --- | --- |
| [architecture](engineering/architecture.md) | Layers, state, DI, source sets and platform bridges |
| [accounts-and-persistence](engineering/accounts-and-persistence.md) | Credentials, account boundaries, settings and durable state |
| [api-and-caching](engineering/api-and-caching.md) | Jellyfin requests, projections, discovery and caches |
| [playback-architecture](engineering/playback-architecture.md) | Playback pipeline and platform ownership |
| [playback-policy](engineering/playback-policy.md) | Quality, capabilities, wire profiles and server evidence |
| [playback-runtime](engineering/playback-runtime.md) | Session installation, activation, reporting and recovery |
| [subtitles](engineering/subtitles.md) | Track delivery, local assets and OpenSubtitles |
| [downloads](engineering/downloads.md) | Admission, storage, artifacts, offline playback and removal |
| [download-execution](engineering/download-execution.md) | Queue execution, retry, checkpoints and OS grants |
| [android-playback](engineering/android-playback.md) | Media3, LibVLC, mpv and Android transport integration |
| [apple-playback](engineering/apple-playback.md) | iOS and tvOS native playback integration |
| [desktop-playback](engineering/desktop-playback.md) | Desktop native playback and presentation |
| [player-interactions](engineering/player-interactions.md) | Shared player input, overlays and picker contracts |
| [diagnostics](engineering/diagnostics.md) | Collection, safe logging, privacy and reporting |
| [ui](engineering/ui.md) | Compose, design, adaptive layout and shared screen contracts |
| [kids-viewing](engineering/kids-viewing.md) | Kids eligibility, single-asset playback and layout |
| [tvos-ui](engineering/tvos-ui.md) | Native tvOS presentation and navigation |
| [tv-ux-behaviors](engineering/tv-ux-behaviors.md) | Android TV focus kernel, navigation and playback links |
| [tv-screen-behavior](engineering/tv-screen-behavior.md) | Android TV screen and remote behavior |
| [workflow](engineering/workflow.md) | Development, tests, reviews and documentation maintenance |
| [audit](engineering/audit.md) | Architecture and code audit method |
| [compose-performance-audit](engineering/compose-performance-audit.md) | Compose correctness and performance audit method |

## Build, release and project records

- [Build](BUILD.md): toolchains, local artifacts, installation commands and module map.
- [Release](RELEASE.md): signing, publication commands and acceptance gates.
- [Native dependencies](operations/android-native-dependencies.md): Android pins, binary verification and source/license obligations.
- [Licensing and distribution](operations/licensing-and-distribution.md): legal direction and distribution requirements.
- [Project overview](../README.md), [contributing](../CONTRIBUTING.md), [project instructions](../AGENTS.md), and [release history](../CHANGELOG.md).
- `design/**`: retained design assets and provenance; not an engineering contract owner.
