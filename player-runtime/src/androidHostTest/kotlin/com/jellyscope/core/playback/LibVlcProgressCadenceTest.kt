// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibVlcProgressCadenceTest {
    @Test
    fun activeTickerSlotRestartsAfterReplacementGenerationInvalidation() =
        runTest {
            val owner = LibVlcTickerOwner()
            var starts = 0
            var cancellations = 0

            fun startTicker() =
                owner.start {
                    backgroundScope.launch {
                        starts += 1
                        try {
                            awaitCancellation()
                        } finally {
                            cancellations += 1
                        }
                    }
                }

            startTicker()
            startTicker()
            runCurrent()
            assertEquals(1, starts)

            owner.invalidateGeneration()
            runCurrent()
            assertEquals(1, cancellations)

            startTicker()
            runCurrent()
            assertEquals(2, starts)
            owner.invalidateGeneration()
        }

    @Test
    fun pairedNativeEventsShareOneDedupeAndCadence() {
        val cadence = LibVlcProgressCadence()

        assertTrue(cadence.onNativeSample(nowMs = 0L, sample = sample(1_000L)))
        assertFalse(cadence.onNativeSample(nowMs = 0L, sample = sample(1_000L)))
        assertFalse(cadence.onNativeSample(nowMs = 100L, sample = sample(1_100L)))
        assertTrue(cadence.onNativeSample(nowMs = 250L, sample = sample(1_250L)))
    }

    @Test
    fun tickerPublishesOnlyAfterNativeProgressBecomesStale() {
        val cadence = LibVlcProgressCadence()

        assertTrue(cadence.onNativeSample(nowMs = 0L, sample = sample(1_000L)))
        assertFalse(cadence.onTicker(nowMs = 999L, sample = sample(2_000L)))
        assertTrue(cadence.onTicker(nowMs = 1_000L, sample = sample(2_000L)))

        assertFalse(cadence.onNativeSample(nowMs = 1_100L, sample = sample(2_100L)))
        assertFalse(cadence.onTicker(nowMs = 2_099L, sample = sample(3_000L)))
        assertTrue(cadence.onTicker(nowMs = 2_100L, sample = sample(3_000L)))
    }

    @Test
    fun seekCompletionForcesImmediatePublicationAndResetStartsFresh() {
        val cadence = LibVlcProgressCadence()

        assertTrue(cadence.onNativeSample(nowMs = 0L, sample = sample(10_000L)))
        assertTrue(cadence.onNativeSample(nowMs = 10L, sample = sample(20_050L), force = true))
        assertFalse(cadence.onNativeSample(nowMs = 20L, sample = sample(20_100L)))

        cadence.reset()

        assertTrue(cadence.onNativeSample(nowMs = 20L, sample = sample(20_100L)))
    }

    @Test
    fun statusAndDurationEdgesPublishImmediatelyAtTheSamePosition() {
        val cadence = LibVlcProgressCadence()

        assertTrue(cadence.onNativeSample(nowMs = 0L, sample = sample(1_000L, status = PlaybackStatus.Buffering)))
        assertTrue(cadence.onNativeSample(nowMs = 10L, sample = sample(1_000L, status = PlaybackStatus.Playing)))
        assertTrue(cadence.onNativeSample(nowMs = 20L, sample = sample(1_000L, durationMs = 20_000L)))
        assertFalse(cadence.onNativeSample(nowMs = 30L, sample = sample(1_000L, durationMs = 20_000L)))
    }

    @Test
    fun inactiveIntervalResetAdmitsTheFirstResumedSample() {
        val cadence = LibVlcProgressCadence()

        assertTrue(cadence.onNativeSample(nowMs = 0L, sample = sample(1_000L)))
        cadence.reset()

        assertTrue(cadence.onNativeSample(nowMs = 10L, sample = sample(1_000L)))
    }

    private fun sample(
        positionMs: Long,
        durationMs: Long? = 10_000L,
        status: PlaybackStatus = PlaybackStatus.Playing,
    ) = LibVlcProgressSample(positionMs = positionMs, durationMs = durationMs, status = status)
}
