# JellyScope

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](https://kotlinlang.org/compose-multiplatform/)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL%202.0-blue.svg)](LICENSE)

JellyScope is a third-party client for Jellyfin. It is not affiliated with,
endorsed by, or maintained by the Jellyfin Project. Jellyfin is a trademark of
Jellyfin, Inc.

JellyScope is a Kotlin and Compose Multiplatform Jellyfin client. Android mobile
and Android TV are the primary platforms; iPhone, iPad, and Apple Silicon macOS
share browsing and use native playback. Shared domain policy coordinates accounts,
media, downloads and playback across platform shells.

## Features

- Server discovery, Quick Connect and multiple accounts
- Libraries, search, recommendations, media details and optional [Seerr requests](docs/user/seerr.md)
- Selectable players, quality and track controls, subtitles, queues and autoplay
- [Kids viewing](docs/user/getting-started.md#kids-viewing) and [offline downloads](docs/user/downloads.md)
- Adaptive layouts, TV remote navigation, Picture-in-Picture and playback diagnostics

Controls and limitations are in the [usage guide](docs/USAGE.md).

## Platforms

Android requires Android 7.1 / API 25 or newer, including Fire OS 6. iPhone and
iPad require iOS 16 or newer. macOS requires macOS 15 or newer on Apple Silicon;
Intel Macs are unsupported. The native Apple TV preview requires tvOS 17 and is
permanently unsupported, without hardware-validation, feature-parity or ongoing
support commitments. Windows and Linux have experimental source-build paths,
with no tested or packaged releases.

See [platform support](docs/user/platforms.md), [player choices and limits](docs/user/playback.md),
and [building](docs/BUILD.md) for the appropriate path. Android native package
and physical playback qualifications are tracked separately in the
[native dependency runbook](docs/operations/android-native-dependencies.md).

The separate [JellyScope Launcher](docs/launcher/README.md) is an Android TV Home
app in development. Its guide covers setup, the optional server plugin and
bounded device qualification.

## Getting started

Choose and install the correct artifact by following
[Installing and upgrading](docs/user/getting-started.md#install-and-upgrade). Then use the
[JellyScope usage guide](docs/USAGE.md) to connect an account, find content,
download for offline playback, and configure playback.

## More documentation

- [Using JellyScope](docs/USAGE.md) — accounts, browsing, downloads, playback,
  subtitles, queues, Picture-in-Picture, and diagnostics
- [Documentation map](docs/README.md) — public and engineering guides
- [Building and running](docs/BUILD.md) — toolchain, builds, installation, and upgrades
- [Contributing](CONTRIBUTING.md) — development requirements
- [Changelog](CHANGELOG.md) — what changed in each release

## License

JellyScope-owned source is licensed under `MPL-2.0`; see [LICENSE](LICENSE).
Bundled and imported third-party components retain their own licenses.
