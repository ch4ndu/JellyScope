// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DroppedFrameRateTest {
    @Test
    fun aMedia3BatchIsScoredOverItsOwnAccumulationInterval() {
        // Media3 can report one
        // 50-frame batch, and the interval it accumulated over says 2 fps.
        assertEquals(2.0, droppedFrameRatePerSecond(droppedFrames = 50L, elapsedMs = 25_000L))
    }

    @Test
    fun severeDroppingScoresProportionallyHigher() {
        assertEquals(10.0, droppedFrameRatePerSecond(droppedFrames = 50L, elapsedMs = 5_000L))
    }

    @Test
    fun aMissingIntervalIsNullRatherThanZeroOrInfinite() {
        assertNull(droppedFrameRatePerSecond(droppedFrames = 50L, elapsedMs = 0L))
        assertNull(droppedFrameRatePerSecond(droppedFrames = 50L, elapsedMs = -1L))
    }

    @Test
    fun aNegativeCountIsNullBecauseACounterResetIsNotAMeasurement() {
        assertNull(droppedFrameRatePerSecond(droppedFrames = -5L, elapsedMs = 1_000L))
    }

    @Test
    fun aCalmIntervalScoresZeroRatherThanNull() {
        // Zero drops over a real interval is a measurement, not missing data.
        assertEquals(0.0, droppedFrameRatePerSecond(droppedFrames = 0L, elapsedMs = 1_000L))
    }

    @Test
    fun theFirstPollOnlyEstablishesABaseline() {
        val poll = droppedFramePoll(previousCount = null, previousTimeMs = null, currentCount = 120L, nowMs = 1_000L)

        assertNull(poll.ratePerSecond)
        assertEquals(120L, poll.baselineCount)
        assertEquals(1_000L, poll.baselineTimeMs)
    }

    @Test
    fun aSubsequentPollRatesTheDeltaOverItsOwnInterval() {
        val poll = droppedFramePoll(previousCount = 120L, previousTimeMs = 1_000L, currentCount = 130L, nowMs = 6_000L)

        assertEquals(2.0, poll.ratePerSecond)
        assertEquals(130L, poll.baselineCount)
        assertEquals(6_000L, poll.baselineTimeMs)
    }

    @Test
    fun anUnavailableCounterDropsTheBaselineEntirely() {
        val poll = droppedFramePoll(previousCount = 120L, previousTimeMs = 1_000L, currentCount = null, nowMs = 6_000L)

        assertNull(poll.ratePerSecond)
        assertNull(poll.baselineCount)
        assertNull(poll.baselineTimeMs)
    }

    @Test
    fun aCounterThatWentBackwardsResetsRatherThanReportingNegativeDropping() {
        // A fresh media item or a native reset rewinds lostPictures.
        val poll = droppedFramePoll(previousCount = 500L, previousTimeMs = 1_000L, currentCount = 3L, nowMs = 6_000L)

        assertNull(poll.ratePerSecond)
        assertNull(poll.baselineCount)
        assertNull(poll.baselineTimeMs)
    }

    @Test
    fun aNonAdvancingClockResetsInsteadOfDividingByZero() {
        val poll = droppedFramePoll(previousCount = 120L, previousTimeMs = 6_000L, currentCount = 130L, nowMs = 6_000L)

        assertNull(poll.ratePerSecond)
        assertNull(poll.baselineCount)
    }

    @Test
    fun aQuietPollReportsZeroAndKeepsTheBaselineMoving() {
        val poll = droppedFramePoll(previousCount = 120L, previousTimeMs = 1_000L, currentCount = 120L, nowMs = 2_000L)

        assertEquals(0.0, poll.ratePerSecond)
        assertEquals(120L, poll.baselineCount)
        assertEquals(2_000L, poll.baselineTimeMs)
    }

    @Test
    fun anMpvPollUsesTheGreaterCounterDeltaWithoutDoubleCounting() {
        val poll =
            mpvDroppedFramePoll(
                previousOutputCount = 100L,
                previousDecoderCount = 100L,
                previousTimeNanos = 1_000_000_000L,
                currentOutputCount = 110L,
                currentDecoderCount = 104L,
                nowNanos = 3_000_000_000L,
            )

        val measurement = assertNotNull(poll.measurement)
        assertEquals(10L, measurement.droppedFrames)
        assertEquals(2_000L, measurement.intervalMs)
        assertEquals(5.0, measurement.ratePerSecond)
        assertEquals(110L, poll.outputBaselineCount)
        assertEquals(104L, poll.decoderBaselineCount)
    }

    @Test
    fun anMpvPollCanUseTheDecoderDeltaWhenItIsTheLargerView() {
        val poll =
            mpvDroppedFramePoll(
                previousOutputCount = 100L,
                previousDecoderCount = 100L,
                previousTimeNanos = 1_000_000_000L,
                currentOutputCount = 102L,
                currentDecoderCount = 112L,
                nowNanos = 2_000_000_000L,
            )

        assertEquals(12L, assertNotNull(poll.measurement).droppedFrames)
    }

    @Test
    fun anMpvCounterRegressionResetsBothBaselines() {
        val poll =
            mpvDroppedFramePoll(
                previousOutputCount = 100L,
                previousDecoderCount = 100L,
                previousTimeNanos = 1_000_000_000L,
                currentOutputCount = 99L,
                currentDecoderCount = 101L,
                nowNanos = 2_000_000_000L,
            )

        assertNull(poll.measurement)
        assertNull(poll.outputBaselineCount)
        assertNull(poll.decoderBaselineCount)
        assertNull(poll.baselineTimeNanos)
    }

    @Test
    fun anMpvUnavailableCounterResetsTheSamplingBaseline() {
        val poll =
            mpvDroppedFramePoll(
                previousOutputCount = 100L,
                previousDecoderCount = 100L,
                previousTimeNanos = 1_000_000_000L,
                currentOutputCount = null,
                currentDecoderCount = 101L,
                nowNanos = 2_000_000_000L,
            )

        assertNull(poll.measurement)
        assertNull(poll.outputBaselineCount)
        assertNull(poll.decoderBaselineCount)
        assertNull(poll.baselineTimeNanos)
    }

    @Test
    fun aMeasurementCarriesBothItsPayloadAndTheRateDerivedFromIt() {
        val measurement = assertNotNull(DroppedFrameMeasurement.create(droppedFrames = 50L, intervalMs = 25_000L))

        assertEquals(50L, measurement.droppedFrames)
        assertEquals(25_000L, measurement.intervalMs)
        assertEquals(2.0, measurement.ratePerSecond)
    }

    @Test
    fun anUnratableReportProducesNoMeasurementAtAll() {
        // The detector never has to interpret a half-formed measurement: the
        // factory refuses to build one, so nothing is emitted.
        assertNull(DroppedFrameMeasurement.create(droppedFrames = 50L, intervalMs = 0L))
        assertNull(DroppedFrameMeasurement.create(droppedFrames = 50L, intervalMs = -1L))
        assertNull(DroppedFrameMeasurement.create(droppedFrames = -5L, intervalMs = 1_000L))
    }

    @Test
    fun aCalmMeasurementIsStillAMeasurement() {
        val measurement = assertNotNull(DroppedFrameMeasurement.create(droppedFrames = 0L, intervalMs = 1_000L))

        assertEquals(0.0, measurement.ratePerSecond)
    }
}
