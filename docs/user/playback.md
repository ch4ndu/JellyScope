# Playback Choices And Controls

[All user guides](../USAGE.md)

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

During online playback, the video-camera button switches player for the current
session on Android, iPhone/iPad and macOS. It is hidden offline. The switch
preserves the selected source and tracks; it does not change the saved player
preference. See [platform differences](platforms.md) for output and PiP limits.

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
