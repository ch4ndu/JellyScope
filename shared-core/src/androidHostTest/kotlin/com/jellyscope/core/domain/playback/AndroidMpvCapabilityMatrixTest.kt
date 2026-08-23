// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMpvCapabilityMatrixTest {
    @Test
    fun pinnedMatrixClaimsDecodedSdrPlaybackWithoutPassthrough() {
        val matrix = androidMpvCapabilityMatrix()
        val capabilities = matrix.toDeviceDecodingCapabilities()

        assertEquals("0.41.0", matrix.engineVersion)
        assertTrue("mkv" in matrix.supportedContainers)
        assertTrue("hevc" in matrix.supportedVideoCodecs)
        assertTrue("truehd" in matrix.supportedAudioCodecs)
        assertFalse(capabilities.supportsHdr)
        assertFalse(capabilities.supportsDolbyVision)
        assertEquals(emptyList<String>(), capabilities.audioPassthroughCodecs)
        assertEquals(8, capabilities.maxAudioChannels)
        assertEquals(null, capabilities.videoConstraintsByCodec.getValue("hevc").maxBitDepth)

        val textProfile = capabilities.subtitleProfiles.first { profile -> profile.format == "srt" }
        assertEquals(
            listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.External, SubtitleDeliveryMethod.Encode),
            textProfile.deliveryMethods,
        )
        assertEquals(SubtitleKind.Text, textProfile.kind)
        val bitmapProfile = capabilities.subtitleProfiles.first { profile -> profile.format == "pgs" }
        assertEquals(listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode), bitmapProfile.deliveryMethods)
        assertEquals(SubtitleKind.Bitmap, bitmapProfile.kind)
    }

    @Test
    fun onlyCompleteProbedTuplesAreProjectedForDelegatedCodecs() {
        val declared = androidMpvCapabilityMatrix().toDeviceDecodingCapabilities()
        val complete =
            VideoCodecResolution(
                maxWidth = 3_840,
                maxHeight = 2_160,
                maxFrameArea = 3_840L * 2_160L,
                maxFrameAreaPerSecond = 3_840L * 2_160L * 30L,
            )
        val incomplete =
            VideoCodecResolution(
                maxWidth = 1_920,
                maxHeight = 1_080,
                maxFrameArea = 1_920L * 1_080L,
            )
        val projected =
            declared.narrowedToProbedVideoResolutions(
                probed =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264", "mpeg4", "not-declared"),
                        audioCodecs = emptyList(),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec =
                            mapOf(
                                "h264" to complete,
                                "mpeg4" to incomplete,
                                "not-declared" to complete,
                            ),
                    ),
                allowPartialProbeLimits = false,
            )

        assertEquals(complete, projected.videoResolutionsByCodec.getValue("h264"))
        assertEquals(VideoCodecResolution(), projected.videoResolutionsByCodec.getValue("mpeg4"))
        assertEquals(VideoCodecResolution(), projected.videoResolutionsByCodec.getValue("vp9"))
        assertFalse("not-declared" in projected.videoResolutionsByCodec)
    }
}
