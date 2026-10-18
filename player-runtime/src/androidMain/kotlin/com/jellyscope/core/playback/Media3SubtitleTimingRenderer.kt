// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.playback.Media3SubtitleTimingRenderer
import com.jellyscope.core.domain.playback.PlayerTimingCommandResult
import com.jellyscope.core.domain.playback.PlayerTimingSupport

/**
 * Project-owned subtitle timing seam for Media3's subtitle surface.
 *
 * Media3 delivers cue groups on the application looper. The renderer keeps a
 * bounded event history so a delayed cue is not lost when the decoder emits a
 * later empty group before the shifted presentation time, and schedules a single
 * one-shot callback for the next shifted transition instead of polling.
 *
 * Support is [PlayerTimingSupport.PositiveOnly]: the decoder callback is the
 * earliest observable point for a cue, so a negative (earlier) shift cannot be
 * honored and is clamped to zero. A both-signs implementation would require
 * replacing Media3's final TextRenderer (deferred).
 */
internal class AndroidMedia3SubtitleTimingRenderer :
    Media3SubtitleTimingRenderer,
    Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var applyCues: ((List<Cue>) -> Unit)? = null

    // Media3 emits cue start and cue-end events as separate CueGroups. Keep
    // both events in source-time order so shifting the timeline cannot lose a
    // short cue when its end event arrives before the delayed start is due.
    private val cueEvents = mutableListOf<CueEvent>()
    private var offsetMs = 0L
    private var lastAppliedCues: List<Cue>? = null

    override val support: PlayerTimingSupport = PlayerTimingSupport.PositiveOnly

    /** Current applied offset; read synchronously by the timing controller. */
    val currentOffsetMs: Long
        get() = offsetMs

    override fun setOffset(offsetMs: Long): PlayerTimingCommandResult {
        // PositiveOnly: a negative shift cannot pull a cue earlier than its
        // decoder callback, so clamp to zero. This is the single clamp for the
        // subtitle path.
        this.offsetMs = offsetMs.coerceIn(0L, PLAYBACK_TIMING_OFFSET_LIMIT_MS)
        renderAtCurrentPosition()
        scheduleNextTransition()
        return PlayerTimingCommandResult.Applied
    }

    override fun clearForDiscontinuity() {
        cueEvents.clear()
        handler.removeCallbacks(transitionRunnable)
        lastAppliedCues = null
        applyCues?.invoke(emptyList())
    }

    fun attach(
        player: Player,
        applyCues: (List<Cue>) -> Unit,
    ) {
        if (this.player !== player) {
            detach()
            this.player = player
            player.addListener(this)
        }
        this.applyCues = applyCues
        // Seed from the currently-visible cues so a mid-cue surface recreation
        // (rotation, PiP, backend swap) does not blank subtitles until the next
        // decoder event.
        seedFromCurrentCues(player)
        renderAtCurrentPosition()
        scheduleNextTransition()
    }

    fun detach() {
        player?.removeListener(this)
        player = null
        applyCues = null
        handler.removeCallbacks(transitionRunnable)
        cueEvents.clear()
        lastAppliedCues = null
    }

    override fun onCues(cueGroup: CueGroup) {
        recordCues(cueGroup)
        renderAtCurrentPosition()
        scheduleNextTransition()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        clearForDiscontinuity()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            clearForDiscontinuity()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            scheduleNextTransition()
        } else {
            handler.removeCallbacks(transitionRunnable)
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        // The one-shot transition delay is speed-scaled; a speed change while a
        // transition is pending would fire it early or late, so reschedule (and
        // re-render) against the new speed.
        renderAtCurrentPosition()
        scheduleNextTransition()
    }

    private fun seedFromCurrentCues(player: Player) {
        val current = player.currentCues
        if (current.cues.isNotEmpty()) recordCues(current)
    }

    private fun recordCues(cueGroup: CueGroup) {
        val presentationTimeMs =
            cueGroup.presentationTimeUs
                .takeIf { value -> value != C.TIME_UNSET && value >= 0L }
                ?.div(1_000L)
                ?: (player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
        cueEvents.removeAll { event -> event.presentationTimeMs == presentationTimeMs }
        cueEvents += CueEvent(presentationTimeMs, cueGroup.cues)
        cueEvents.sortBy { event -> event.presentationTimeMs }
        while (cueEvents.size > MAX_CUE_EVENTS) {
            cueEvents.removeAt(0)
        }
    }

    private fun renderAtCurrentPosition() {
        val currentPosition = player?.currentPosition?.coerceAtLeast(0L) ?: return
        val visibleCues =
            cueEvents
                .lastOrNull { event -> currentPosition >= event.presentationTimeMs + offsetMs }
                ?.cues
                ?: emptyList()
        // Reference-equal check: cueEvents store stable list references and
        // emptyList() is a singleton, so this dedups redundant surface writes.
        if (visibleCues !== lastAppliedCues) {
            lastAppliedCues = visibleCues
            applyCues?.invoke(visibleCues)
        }
    }

    private fun scheduleNextTransition() {
        handler.removeCallbacks(transitionRunnable)
        val player = player ?: return
        if (!player.isPlaying) return
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val nextShifted =
            cueEvents
                .asSequence()
                .map { event -> event.presentationTimeMs + offsetMs }
                .filter { shifted -> shifted > currentPosition }
                .minOrNull() ?: return
        val speed = player.playbackParameters.speed.takeIf { value -> value > 0f } ?: 1f
        val delayMs = ((nextShifted - currentPosition) / speed).toLong().coerceAtLeast(0L)
        handler.postDelayed(transitionRunnable, delayMs)
    }

    private val transitionRunnable =
        Runnable {
            if (player == null) return@Runnable
            renderAtCurrentPosition()
            scheduleNextTransition()
        }

    private companion object {
        const val MAX_CUE_EVENTS = 256
    }

    private data class CueEvent(
        val presentationTimeMs: Long,
        val cues: List<Cue>,
    )
}

/** Keeps the Media3 subtitle view behind a project-owned controller seam. */
interface AndroidMedia3SubtitleTimingBridge {
    fun attachSubtitleView(
        player: Player,
        applyCues: (List<Cue>) -> Unit,
    )

    fun detachSubtitleView()
}
