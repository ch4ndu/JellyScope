// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NoopPlayerController : PlayerController {
    private val state =
        MutableStateFlow(
            PlaybackState(
                status = PlaybackStatus.Failed,
                positionMs = 0L,
                durationMs = null,
                bufferedPositionMs = 0L,
                error = PlaybackError.Unknown,
            ),
        )

    override val playbackState: StateFlow<PlaybackState> = state
    override val runtimeDiagnostics: StateFlow<PlaybackRuntimeDiagnostics> =
        MutableStateFlow(PlaybackRuntimeDiagnostics.EMPTY)
    override val platformPlayer: Any? = null
    override val playbackHealthMeasurementCapabilities: PlaybackHealthMeasurementCapabilities =
        PlaybackHealthMeasurementCapabilities.None
    override val videoOutputMeasurementCapabilities: VideoOutputMeasurementCapabilities =
        VideoOutputMeasurementCapabilities.Unsupported

    override fun prepare(
        plan: PlaybackPlan,
        subtitleAsset: SubtitleAsset?,
    ) = Unit

    override fun selectEmbeddedAudio(selection: EmbeddedAudioSelection) = Unit

    override fun selectEmbeddedSubtitle(selection: EmbeddedSubtitleSelection?) = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun setSubtitleStyle(style: SubtitleStyle) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun stop() = Unit

    override fun retry() = Unit

    override fun release() = Unit
}
