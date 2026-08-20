// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoldSeekAggregatorTest {
    private val twoHoursMs = 7_200_000L

    @Test
    fun stepCurveStaysFlatUntilRampThenGrowsMonotonicallyToDurationCap() {
        val steps = (0..8).map { index -> holdSeekStepMs(index, twoHoursMs) }

        repeat(HOLD_SEEK_RAMP_START) { index ->
            assertEquals(HOLD_SEEK_BASE_STEP_MS, steps[index])
        }
        steps.zipWithNext().forEach { (previous, next) ->
            assertTrue(next >= previous, "curve must never shrink: $steps")
        }
        assertTrue(steps.last() > HOLD_SEEK_BASE_STEP_MS)
        assertTrue(steps.all { step -> step <= HOLD_SEEK_MAX_STEP_MS })
    }

    @Test
    fun stepCurveWithoutDurationNeverAcceleratesBeyondBase() {
        (0..10).forEach { index ->
            assertEquals(HOLD_SEEK_BASE_STEP_MS, holdSeekStepMs(index, durationMs = null))
        }
    }

    @Test
    fun shortContentCapsAccelerationAtBaseStep() {
        (0..10).forEach { index ->
            assertEquals(HOLD_SEEK_BASE_STEP_MS, holdSeekStepMs(index, durationMs = 600_000L))
        }
    }

    @Test
    fun firstKeyAnchorsAtCurrentPositionAndAdvancesByBaseStep() {
        val aggregator = HoldSeekAggregator()

        val target = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 100_000L, durationMs = twoHoursMs)

        assertEquals(100_000L + HOLD_SEEK_BASE_STEP_MS, target)
        assertTrue(aggregator.sessionActive)
    }

    @Test
    fun repeatEventsAdvanceOnEverySecondEventWhileTapStepStaysUnchanged() {
        val aggregator = HoldSeekAggregator()

        val first = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 0L, durationMs = twoHoursMs)
        val skipped = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 0L, durationMs = twoHoursMs)
        val second = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 0L, durationMs = twoHoursMs)

        assertEquals(HOLD_SEEK_BASE_STEP_MS, first)
        assertEquals(first, skipped)
        assertEquals(first + HOLD_SEEK_BASE_STEP_MS, second)
    }

    @Test
    fun repeatGatingPreservesTheAccelerationCurveAtHalfRate() {
        val aggregator = HoldSeekAggregator()

        val targets =
            (1..10).map {
                aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 0L, durationMs = twoHoursMs)
            }
        val advances = targets.zipWithNext().map { (previous, next) -> next - previous }

        assertEquals(
            listOf(
                0L,
                HOLD_SEEK_BASE_STEP_MS,
                0L,
                HOLD_SEEK_BASE_STEP_MS,
                0L,
                HOLD_SEEK_BASE_STEP_MS * HOLD_SEEK_GROWTH_FACTOR,
                0L,
                HOLD_SEEK_BASE_STEP_MS * HOLD_SEEK_GROWTH_FACTOR * HOLD_SEEK_GROWTH_FACTOR,
                0L,
            ),
            advances,
        )
    }

    @Test
    fun pendingTargetClampsAtZeroAndDuration() {
        val aggregator = HoldSeekAggregator()
        assertEquals(0L, aggregator.onSeekKey(HoldSeekDirection.Backward, currentPositionMs = 5_000L, durationMs = twoHoursMs))
        aggregator.cancel()

        assertEquals(
            twoHoursMs,
            aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = twoHoursMs - 5_000L, durationMs = twoHoursMs),
        )
    }

    @Test
    fun directionReversalRebasesFromPendingTargetAndResetsRamp() {
        val aggregator = HoldSeekAggregator()
        var pending = 0L
        repeat(6) {
            pending = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 1_000_000L, durationMs = twoHoursMs)
        }

        val reversed = aggregator.onSeekKey(HoldSeekDirection.Backward, currentPositionMs = 999L, durationMs = twoHoursMs)

        assertEquals(pending - HOLD_SEEK_BASE_STEP_MS, reversed)
        assertEquals(
            reversed,
            aggregator.onSeekKey(HoldSeekDirection.Backward, currentPositionMs = 999L, durationMs = twoHoursMs),
        )
        assertEquals(
            reversed - HOLD_SEEK_BASE_STEP_MS,
            aggregator.onSeekKey(HoldSeekDirection.Backward, currentPositionMs = 999L, durationMs = twoHoursMs),
        )
    }

    @Test
    fun finalCommitReturnsTargetAndClearsSession() {
        val aggregator = HoldSeekAggregator()
        val pending = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 50_000L, durationMs = twoHoursMs)

        assertEquals(pending, aggregator.takeFinalCommit())
        assertFalse(aggregator.sessionActive)
        assertNull(aggregator.peekPendingTarget())
        assertNull(aggregator.takeFinalCommit())
    }

    @Test
    fun cadenceCommitKeepsSessionAliveWithRampRetained() {
        val aggregator = HoldSeekAggregator()
        var pending = 0L
        repeat(7) {
            pending = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 0L, durationMs = twoHoursMs)
        }

        assertEquals(pending, aggregator.takeCadenceCommit())
        assertTrue(aggregator.sessionActive)
        assertEquals(pending, aggregator.peekPendingTarget())

        val skipped = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 0L, durationMs = twoHoursMs)
        assertEquals(pending, skipped)

        val next = aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 0L, durationMs = twoHoursMs)
        assertTrue(
            next - pending > HOLD_SEEK_BASE_STEP_MS,
            "ramp must survive a cadence commit: advanced by ${next - pending}",
        )
    }

    @Test
    fun cancelClearsSessionWithoutCommit() {
        val aggregator = HoldSeekAggregator()
        aggregator.onSeekKey(HoldSeekDirection.Forward, currentPositionMs = 10_000L, durationMs = twoHoursMs)

        aggregator.cancel()

        assertFalse(aggregator.sessionActive)
        assertNull(aggregator.takeFinalCommit())
    }
}
