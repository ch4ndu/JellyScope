# JellyScope

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL%202.0-blue.svg)](LICENSE)

JellyScope is a third-party client for Jellyfin. It is not affiliated with,
endorsed by, or maintained by the Jellyfin Project. Jellyfin is a trademark of
Jellyfin, Inc.

JellyScope is a Kotlin and Compose Multiplatform client for Android phones and
tablets, Android TV, Google TV, Fire TV, iPhone, iPad, and Apple Silicon Macs.
Apple TV has an experimental native SwiftUI preview. The current alpha release
is **0.1.0-alpha94**.

## Features

- Server discovery on Android and desktop, direct URL entry, Quick Connect, and
  multiple accounts
- Home, library browse, search, recommendations, Discover, people, and detailed
  movie/show/episode pages with versions, cast, trailers, chapters, and skipping
- Direct play, audio-only conversion, or transcoding based on the selected
  player, quality, source, tracks, and device capabilities
- Selectable players on Android, iOS, and macOS, including eligible
  session-only switching during online playback
- Text and image subtitles, local OpenSubtitles files, supported timing and
  audio controls, autoplay next, and queues
- [Kids viewing](docs/guides/ui.md#kids-watch-page) on Android/iOS phones and
  tablets, selected by the account's parental rating, with single-video
  playback, shuffled library choices, compact video controls, and permitted downloads
- Offline downloads for individual movies and episodes on Android, iOS, macOS,
  and the Apple TV preview when content downloading is enabled for the account,
  with a library-style grid and offline details; new downloads save metadata
  and artwork. iOS 26+ supports user-initiated background continuation for both
  Original and converted downloads; earlier iOS versions require the app to
  remain active. See [download limits](docs/guides/data-playback.md#downloads-and-offline).
- HDR handling, Android TV refresh-rate matching, Picture-in-Picture, Fire TV
  Watch Next, Apple media controls, themes, tile sizes, and adaptive layouts
- Account-qualified [Android TV playback links](docs/guides/tv-ux-behaviors.md#playback-links)
  that open an item directly with the current playback settings

Detailed controls and platform limits are in the [usage guide](docs/USAGE.md).

## Platforms

Android mobile and Android TV are the primary platforms. iPhone, iPad, and
macOS share the browse experience with native playback.

Android requires Android 7.1 / API 25 or newer, including Fire OS 6. The mpv
backend requires API 26+; ExoPlayer remains available on API 25. Android builds
use a pinned [JellyScope mpv bundle](docs/operations/android-native-dependencies.md).
Android TV offers GPU output (default) or Direct MediaCodec output; the
[usage guide](docs/USAGE.md#choose-a-player) describes its limitations.

iPhone and iPad require iOS 16 or newer. The Apple TV preview requires tvOS 17
or newer and is permanently unsupported, with no hardware-validation,
feature-parity, or ongoing-support commitment. The macOS package requires
macOS 13 or newer on Apple Silicon;
Intel Macs are unsupported. Windows and Linux have experimental source-build
paths, with no tested or packaged releases yet.

## Getting started

Choose and install the correct artifact by following
[Installing and upgrading](docs/BUILD.md#installing-and-upgrading). Then use the
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
