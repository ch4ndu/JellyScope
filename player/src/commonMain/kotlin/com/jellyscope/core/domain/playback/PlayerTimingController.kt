// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.model.PlaybackTimingKind
import kotlinx.coroutines.flow.StateFlow

enum class PlayerTimingSupport {
    Supported,

    /**
     * Only a positive (later) offset can be honored. Media3's subtitle timing
     * shifts cues against the decoder callback, which cannot deliver a cue
     * earlier than its natural time, so negative subtitle offsets are clamped to
     * zero. The UI must hide/disable the negative controls for this state.
     */
    PositiveOnly,
    Unsupported,
}

data class PlayerTimingValue(
    val offsetMs: Long = 0L,
    val support: PlayerTimingSupport = PlayerTimingSupport.Unsupported,
) {
    val isSupported: Boolean
        get() = support != PlayerTimingSupport.Unsupported

    val supportsNegative: Boolean
        get() = support == PlayerTimingSupport.Supported

    fun normalized(): PlayerTimingValue =
        copy(offsetMs = offsetMs.coerceIn(-PLAYBACK_TIMING_OFFSET_LIMIT_MS, PLAYBACK_TIMING_OFFSET_LIMIT_MS))
}

data class PlayerTimingState(
    val audio: PlayerTimingValue = PlayerTimingValue(),
    val subtitle: PlayerTimingValue = PlayerTimingValue(),
) {
    fun normalized(): PlayerTimingState = copy(audio = audio.normalized(), subtitle = subtitle.normalized())

    fun value(kind: PlaybackTimingKind): PlayerTimingValue =
        when (kind) {
            PlaybackTimingKind.Audio -> audio
            PlaybackTimingKind.Subtitle -> subtitle
        }

    companion object {
        val Unsupported = PlayerTimingState()
    }
}

enum class PlayerTimingCommandResult {
    Applied,
    Unsupported,
}

/** Optional timing capability exposed by a platform player when the active engine supports it. */
interface PlayerTimingController {
    val timingState: StateFlow<PlayerTimingState>

    fun setOffset(
        kind: PlaybackTimingKind,
        offsetMs: Long,
    ): PlayerTimingCommandResult

    fun reset(kind: PlaybackTimingKind): PlayerTimingCommandResult = setOffset(kind, 0L)
}

/** Project-owned seam for a Media3 decoder-output subtitle timing implementation. */
interface Media3SubtitleTimingRenderer {
    val support: PlayerTimingSupport

    fun setOffset(offsetMs: Long): PlayerTimingCommandResult

    fun clearForDiscontinuity()
}

/**
 * Project-owned seam for a Media3 PCM timing implementation. An offset change is
 * applied by a controller-driven re-prepare (whose sink flush re-establishes the
 * shift), so there is no in-place discontinuity call on this seam.
 */
interface Media3AudioTimingProcessor {
    val support: PlayerTimingSupport

    fun setOffset(offsetMs: Long): PlayerTimingCommandResult
}

/** Project-owned seam for LibVLC's native audio/subtitle delay controls. */
interface LibVlcTimingController : PlayerTimingController
