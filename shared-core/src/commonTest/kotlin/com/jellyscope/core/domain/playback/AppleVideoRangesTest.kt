// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.data.remote.buildDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppleVideoRangesTest {
    @Test
    fun hdrDisplayClaimsUseTheSharedMapperPolicyForAppleCodecs() {
        val videoCodecs = listOf("h264", "hevc", "av1")
        val ranges = appleVideoRanges(videoCodecs, supportsHdr = true)
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = videoCodecs,
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                    supportsHdr = true,
                    videoRangeCapabilitiesByCodec = ranges,
                ),
                maxStreamingBitrate = null,
            )

        assertEquals(
            mapOf(
                "h264" to "SDR|DOVIWithSDR",
                "hevc" to "SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHDR10Plus|DOVIWithHLG|DOVIWithSDR",
                "av1" to "SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHDR10Plus|DOVIWithHLG|DOVIWithSDR",
            ),
            profile.codecProfiles
                .filter { codecProfile -> codecProfile.type == "Video" }
                .associate { codecProfile ->
                    codecProfile.codec to codecProfile.conditions.single { condition -> condition.property == "VideoRangeType" }.value
                },
        )
        assertFalse(ranges.values.any { capabilities -> capabilities.supportsDolbyVision })
        assertFalse(ranges.values.any { capabilities -> capabilities.supportsDolbyVisionWithEL })
    }

    @Test
    fun unavailableHdrSignalKeepsEveryAppleCodecSdrOnly() {
        assertEquals(
            mapOf(
                "h264" to VideoRangeCapabilities(),
                "hevc" to VideoRangeCapabilities(),
                "av1" to VideoRangeCapabilities(),
            ),
            appleVideoRanges(listOf("h264", "hevc", "av1"), supportsHdr = false),
        )
    }
}
