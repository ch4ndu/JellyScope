// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.playback.PlaybackAction
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.SubtitleStyle

/** User commands; controller observation and lifecycle remain separate inputs. */
sealed interface PlayerIntent {
    data object Play : PlayerIntent

    data object Pause : PlayerIntent

    data object TogglePlayPause : PlayerIntent

    data object PlayPrevious : PlayerIntent

    data class SetVolume(
        val percent: Int,
    ) : PlayerIntent

    data object ToggleMute : PlayerIntent

    data class SeekTo(
        val positionMs: Long,
    ) : PlayerIntent

    data object Retry : PlayerIntent

    data class PlayQueueItem(
        val index: Int,
    ) : PlayerIntent

    data object ConfirmStillWatching : PlayerIntent

    data object SkipCurrentSegment : PlayerIntent

    data class SetResizeMode(
        val mode: PlayerResizeMode,
    ) : PlayerIntent

    data class CycleResizeMode(
        val twoState: Boolean = false,
    ) : PlayerIntent

    data object ShuffleQueue : PlayerIntent

    data object Stop : PlayerIntent

    data class ShowPicker(
        val picker: PlayerPicker,
    ) : PlayerIntent

    data object HidePicker : PlayerIntent

    data class SelectBackend(
        val backend: PlayerBackend,
    ) : PlayerIntent

    data class SelectAudio(
        val streamIndex: Int,
    ) : PlayerIntent

    data object SelectOfflineSidecar : PlayerIntent

    data class SelectSubtitle(
        val streamIndex: Int?,
    ) : PlayerIntent

    data class SelectLocalSubtitle(
        val assetId: String,
    ) : PlayerIntent

    data class SelectQualityBitrate(
        val maxBitrateBps: Long?,
    ) : PlayerIntent

    data class SelectQuality(
        val policy: PlaybackQualityPolicy,
    ) : PlayerIntent

    data object ClearQualityOverride : PlayerIntent

    data class HandlePlaybackAction(
        val action: PlaybackAction,
    ) : PlayerIntent

    data object DismissPlaybackGuidance : PlayerIntent

    data class PictureInPictureChanged(
        val inPictureInPicture: Boolean,
    ) : PlayerIntent

    data class SetPlaybackSpeed(
        val speed: Float,
    ) : PlayerIntent

    data class SetSubtitleStyle(
        val style: SubtitleStyle,
    ) : PlayerIntent

    data class AdjustAudioTiming(
        val deltaMs: Long,
    ) : PlayerIntent

    data class AdjustSubtitleTiming(
        val deltaMs: Long,
    ) : PlayerIntent

    data object ResetAudioTiming : PlayerIntent

    data object ResetSubtitleTiming : PlayerIntent

    data class ContinueSoftwareRecovery(
        val token: Long,
    ) : PlayerIntent

    data class SwitchSoftwareRecovery(
        val token: Long,
    ) : PlayerIntent

    /** The caller uses acceptance to retain Up Next or the completed-item UI. */
    sealed interface Request : PlayerIntent

    data class PlayNext(
        val auto: Boolean = false,
        val expectedGeneration: Long? = null,
    ) : Request

    data object ReplayCurrent : Request

    data class SelectAsset(
        val itemId: String,
        val offlineDownloadId: DownloadId? = null,
        val restartFromBeginning: Boolean = false,
        val allowCurrentTargetRetry: Boolean = false,
    ) : Request

    /** Suspends until admission can be decided; the caller must consume the result. */
    sealed interface Admission

    data class KidsLaunch(
        val itemId: String,
        val options: PlayerLaunchOptions,
    ) : Admission
}
