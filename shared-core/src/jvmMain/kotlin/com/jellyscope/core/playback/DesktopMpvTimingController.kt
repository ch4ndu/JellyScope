// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.model.PlaybackTimingKind
import com.jellyscope.core.domain.playback.PlayerTimingCommandResult
import com.jellyscope.core.domain.playback.PlayerTimingController
import com.jellyscope.core.domain.playback.PlayerTimingState
import com.jellyscope.core.domain.playback.PlayerTimingSupport
import com.jellyscope.core.domain.playback.PlayerTimingValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class DesktopMpvTimingController(
    private val applyOffset: (PlaybackTimingKind, Long) -> Unit,
) : PlayerTimingController {
    private val lock = Any()
    private val _timingState =
        MutableStateFlow(
            PlayerTimingState(
                audio = PlayerTimingValue(support = PlayerTimingSupport.Supported),
                subtitle = PlayerTimingValue(support = PlayerTimingSupport.Supported),
            ),
        )

    override val timingState: StateFlow<PlayerTimingState> = _timingState.asStateFlow()

    override fun setOffset(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ): PlayerTimingCommandResult =
        synchronized(lock) {
            val normalized = offsetMs.coerceIn(-PLAYBACK_TIMING_OFFSET_LIMIT_MS, PLAYBACK_TIMING_OFFSET_LIMIT_MS)
            _timingState.update { current ->
                when (kind) {
                    PlaybackTimingKind.Audio -> current.copy(audio = current.audio.copy(offsetMs = normalized))
                    PlaybackTimingKind.Subtitle -> current.copy(subtitle = current.subtitle.copy(offsetMs = normalized))
                }
            }
            applyOffset(kind, normalized)
            PlayerTimingCommandResult.Applied
        }

    fun applyRetainedOffsets(apply: (PlaybackTimingKind, Long) -> Unit) {
        synchronized(lock) {
            val state = _timingState.value
            apply(PlaybackTimingKind.Audio, state.audio.offsetMs)
            apply(PlaybackTimingKind.Subtitle, state.subtitle.offsetMs)
        }
    }
}
