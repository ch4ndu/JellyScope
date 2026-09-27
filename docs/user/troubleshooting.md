# Diagnostics And Troubleshooting

[All user guides](../USAGE.md)

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

## Include useful evidence

Include the app version, platform, selected player, the action that failed, and
whether the issue affects online or downloaded playback. In the player, the bug
icon opens **Playback info**, which shows the active player, stream decision,
server reasons, codecs and runtime information. Describe what you observed;
format acceptance alone does not establish successful decoding or presentation.

If a download fails, use **Retry** for that item; bulk **Resume** applies to
paused items. For storage, permission and background-execution limits, see
[downloads](downloads.md). For subtitle selection, local files, and upload state,
see [audio and subtitles](subtitles.md).
