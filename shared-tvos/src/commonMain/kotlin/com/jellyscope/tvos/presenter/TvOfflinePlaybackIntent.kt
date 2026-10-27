// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvOfflinePlaybackIntent {
    data object Start : TvOfflinePlaybackIntent

    data object Retry : TvOfflinePlaybackIntent

    data object Play : TvOfflinePlaybackIntent

    data object Pause : TvOfflinePlaybackIntent

    data object TogglePlayPause : TvOfflinePlaybackIntent

    data class SeekTo(
        val positionMs: Long,
    ) : TvOfflinePlaybackIntent

    data class SelectAudio(
        val streamIndex: Int,
    ) : TvOfflinePlaybackIntent

    data class SelectSubtitle(
        val streamIndex: Int?,
    ) : TvOfflinePlaybackIntent

    data class SeekToChapter(
        val index: Int,
    ) : TvOfflinePlaybackIntent

    data class SetPlaybackSpeed(
        val speed: Float,
    ) : TvOfflinePlaybackIntent

    data class SetDiagnosticsVisible(
        val visible: Boolean,
    ) : TvOfflinePlaybackIntent
}
