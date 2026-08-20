// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackStatus

/**
 * Accepts VLC's sometimes-spurious `Ended` only near known EOF. Callers must
 * preserve the last published position because libVLC's stopped clock may read zero.
 */
fun isVlcEndedNearCompletion(
    lastKnownPositionMs: Long,
    durationMs: Long?,
): Boolean {
    if (durationMs == null || durationMs <= 0L) return true
    return lastKnownPositionMs >= durationMs - VLC_ENDED_NEAR_END_TOLERANCE_MS
}

const val VLC_ENDED_NEAR_END_TOLERANCE_MS = 2_000L

/** Proves playback-driven movement; paused and seek-derived samples do not count. */
fun hasVlcPlaybackProgressed(
    positionMs: Long,
    baselineMs: Long,
    playing: Boolean,
    seekInFlight: Boolean,
): Boolean {
    if (!playing || seekInFlight) return false
    return positionMs > baselineMs + VLC_PROGRESS_ADVANCE_MS
}

const val VLC_PROGRESS_ADVANCE_MS = 250L

enum class VlcTerminalEvent {
    EndReached,
    Stopped,
}

/** Per-prepare progress and rejected-end evidence for VLC controllers. */
class VlcEndOfStreamEvidence {
    var baselineMs: Long = 0L
        private set

    var playbackEverProgressed: Boolean = false
        private set

    var endRejectedAwaitingStopped: Boolean = false
        private set

    var rejectionLogged: Boolean = false
        private set

    private var ignoreNextSample = false

    fun onPrepare(startPositionMs: Long) {
        baselineMs = startPositionMs.coerceAtLeast(0L)
        playbackEverProgressed = false
        endRejectedAwaitingStopped = false
        rejectionLogged = false
        ignoreNextSample = false
    }

    fun onStop() {
        playbackEverProgressed = false
        endRejectedAwaitingStopped = false
        ignoreNextSample = false
    }

    /** Ignores the seek-derived sample emitted when either seek path resolves. */
    fun ignoreNextSample() {
        ignoreNextSample = true
    }

    /** Progress remains latched until the next prepare. */
    fun onPositionSample(
        positionMs: Long,
        playing: Boolean,
        seekInFlight: Boolean,
    ) {
        if (ignoreNextSample) {
            ignoreNextSample = false
            return
        }
        if (playbackEverProgressed) return
        if (hasVlcPlaybackProgressed(
                positionMs = positionMs,
                baselineMs = baselineMs,
                playing = playing,
                seekInFlight = seekInFlight,
            )
        ) {
            playbackEverProgressed = true
        }
    }

    fun onEndRejected() {
        endRejectedAwaitingStopped = true
    }

    fun consumeEndRejected() {
        endRejectedAwaitingStopped = false
    }

    fun shouldLogRejection(): Boolean {
        if (rejectionLogged) return false
        rejectionLogged = true
        return true
    }
}

/** A null [publishedStatus] preserves state without re-reading VLC's stopped clock. */
data class VlcTerminalStatusDecision(
    val publishedStatus: PlaybackStatus? = null,
    val positionMs: Long = 0L,
    val endRejected: Boolean = false,
    val consumeEndRejected: Boolean = false,
)

/**
 * Preserves completed or rejected ends across libVLC's trailing `Stopped` event.
 * Rejected ends pause so a progress tick cannot publish the stopped clock's zero.
 */
fun resolveVlcTerminalStatus(
    event: VlcTerminalEvent,
    currentStatus: PlaybackStatus,
    playIntent: Boolean,
    playbackEverProgressed: Boolean,
    endRejectedAwaitingStopped: Boolean,
    nativePositionMs: Long,
    lastPublishedPositionMs: Long,
    durationMs: Long?,
): VlcTerminalStatusDecision {
    val lastKnownPositionMs = maxOf(nativePositionMs, lastPublishedPositionMs).coerceAtLeast(0L)
    return when (event) {
        VlcTerminalEvent.EndReached -> {
            val genuineEnd =
                playbackEverProgressed &&
                    isVlcEndedNearCompletion(
                        lastKnownPositionMs = lastKnownPositionMs,
                        durationMs = durationMs,
                    )
            if (genuineEnd) {
                VlcTerminalStatusDecision(
                    publishedStatus = PlaybackStatus.Completed,
                    positionMs = durationMs ?: lastKnownPositionMs,
                )
            } else {
                VlcTerminalStatusDecision(
                    publishedStatus = PlaybackStatus.Paused,
                    positionMs = lastKnownPositionMs,
                    endRejected = true,
                )
            }
        }
        VlcTerminalEvent.Stopped ->
            when {
                // Preserve the honest end position published by EndReached.
                currentStatus == PlaybackStatus.Completed -> VlcTerminalStatusDecision()
                // Consume only the Stopped paired with the rejected EndReached.
                endRejectedAwaitingStopped -> VlcTerminalStatusDecision(consumeEndRejected = true)
                playIntent ->
                    VlcTerminalStatusDecision(
                        publishedStatus = PlaybackStatus.Buffering,
                        positionMs = lastKnownPositionMs,
                    )
                else -> VlcTerminalStatusDecision()
            }
    }
}

/**
 * Treats VLCKit 4's sticky `Stopped` as completion only with play intent,
 * playback progress, and a known near-end position.
 */
fun classifyVlcKit4StoppedTransition(
    playIntent: Boolean,
    playbackEverProgressed: Boolean,
    nativePositionMs: Long,
    lastPublishedPositionMs: Long,
    durationMs: Long?,
): VlcTerminalEvent {
    val knownDurationMs = durationMs?.takeIf { value -> value > 0L } ?: return VlcTerminalEvent.Stopped
    if (!playIntent || !playbackEverProgressed) return VlcTerminalEvent.Stopped
    val lastKnownPositionMs = maxOf(nativePositionMs, lastPublishedPositionMs).coerceAtLeast(0L)
    return if (lastKnownPositionMs >= knownDurationMs - VLC_ENDED_NEAR_END_TOLERANCE_MS) {
        VlcTerminalEvent.EndReached
    } else {
        VlcTerminalEvent.Stopped
    }
}

/** Pauses an interrupted VLCKit 4 session; explicit stops publish nothing here. */
fun resolveVlcKit4StoppedTransition(
    currentStatus: PlaybackStatus,
    playIntent: Boolean,
    nativePositionMs: Long,
    lastPublishedPositionMs: Long,
): VlcTerminalStatusDecision {
    if (currentStatus == PlaybackStatus.Completed || !playIntent) {
        return VlcTerminalStatusDecision()
    }
    return VlcTerminalStatusDecision(
        publishedStatus = PlaybackStatus.Paused,
        positionMs = maxOf(nativePositionMs, lastPublishedPositionMs).coerceAtLeast(0L),
    )
}

/**
 * Converts VLCKit 4's sticky terminal level into edges. Repeated `Stopped`
 * values publish nothing and must not fall back to the stopped native clock.
 */
class VlcTerminalEventLatch {
    // A new player starts in Stopped before any terminal event.
    private var last: VlcTerminalEvent? = VlcTerminalEvent.Stopped

    fun observe(event: VlcTerminalEvent?): VlcTerminalEvent? {
        val edge = event?.takeIf { observed -> observed != last }
        last = event
        return edge
    }

    fun onSessionReset() {
        last = VlcTerminalEvent.Stopped
    }
}
