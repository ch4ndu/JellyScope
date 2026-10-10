// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackRuntimeDiagnosticsTest {
    @Test
    fun sumsDroppedFramesAcrossAccessLogPlaybackPeriods() {
        assertEquals(7L, sumValidDroppedFrames(listOf(2L, 5L)))
    }

    @Test
    fun skipsNegativeAccessLogSentinels() {
        assertEquals(3L, sumValidDroppedFrames(listOf(-1L, 3L, -1L)))
    }

    @Test
    fun returnsNullWhenEveryAccessLogCountIsUnknown() {
        assertNull(sumValidDroppedFrames(listOf(-1L, -5L)))
    }

    @Test
    fun preservesZeroWhenFirstValidAccessLogCountHasNoDrops() {
        assertEquals(0L, sumValidDroppedFrames(listOf(0L)))
    }

    @Test
    fun absentOrAmbiguousRendererTargetNeverClaimsRangeOrConversion() {
        fun sample(
            target: String?,
            videoPresent: Boolean = true,
        ) = PlaybackOutputColor.fromMpv(
            videoPresent = videoPresent,
            inputTransfer = "pq",
            inputPrimaries = "bt.2020",
            targetTransfer = target,
            targetPrimaries = "bt.709",
        )

        val linear = sample("linear")
        assertEquals(PlaybackColorTransfer.Linear, linear.targetTransfer)
        assertEquals(PlaybackOutputColorEvidence.NativeTarget, linear.evidence)
        listOf(linear, sample(null), sample("not-a-transfer"), sample("bt.1886", videoPresent = false)).forEach { color ->
            assertEquals(PlaybackRendererRange.Unknown, color.rendererRange)
            assertEquals(PlaybackOutputColorConversion.Unknown, color.conversion)
            assertEquals(PlaybackPhysicalDisplayConfirmation.Unknown, color.physicalDisplay)
        }
        assertNull(sample(null).targetPrimaries)
        assertEquals(PlaybackOutputColorEvidence.Unavailable, sample(null).evidence)
        assertEquals(PlaybackOutputColor.Unknown, sample("bt.1886", videoPresent = false))

        // Control: a recognized SDR target is mapped, but only as a renderer target observation.
        val sdrTarget = sample("bt.1886")
        assertEquals(PlaybackRendererRange.Sdr, sdrTarget.rendererRange)
        assertEquals(PlaybackOutputColorConversion.HdrInputToSdrTarget, sdrTarget.conversion)
        assertEquals(PlaybackPhysicalDisplayConfirmation.Unknown, sdrTarget.physicalDisplay)
    }
}
