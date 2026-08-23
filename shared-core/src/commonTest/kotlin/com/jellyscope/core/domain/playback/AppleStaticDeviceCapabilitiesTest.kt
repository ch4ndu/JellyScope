// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.data.remote.ProfileConditionDto
import com.jellyscope.core.data.remote.buildDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleStaticDeviceCapabilitiesTest {
    @Test
    fun advertisesOnlyAvFoundationDecodableAudioCodecsAndSixChannels() {
        val capabilities = appleStaticDeviceCapabilities()

        assertEquals(listOf("aac", "mp3", "ac3", "eac3", "flac", "alac"), capabilities.audioCodecs)
        assertEquals(6, capabilities.maxAudioChannels)
    }

    @Test
    fun hdrDisplayClaimsHdr10AndHlgForHevcAndAv1Only() {
        val ranges = appleVideoRanges(listOf("h264", "hevc", "av1"), supportsHdr = true)

        assertEquals(VideoRangeCapabilities(supportsHdr10 = true, supportsHlg = true), ranges["hevc"])
        assertEquals(VideoRangeCapabilities(supportsHdr10 = true, supportsHlg = true), ranges["av1"])
        // H.264 stays SDR even on an HDR display, and no codec claims Dolby Vision.
        assertEquals(VideoRangeCapabilities(), ranges["h264"])
    }

    @Test
    fun sdrDisplayClaimsNoHdrRanges() {
        val ranges = appleVideoRanges(listOf("h264", "hevc", "av1"), supportsHdr = false)

        assertEquals(VideoRangeCapabilities(), ranges["hevc"])
        assertEquals(VideoRangeCapabilities(), ranges["av1"])
        assertEquals(VideoRangeCapabilities(), ranges["h264"])
    }

    @Test
    fun hdrHevcResolvesToExactWireAllowlistWithoutStandaloneDolbyVision() {
        val hevc = appleVideoRanges(listOf("hevc"), supportsHdr = true).getValue("hevc")

        // The shared range policy derives HDR10Plus from HDR10 plus the whole
        // DOVIWith* fallback family; Apple never advertises standalone "DOVI".
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
            VideoRangeTypePolicy.supportedRangeTypes(hevc, preferSdr = false),
        )

        val sdr = appleVideoRanges(listOf("h264"), supportsHdr = true).getValue("h264")
        assertEquals(
            listOf("SDR", "DOVIWithSDR"),
            VideoRangeTypePolicy.supportedRangeTypes(sdr, preferSdr = false),
        )
    }

    @Test
    fun vlcKitProfileAdvertisesValidatedCodecsContainersAndSubtitleMethods() {
        val capabilities = vlcKitDeviceCapabilities()

        assertTrue("mkv" in capabilities.directPlayProfiles.single().containers)
        assertTrue("webm" in capabilities.directPlayProfiles.single().containers)
        assertTrue("vp9" in capabilities.videoCodecs)
        assertTrue("dts" in capabilities.audioCodecs)
        assertTrue("dca" in capabilities.audioCodecs)
        assertFalse("truehd" in capabilities.audioCodecs)
        assertFalse("mlp" in capabilities.audioCodecs)

        // VLCKit text = Embed (native direct-play tracks) + Encode (server burn-in on
        // transcode), matching AVPlayer: VLC selects but does NOT render an external
        // subtitle slave over an HLS/transcode stream, so External is not advertised.
        val text = capabilities.subtitleProfiles.single { it.format == "srt" }
        assertEquals(listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode), text.deliveryMethods)
        assertFalse(SubtitleDeliveryMethod.External in text.deliveryMethods)

        val bitmap = capabilities.subtitleProfiles.single { it.format == "pgs" }
        assertEquals(listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode), bitmap.deliveryMethods)

        val profile =
            buildDeviceProfile(
                capabilities = capabilities,
                maxStreamingBitrate = null,
                playerSettings = PlayerDeviceSettings(hdrMode = PlayerHdrMode.PreferSdr),
                requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.VlcKit),
            )
        assertEquals(
            "SDR|DOVIWithSDR",
            profile.codecProfiles
                .first { it.type == "Video" }
                .conditions
                .single { it.property == "VideoRangeType" }
                .value,
        )
    }

    @Test
    fun vlcKitPinnedDeclarationIncludesAv1() {
        assertTrue("av1" in vlcKitDeviceProfileDeclaration.videoCodecs)
        assertTrue("av1" in vlcKitDeviceCapabilities().videoCodecs)
    }

    @Test
    fun avPlayerProfilesAreProgressiveOnlyAndHevcAloneGetsTagAndFrameRateLimits() {
        val avPlayerCapabilities =
            appleAvPlayerDeviceCapabilities(
                videoCodecs = listOf("h264", "hevc", "av1"),
                supportsHdr = true,
                videoResolutionsByCodec = emptyMap(),
            )
        val avPlayerProfile = buildDeviceProfile(avPlayerCapabilities, maxStreamingBitrate = null)
        val restrictedProperties = setOf("VideoCodecTag", "VideoFramerate", "IsInterlaced")

        assertEquals(
            listOf(
                ProfileConditionDto("EqualsAny", "VideoCodecTag", "hvc1|dvh1", true),
                ProfileConditionDto("LessThanEqual", "VideoFramerate", "60", true),
                ProfileConditionDto("NotEquals", "IsInterlaced", "true", true),
            ),
            avPlayerProfile.codecProfiles
                .single { profile -> profile.codec == "hevc" }
                .conditions
                .filter { condition -> condition.property in restrictedProperties },
        )
        listOf("h264", "av1").forEach { codec ->
            assertEquals(
                listOf(ProfileConditionDto("NotEquals", "IsInterlaced", "true", true)),
                avPlayerProfile.codecProfiles
                    .single { profile -> profile.codec == codec }
                    .conditions
                    .filter { condition -> condition.property in restrictedProperties },
            )
        }

        val vlcKitProfile =
            buildDeviceProfile(
                vlcKitDeviceCapabilities(),
                maxStreamingBitrate = null,
                requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.VlcKit),
            )
        assertTrue(
            vlcKitProfile.codecProfiles
                .flatMap { profile -> profile.conditions }
                .none { condition -> condition.property in restrictedProperties },
        )
    }

    @Test
    fun avPlayerBackendKeepsTheExistingProfileMappingUnchanged() {
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264", "hevc"),
                audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "flac", "alac"),
                supportsDolbyVision = false,
                directPlayProfiles =
                    listOf(
                        DeviceDirectPlayProfile(
                            containers = listOf("mp4", "m4v", "mov", "mpegts", "ts"),
                            videoCodecs = listOf("h264", "hevc"),
                            audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "flac", "alac"),
                        ),
                    ),
                subtitleProfiles =
                    listOf(
                        DeviceSubtitleProfile(
                            "vtt",
                            listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                            SubtitleKind.Text,
                        ),
                        DeviceSubtitleProfile(
                            "webvtt",
                            listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                            SubtitleKind.Text,
                        ),
                        DeviceSubtitleProfile("srt", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
                    ),
            )

        val defaultProfile = buildDeviceProfile(capabilities, maxStreamingBitrate = null)
        val avPlayerProfile =
            buildDeviceProfile(
                capabilities,
                maxStreamingBitrate = null,
                requestPolicy = PlaybackInfoRequestPolicy(backend = PlayerBackend.AVPlayer),
            )

        assertEquals(defaultProfile, avPlayerProfile)
        assertFalse(
            avPlayerProfile.directPlayProfiles
                .first()
                .container
                .contains("mkv"),
        )
        assertFalse(avPlayerProfile.subtitleProfiles.any { it.method == "External" })
    }
}
