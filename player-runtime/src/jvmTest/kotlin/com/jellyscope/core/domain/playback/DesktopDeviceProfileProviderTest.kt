// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.data.remote.buildDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DesktopDeviceProfileProviderTest {
    @Test
    fun desktopMpvAndLibVlcAdvertiseIndependentHdrCapabilitiesAndWireProfiles() {
        val provider = DesktopDeviceProfileProvider(hostOsName = "Linux")
        val mpv = provider.capabilities(PlayerBackend.Mpv)
        val libVlc = provider.capabilities(PlayerBackend.LibVlc)

        assertTrue(mpv.supportsHdr)
        assertEquals(VideoRangeCapabilities(supportsHdr10 = true), mpv.videoRangeCapabilitiesByCodec.getValue("hevc"))

        assertFalse(libVlc.supportsHdr)
        assertEquals(VideoRangeCapabilities(), libVlc.videoRangeCapabilitiesByCodec.getValue("hevc"))

        assertEquals(
            "SDR|HDR10|HDR10Plus|DOVIWithHDR10|DOVIWithHDR10Plus|DOVIWithSDR",
            videoRangeConditions(buildDeviceProfile(mpv, maxStreamingBitrate = null)).getValue("hevc"),
        )
    }

    @Test
    fun advertisesMpvAudioDecodeAndDownmixCapabilities() {
        val provider = DesktopDeviceProfileProvider(hostOsName = "Mac OS X")
        val capabilities = provider.capabilities(PlayerBackend.Mpv)
        val expectedAudioCodecs = listOf("aac", "mp3", "flac", "ac3", "eac3", "opus", "vorbis", "pcm", "dts", "dca", "truehd", "mp2")

        assertEquals(8, capabilities.maxAudioChannels)
        assertEquals(expectedAudioCodecs, capabilities.audioCodecs)
        assertEquals(expectedAudioCodecs, capabilities.directPlayProfiles.single().audioCodecs)
        assertTrue(capabilities.supportsHdr)
        assertEquals(VideoRangeCapabilities(), capabilities.videoRangeCapabilitiesByCodec.getValue("h264"))
        assertTrue(capabilities.videoRangeCapabilitiesByCodec.getValue("hevc").supportsHdr10)
        assertTrue(capabilities.videoRangeCapabilitiesByCodec.getValue("vp9").supportsHdr10)
        assertTrue(capabilities.videoRangeCapabilitiesByCodec.getValue("av1").supportsHdr10)
        assertFalse(capabilities.videoRangeCapabilitiesByCodec.values.any { it.supportsDolbyVision })
    }

    @Test
    fun preservesTheSameUnboundedUnknownResolutionPolicyForBothDesktopBackends() {
        val provider = DesktopDeviceProfileProvider(hostOsName = "Windows 11")
        val mpv = provider.capabilities(PlayerBackend.Mpv)
        val libVlc = provider.capabilities(PlayerBackend.LibVlc)

        assertNotSame(mpv, libVlc)
        assertEquals(
            setOf(CapabilityEvidenceSource.PinnedEngineDeclaration),
            mpv.videoCodecEvidence.getValue("h264").decodeSources,
        )
        assertEquals(
            setOf(CapabilityEvidenceSource.StaticDeclaration),
            libVlc.videoCodecEvidence.getValue("h264").decodeSources,
        )
        assertEquals(
            setOf(CapabilityEvidenceSource.Unknown),
            libVlc.videoCodecEvidence.getValue("h264").finiteLimitSources,
        )

        listOf(mpv, libVlc).forEach { capabilities ->

            assertTrue(capabilities.videoCodecs.isNotEmpty())
            assertTrue(capabilities.videoResolutionsByCodec.isEmpty())
            assertEquals(capabilities.videoCodecs, capabilities.directPlayProfiles.single().videoCodecs)
        }
    }

    @Test
    fun macOsBoundsOnlyLibVlcToTheStandard4k60EquivalentInputEnvelope() {
        val provider = DesktopDeviceProfileProvider(hostOsName = "Mac OS X")
        val mpv = provider.capabilities(PlayerBackend.Mpv)
        val libVlc = provider.capabilities(PlayerBackend.LibVlc)
        val expectedEnvelope =
            VideoCodecResolution(
                maxWidth = 3_840,
                maxHeight = 2_160,
                maxFrameArea = 8_294_400L,
                maxFrameAreaPerSecond = 497_664_000L,
            )

        assertTrue(mpv.videoResolutionsByCodec.isEmpty())
        assertFalse(mpv.hasUserOverridableVideoInputEnvelope)
        assertEquals(libVlc.videoCodecs.toSet(), libVlc.videoResolutionsByCodec.keys)
        assertTrue(libVlc.videoResolutionsByCodec.values.all { resolution -> resolution == expectedEnvelope })
        assertTrue(libVlc.hasUserOverridableVideoInputEnvelope)
        libVlc.videoCodecEvidence.values.forEach { evidence ->
            assertEquals(
                setOf(CapabilityEvidenceSource.StaticDeclaration),
                evidence.finiteLimitSources,
            )
        }
    }

    @Test
    fun macOsLibVlcProfileWiresOnlyTheEnvelopeWidthAndHeight() {
        val capabilities =
            DesktopDeviceProfileProvider(hostOsName = "macOS")
                .capabilities(PlayerBackend.LibVlc)
        val profile = buildDeviceProfile(capabilities, maxStreamingBitrate = null)
        val videoProfiles = profile.codecProfiles.filter { codecProfile -> codecProfile.type == "Video" }

        assertEquals(capabilities.videoCodecs.toSet(), videoProfiles.map { codecProfile -> codecProfile.codec }.toSet())
        videoProfiles.forEach { codecProfile ->
            assertEquals("3840", codecProfile.conditions.single { condition -> condition.property == "Width" }.value)
            assertEquals("2160", codecProfile.conditions.single { condition -> condition.property == "Height" }.value)
            assertTrue(
                codecProfile.conditions.none { condition ->
                    condition.property in setOf("VideoFramerate", "FrameArea", "FrameAreaPerSecond")
                },
            )
        }
    }
}

private fun videoRangeConditions(profile: com.jellyscope.core.data.remote.PlaybackDeviceProfileDto): Map<String, String> =
    profile.codecProfiles
        .filter { codecProfile -> codecProfile.type == "Video" }
        .associate { codecProfile ->
            codecProfile.codec to codecProfile.conditions.single { condition -> condition.property == "VideoRangeType" }.value
        }
