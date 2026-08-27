# JellyScope

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL%202.0-blue.svg)](LICENSE)

JellyScope is a third-party client for Jellyfin. It is not affiliated with,
endorsed by, or maintained by the Jellyfin Project. Jellyfin is a trademark of
Jellyfin, Inc.

JellyScope is a Jellyfin client written in Kotlin with Compose Multiplatform.
It runs on Android phones and tablets, Android TV, Google TV, Fire TV, iPhone,
iPad, and Apple Silicon Macs. There's also an experimental Apple TV preview.
The current alpha release is **0.1.0-alpha91**.

## Features

- Network server discovery on Android and desktop, direct server URL entry on iOS,
  Quick Connect, and multiple accounts
- Home shelves, library browse with sort and filters, and per-library
  recommendations
- Search, and a Discover surface for genres, studios, collections, people, and
  upcoming episodes
- Movie, show, season, and episode detail with a session-only Version picker
  for alternate sources, plus cast, trailers, and related titles
- A choice of playback engine on Android, iOS, and macOS, switchable from the
  player without leaving your video
- Direct play, audio-only conversion, or server transcoding, chosen per title
- Text and image subtitles, OpenSubtitles downloads, and audio/subtitle timing
  offsets
- Chapters, segment skipping, autoplay next, and a playback queue
- HDR handling, refresh-rate matching on Android TV, and Picture-in-Picture
- Offline downloads are currently unavailable while the existing Android, iOS,
  and macOS support is re-evaluated
- Three dark themes, three tile sizes, and a layout that adapts from phone to TV
- Fire TV Watch Next, lock-screen and Control Center controls, and macOS media
  keys

## Platforms

Android TV and Android mobile are the primary platforms, with full browse,
discovery, detail, settings, and playback surfaces. iPhone, iPad, and macOS
share the same browse experience with native playback. Apple TV is an
unsupported alpha preview: its native shell is available only for best-effort
simulator/build experimentation, with no hardware validation, feature-parity,
or ongoing support commitment.

| Platform | Players |
| --- | --- |
| Android TV, Google TV, and Fire TV | ExoPlayer (default), mpv, LibVLC (beta) |
| Android phone and tablet | ExoPlayer (default), mpv, LibVLC (beta) |
| iPhone and iPad | AVPlayer (default), VLCKit |
| macOS | mpv (default), LibVLC (beta) |
| Apple TV | System player (unsupported alpha preview) |

Android builds install on Android 7.1 / API 25 or newer, including Fire OS 6
devices (original Fire TV Cube). The mpv backend requires API 26+ and reports
itself unavailable on older devices, where playback stays on ExoPlayer —
which remains the default everywhere. When "Collect diagnostics" is enabled,
mpv keeps a size-capped raw verbose log on-device. "Send logs to server"
uploads only bounded structured and scrubbed diagnostics; raw mpv log files
remain local and never leave the app through client-log upload.

The iPhone and iPad app requires iOS 16 or newer. The Apple TV preview requires
tvOS 17 or newer.

The macOS package requires macOS 13 or newer on Apple Silicon; Intel Macs are
unsupported.

Windows and Linux have experimental source-build paths, but no tested or
packaged releases yet.

## Playback

### Players

| Player | Strengths | Limits |
| --- | --- | --- |
| **ExoPlayer** — Android default | Uses the device's AV1 hardware decoder; broadest Android format coverage with HDR and passthrough | Renders styled ASS/SSA subtitles only in part |
| **mpv** — Android alternate | Covers additional containers and codecs, styled text, and bitmap subtitles | No HDR, Dolby Vision, or audio passthrough; requires Android 8.0 / API 26 |
| **LibVLC (beta)** — Android | Plays unusual containers and codecs and renders ASS/SSA properly | No HDR or audio passthrough; software decoding may be too slow or memory-heavy for demanding files |
| **AVPlayer** — iOS default, Apple TV | Efficient, best system integration | Narrowest format range; only WebVTT subtitles render without server help |
| **VLCKit** — iOS | Wide format range including MKV oddities and Windows Media | No TrueHD; no HDR |
| **mpv** — macOS default | Wide format range including TrueHD | HDR is tone-mapped in software, so colours differ from a true HDR display; very-high-resolution playback can stutter during continuous pointer movement (LibVLC avoids that specific bottleneck) |
| **LibVLC (beta)** — macOS | Same wide format range, bundled with the app | Requests server-side HDR-to-SDR conversion; it has an independent very-high-resolution output ceiling and is not a universal fallback |

While watching a video on Android, iPhone, iPad, or macOS, open the video-camera
button in the player controls to switch to another available player for the
current session. JellyScope first checks that the new player can preserve the current
source and track choices; if it cannot, your video keeps playing with the current player.

mpv and the VLC players accept more formats than the system players, sometimes
through software decoding. A listed codec does not guarantee that every device
can play every stream smoothly.

On macOS, the default **Standard** playback-compatibility mode gives LibVLC a
conservative 4K/60-equivalent input envelope. Auto and eligible fixed-quality
playback can ask the server for a compatible transcode when a source exceeds
it; Original reports Unsupported Media instead of silently changing the
stream. **Unrestricted (experimental)** removes only that LibVLC envelope for
a deliberate unchanged-source attempt. mpv is unaffected.

### Direct play and conversion

- **Direct play** — the file streams untouched.
- **Audio-only conversion** — when just the audio is unsupported, the video
  still passes through untouched.
- **Conversion (transcode)** — your server re-encodes while you watch. It costs
  server CPU, can reduce quality, and is the only way subtitles get rendered
  into the picture.

The **Playback info** panel in the player (bug icon) shows which one you got and
why: player in use, conversion reasons from the server, codecs and bitrates,
decoder, resolution and frame rate, dropped frames, and bandwidth estimate.

### Formats

What each player requests directly:

| Player | Containers | Video | Audio |
| --- | --- | --- | --- |
| ExoPlayer (Android) | MP4, M4V, MOV, MKV, WebM, TS | H.264, HEVC, VP9, AV1 | AAC, MP3, AC-3, E-AC-3, DTS, FLAC, Opus, Vorbis, PCM |
| mpv (Android) | AVI, MKV, M2TS, MOV, MP4, TS, WebM | H.264, HEVC, VP8, VP9, AV1, MPEG-2, MPEG-4 | AAC, AC-3, E-AC-3, DTS, FLAC, MP3, Opus, PCM, TrueHD, Vorbis |
| LibVLC (Android) | MP4, MOV, MKV, WebM, TS, AVI | H.264, HEVC, MPEG-1, MPEG-2, VP8, VP9, AV1 | AAC, AC-3, E-AC-3, MP3, FLAC, Opus, Vorbis |
| AVPlayer (iOS, Apple TV) | MP4, M4V, MOV, TS | H.264, HEVC, AV1 | AAC, MP3, AC-3, E-AC-3, FLAC, ALAC |
| VLCKit (iOS) | MP4, M4V, MOV, MKV, WebM, TS, M2TS, AVI, FLV, 3GP, OGV, ASF, WMV | H.264, HEVC, VP8, VP9, MPEG-1/2/4, VC-1, WMV, ProRes, Theora, MJPEG, AV1, and more | AAC, AC-3, E-AC-3, DTS, MP1–MP3, FLAC, ALAC, Opus, Vorbis, PCM, WMA, and more |
| macOS (both players) | MP4, M4V, MOV, MKV, WebM, TS, AVI | H.264, HEVC, VP9, AV1 | AAC, MP3, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis, PCM |

On Android, actual support depends on the selected player and the device's
decoders. mpv does not claim HDR, Dolby Vision, or passthrough; LibVLC may use
software decoding. On Apple devices, AV1, 10-bit video, and HDR are requested
only when the hardware and display support them.

### Picture and display

- **HDR** — *Auto HDR* uses HDR where your display reports it and falls back to
  SDR otherwise; *Prefer SDR* always asks for SDR. Dolby Vision is used only
  where the device genuinely supports it.
- **Match display refresh rate** (Android TV) switches your TV to a refresh rate
  that suits the video, removing judder on 24fps films. The display is always
  restored afterwards.
- Playback progress reports back to Jellyfin as you watch, so Continue Watching
  stays in step across devices.

### Audio

- **Output** — *Auto*, *Stereo PCM*, or *Passthrough when supported*.
  Passthrough is offered only when the output you're using reports it, and an
  option your current setup can't do stays saved with an explanation rather than
  changing silently. **Refresh capabilities** re-checks your TV, receiver, or
  headphones.
- **Track selection** is remembered per title and version.
- **Audio failure** keeps the video playing with a muted icon; choosing a track
  again, or playing something else, retries audio.
- **Out of sync?** The track picker's **Offset** entry shifts audio up to ±20
  seconds, remembered for that title and track.

### Subtitles

Text formats: SubRip/SRT, WebVTT, ASS/SSA, and TTML on Android. Image formats:
PGS, VobSub/DVD, DVB. Subtitles are either rendered by the app over the video,
or rendered into the video by your server — which requires converting the video.

| Player | Text | Image (PGS, VobSub, DVB) |
| --- | --- | --- |
| ExoPlayer (Android) | App-rendered, from inside the file or a separate subtitle file | App-rendered for most formats, otherwise server-rendered |
| mpv (Android) | App-rendered from inside the file, same-origin remote, or project-owned local subtitle file | App-rendered for supported bitmap tracks, otherwise server-rendered |
| LibVLC (Android) | App-rendered from inside the file only; separate subtitle files fall back to server-rendered | PGS app-rendered, otherwise server-rendered |
| AVPlayer (iOS, Apple TV) | WebVTT inside the file is app-rendered during direct play; SRT, ASS/SSA, and TTML are server-rendered | Server-rendered |
| VLCKit (iOS) | App-rendered from inside the file, including ASS/SSA; separate subtitle files fall back to server-rendered | App-rendered from inside the file, otherwise server-rendered |
| mpv (macOS) | App-rendered, including separate subtitle files | App-rendered |
| LibVLC (macOS) | App-rendered from inside the file; separate subtitle files attach when the player accepts them, otherwise server-rendered | App-rendered |

**Styled ASS/SSA.** These subtitles can include typeset signs, karaoke, and
bundled fonts. ExoPlayer keeps only basic position, alignment, colour, size,
bold, and italic styling; AVPlayer does not render these files directly. For the
intended look, use mpv or LibVLC on Android, VLCKit on iPhone or iPad with the
track stored inside the file, or let the server render it through video
conversion.

**Selection, size, timing.**

- **Off is always available**, even with a single subtitle track. Your choice
  is remembered per title and version. Resolution order: your last choice for
  that title, your preferred subtitle language from Settings, the server's
  default track, then off.
- **Size** — Small, Normal, or Large — is offered whenever the app renders the
  subtitles and the player can apply it; absent on Android's LibVLC (which uses
  the track's own size) and on server-rendered subtitles.
- **Out of sync?** The same **Offset** entry, up to ±20 seconds. ExoPlayer can
  only delay subtitles; LibVLC and the macOS players shift both directions.
- A subtitle track that fails to appear never becomes a playback error: the film
  keeps playing and JellyScope switches that track to server-rendered once.

**Downloading subtitles (alpha).** This hasn't been tested yet. Add your own
OpenSubtitles consumer key in Settings, then search from a movie or a specific
episode. Single-file SRT and WebVTT results install; image-based and multi-part
results don't. Downloads stay on the device after you sign out until you remove
them, and where your server allows it they're uploaded there too.

### Skipping, chapters, and autoplay

- **Chapters** come from your server; the picker follows along and is hidden for
  titles without them.
- **Skip segments** — intros, credits, recaps, previews, commercials — each set
  to **Auto-skip**, **Ask** (a Skip button), or **Ignore**. Ask is the default.
  Auto-skip fires once per segment, so rewinding into an intro won't yank you
  forward again.
- **Autoplay next** is on with a 10-second countdown, adjustable from 0 to 60
  seconds. *Up Next* can appear before the end, but the countdown starts only
  when the episode finishes. *Not now* closes the player; with autoplay off,
  *Play now* is still there.
- After three consecutive automatic episodes, JellyScope asks **Still
  watching?** Choosing episodes yourself doesn't count toward that, and the
  prompt can be switched off in Settings.

### Quality

**Maximum bitrate** in Settings sets a ceiling; `Original` streams at full
quality. A choice made in the player lasts only for that playback, while a new
session starts from your saved default. If playback keeps stalling — or Android
keeps dropping frames — Auto chosen in the player may step down once. Default
Auto asks before changing quality. You can then keep it, try higher, or choose
another level.

## More documentation

- [CHANGELOG.md](CHANGELOG.md) — what changed in each release.
- [docs/README.md](docs/README.md) — the documentation map: which file owns
  what.
- [docs/BUILD.md](docs/BUILD.md) — building and running from source.
- [CONTRIBUTING.md](CONTRIBUTING.md) — working on JellyScope.

## License

JellyScope-owned source is licensed under `MPL-2.0`; see [LICENSE](LICENSE).
Bundled and imported third-party components retain their own licenses.
