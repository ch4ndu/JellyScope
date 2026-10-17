// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoRangeTypePolicyTest {
    @Test
    fun nonDolbyVisionCapabilitiesAdvertiseOnlyBaseRangesAndFallbacks() {
        assertEquals(
            listOf(
                "SDR",
                "HDR10",
                "HDR10Plus",
                "HLG",
                "DOVIWithHDR10",
                "DOVIWithHDR10Plus",
                "DOVIWithHLG",
                "DOVIWithSDR",
            ),
            VideoRangeTypePolicy.supportedRangeTypes(
                VideoRangeCapabilities(supportsHdr10 = true, supportsHlg = true),
                preferSdr = false,
            ),
        )
    }

    @Test
    fun baseDolbyVisionDoesNotImplyEnhancementLayerSupport() {
        assertEquals(
            listOf(
                "SDR",
                "HDR10",
                "HDR10Plus",
                "DOVI",
                "DOVIWithHDR10",
                "DOVIWithHDR10Plus",
                "DOVIWithHLG",
                "DOVIWithSDR",
            ),
            VideoRangeTypePolicy.supportedRangeTypes(
                VideoRangeCapabilities(supportsHdr10 = true, supportsDolbyVision = true),
                preferSdr = false,
            ),
        )
    }

    @Test
    fun dolbyVisionAloneAdvertisesTheWholeDoviWithFamilyButNotPlainHdrRanges() {
        // A DV decoder renders the DV layer of every DOVIWith* variant even
        // when the display lacks the fallback base-range flags; plain
        // HDR10/HDR10Plus/HLG stay gated on their own capabilities.
        assertEquals(
            listOf(
                "SDR",
                "DOVI",
                "DOVIWithHDR10",
                "DOVIWithHDR10Plus",
                "DOVIWithHLG",
                "DOVIWithSDR",
            ),
            VideoRangeTypePolicy.supportedRangeTypes(
                VideoRangeCapabilities(supportsDolbyVision = true),
                preferSdr = false,
            ),
        )
    }

    @Test
    fun enhancementLayerTypesRequireAndRespectTheirIndependentCapability() {
        assertEquals(
            listOf(
                "SDR",
                "DOVI",
                "DOVIWithHDR10",
                "DOVIWithHDR10Plus",
                "DOVIWithHLG",
                "DOVIWithSDR",
                "DOVIWithEL",
                "DOVIWithELHDR10Plus",
            ),
            VideoRangeTypePolicy.supportedRangeTypes(
                VideoRangeCapabilities(
                    supportsDolbyVision = true,
                    supportsDolbyVisionWithEL = true,
                ),
                preferSdr = false,
            ),
        )
    }

    @Test
    fun hdr10AlsoAllowsHdr10PlusAndItsDolbyVisionFallback() {
        assertEquals(
            listOf(
                "SDR",
                "HDR10",
                "HDR10Plus",
                "DOVIWithHDR10",
                "DOVIWithHDR10Plus",
                "DOVIWithSDR",
            ),
            VideoRangeTypePolicy.supportedRangeTypes(
                VideoRangeCapabilities(supportsHdr10 = true),
                preferSdr = false,
            ),
        )
    }

    @Test
    fun preferSdrUsesOnlySdrAndItsDolbyVisionFallback() {
        assertEquals(
            listOf("SDR", "DOVIWithSDR"),
            VideoRangeTypePolicy.supportedRangeTypes(
                VideoRangeCapabilities(
                    supportsHdr10 = true,
                    supportsHdr10Plus = true,
                    supportsHlg = true,
                    supportsDolbyVision = true,
                    supportsDolbyVisionWithEL = true,
                ),
                preferSdr = true,
            ),
        )
    }

    @Test
    fun unsupportedAndInvalidRangeTypesNeverPassTheAllowlist() {
        val supported = VideoRangeTypePolicy.supportedRangeTypes(VideoRangeCapabilities(), preferSdr = false)

        assertFalse("DOVI" in supported)
        assertFalse("DOVIWithEL" in supported)
        assertFalse("DOVIInvalid" in supported)
        assertFalse("FutureServerRangeType" in supported)
    }

    @Test
    fun explicitExclusionsAreSubtractedAfterCanonicalRangeNormalization() {
        val supported =
            VideoRangeTypePolicy.supportedRangeTypes(
                capabilities =
                    VideoRangeCapabilities(
                        supportsHdr10 = true,
                        supportsHdr10Plus = true,
                        supportsDolbyVision = true,
                        supportsDolbyVisionWithEL = true,
                    ),
                preferSdr = false,
                unsupportedRangeTypes =
                    setOf(
                        "dovi-with-hdr10-plus",
                        "DOVI_WITH_EL_HDR10_PLUS",
                    ),
            )

        assertEquals(
            listOf(
                "SDR",
                "HDR10",
                "HDR10Plus",
                "DOVI",
                "DOVIWithHDR10",
                "DOVIWithHLG",
                "DOVIWithSDR",
                "DOVIWithEL",
            ),
            supported,
        )
    }

    @Test
    fun explicitExclusionLookupCanonicalizesCodecAliasesAndRangeSpelling() {
        val exclusions =
            mapOf(
                "H265" to setOf("DOVIWithHDR10Plus", "DOVIWithELHDR10Plus"),
            )

        assertTrue(
            VideoRangeTypePolicy.isExplicitlyUnsupported(
                codec = "HEVC",
                rangeType = "dovi-with-hdr10-plus",
                unsupportedRangeTypesByCodec = exclusions,
            ),
        )
        assertTrue(
            VideoRangeTypePolicy.isExplicitlyUnsupported(
                codec = "x265",
                rangeType = "DOVI_WITH_EL_HDR10_PLUS",
                unsupportedRangeTypesByCodec = exclusions,
            ),
        )
        assertFalse(
            VideoRangeTypePolicy.isExplicitlyUnsupported(
                codec = "h264",
                rangeType = "DOVIWithHDR10Plus",
                unsupportedRangeTypesByCodec = exclusions,
            ),
        )
        assertFalse(
            VideoRangeTypePolicy.isExplicitlyUnsupported(
                codec = "hevc",
                rangeType = "DOVIWithHDR10",
                unsupportedRangeTypesByCodec = exclusions,
            ),
        )
    }
}
