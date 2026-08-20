// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHealthEvaluatorTest {
    @Test
    fun slowStartupEmitsAtTenSecondsOnlyOnceAndStopsAfterPlaying() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 7L, launchAtMs = 1_000L)
        evaluator.onStatusChanged(PlaybackStatus.Loading, 1_000L)

        assertTrue(evaluator.evaluateAt(10_999L).isEmpty())
        assertIs<PlaybackHealthSignal.SlowStartup>(evaluator.evaluateAt(11_000L).single())
        assertTrue(evaluator.evaluateAt(12_000L).isEmpty())

        evaluator.onStatusChanged(PlaybackStatus.Playing, 12_000L)
        assertTrue(evaluator.evaluateAt(30_000L).isEmpty())
    }

    @Test
    fun longBufferingCountsOnlyTimeAfterTheExclusionWindow() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 1L, launchAtMs = 0L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 0L)
        evaluator.markExclusion(2_000L, PlaybackHealthExclusionReason.Seek)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 2_000L)

        assertTrue(evaluator.evaluateAt(8_999L).isEmpty())
        assertIs<PlaybackHealthSignal.LongBuffering>(evaluator.evaluateAt(9_000L).single())
    }

    @Test
    fun cumulativeBufferingCombinesIntervalsWithoutRequiringOneLongStall() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 1L, launchAtMs = 0L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 0L)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 30_000L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 36_000L)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 55_000L)

        val signal = assertIs<PlaybackHealthSignal.CumulativeBuffering>(evaluator.evaluateAt(59_000L).single())
        assertEquals(10_000L, signal.durationMs)
        assertEquals(2, signal.count)
    }

    @Test
    fun repeatedStallsUsesTheRollingWindowAndIgnoresExcludedTransitions() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 1L, launchAtMs = 0L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 0L)

        evaluator.markExclusion(1_000L, PlaybackHealthExclusionReason.Prepare)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 1_500L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 2_000L)
        val signals = mutableListOf<PlaybackHealthSignal>()
        listOf(4_000L, 6_000L, 8_000L).forEach { atMs ->
            signals += evaluator.onStatusChanged(PlaybackStatus.Buffering, atMs)
            evaluator.onStatusChanged(PlaybackStatus.Playing, atMs + 100L)
        }

        val signal = assertIs<PlaybackHealthSignal.RepeatedStalls>(signals.single())
        assertEquals(3, signal.count)
    }

    @Test
    fun overlappingDroppedFrameMeasurementsCountTheirUnion() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 1L, launchAtMs = 0L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 0L)

        val first = requireNotNull(DroppedFrameMeasurement.create(droppedFrames = 30L, intervalMs = 15_000L))
        val second = requireNotNull(DroppedFrameMeasurement.create(droppedFrames = 30L, intervalMs = 15_000L))
        assertTrue(evaluator.recordDroppedFrameMeasurement(first, 20_000L).isEmpty())
        val signal =
            assertIs<PlaybackHealthSignal.DroppedFrames>(
                evaluator.recordDroppedFrameMeasurement(second, 25_000L).single(),
            )
        assertEquals(20_000L, signal.durationMs)
        assertEquals(2, signal.count)
    }

    @Test
    fun resetClearsEvidenceAndUsesTheNewGeneration() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 1L, launchAtMs = 0L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 0L)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 1_000L)
        evaluator.evaluateAt(6_000L)

        evaluator.reset(generation = 2L, launchAtMs = 10_000L)

        assertEquals(2L, evaluator.summary().generation)
        assertEquals(0L, evaluator.summary().bufferingDurationMs)
        assertTrue(evaluator.summary().emittedSignals.isEmpty())
    }

    @Test
    fun evidenceRestartClearsMeasurementsButPreservesSessionStateAndEmittedSignals() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 7L, launchAtMs = 100L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 100L)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 1_000L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 1_100L)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 2_000L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 2_100L)
        val signals = evaluator.onStatusChanged(PlaybackStatus.Buffering, 3_000L)

        assertIs<PlaybackHealthSignal.RepeatedStalls>(signals.single())
        evaluator.restartEvidenceWindow()

        val summary = evaluator.summary(3_000L)
        assertEquals(7L, summary.generation)
        assertEquals(100L, evaluator.startedAtMs)
        assertTrue(summary.firstPlayingObserved)
        assertEquals(PlaybackStatus.Buffering, evaluator.currentStatus)
        assertEquals(0L, summary.bufferingDurationMs)
        assertEquals(0, summary.stallCount)
        assertEquals(listOf(PlaybackHealthSignalKind.RepeatedStalls), summary.emittedSignals)
    }

    // Restarting while still Buffering must REOPEN the interval at the boundary.
    // lastStatus is preserved and onStatusChanged() returns early for an unchanged
    // status, so without the reopen the ongoing wait would never be measured again:
    // no duration, no timer, no warning, no recovery. A second seek during an
    // existing buffering episode takes exactly this path.
    @Test
    fun evidenceRestartReopensAnOngoingBufferingIntervalAtTheBoundary() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 1L, launchAtMs = 0L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 0L)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 1_000L)

        evaluator.restartEvidenceWindow()

        assertEquals(1_000L, evaluator.bufferingStartedAtMs)
        assertTrue(evaluator.summary(1_000L).bufferingIntervalOpenedSinceEvidenceRestart)
        // The reopened interval accrues from the boundary, so the wait the user is
        // now experiencing is still measured and can still reach a threshold.
        assertEquals(4_000L, evaluator.summary(5_000L).bufferingDurationMs)
        assertNotNull(evaluator.nextBufferingEvaluationDelayMs(1_000L))
    }

    @Test
    fun evidenceRestartWhileNotBufferingLeavesNoActiveInterval() {
        val evaluator = PlaybackHealthEvaluator()
        evaluator.reset(generation = 1L, launchAtMs = 0L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 0L)
        evaluator.onStatusChanged(PlaybackStatus.Buffering, 1_000L)
        evaluator.onStatusChanged(PlaybackStatus.Playing, 2_000L)

        evaluator.restartEvidenceWindow()

        assertNull(evaluator.bufferingStartedAtMs)
        val summary = evaluator.summary(2_000L)
        assertEquals(0L, summary.bufferingDurationMs)
        assertEquals(0, summary.bufferingIntervalCount)
        assertFalse(summary.bufferingIntervalOpenedSinceEvidenceRestart)
    }
}
