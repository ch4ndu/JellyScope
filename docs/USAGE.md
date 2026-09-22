# Using JellyScope

Install or upgrade first with [Installing and upgrading](BUILD.md#installing-and-upgrading).
Here, Android TV also means Google TV and Fire TV.

## Connect, browse, and find content

Add a server by discovery where available or enter its URL, then use a password
or Quick Connect. For multiple accounts, use **Switch** on phones, tablets, and
macOS; **Switch Users** on Android TV; or **Settings → Manage Accounts** on
Apple TV. The TV tile opens **Switch account**.

Choose a library from the **Library** title on phones, tablets, and macOS, or
from the Android TV drawer. Movie and show libraries offer **Recommended** and
**Library**. In **Library**, use **Sort** and **Filters**; deselect filters and
choose **Apply**, or use Android TV's **Clear filters**. Available choices vary
by library. Touch/desktop layouts also offer grid and list modes.
**Appearance → Remember last library view** controls restoration.

Detail pages provide **Favorite** or **Unfavorite** and **Mark watched** or
**Mark unwatched**. Favorites appear on Home and through the library's favorite
filter. Apple TV also has a dedicated **Favorites** tab. Changing watched state
can change whether a title appears in **Continue Watching**.

**Find** searches titles, collections, and people on shared layouts and Android
TV. Phones, tablets, and macOS also offer mood/genre, runtime, watched, and
person filters; cast links open person pages. **Discover** contains genres,
studios, collections, suggestions, and upcoming episodes. Apple TV instead has
Home, Favorites, Libraries, Search, Downloads, and Settings tabs; Libraries
includes sort/filter views, Search accepts text/year, person, genre, runtime,
and watched filters, and there is no separate Discover tab.

### Choose a media version

On phones, tablets, macOS, Android TV, and Apple TV, a movie or episode with at
least two valid sources shows **Version** on its detail page. Choose the version
before **Play** or **Download**, then check the audio tracks, subtitle tracks,
and media information for that source. The choice applies to the current detail
and playback session; it is not a global preferred-version setting. The picker
is hidden when only one source is available. For a series or season, make the
choice on the concrete episode where it is offered.

## Download for offline playback

Downloads are available on Android, iPhone, iPad, macOS, and the Apple TV
preview when the account has **Content downloading** permission. Without it,
the destination and item actions are hidden.

JellyScope does not continuously refresh that permission in a signed-in
session. If permission is granted later, open Settings, choose
**Add account**, and authenticate the same server account again. This replaces
the stored session for that account and preserves its downloads. Do not use
**Remove account**, **Sign out**, or Android TV **Logout** merely to
refresh permission: those actions remove the affected downloaded media after
confirmation.

Before the first download, set an allocation:

1. Open **Downloads** and choose **Manage allocated size** on touch layouts or
   **Manage allocation** on Android TV, or **Download Storage** on Apple TV.
2. Choose how much storage JellyScope may use. A fresh installation shows
   **Quota not configured** and will not start a download until this is set.
3. On Android 13 or newer, JellyScope asks for notification permission when a
   download is confirmed while that permission is missing. Denying it hides
   download notifications; it does not cancel or prevent the download itself.

To download a movie or a specific episode:

1. Open its detail page and choose **Download**.
2. Choose **Original (exact source)** to save the server file unchanged, or
   **Fixed converted** to request the selected converted quality. Choose the
   audio track. Original also offers eligible subtitle choices. Fixed offers
   **Off** or a compatible embedded text subtitle; after confirmation, the
   selected subtitle is permanently burned into the converted picture and
   cannot be switched off during offline playback.
3. Select **Review download size**, check the estimate, and choose
   **Start download**.
4. Follow progress in **Downloads**. A paused item offers **Resume** and a
   failed item offers **Retry**. The bulk **Resume** action resumes paused
   items; it does not silently retry failures.

A completed item offers **Play offline**, or **Resume** after local progress, in
the touch Downloads screen; Android TV labels the action **Play**.
Its detail page offers **Play offline**. These actions use the saved copy. The
ordinary **Play** action on a detail page follows the server playback path even
when a completed download exists. Offline playback retains resume progress on
this device.
On Android, iOS, and macOS, downloaded playback can also make best-effort live
server reports when connected to the matching account. Missed reports are not
automatically replayed or synchronized. Apple TV offline progress stays local.

On iPhone, iPad, and Apple TV, offline playback requires VLCKit for that session.
This does not change any saved online player preference.
If VLCKit is unavailable, the offline session reports an error instead of
silently switching to AVPlayer.

If allocation is full, increase it or delete completed downloads, then resume
the paused item. Opening **Downloads** wakes eligible queued work on Android,
iOS, and macOS without overriding an explicit pause. Android can schedule work
with the operating system.

On iOS 26+, an explicit **Start download**, **Resume**, bulk **Resume**, or
**Retry** for either Original or Fixed requests continued-processing time. Work
can continue after backgrounding only when iOS grants that request. Expiration
or interruption checkpoints active work to Paused; return and choose **Resume**.
Earlier iOS versions, or iOS without a grant, require JellyScope to remain
active. This does not promise completion or survival after process death or a
force quit.

On macOS, transfers run while JellyScope is open. Launch recovers checkpointed
state, and opening **Downloads** wakes eligible queued work; an explicit pause
still requires **Resume**. On Apple TV, choose **Resume Queued Downloads**;
opening or refreshing the screen is passive, transfers run only while the app
is active, and tvOS may reclaim saved copies.

Switching accounts preserves downloads. On phone, tablet and desktop layouts,
**Remove account** and the global **Sign out** flow show the affected download
count and size before confirmation, then remove downloaded media for the
affected account or accounts. On Android TV, use **Switch Users** to
switch or **Logout** to sign out; logout confirms removal when downloaded media
is present. Android TV does not offer per-account removal. Apple TV offers
confirmed per-account removal through **Manage Accounts**, including the
affected download count and size.
OpenSubtitles files have a separate lifecycle and are removed only through
their own delete or clear actions.

## Choose a player

| Platform | Player | Useful for | Limits |
| --- | --- | --- | --- |
| Android | ExoPlayer (default) | Device decoding, HDR, passthrough | Styled ASS/SSA is only partly rendered |
| Android | mpv | More containers/codecs, styled text, bitmap subtitles | API 26+; no HDR, Dolby Vision, or passthrough |
| Android | LibVLC (beta) | Unusual media and styled ASS/SSA | No HDR or passthrough; demanding software decode can be slow |
| iPhone/iPad | AVPlayer (default) | Efficiency and system integration | Narrow direct-play and subtitle range |
| iPhone/iPad | VLCKit (beta) | MKV and other uncommon media | No TrueHD or HDR |
| macOS | mpv (default) | Broad format range, including TrueHD | HDR is tone-mapped; continuous pointer movement can affect very-high-resolution playback |
| macOS | LibVLC (beta) | Broad bundled fallback | Requests HDR-to-SDR conversion and has its own input ceiling |
| Apple TV preview | AVPlayer online; VLCKit offline | Native online controls and explicit downloaded playback | Permanently unsupported alpha; no hardware-validation commitment |

On Android TV, **Settings → Advanced playback → mpv video output** selects
**GPU (default)** or **Direct MediaCodec** for the next mpv playback session.
GPU supports subtitles and Fit/Fill/Zoom controls. Direct MediaCodec requires
hardware decoding and sends video directly to the display; separate subtitle
tracks and sizing controls are unavailable. Server-rendered subtitles, when
available, remain visible because they are part of the video.

During online video on Android, iPhone, iPad, or macOS, use the video-camera
button to change player for this session. It is hidden offline, and switching
requires preserving the chosen source and tracks. Format acceptance does not
guarantee smooth decoding; an alternate player may use software decoding.

### Direct play and conversion

- **Direct play** streams the source unchanged.
- **Audio-only conversion** preserves the video while the server converts the
  audio.
- **Conversion (transcode)** makes the server re-encode video, using server
  resources and possibly reducing quality. Subtitle burn-in requires it.

The player's **Playback info** panel, opened with the bug icon, reports the
active player, stream decision and server reasons, codecs, bitrate, decoder,
resolution, frame rate, dropped frames, and bandwidth estimate.

### Representative direct-play declarations

These are representative formats each player requests directly. Device
decoders, output capabilities, media profiles, and stream details can still
cause conversion or an unsupported result.

| Player | Containers | Video | Audio |
| --- | --- | --- | --- |
| ExoPlayer (Android) | MP4, M4V, MOV, MKV, WebM, TS | H.264, HEVC, VP9, AV1 | AAC, MP3, AC-3, E-AC-3, DTS, FLAC, Opus, Vorbis, PCM |
| mpv (Android) | AVI, MKV, M2TS, MOV, MP4, TS, WebM | H.264, HEVC, VP8, VP9, AV1, MPEG-2, MPEG-4 | AAC, AC-3, E-AC-3, DTS, FLAC, MP3, Opus, PCM, TrueHD, Vorbis |
| LibVLC (Android) | MP4, MOV, MKV, WebM, TS, AVI | H.264, HEVC, MPEG-1, MPEG-2, VP8, VP9, AV1 | AAC, AC-3, E-AC-3, MP3, FLAC, Opus, Vorbis |
| AVPlayer (iOS, Apple TV) | MP4, M4V, MOV, TS | H.264, HEVC, AV1 | AAC, MP3, AC-3, E-AC-3, FLAC, ALAC |
| VLCKit (iOS) | MP4, M4V, MOV, MKV, WebM, TS, M2TS, AVI, FLV, 3GP, OGV, ASF, WMV | H.264, HEVC, VP8, VP9, MPEG-1/2/4, VC-1, WMV, ProRes, Theora, MJPEG, AV1, and more | AAC, AC-3, E-AC-3, DTS, MP1–MP3, FLAC, ALAC, Opus, Vorbis, PCM, WMA, and more |
| macOS (both players) | MP4, M4V, MOV, MKV, WebM, TS, AVI | H.264, HEVC, VP9, AV1 | AAC, MP2, MP3, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis, PCM |

On Android, support depends on the selected player and device decoders. On
Apple devices, the hardware AV1 check applies to AVPlayer. VLCKit declares AV1
without that hardware gate and does not claim HDR support.

## Set compatibility and quality

On iPhone, iPad, and macOS, open **Settings → Advanced playback → Playback
compatibility**:

- **Standard - compatible limits (recommended)** applies a conservative app
  safety envelope. On iPhone and iPad it limits unchanged-source attempts to a
  4K/30-equivalent input for both AVPlayer and VLCKit. On macOS it gives LibVLC
  a 4K/60-equivalent envelope; mpv is unaffected. The iOS policy can therefore
  convert a 4K60 source even when the hardware may be capable; it is a product
  safety policy, not a measurement of that device's decoder.
  Auto and eligible fixed-quality playback can request conversion when a source
  exceeds this envelope. **Original** instead reports Unsupported Media when
  unchanged-source playback is rejected.
- **Unrestricted (experimental)** removes only that app envelope for a
  deliberate unchanged-source attempt. It does not add hardware decoding,
  expand a player's real codec support, or guarantee smooth playback.

**Maximum bitrate** sets the saved ceiling; **Original** requests full source
quality. An in-player choice lasts for that session. Saved or inherited **Auto**
does not authorize a quality drop after runtime trouble; explicitly choosing
**Auto** in the player authorizes one bounded lower-quality recovery attempt.

**Playback warnings (beta)** is off by default. Enable **Settings → Playback →
Playback warnings (beta)** for actions such as **Use Auto**, **Keep this
quality**, **Choose lower quality**, **Try higher**, or **Playback settings**.
The switch hides optional prompts, not decisions already allowed by the quality
mode or fatal errors.

## Picture and display

Enable **Settings → Advanced playback → Picture-in-Picture** on Android phones
and tablets, iPhone, or iPad; start eligible playback, then leave JellyScope.
Android requires API 26+, device support, and system permission. The player,
playback state, or operating system may still refuse entry.

On iPhone and iPad, AVPlayer marks a transcoded PiP session as linear, so the
system does not offer seeking for that transcode. VLCKit uses a separate
experimental PiP path; seeking may close PiP or interrupt playback. macOS does
not expose the app's PiP setting. On Android TV, Home stops playback
and exits the player. This guide does not certify PiP behavior in the unsupported
Apple TV preview.

**Auto HDR** uses the HDR capabilities reported by the display and selected
player; **Prefer SDR** requests SDR instead. Dolby Vision likewise depends on
the device and player, not just the source format. The player limits above
still apply.

On Android TV, **Settings → Advanced playback → Match display refresh
rate** is off by default. When enabled, playback requests a suitable display
cadence and releases JellyScope's display-mode request afterwards so the
operating system can choose the next mode. A successful request does not
guarantee judder-free playback on every TV.

## Audio and subtitles

Choose **Auto**, **Stereo PCM**, or **Passthrough when supported** for audio
output. Passthrough is offered only when the current output reports it. An
unavailable saved choice remains visible with an explanation, and **Refresh
capabilities** rechecks the TV, receiver, or headphones. On Android ExoPlayer,
an exhausted audio-output failure can leave the video playing with a muted
indicator. Other player or exact-track activation failures may retry with a
new server plan or end playback. Choosing a track again or opening another
title makes a new audio attempt. Audio track selection is remembered per title
and version.

JellyScope handles SubRip/SRT, WebVTT, ASS/SSA, and TTML text on Android, plus
PGS, VobSub/DVD, and DVB image subtitles. The table describes subtitle tracks
negotiated with the Jellyfin server while video is streamed from it:

| Player | Jellyfin text tracks | Jellyfin image tracks |
| --- | --- | --- |
| ExoPlayer (Android) | Embedded or separate files are app-rendered; partial ASS/SSA styling | Usually app-rendered, otherwise server-rendered |
| mpv (Android) | Embedded and same-origin separate files are app-rendered with GPU output | App-rendered with GPU output when supported, otherwise server-rendered |
| LibVLC (Android) | Embedded text is app-rendered; separate files are server-rendered | PGS is app-rendered; other image formats are server-rendered |
| AVPlayer (iOS, Apple TV) | Embedded WebVTT is app-rendered; SRT, ASS/SSA, and TTML are server-rendered | Server-rendered |
| VLCKit (iOS) | Embedded text, including ASS/SSA, is app-rendered; separate files are server-rendered | Embedded tracks are app-rendered; separate files are server-rendered |
| mpv (macOS) | App-rendered, including separate files | App-rendered |
| LibVLC (macOS) | Embedded text is app-rendered; separate files attach when accepted, otherwise they are server-rendered | App-rendered |

For selectable Jellyfin and app-local subtitle tracks, **Off** is available,
and the selected track is remembered per title and version. This selection
cannot remove subtitles already burned into a **Fixed converted** download; see
[Download for offline playback](#download-for-offline-playback). JellyScope
resolves subtitles from the last title choice, preferred language, server
default, then off. **Small**, **Normal**, and **Large** apply when JellyScope
controls rendering; Android LibVLC and server-rendered tracks do not expose
this size control.

The track picker's **Offset** action is available only where the active player
implements it:

| Player | Audio offset | Subtitle offset |
| --- | --- | --- |
| ExoPlayer (Android) | ±20 seconds | Delay only, up to 20 seconds |
| mpv or LibVLC (Android) | ±20 seconds | ±20 seconds |
| mpv (macOS) | ±20 seconds | ±20 seconds |
| AVPlayer or VLCKit (iOS), LibVLC (macOS), Apple TV preview | Unavailable | Unavailable |

Supported offsets are remembered for the account, title/version and selected
track. JellyScope restores the value accepted by the player when preparing
that track again.

When a Jellyfin subtitle track cannot render locally, JellyScope can retry with
the server rendering that eligible track into converted video. A Jellyfin track
that cannot be represented to the server is marked unavailable. App-local
OpenSubtitles files and downloaded offline sidecars use a separate attachment
path: JellyScope asks the active player to attach them, and marks a file
unavailable if the player cannot. In either case the video keeps playing and
JellyScope does not silently choose a different subtitle. A local or offline
file cannot be sent to the server for a burn-in fallback.

### OpenSubtitles downloads (alpha)

Live validation is pending.
To configure it:

1. On touch layouts, open **Settings → Services → OpenSubtitles consumer key**.
   On Android TV, use **Settings → Services & About**. Enter a
   consumer API key. On Apple TV, use **Settings → Subtitles → OpenSubtitles key**.
2. On phones, tablets, and macOS, optionally set **Subtitle result preference**.
   Preferred results move earlier; other results are not filtered out. Android
   TV does not expose this preference control; Apple TV includes it in Subtitles
   settings.
3. Open a movie or a specific episode, open its subtitle picker, and choose
   **Search subtitles**.
4. Choose a single-file SRT or WebVTT result. Image-based and multi-file results
   are not selectable.

JellyScope installs the normalized file locally, then tries to upload it when
the account may edit subtitles. Forbidden uploads and non-primary-version files
remain local. For an uploaded file the server did not confirm, select the local
track and choose **Retry Jellyfin sync**; that action appears only in this state.

Use **Delete selected download** for one local file. To remove all local files,
use **Settings → Services → Clear local subtitles** on touch layouts or
**Settings → Services & About → Clear downloaded subtitles** on Android TV.
These actions do not delete copies already uploaded to Jellyfin; local files
survive account switching/removal and sign-out until cleared. Apple TV offers
search from detail/online playback, local Select/Delete/Retry Sync, and confirmed
clearing in Subtitles settings. Offline playback has no remote search or file import.

## Queue, chapters, skipping, and autoplay

- On shared layouts and Android TV, **Shuffle All** in a movie library creates a
  queue from current filtered results. A standalone item need not expose one.
- On phones, tablets, and macOS, open **Queue** in the player and select an item
  to play it. **Shuffle queue** keeps the current item first and randomizes all
  other queued items.
- On Android TV, press Down from controls for **Up Next**, Up to return, and
  select a card to play; the ribbon has no **Shuffle queue**. Apple TV's native
  **Queue** offers selection, previous/next, and shuffle; next-up offers **Play
  Now** and **Dismiss**. Apple TV has no library **Shuffle All**.
- The chapter picker follows server-provided chapters and is hidden when none
  exist.
- Intro, credits, recap, preview, and commercial segments can be set to
  **Auto-skip**, **Ask**, or **Ignore**. **Ask** is the default. Auto-skip fires
  once for each segment during that playback.
- **Autoplay next** uses an adjustable 0–60 second countdown, initially 10
  seconds. **Up Next** may appear earlier, but its countdown starts only after
  the current item completes. **Play now** starts the next item. **Not now**
  dismisses the Up Next card and cancels its countdown while the player remains
  open; **Back** exits playback.
- The separate **Still watching?** prompt appears after three consecutive
  automatically started episodes when enabled. Dismissing that prompt follows
  its own exit behavior.

## Diagnostics and troubleshooting

For a reproducible problem, open **Settings → Diagnostics**:

1. Turn on **Collect diagnostic logs** before reproducing the problem. Collection
   starts after the switch is enabled; turning it off deletes the retained log
   history.
2. Reproduce the problem, return to Diagnostics, and choose **Send diagnostics
   to server**. The Apple TV preview labels this action **Send Client Logs**.
3. Save the returned server filename and include it with the bug report. The
   upload goes to the connected Jellyfin server. The server must
   enable **Allow client log upload** (`EnableClientLogUpload`) before
   JellyScope can send it.

Every send includes a sanitized app/device/OS snapshot and, when available,
decoder and recent playback-failure facts. **Collect diagnostic logs** controls
only the retained history added to it. On Android mpv, collection also keeps a
size-capped raw verbose file on-device; uploads exclude it and send only bounded,
structured, scrubbed data. There is no local crash-report export. If upload is
disabled or fails, report that result with reproduction and environment details.

## Playback controls

| Input | Action |
| --- | --- |
| Touch, single tap | Show or hide controls |
| Touch, double tap left or right | Seek back or forward 10 seconds |
| Touch, hold for 500 ms | Play at 2× speed until released |
| Touch, vertical swipe on Android | Brightness on the left; volume on the right |
| Desktop mouse, double click | Toggle fullscreen |
| Desktop keyboard, Space | Play or pause |
| Desktop keyboard, Left / Right | Seek back or forward 10 seconds; hold to repeat |
| Desktop keyboard, Up / Down | Change volume by 5% when the player exposes volume control |
| Desktop keyboard, M | Toggle mute |
| Desktop keyboard, Escape | Exit fullscreen first; otherwise go back |
| Android TV remote, dedicated media keys | Play/pause and transport control in the player |
| Android TV browse tile, Play / Play-Pause | Resume or play a movie or episode directly |
| Android TV browse tile, Select | Open the title's detail page |

The Apple TV preview uses native SwiftUI controls, AVKit online playback, and
VLC offline playback. The Android TV key mappings in this table do not describe
or certify Siri Remote behavior.
