// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackRuntimeDiagnostics
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlayerBackend

internal fun tvPlaybackDiagnosticRows(
    backend: PlayerBackend,
    plan: PlaybackPlan?,
    playbackState: PlaybackState,
    runtime: PlaybackRuntimeDiagnostics,
): List<TvPlaybackDiagnosticRow> =
    buildList {
        add(TvPlaybackDiagnosticRow(TvPlaybackDiagnosticField.Backend, backend.name))
        add(TvPlaybackDiagnosticRow(TvPlaybackDiagnosticField.PlayMethod, plan?.streamMode?.name))
        add(TvPlaybackDiagnosticRow(TvPlaybackDiagnosticField.Status, playbackState.status.name))
        add(
            TvPlaybackDiagnosticRow(
                TvPlaybackDiagnosticField.RuntimeFormat,
                runtimeFormat(runtime),
            ),
        )
        add(
            TvPlaybackDiagnosticRow(
                TvPlaybackDiagnosticField.DroppedFrames,
                runtime.droppedVideoFrames?.toString(),
            ),
        )
        add(
            TvPlaybackDiagnosticRow(
                TvPlaybackDiagnosticField.Bandwidth,
                runtime.bandwidthEstimateBps?.toString(),
            ),
        )
        add(
            TvPlaybackDiagnosticRow(
                TvPlaybackDiagnosticField.BufferedAhead,
                runtime.bufferedAheadMs?.toString(),
            ),
        )
        add(
            TvPlaybackDiagnosticRow(
                TvPlaybackDiagnosticField.Rebuffers,
                runtime.rebufferCount?.toString(),
            ),
        )
        add(
            TvPlaybackDiagnosticRow(
                TvPlaybackDiagnosticField.AudioUnderruns,
                runtime.audioUnderrunCount?.toString(),
            ),
        )
    }

private fun runtimeFormat(runtime: PlaybackRuntimeDiagnostics): String? {
    val width = runtime.videoWidth
    val height = runtime.videoHeight
    val frameRate = runtime.videoFrameRate
    val size = if (width != null && height != null) "${width}x$height" else null
    return when {
        size != null && frameRate != null -> "$size @ $frameRate fps"
        size != null -> size
        frameRate != null -> "$frameRate fps"
        else -> null
    }
}
