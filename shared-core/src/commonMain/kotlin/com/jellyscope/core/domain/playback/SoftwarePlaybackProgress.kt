// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

const val SOFTWARE_PLAYBACK_SLOW_THRESHOLD_MS = 20_000L
private const val PROGRESS_WINDOW_MS = 5_000L
private const val MAX_OBSERVATION_GAP_MS = 10_000L
private const val MIN_BUFFER_AHEAD_MS = 3_000L

enum class SoftwarePlaybackRecoveryDecision {
    Prompted,
    SwitchRequested,
    ContinueRequested,
    SwitchPrepared,
    SwitchFailed,
    Stopped,
    Superseded,
}

enum class SoftwarePlaybackProgressOutcome {
    Inactive,
    Transition,
    NotSoftware,
    UnknownDecoding,
    InsufficientBuffer,
    Observing,
    Discontinuity,
    KeepingUp,
    SlowWindow,
    TooSlow,
}

data class SoftwarePlaybackProgressEvidence(
    val outcome: SoftwarePlaybackProgressOutcome,
    val durationMs: Long = 0L,
    val progressMs: Long = 0L,
    val expectedProgressMs: Long = 0L,
    val slowDurationMs: Long = 0L,
)

/** Consumes current playback observations; never samples the player or chooses recovery. */
class SoftwarePlaybackProgress {
    private var windowAtMs: Long? = null
    private var windowPositionMs = 0L
    private var lastAtMs = 0L
    private var lastPositionMs = 0L
    private var speed = 1f
    private var slowDurationMs = 0L
    private var lastReportedOutcome: SoftwarePlaybackProgressOutcome? = null

    fun reset() {
        windowAtMs = null
        slowDurationMs = 0L
    }

    fun observe(
        state: PlaybackState,
        decodingMode: PlaybackVideoDecodingMode?,
        admitted: Boolean,
        excluded: Boolean,
        nowMs: Long,
    ): SoftwarePlaybackProgressEvidence? {
        val rejection =
            when {
                !admitted || state.status != PlaybackStatus.Playing -> SoftwarePlaybackProgressOutcome.Inactive
                excluded -> SoftwarePlaybackProgressOutcome.Transition
                decodingMode == null -> SoftwarePlaybackProgressOutcome.UnknownDecoding
                decodingMode != PlaybackVideoDecodingMode.Software -> SoftwarePlaybackProgressOutcome.NotSoftware
                state.bufferedPositionMs - state.positionMs < MIN_BUFFER_AHEAD_MS -> SoftwarePlaybackProgressOutcome.InsufficientBuffer
                else -> null
            }
        if (rejection != null) {
            reset()
            return report(SoftwarePlaybackProgressEvidence(rejection))
        }
        val start = windowAtMs
        if (start == null) {
            startWindow(state, nowMs)
            return report(SoftwarePlaybackProgressEvidence(SoftwarePlaybackProgressOutcome.Observing))
        }
        val gapMs = nowMs - lastAtMs
        val advanceMs = state.positionMs - lastPositionMs
        val discontinuity =
            gapMs < 0L ||
                gapMs > MAX_OBSERVATION_GAP_MS ||
                advanceMs < 0L ||
                state.playbackSpeed != speed ||
                !speed.isFinite() ||
                speed <= 0f ||
                advanceMs > gapMs * speed * 2 + 1_000L
        lastAtMs = nowMs
        lastPositionMs = state.positionMs
        if (discontinuity) {
            reset()
            return report(SoftwarePlaybackProgressEvidence(SoftwarePlaybackProgressOutcome.Discontinuity))
        }
        val elapsedMs = nowMs - start
        if (elapsedMs < PROGRESS_WINDOW_MS) return null
        val progressMs = state.positionMs - windowPositionMs
        val expectedMs = (elapsedMs * speed.toDouble()).toLong()
        val slow = progressMs < expectedMs / 2
        slowDurationMs = if (slow) slowDurationMs + elapsedMs else 0L
        val outcome =
            when {
                slowDurationMs >= SOFTWARE_PLAYBACK_SLOW_THRESHOLD_MS -> SoftwarePlaybackProgressOutcome.TooSlow
                slow -> SoftwarePlaybackProgressOutcome.SlowWindow
                else -> SoftwarePlaybackProgressOutcome.KeepingUp
            }
        startWindow(state, nowMs)
        return report(SoftwarePlaybackProgressEvidence(outcome, elapsedMs, progressMs, expectedMs, slowDurationMs))
    }

    private fun startWindow(
        state: PlaybackState,
        nowMs: Long,
    ) {
        windowAtMs = nowMs
        windowPositionMs = state.positionMs
        lastAtMs = nowMs
        lastPositionMs = state.positionMs
        speed = state.playbackSpeed
    }

    private fun report(evidence: SoftwarePlaybackProgressEvidence): SoftwarePlaybackProgressEvidence? {
        val repeated = evidence.outcome == lastReportedOutcome
        lastReportedOutcome = evidence.outcome
        return evidence.takeUnless { repeated && evidence.outcome != SoftwarePlaybackProgressOutcome.SlowWindow }
    }
}
