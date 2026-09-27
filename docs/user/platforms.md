# Platform And Player Differences

[All user guides](../USAGE.md)

On Android TV, **Settings → Advanced playback → mpv video output** selects
**GPU (default)** or **Direct MediaCodec** for the next mpv playback session.
GPU supports subtitles and Fit/Fill/Zoom controls. Direct MediaCodec requires
hardware decoding and sends video directly to the display; separate subtitle
tracks and sizing controls are unavailable. Server-rendered subtitles, when
available, remain visible because they are part of the video.

For session-only player switching, see [playback choices](playback.md#choose-a-player).
Format acceptance does not guarantee smooth decoding; an alternate player may
use software decoding.

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
the device and player, not just the source format. The [player limits](playback.md#choose-a-player) still apply.

On Android TV, **Settings → Advanced playback → Match display refresh
rate** is off by default. When enabled, playback requests a suitable display
cadence and releases JellyScope's display-mode request afterwards so the
operating system can choose the next mode. A successful request does not
guarantee judder-free playback on every TV.

## Support and capability limits

Android mobile and Android TV are the primary platforms. The Apple TV preview
is permanently unsupported, without a hardware-validation or feature-parity
commitment. Windows and Linux have experimental source-build paths only; macOS
packages require Apple Silicon. See [platform requirements](../../README.md#platforms)
and the [player limits table](playback.md#choose-a-player).

A player accepting a container or codec does not guarantee smooth decoding.
The selected player, device decoder, output route, and media profile all matter.
For implementation details, see the engineering [capability declarations](../engineering/playback-policy.md#representative-direct-play-declarations).
