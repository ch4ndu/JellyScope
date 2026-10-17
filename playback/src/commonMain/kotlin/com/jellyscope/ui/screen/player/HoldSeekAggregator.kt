// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HoldSeekDirection(
    internal val sign: Int,
) {
    Backward(-1),
    Forward(1),
}

/** Accelerates a held seek up to a duration-scaled cap. */
internal fun holdSeekStepMs(
    stepIndex: Int,
    durationMs: Long?,
): Long {
    if (stepIndex < HOLD_SEEK_RAMP_START) return HOLD_SEEK_BASE_STEP_MS
    val maxStep =
        (durationMs?.let { duration -> duration / HOLD_SEEK_MAX_STEP_DIVISOR } ?: HOLD_SEEK_BASE_STEP_MS)
            .coerceIn(HOLD_SEEK_BASE_STEP_MS, HOLD_SEEK_MAX_STEP_MS)
    var step = HOLD_SEEK_BASE_STEP_MS
    repeat(stepIndex - HOLD_SEEK_RAMP_START + 1) {
        step = (step * HOLD_SEEK_GROWTH_FACTOR).coerceAtMost(maxStep)
    }
    return step
}

/** Accumulates a held seek target; [HoldSeekTimers] owns scheduling. */
class HoldSeekAggregator {
    var pendingTargetMs: Long? = null
        private set
    private var stepIndex: Int = 0
    private var repeatEventCount: Int = 0
    private var direction: HoldSeekDirection? = null

    val sessionActive: Boolean get() = pendingTargetMs != null

    /** Advances every second repeat; reversing direction restarts the ramp. */
    fun onSeekKey(
        newDirection: HoldSeekDirection,
        currentPositionMs: Long,
        durationMs: Long?,
    ): Long {
        val anchor = pendingTargetMs
        val target =
            when {
                anchor == null -> {
                    stepIndex = 0
                    repeatEventCount = 0
                    direction = newDirection
                    currentPositionMs + newDirection.sign * holdSeekStepMs(stepIndex, durationMs)
                }
                newDirection != direction -> {
                    stepIndex = 0
                    repeatEventCount = 0
                    direction = newDirection
                    anchor + newDirection.sign * holdSeekStepMs(stepIndex, durationMs)
                }
                else -> {
                    repeatEventCount += 1
                    if (repeatEventCount % HOLD_SEEK_REPEAT_GATING_DIVISOR != 0) {
                        anchor
                    } else {
                        stepIndex += 1
                        anchor + newDirection.sign * holdSeekStepMs(stepIndex, durationMs)
                    }
                }
            }
        val clamped = target.coerceAtLeast(0L).let { value -> durationMs?.let(value::coerceAtMost) ?: value }
        pendingTargetMs = clamped
        return clamped
    }

    fun takeFinalCommit(): Long? {
        val target = pendingTargetMs
        cancel()
        return target
    }

    /** Returns a catch-up target without ending the hold session. */
    fun takeCadenceCommit(): Long? = pendingTargetMs

    fun peekPendingTarget(): Long? = pendingTargetMs

    fun cancel() {
        pendingTargetMs = null
        stepIndex = 0
        repeatEventCount = 0
        direction = null
    }
}

/** Ends holds after a missing key-up and schedules bounded catch-up commits. */
class HoldSeekTimers(
    private val scope: CoroutineScope,
    private val onWatchdogExpired: () -> Unit,
    private val onCadenceTick: () -> Unit,
    private val firstRepeatGraceMs: Long = HOLD_SEEK_FIRST_REPEAT_GRACE_MS,
    private val watchdogMs: Long = HOLD_SEEK_WATCHDOG_MS,
    private val cadenceMs: Long = HOLD_SEEK_CADENCE_COMMIT_MS,
) {
    private var watchdogJob: Job? = null
    private var cadenceJob: Job? = null

    fun onSessionStarted() {
        onSessionEnded()
        armWatchdog(firstRepeatGraceMs)
        cadenceJob =
            scope.launch {
                while (isActive) {
                    delay(cadenceMs)
                    onCadenceTick()
                }
            }
    }

    fun onRepeatEvent() {
        if (watchdogJob == null && cadenceJob == null) return
        armWatchdog(watchdogMs)
    }

    fun onSessionEnded() {
        watchdogJob?.cancel()
        watchdogJob = null
        cadenceJob?.cancel()
        cadenceJob = null
    }

    private fun armWatchdog(delayMs: Long) {
        watchdogJob?.cancel()
        watchdogJob =
            scope.launch {
                delay(delayMs)
                cadenceJob?.cancel()
                cadenceJob = null
                watchdogJob = null
                onWatchdogExpired()
            }
    }
}

internal const val HOLD_SEEK_BASE_STEP_MS = 10_000L
internal const val HOLD_SEEK_REPEAT_GATING_DIVISOR = 2
internal const val HOLD_SEEK_RAMP_START = 3
internal const val HOLD_SEEK_GROWTH_FACTOR = 2L
internal const val HOLD_SEEK_MAX_STEP_DIVISOR = 60L
internal const val HOLD_SEEK_MAX_STEP_MS = 120_000L
internal const val HOLD_SEEK_FIRST_REPEAT_GRACE_MS = 600L
internal const val HOLD_SEEK_WATCHDOG_MS = 300L
internal const val HOLD_SEEK_CADENCE_COMMIT_MS = 2_000L
