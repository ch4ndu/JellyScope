// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy

sealed interface TvPlaybackSessionIntent {
    data object Start : TvPlaybackSessionIntent

    data object Play : TvPlaybackSessionIntent

    data object Pause : TvPlaybackSessionIntent

    data object TogglePlayPause : TvPlaybackSessionIntent

    data class SeekTo(
        val positionMs: Long,
    ) : TvPlaybackSessionIntent

    data class SetPlaybackSpeed(
        val speed: Float,
    ) : TvPlaybackSessionIntent

    data class SetSubtitleStyle(
        val fontScale: Float,
        val foregroundColor: String?,
        val backgroundColor: String?,
        val edgeStyleName: String,
    ) : TvPlaybackSessionIntent

    data class SetDiagnosticsVisible(
        val visible: Boolean,
    ) : TvPlaybackSessionIntent

    data object DismissSubtitleNotice : TvPlaybackSessionIntent

    data object Stop : TvPlaybackSessionIntent

    data object SkipActiveSegment : TvPlaybackSessionIntent

    data object PlayNextEpisode : TvPlaybackSessionIntent

    data object PlayPreviousEpisode : TvPlaybackSessionIntent

    data class SelectQueueItem(
        val index: Int,
    ) : TvPlaybackSessionIntent

    data object ShuffleQueue : TvPlaybackSessionIntent

    data object DismissNextUp : TvPlaybackSessionIntent

    data object ContinueStillWatching : TvPlaybackSessionIntent

    data object DismissStillWatching : TvPlaybackSessionIntent

    data class SelectAudio(
        val streamIndex: Int,
    ) : TvPlaybackSessionIntent

    data class SelectSubtitle(
        val streamIndex: Int?,
    ) : TvPlaybackSessionIntent

    data class SelectLocalSubtitle(
        val assetId: String?,
    ) : TvPlaybackSessionIntent

    data class SelectQualityLimit(
        val maxBitrateBps: Long?,
    ) : TvPlaybackSessionIntent

    data class SelectQualityPolicy(
        val policy: PlaybackQualityPolicy,
    ) : TvPlaybackSessionIntent

    data class SelectQualityChoice(
        val modeName: String,
        val maxBitrateBps: Long?,
    ) : TvPlaybackSessionIntent

    data class RecordVideoOutputReady(
        val generation: Long,
    ) : TvPlaybackSessionIntent

    data class RecordNativePlaybackIntent(
        val playerIdentity: Long,
        val planEpoch: Long,
        val itemId: String,
        val isPlaying: Boolean,
    ) : TvPlaybackSessionIntent

    data class HandlePlaybackAction(
        val action: PlaybackAction,
    ) : TvPlaybackSessionIntent
}
