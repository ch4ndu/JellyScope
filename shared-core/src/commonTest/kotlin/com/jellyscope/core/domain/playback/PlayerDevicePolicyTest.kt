// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDevicePolicyTest {
    @Test
    fun videoResolutionLimitsMapToTheStableDimensionTable() {
        assertNull(PlayerVideoResolutionLimit.Unlimited.resolutionCap)
        assertEquals(VideoCodecResolution(7_680, 4_320), PlayerVideoResolutionLimit.Height4320.resolutionCap)
        assertEquals(VideoCodecResolution(3_840, 2_160), PlayerVideoResolutionLimit.Height2160.resolutionCap)
        assertEquals(VideoCodecResolution(2_560, 1_440), PlayerVideoResolutionLimit.Height1440.resolutionCap)
        assertEquals(VideoCodecResolution(1_920, 1_080), PlayerVideoResolutionLimit.Height1080.resolutionCap)
        assertEquals(VideoCodecResolution(1_280, 720), PlayerVideoResolutionLimit.Height720.resolutionCap)
        assertEquals(VideoCodecResolution(854, 480), PlayerVideoResolutionLimit.Height480.resolutionCap)
        assertEquals(VideoCodecResolution(640, 360), PlayerVideoResolutionLimit.Height360.resolutionCap)
    }

    @Test
    fun resolutionReconciliationUsesTheTightestFiniteFieldAcrossAllThreeBounds() {
        val effective =
            reconcileVideoResolutionBounds(
                deviceCeiling =
                    VideoCodecResolution(
                        maxWidth = 3_840,
                        maxHeight = 2_160,
                        maxFrameArea = 8_000_000,
                    ),
                qualityRungCeiling =
                    VideoCodecResolution(
                        maxWidth = 2_560,
                        maxHeight = 1_440,
                        maxFrameAreaPerSecond = 90_000_000,
                    ),
                userResolutionCeiling = VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080),
            )

        assertEquals(
            VideoCodecResolution(
                maxWidth = 1_920,
                maxHeight = 1_080,
                maxFrameArea = 8_000_000,
                maxFrameAreaPerSecond = 90_000_000,
            ),
            effective,
        )
    }

    @Test
    fun onlyTheCompleteAvPlayerPolicyIsDefault() {
        assertTrue(PlaybackInfoRequestPolicy().isDefault)
        assertTrue(PlaybackInfoRequestPolicy(diagnosticSessionSequence = 77L).isDefault)
        assertFalse(PlaybackInfoRequestPolicy(backend = PlayerBackend.VlcKit).isDefault)
        assertFalse(PlaybackInfoRequestPolicy(enableDirectPlay = false).isDefault)
        assertFalse(PlaybackInfoRequestPolicy(forceEncodeSubtitle = ForceEncodeSubtitle(1, "srt")).isDefault)
    }

    @Test
    fun refreshRateMatchingDefaultsOffAndIsRetainedInSettings() {
        assertFalse(PlayerDeviceSettings().matchDisplayRefreshRate)
        assertTrue(PlayerDeviceSettings().copy(matchDisplayRefreshRate = true).matchDisplayRefreshRate)
    }

    @Test
    fun unrestrictedCompatibilityRemovesOnlyTheUserOverridableInputEnvelope() {
        val envelope = VideoCodecResolution(3_840, 2_160, 8_294_400, 248_832_000)
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                videoResolutionsByCodec = mapOf("h264" to envelope),
                videoCodecEvidence =
                    mapOf(
                        "h264" to
                            CodecCapabilityEvidence(
                                decodeSources = setOf(CapabilityEvidenceSource.StaticDeclaration),
                                finiteLimitSources = setOf(CapabilityEvidenceSource.StaticDeclaration),
                            ),
                    ),
                unsupportedVideoRangeTypesByCodec =
                    mapOf("h264" to setOf("DOVIWithHDR10Plus")),
                hasUserOverridableVideoInputEnvelope = true,
            )

        val standard = resolvePlayerDevicePolicy(capabilities, PlayerDeviceSettings())
        val unrestricted =
            resolvePlayerDevicePolicy(
                capabilities,
                PlayerDeviceSettings(iosPlaybackCompatibilityMode = IosPlaybackCompatibilityMode.Unrestricted),
            )

        assertEquals(envelope, standard.capabilities.videoResolutionsByCodec["h264"])
        assertTrue(unrestricted.capabilities.videoResolutionsByCodec.isEmpty())
        assertEquals(
            setOf(CapabilityEvidenceSource.StaticDeclaration),
            unrestricted.capabilities.videoCodecEvidence
                .getValue("h264")
                .decodeSources,
        )
        assertEquals(
            setOf(CapabilityEvidenceSource.Unknown),
            unrestricted.capabilities.videoCodecEvidence
                .getValue("h264")
                .finiteLimitSources,
        )
        assertEquals(capabilities.directPlayProfiles, unrestricted.capabilities.directPlayProfiles)
        assertEquals(
            capabilities.unsupportedVideoRangeTypesByCodec,
            unrestricted.capabilities.unsupportedVideoRangeTypesByCodec,
        )
    }

    @Test
    fun autoAudioUsesPassthroughCodecsOnlyWhenRouteReportsThem() {
        val policy =
            resolvePlayerDevicePolicy(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac", "mp3"),
                        supportsDolbyVision = false,
                        audioPassthroughCodecs = listOf("ac3", "eac3"),
                        maxAudioChannels = 6,
                    ),
                settings = PlayerDeviceSettings(audioMode = PlayerAudioMode.Auto),
            )

        assertEquals(PlayerAudioMode.Auto, policy.effectiveAudioMode)
        assertEquals(listOf("aac", "mp3", "ac3", "eac3"), policy.audioCodecs)
        assertEquals(6, policy.maxAudioChannels)
        assertTrue(policy.allowAudioStreamCopy)
    }

    @Test
    fun unsupportedPassthroughFallsBackToAutoButPreservesSavedSetting() {
        val settings = PlayerDeviceSettings(audioMode = PlayerAudioMode.PassthroughWhenSupported)

        val policy =
            resolvePlayerDevicePolicy(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac", "mp3"),
                        supportsDolbyVision = false,
                    ),
                settings = settings,
            )

        assertEquals(settings, policy.settings)
        assertEquals(PlayerAudioMode.Auto, policy.effectiveAudioMode)
        assertFalse(policy.audioModeSupported)
        assertEquals(PlayerSettingDisabledReason.AudioRouteUnsupported, policy.audioDisabledReason)
    }

    @Test
    fun stereoPcmConstrainsAudioChannelsAndDisablesAudioStreamCopy() {
        val policy =
            resolvePlayerDevicePolicy(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac", "mp3", "ac3"),
                        supportsDolbyVision = false,
                        audioPassthroughCodecs = listOf("ac3"),
                        maxAudioChannels = 8,
                    ),
                settings = PlayerDeviceSettings(audioMode = PlayerAudioMode.StereoPcm),
            )

        assertEquals(PlayerAudioMode.StereoPcm, policy.effectiveAudioMode)
        assertEquals(listOf("aac", "mp3"), policy.audioCodecs)
        assertEquals(2, policy.maxAudioChannels)
        assertFalse(policy.allowAudioStreamCopy)
    }

    @Test
    fun autoHdrFallsBackToPreferSdrWhenDisplayDoesNotReportHdr() {
        val settings = PlayerDeviceSettings(hdrMode = PlayerHdrMode.Auto)

        val policy =
            resolvePlayerDevicePolicy(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        supportsHdr = false,
                    ),
                settings = settings,
            )

        assertEquals(settings, policy.settings)
        assertEquals(PlayerHdrMode.PreferSdr, policy.effectiveHdrMode)
        assertFalse(policy.hdrModeSupported)
        assertEquals(PlayerSettingDisabledReason.HdrDisplayUnsupported, policy.hdrDisabledReason)
        // Copy stays allowed under PreferSdr: HDR sources are excluded from
        // copy by the VideoRangeType conditions, and SDR sources must remux.
        assertTrue(policy.allowVideoStreamCopy)
    }

    @Test
    fun autoHdrRemainsAutoWhenDisplayReportsHdr() {
        val settings = PlayerDeviceSettings(hdrMode = PlayerHdrMode.Auto)

        val policy =
            resolvePlayerDevicePolicy(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264", "hevc"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        supportsHdr = true,
                    ),
                settings = settings,
            )

        assertEquals(settings, policy.settings)
        assertEquals(PlayerHdrMode.Auto, policy.effectiveHdrMode)
        assertTrue(policy.hdrModeSupported)
        assertEquals(null, policy.hdrDisabledReason)
    }
}
