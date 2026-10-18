// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.playback.Media3AudioTimingProcessor
import com.jellyscope.core.domain.playback.PlayerTimingCommandResult
import com.jellyscope.core.domain.playback.PlayerTimingController
import com.jellyscope.core.domain.playback.PlayerTimingState
import com.jellyscope.core.domain.playback.PlayerTimingSupport
import com.jellyscope.core.domain.playback.PlayerTimingValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Media3 timing state backed by the PCM audio sink processor and the subtitle
 * renderer. Offsets arrive already clamped from the ViewModel seam. An audio
 * offset change triggers [onAudioOutputModeChanged] (a coalesced re-prepare in
 * the controller) so the sink flush re-establishes the shift.
 */
internal class Media3TimingController(
    private val audioProcessor: Media3AudioTimingProcessor,
    private val subtitleRenderer: AndroidMedia3SubtitleTimingRenderer,
    private val onAudioOutputModeChanged: () -> Unit = {},
) : PlayerTimingController {
    private val _timingState =
        MutableStateFlow(
            PlayerTimingState(
                audio = PlayerTimingValue(support = audioProcessor.support),
                subtitle = PlayerTimingValue(support = subtitleRenderer.support),
            ),
        )

    override val timingState: StateFlow<PlayerTimingState> = _timingState.asStateFlow()

    override fun setOffset(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ): PlayerTimingCommandResult {
        val result =
            when (kind) {
                PlaybackTimingKind.Audio -> {
                    if (audioProcessor.support != PlayerTimingSupport.Supported) {
                        PlayerTimingCommandResult.Unsupported
                    } else {
                        val changed = offsetMs != _timingState.value.audio.offsetMs
                        audioProcessor.setOffset(offsetMs).also { applied ->
                            if (applied == PlayerTimingCommandResult.Applied && changed) {
                                onAudioOutputModeChanged()
                            }
                        }
                    }
                }
                PlaybackTimingKind.Subtitle -> subtitleRenderer.setOffset(offsetMs)
            }
        if (result == PlayerTimingCommandResult.Applied) {
            _timingState.update { current ->
                when (kind) {
                    PlaybackTimingKind.Audio ->
                        current.copy(audio = current.audio.copy(offsetMs = offsetMs))
                    // Subtitle timing is PositiveOnly: report the value the
                    // renderer actually applied (a negative request is clamped
                    // to 0), never the raw request.
                    PlaybackTimingKind.Subtitle ->
                        current.copy(subtitle = current.subtitle.copy(offsetMs = subtitleRenderer.currentOffsetMs))
                }
            }
        }
        return result
    }

    fun clearForDiscontinuity() {
        // The audio processor is re-established by the sink flush on prepare/seek;
        // only the subtitle renderer needs an explicit discontinuity clear.
        subtitleRenderer.clearForDiscontinuity()
    }
}
