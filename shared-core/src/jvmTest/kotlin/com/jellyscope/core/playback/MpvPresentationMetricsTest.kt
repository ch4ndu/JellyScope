// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvPresentationMetricsTest {
    @Test
    fun calculatesP95FromTheBoundedRecentRenderWindow() {
        val metrics = MpvPresentationMetrics(renderCapacity = 3, publicationCapacity = 4)
        metrics.recordRenderDuration(100_000_000L)
        metrics.recordRenderDuration(1_000_000L)
        metrics.recordRenderDuration(2_000_000L)
        metrics.recordRenderDuration(3_000_000L)

        assertEquals(3.0, metrics.snapshot().renderP95Ms)
    }

    @Test
    fun calculatesPresentedRateFromTheRecentPublicationWindow() {
        val metrics = MpvPresentationMetrics(renderCapacity = 3, publicationCapacity = 5)
        metrics.recordPublication(1_000_000_000L)
        metrics.recordPublication(1_500_000_000L)
        metrics.recordPublication(2_000_000_000L)

        assertEquals(2.0, metrics.snapshot(nowNanos = 2_000_000_000L).presentedFrameRate)
    }

    @Test
    fun ignoresPublicationsOutsideThePresentedRateWindow() {
        val metrics = MpvPresentationMetrics(renderCapacity = 3, publicationCapacity = 5)
        metrics.recordPublication(1_000_000_000L)
        metrics.recordPublication(1_500_000_000L)

        assertNull(metrics.snapshot(nowNanos = 4_000_000_001L).presentedFrameRate)
    }

    @Test
    fun countsOnlyIntervalsBeyondTheFrameRateDerivedGapThreshold() {
        val metrics = MpvPresentationMetrics(renderCapacity = 3, publicationCapacity = 5)
        metrics.setInstalledFrameRate(60.0)
        val threshold = presentationGapThresholdNanos(60.0)
        metrics.recordPublication(1_000_000_000L)
        metrics.recordPublication(1_000_000_000L + threshold)
        metrics.recordPublication(1_000_000_000L + threshold * 2L + 1L)

        assertEquals(1L, metrics.snapshot(nowNanos = 2_000_000_000L).presentationGapCount)
    }

    @Test
    fun cadenceResetPreservesPreparedItemGapCountButBreaksTheInterval() {
        val metrics = MpvPresentationMetrics(renderCapacity = 3, publicationCapacity = 5)
        metrics.setInstalledFrameRate(60.0)
        val threshold = presentationGapThresholdNanos(60.0)
        metrics.recordPublication(1_000_000_000L)
        metrics.recordPublication(1_000_000_000L + threshold + 1L)

        metrics.resetPublicationCadence()
        metrics.recordPublication(9_000_000_000L)

        val snapshot = metrics.snapshot(nowNanos = 9_000_000_000L)
        assertEquals(1L, snapshot.presentationGapCount)
        assertNull(snapshot.presentedFrameRate)
    }

    @Test
    fun fullResetClearsEveryObservation() {
        val metrics = MpvPresentationMetrics(renderCapacity = 3, publicationCapacity = 5)
        metrics.setInstalledFrameRate(60.0)
        metrics.recordRenderDuration(5_000_000L)
        metrics.recordPublication(1_000_000_000L)
        metrics.recordPublication(2_000_000_000L)

        metrics.reset()

        val snapshot = metrics.snapshot(nowNanos = 2_000_000_000L)
        assertNull(snapshot.renderP95Ms)
        assertNull(snapshot.presentedFrameRate)
        assertEquals(0L, snapshot.presentationGapCount)
    }

    @Test
    fun rejectsInvalidSamplesAndUsesAConservativeGapFallback() {
        val metrics = MpvPresentationMetrics(renderCapacity = 3, publicationCapacity = 5)
        metrics.recordRenderDuration(-1L)
        metrics.recordPublication(0L)

        val snapshot = metrics.snapshot()
        assertNull(snapshot.renderP95Ms)
        assertNull(snapshot.presentedFrameRate)
        assertEquals(0L, snapshot.presentationGapCount)
        assertEquals(MPV_PRESENTATION_GAP_FALLBACK_NANOS, presentationGapThresholdNanos(Double.NaN))
        assertTrue(presentationGapThresholdNanos(60.0) >= MPV_PRESENTATION_GAP_MINIMUM_NANOS)
    }
}
