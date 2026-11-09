# Container, video and audio compatibility

[All user guides](../USAGE.md)

These are representative formats each player can request without server
conversion. Device support and the particular media file may still require
conversion or prevent playback.

| Player | Containers | Video | Audio |
| --- | --- | --- | --- |
| ExoPlayer (Android) | MP4, M4V, MOV, MKV, WebM, TS | H.264, HEVC, VP9, AV1 | AAC, MP3, AC-3, E-AC-3, DTS, FLAC, Opus, Vorbis, exact PCM (8 names) in MKV only |
| mpv (Android) | AVI, MKV, M2TS, MOV, MP4, TS, WebM | H.264, HEVC, VP8, VP9, AV1, MPEG-2, MPEG-4 | AAC, AC-3, E-AC-3, DTS, FLAC, ALAC, MLP, MP3, Opus, exact `pcm_*` formats, TrueHD, Vorbis |
| LibVLC (Android) | MP4, MOV, MKV, WebM, TS, AVI | H.264, HEVC, MPEG-1, MPEG-2, VP8, VP9, AV1 | AAC, AC-3, E-AC-3, MP3, FLAC, Opus, Vorbis, ALAC, MLP, TrueHD, exact PCM formats |
| AVPlayer (iOS, Apple TV) | MP4, M4V, MOV, TS | H.264, HEVC, AV1 | AAC, MP3, AC-3, E-AC-3, FLAC, ALAC |
| VLCKit (iOS) | MP4, M4V, MOV, MKV, WebM, TS, M2TS, AVI, FLV, 3GP, OGV, ASF, WMV | H.264, HEVC, VP8, VP9, MPEG-1/2/4, VC-1, WMV, ProRes, Theora, MJPEG, AV1, and more | AAC, AC-3, E-AC-3, DTS, MP1–MP3, FLAC, ALAC, Opus, Vorbis, PCM, WMA, and more |
| Bundled macOS mpv | MP4, M4V, MOV, MKV, WebM, TS, AVI | H.264, HEVC, VP9, AV1 | AAC, MP2, MP3, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis, 35 exact PCM formats |
| Other desktop mpv runtimes | MP4, M4V, MOV, MKV, WebM, TS, AVI | H.264, HEVC, VP9, AV1 | AAC, MP2, MP3, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis |
| Bundled macOS LibVLC | MP4, M4V, MOV, MKV, WebM, TS, AVI | H.264, HEVC, VP9, AV1 | AAC, MP2, MP3, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis, 23 exact PCM formats |
| Installed desktop LibVLC | MP4, M4V, MOV, MKV, WebM, TS, AVI | H.264, HEVC, VP9, AV1 | AAC, MP2, MP3, AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis |

On Android, support depends on the selected player and device decoders. On
Apple devices, the hardware AV1 check applies to AVPlayer. VLCKit declares AV1
without that hardware gate and does not claim HDR support.

Format acceptance does not guarantee smooth decoding, hardware acceleration,
HDR output or audio passthrough. Device, output route and media profile matter.
See [player choices](playback.md#choose-a-player) and [platform differences](platforms.md).
