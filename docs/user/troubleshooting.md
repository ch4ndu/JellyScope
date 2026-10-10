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

The regular diagnostics report includes a sanitized app/device/OS snapshot and, when available,
decoder and recent playback-failure facts. **Collect diagnostic logs** controls
only the retained history added to it. On Android mpv, collection also keeps a
size-capped raw verbose file on-device; uploads exclude it and send only bounded,
structured, scrubbed data. If upload is
disabled or fails, report that result with reproduction and environment details.

## Save a playback timeline

Turn on **Collect diagnostic logs**, reproduce the playback problem, then return
to **Settings → Diagnostics → Save playback timeline**. On Android phones and
tablets, iOS and desktop, choose a destination in the native save picker. The
app creates `jellyscope-playback-timeline.json` directly; no offline converter is
needed. The JSON can be opened in a compatible trace viewer such as Perfetto.
Choosing an external viewer and sharing the file remain your decisions.

Android TV offers Save where a document provider is installed, plus a separate
**Send playback timeline to server** action. If saving is unavailable, use that
explicit send action. Apple TV offers sending only. Sending requires the same
server permission as regular diagnostics. Keep its returned filename; the
server may name the JSON file with a `.log` extension. It can be retrieved from
the server and opened as trace JSON without conversion.

Timelines contain bounded, sanitized playback events and coverage markers.
They may omit older events, and missing boundaries do not become measured
durations. If no timed events are available, keep collection enabled and
reproduce playback again. Saving or sending a timeline leaves the retained
history available for a later regular diagnostics report.

Collection is off by default. Turning it off deletes retained app history and
invalidates pending exports, but cannot remove a copy already handed to the OS
or server. If Android interrupts the app while its picker is open, the app
reports an interrupted export instead of rebuilding a different report; the
destination may contain an empty file. A failed write may leave an incomplete
file. On iOS, a staging-cleanup failure can be reported after the selected
destination already received a copy. Check that destination before retrying.

## Include useful evidence

Include the app version, platform, selected player, the action that failed, and
whether the issue affects online or downloaded playback. In the player, the bug
icon opens **Playback info**, which shows the active player, stream decision,
server reasons, codecs and runtime information. Describe what you observed;
format acceptance alone does not establish successful decoding or presentation.

**Renderer target** shows HDR, SDR, SDR with HDR input observed, or Unknown.
Android and desktop mpv supply this observation when available; other players
currently show Unknown. Unknown does not mean SDR. The row describes the
player's renderer target, not confirmation of the physical display's HDR mode
or the quality of tone mapping.

If a download fails, use **Retry** for that item; bulk **Resume** applies to
paused items. For storage, permission and background-execution limits, see
[downloads](downloads.md). For subtitle selection, local files, and upload state,
see [audio and subtitles](subtitles.md).
