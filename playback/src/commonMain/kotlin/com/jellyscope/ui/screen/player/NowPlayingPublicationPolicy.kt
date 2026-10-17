// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackStatus

internal const val NOW_PLAYING_POSITION_PUSH_DRIFT_MS = 5_000L

internal data class NowPlayingSnapshot(
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    val status: PlaybackStatus,
    val positionMs: Long,
    val playbackSpeed: Float,
)

internal sealed interface NowPlayingPublication {
    data object Clear : NowPlayingPublication

    data object NoChange : NowPlayingPublication

    data object PublishFull : NowPlayingPublication
}

internal fun nowPlayingPlaybackRate(
    status: PlaybackStatus,
    playbackSpeed: Float,
): Double =
    if (status == PlaybackStatus.Playing) {
        playbackSpeed.toDouble()
    } else {
        0.0
    }

internal fun nowPlayingPublicationDecision(
    previous: NowPlayingSnapshot?,
    next: NowPlayingSnapshot?,
): NowPlayingPublication {
    if (next == null) {
        return NowPlayingPublication.Clear
    }

    val previousPositionMs = previous?.positionMs ?: Long.MIN_VALUE
    val changedEdge =
        previous == null ||
            next.title != previous.title ||
            next.artist != previous.artist ||
            next.durationMs != previous.durationMs ||
            next.status != previous.status ||
            next.playbackSpeed != previous.playbackSpeed
    val positionJumped =
        previousPositionMs == Long.MIN_VALUE ||
            kotlin.math.abs(next.positionMs - previousPositionMs) > NOW_PLAYING_POSITION_PUSH_DRIFT_MS

    return if (changedEdge || positionJumped) {
        NowPlayingPublication.PublishFull
    } else {
        NowPlayingPublication.NoChange
    }
}
