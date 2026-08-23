// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.data.remote.buildDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleDecodeCeilingsTest {
    @Test
    fun knownAppleTvModelUsesItsDocumentedTableEntry() {
        val ceilings =
            resolveAppleDecodeCeilings(
                deviceModelIdentifier = "AppleTV11,1",
                platformFamily = ApplePlatformFamily.TvOs,
                backend = PlayerBackend.AVPlayer,
                videoCodecs = listOf("h264", "hevc"),
            )

        assertEquals(VideoCodecResolution(3_840, 2_160, 8_294_400L, 497_664_000L), ceilings["h264"])
        assertEquals(VideoCodecResolution(3_840, 2_160, 8_294_400L, 497_664_000L), ceilings["hevc"])
    }

    @Test
    fun unknownModelsUseTheirPlatformSpecificConservativeFallbacks() {
        val ios =
            resolveAppleDecodeCeilings(
                deviceModelIdentifier = "FuturePhone99,1",
                platformFamily = ApplePlatformFamily.Ios,
                backend = PlayerBackend.AVPlayer,
                videoCodecs = listOf("h264", "hevc", "av1"),
            )
        val tvos =
            resolveAppleDecodeCeilings(
                deviceModelIdentifier = "AppleTV99,1",
                platformFamily = ApplePlatformFamily.TvOs,
                backend = PlayerBackend.AVPlayer,
                videoCodecs = listOf("h264", "hevc"),
            )

        assertEquals(3_840, ios.getValue("h264").maxWidth)
        assertEquals(2_160, ios.getValue("hevc").maxHeight)
        assertEquals(248_832_000L, ios.getValue("av1").maxFrameAreaPerSecond)
        assertEquals(1_920, tvos.getValue("h264").maxWidth)
        assertEquals(125_337_600L, tvos.getValue("h264").maxFrameAreaPerSecond)
        assertEquals(62_668_800L, tvos.getValue("hevc").maxFrameAreaPerSecond)
    }

    @Test
    fun vlcKitUsesTheUniversalIosSafetyEnvelopeWithoutReusingAvPlayerCodecFiltering() {
        val codecs = listOf("h264", "hevc", "vp9", "av1")
        val avPlayer =
            resolveAppleDecodeCeilings(
                deviceModelIdentifier = "FuturePhone99,1",
                platformFamily = ApplePlatformFamily.Ios,
                backend = PlayerBackend.AVPlayer,
                videoCodecs = codecs,
            )
        val vlcKit =
            resolveAppleDecodeCeilings(
                deviceModelIdentifier = "FuturePhone99,1",
                platformFamily = ApplePlatformFamily.Ios,
                backend = PlayerBackend.VlcKit,
                videoCodecs = codecs,
            )

        codecs.forEach { codec ->
            assertEquals(VideoCodecResolution(3_840, 2_160, 8_294_400L, 248_832_000L), vlcKit[codec])
        }
        assertEquals(VideoCodecResolution(), avPlayer["vp9"])

        val capabilities =
            vlcKitDeviceCapabilities(
                videoResolutionsByCodec = vlcKit,
            )
        codecs.forEach { codec ->
            assertEquals(
                setOf(CapabilityEvidenceSource.StaticDeclaration),
                capabilities.videoCodecEvidence.getValue(codec).finiteLimitSources,
            )
        }
    }

    @Test
    fun bothAppleCapabilitySetsFilterFutureMapGapsAndEmitBoundedProfiles() {
        val avPlayerCodecs = listOf("h264", "hevc")
        val avPlayer =
            appleAvPlayerDeviceCapabilities(
                videoCodecs = avPlayerCodecs,
                supportsHdr = false,
                videoResolutionsByCodec =
                    resolveAppleDecodeCeilings(
                        deviceModelIdentifier = "FuturePhone99,1",
                        platformFamily = ApplePlatformFamily.Ios,
                        backend = PlayerBackend.AVPlayer,
                        videoCodecs = avPlayerCodecs,
                    ) - "hevc",
            )
        assertEquals(avPlayerCodecs, avPlayer.videoCodecs)
        assertEquals(avPlayerCodecs, avPlayer.directPlayProfiles.single().videoCodecs)

        val vlcKitCodecs = vlcKitDeviceProfileDeclaration.videoCodecs
        val vlcKit =
            vlcKitDeviceCapabilities(
                videoResolutionsByCodec =
                    resolveAppleDecodeCeilings(
                        deviceModelIdentifier = "FuturePhone99,1",
                        platformFamily = ApplePlatformFamily.Ios,
                        backend = PlayerBackend.VlcKit,
                        videoCodecs = vlcKitCodecs,
                    ) - "vp9",
            )
        assertTrue("vp9" in vlcKit.videoCodecs)
        assertTrue("vp9" in vlcKit.directPlayProfiles.single().videoCodecs)

        val completeVlcKit = vlcKitDeviceCapabilities()
        assertEquals(vlcKitDeviceProfileDeclaration.videoCodecs, completeVlcKit.videoCodecs)
        assertEquals(
            vlcKitDeviceProfileDeclaration.videoCodecs.toSet(),
            completeVlcKit.videoResolutionsByCodec.keys,
        )

        val completeAvPlayer =
            appleAvPlayerDeviceCapabilities(
                videoCodecs = avPlayerCodecs,
                supportsHdr = false,
                videoResolutionsByCodec =
                    resolveAppleDecodeCeilings(
                        deviceModelIdentifier = "FuturePhone99,1",
                        platformFamily = ApplePlatformFamily.Ios,
                        backend = PlayerBackend.AVPlayer,
                        videoCodecs = avPlayerCodecs,
                    ),
            )
        val conditions =
            buildDeviceProfile(completeAvPlayer, maxStreamingBitrate = null)
                .codecProfiles
                .first { profile -> profile.type == "Video" && profile.codec == "h264" }
                .conditions
                .map { condition -> condition.property }
        assertTrue("Width" in conditions)
        assertTrue("Height" in conditions)

        assertEquals(
            "/videos/item/master.m3u8?VideoCodec=h264&MaxWidth=3840&MaxHeight=2160&Width=3840&Height=2160",
            transcodeResolutionCap(
                transcodingUrl = "/videos/item/master.m3u8?VideoCodec=h264",
                sourceWidth = 7_680,
                sourceHeight = 4_320,
                videoResolutionsByCodec = completeAvPlayer.videoResolutionsByCodec,
            ),
        )
    }
}
