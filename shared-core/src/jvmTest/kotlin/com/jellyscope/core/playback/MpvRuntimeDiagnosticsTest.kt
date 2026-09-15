// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvRuntimeDiagnosticsTest {
    @Test
    fun mapsMpvPropertiesAndConvertsCacheSpeedToBitsPerSecond() {
        val diagnostics =
            mpvRuntimeDiagnostics(
                videoCodec = "hevc",
                hardwareDecoder = "vaapi",
                width = 3840,
                height = 2160,
                frameRate = 23.976,
                droppedFrames = 4L,
                cacheSpeedBytesPerSecond = 1_250_000L,
                decoderDroppedFrames = 2L,
                presentation =
                    MpvPresentationMetricsSnapshot(
                        renderP95Ms = 8.5,
                        presentedFrameRate = 59.95,
                        presentationGapCount = 3L,
                    ),
                presentationPath = MPV_PRESENTATION_SOFTWARE,
            )

        assertEquals("hevc", diagnostics.videoDecoderName)
        assertEquals(3840, diagnostics.videoWidth)
        assertEquals(2160, diagnostics.videoHeight)
        assertEquals(23.976, diagnostics.videoFrameRate)
        assertEquals(4L, diagnostics.droppedVideoFrames)
        assertEquals(2L, diagnostics.decoderDroppedVideoFrames)
        assertEquals(4L, diagnostics.outputDroppedVideoFrames)
        assertEquals(8.5, diagnostics.recentVideoRenderP95Ms)
        assertEquals(59.95, diagnostics.recentPresentedFrameRate)
        assertEquals(3L, diagnostics.presentationGapCount)
        assertEquals(MPV_PRESENTATION_SOFTWARE, diagnostics.presentationPath)
        assertEquals(10_000_000L, diagnostics.bandwidthEstimateBps)
    }

    @Test
    fun openGlPresentationKeepsMpvCountersAndOmitsSoftwareOnlyMetrics() {
        val diagnostics =
            mpvRuntimeDiagnostics(
                videoCodec = "av1",
                hardwareDecoder = "no",
                width = 7680,
                height = 4320,
                frameRate = 60.0,
                droppedFrames = 2L,
                cacheSpeedBytesPerSecond = 5_500_000L,
                decoderDroppedFrames = 0L,
                presentation = null,
                presentationPath = MPV_PRESENTATION_OPENGL,
            )

        assertEquals(0L, diagnostics.decoderDroppedVideoFrames)
        assertEquals(2L, diagnostics.outputDroppedVideoFrames)
        assertEquals(MPV_PRESENTATION_OPENGL, diagnostics.presentationPath)
        assertNull(diagnostics.recentVideoRenderP95Ms)
        assertNull(diagnostics.recentPresentedFrameRate)
        assertNull(diagnostics.presentationGapCount)
    }

    @Test
    fun mapsUnavailableMpvPropertiesToNull() {
        val diagnostics =
            mpvRuntimeDiagnostics(
                videoCodec = null,
                hardwareDecoder = null,
                width = null,
                height = null,
                frameRate = null,
                droppedFrames = null,
                cacheSpeedBytesPerSecond = null,
            )

        assertNull(diagnostics.videoDecoderName)
        assertNull(diagnostics.videoWidth)
        assertNull(diagnostics.videoHeight)
        assertNull(diagnostics.videoFrameRate)
        assertNull(diagnostics.droppedVideoFrames)
        assertNull(diagnostics.decoderDroppedVideoFrames)
        assertNull(diagnostics.outputDroppedVideoFrames)
        assertNull(diagnostics.recentVideoRenderP95Ms)
        assertNull(diagnostics.recentPresentedFrameRate)
        assertNull(diagnostics.presentationGapCount)
        assertNull(diagnostics.bandwidthEstimateBps)
    }

    @Test
    fun rejectsNegativeMpvDropCounters() {
        val diagnostics =
            mpvRuntimeDiagnostics(
                videoCodec = "av1",
                hardwareDecoder = null,
                width = 7680,
                height = 4320,
                frameRate = 60.0,
                droppedFrames = -1L,
                cacheSpeedBytesPerSecond = 5_500_000L,
                decoderDroppedFrames = -2L,
            )

        assertNull(diagnostics.droppedVideoFrames)
        assertNull(diagnostics.decoderDroppedVideoFrames)
        assertNull(diagnostics.outputDroppedVideoFrames)
    }
}
