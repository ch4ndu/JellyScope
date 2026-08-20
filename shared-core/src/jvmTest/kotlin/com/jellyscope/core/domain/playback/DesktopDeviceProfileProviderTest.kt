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
    fun desktopCapabilitiesAndWireProfilesRemainStable() {
        val provider = DesktopDeviceProfileProvider(hostOsName = "Linux")
        val expected = desktopCapabilities()
        val expectedProfile = buildDeviceProfile(expected, maxStreamingBitrate = null)

        listOf(PlayerBackend.Mpv, PlayerBackend.LibVlc).forEach { backend ->
            val actual = provider.capabilities(backend)

            assertEquals(
                expected,
                actual.copy(videoCodecEvidence = emptyMap(), audioCodecEvidence = emptyMap()),
            )
            assertEquals(expectedProfile, buildDeviceProfile(actual, maxStreamingBitrate = null))
        }
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

private fun desktopCapabilities(): DeviceDecodingCapabilities {
    val videoCodecs = listOf("h264", "hevc", "vp9", "av1")
    val audioCodecs = listOf("aac", "mp3", "flac", "ac3", "eac3", "opus", "vorbis", "pcm", "dts", "dca", "truehd", "mp2")
    return DeviceDecodingCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        supportsDolbyVision = false,
        maxAudioChannels = 8,
        videoResolutionsByCodec = emptyMap(),
        supportsHdr = true,
        videoRangeCapabilitiesByCodec =
            mapOf(
                "h264" to VideoRangeCapabilities(),
                "hevc" to VideoRangeCapabilities(supportsHdr10 = true),
                "vp9" to VideoRangeCapabilities(supportsHdr10 = true),
                "av1" to VideoRangeCapabilities(supportsHdr10 = true),
            ),
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts", "avi"),
                    videoCodecs = videoCodecs,
                    audioCodecs = audioCodecs,
                ),
            ),
        subtitleProfiles =
            listOf("vtt", "webvtt", "ttml", "srt", "subrip", "ass", "ssa").map { format ->
                DeviceSubtitleProfile(
                    format = format,
                    deliveryMethods =
                        listOf(
                            SubtitleDeliveryMethod.Embed,
                            SubtitleDeliveryMethod.External,
                            SubtitleDeliveryMethod.Hls,
                            SubtitleDeliveryMethod.Encode,
                        ),
                    kind = SubtitleKind.Text,
                )
            } +
                listOf("pgs", "pgssub", "vobsub", "dvdsub", "dvbsub", "dvb").map { format ->
                    DeviceSubtitleProfile(
                        format = format,
                        deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                        kind = SubtitleKind.Bitmap,
                    )
                },
    )
}
