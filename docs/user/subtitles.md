# Audio And Subtitles

[All user guides](../USAGE.md)

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
[Download for offline playback](downloads.md#start-and-manage-downloads). JellyScope
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
