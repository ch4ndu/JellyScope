// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceDirectPlayProfile
import com.jellyscope.core.domain.playback.DeviceSubtitleProfile
import com.jellyscope.core.domain.playback.ForceEncodeSubtitle
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.SubtitleDeliveryMethod
import com.jellyscope.core.domain.playback.SubtitleKind
import com.jellyscope.core.domain.playback.VideoCodecConstraints
import com.jellyscope.core.domain.playback.VideoCodecResolution
import com.jellyscope.core.domain.playback.VideoRangeCapabilities
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceProfileDtoMapperTest {
    @Test
    fun userResolutionCapTightensDeviceProfileWithoutChangingBitratePolicy() {
        val profile =
            buildDeviceProfile(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 3_840, maxHeight = 2_160)),
                    ),
                maxStreamingBitrate = null,
                requestPolicy =
                    PlaybackInfoRequestPolicy(
                        userVideoResolutionCap = VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080),
                        userVideoResolutionCapIsResolved = true,
                    ),
            )

        val conditions = profile.codecProfiles.single { it.codec == "h264" }.conditions
        assertEquals("1920", conditions.single { it.property == "Width" }.value)
        assertEquals("1080", conditions.single { it.property == "Height" }.value)
        assertEquals(Int.MAX_VALUE.toLong(), profile.maxStreamingBitrate)
        assertTrue(conditions.none { it.property == "VideoBitrate" })
    }

    @Test
    fun selectedQualityResolutionCapTightensVideoProfileConditions() {
        val profile =
            buildDeviceProfile(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(maxWidth = 3_840, maxHeight = 2_160)),
                    ),
                maxStreamingBitrate = 4_000_000L,
                requestPolicy =
                    PlaybackInfoRequestPolicy(
                        qualityResolutionCap = VideoCodecResolution(maxWidth = 1_280, maxHeight = 720),
                    ),
            )

        val conditions = profile.codecProfiles.single { it.codec == "h264" }.conditions
        assertEquals("1280", conditions.single { it.property == "Width" }.value)
        assertEquals("720", conditions.single { it.property == "Height" }.value)
    }

    @Test
    fun videoCodecProfileIncludesDetectedProfileLevelAndBitDepth() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("hevc"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = true,
                    videoConstraintsByCodec =
                        mapOf(
                            "hevc" to
                                VideoCodecConstraints(
                                    supportedProfiles = listOf("main", "main 10"),
                                    maxLevel = 153,
                                    maxBitDepth = 10,
                                ),
                        ),
                ),
                maxStreamingBitrate = null,
            )

        val conditions = profile.codecProfiles.single { it.codec == "hevc" }.conditions
        assertEquals(
            setOf("VideoRangeType", "VideoProfile", "VideoLevel", "VideoBitDepth"),
            conditions.map { it.property }.toSet(),
        )
        assertEquals(
            ProfileConditionDto(
                condition = "EqualsAny",
                property = "VideoProfile",
                value = "main|main 10",
                isRequired = true,
            ),
            conditions.single { it.property == "VideoProfile" },
        )
        assertTrue(
            conditions.none { condition ->
                condition.property in setOf("VideoCodecTag", "VideoFramerate", "IsInterlaced")
            },
        )
        assertEquals(conditions.size, conditions.distinct().size)
    }

    @Test
    fun videoCodecProfileMapsBackendSpecificTagFrameRateAndProgressiveConstraints() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("hevc"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                    videoConstraintsByCodec =
                        mapOf(
                            "hevc" to
                                VideoCodecConstraints(
                                    requiredCodecTags = listOf("hvc1", "dvh1"),
                                    maxVideoFrameRate = 60,
                                    allowsInterlaced = false,
                                ),
                        ),
                ),
                maxStreamingBitrate = null,
            )

        val mappedProperties = setOf("VideoCodecTag", "VideoFramerate", "IsInterlaced")
        val conditions =
            profile.codecProfiles
                .single { it.codec == "hevc" }
                .conditions
                .filter { condition -> condition.property in mappedProperties }

        assertEquals(
            listOf(
                ProfileConditionDto("EqualsAny", "VideoCodecTag", "hvc1|dvh1", true),
                ProfileConditionDto("LessThanEqual", "VideoFramerate", "60", true),
                ProfileConditionDto("NotEquals", "IsInterlaced", "true", true),
            ),
            conditions,
        )
        assertEquals(conditions.size, conditions.distinct().size)
    }

    @Test
    fun untrustedEnumerationConstraintsEmitOnlyTheEightBitCap() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                    videoConstraintsByCodec =
                        mapOf(
                            "h264" to
                                VideoCodecConstraints(
                                    supportedProfiles = emptyList(),
                                    maxLevel = null,
                                    maxBitDepth = 8,
                                ),
                        ),
                ),
                maxStreamingBitrate = null,
            )

        val conditions = profile.codecProfiles.single { it.codec == "h264" }.conditions
        assertEquals(
            listOf(
                ProfileConditionDto(
                    condition = "LessThanEqual",
                    property = "VideoBitDepth",
                    value = "8",
                    isRequired = true,
                ),
            ),
            conditions.filter { it.property in setOf("VideoProfile", "VideoLevel", "VideoBitDepth") },
        )
    }

    @Test
    fun codecAbsentFromConstraintsMapEmitsNoProfileLevelOrBitDepthConditions() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = null,
            )

        val conditions =
            profile.codecProfiles.filter { it.codec == "h264" }.flatMap { it.conditions }
        assertTrue(
            conditions.none {
                it.property in
                    setOf(
                        "VideoProfile",
                        "VideoLevel",
                        "VideoBitDepth",
                        "VideoCodecTag",
                        "VideoFramerate",
                        "IsInterlaced",
                    )
            },
        )
    }

    @Test
    fun subtitleProfilesPreferLocalDeliveryAndForceEncodeNarrowsOnlySelectedFormat() {
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                subtitleProfiles =
                    listOf(
                        DeviceSubtitleProfile(
                            "srt",
                            listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.External, SubtitleDeliveryMethod.Encode),
                            SubtitleKind.Text,
                        ),
                        DeviceSubtitleProfile(
                            "pgs",
                            listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                            SubtitleKind.Bitmap,
                        ),
                    ),
            )

        val normal = buildDeviceProfile(capabilities, null)
        val forced =
            buildDeviceProfile(
                capabilities,
                null,
                requestPolicy = PlaybackInfoRequestPolicy(forceEncodeSubtitle = ForceEncodeSubtitle(4, "srt")),
            )

        assertEquals(listOf("Embed", "External", "Encode"), normal.subtitleProfiles.filter { it.format == "srt" }.map { it.method })
        assertEquals(listOf("Encode"), forced.subtitleProfiles.filter { it.format == "srt" }.map { it.method })
        assertEquals(listOf("Embed", "Encode"), forced.subtitleProfiles.filter { it.format == "pgs" }.map { it.method })
    }

    @Test
    fun forceEncodeNarrowsEveryEquivalentAliasWithoutChangingWireSpellings() {
        val aliases = listOf("srt", "subrip", "application/x-subrip")
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                subtitleProfiles =
                    aliases.map { format ->
                        DeviceSubtitleProfile(
                            format,
                            listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.External, SubtitleDeliveryMethod.Encode),
                            SubtitleKind.Text,
                        )
                    } +
                        DeviceSubtitleProfile(
                            "pgs",
                            listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                            SubtitleKind.Bitmap,
                        ),
            )

        val forced =
            buildDeviceProfile(
                capabilities,
                null,
                requestPolicy = PlaybackInfoRequestPolicy(forceEncodeSubtitle = ForceEncodeSubtitle(4, "application/x-subrip")),
            )

        aliases.forEach { alias ->
            assertEquals(listOf("Encode"), forced.subtitleProfiles.filter { it.format == alias }.map { it.method })
        }
        assertEquals(listOf("Embed", "Encode"), forced.subtitleProfiles.filter { it.format == "pgs" }.map { it.method })
    }

    @Test
    fun buildDeviceProfileJoinsDetectedCodecsIntoDirectPlayProfiles() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264", "hevc"),
                    audioCodecs = listOf("aac", "eac3", "opus"),
                    supportsDolbyVision = true,
                ),
                maxStreamingBitrate = null,
            )

        assertEquals(2, profile.directPlayProfiles.size)
        assertEquals("Video", profile.directPlayProfiles[0].type)
        assertEquals("mp4,m4v", profile.directPlayProfiles[0].container)
        assertEquals("h264,hevc", profile.directPlayProfiles[0].videoCodec)
        assertEquals("aac,eac3,opus", profile.directPlayProfiles[0].audioCodec)
        assertEquals("Audio", profile.directPlayProfiles[1].type)
        assertEquals("mp3,aac,flac,ogg", profile.directPlayProfiles[1].container)
        assertEquals(null, profile.directPlayProfiles[1].videoCodec)
        assertEquals("aac,eac3,opus", profile.directPlayProfiles[1].audioCodec)
    }

    @Test
    fun buildDeviceProfileRetainsDtsAndDcaAliasesInDirectPlayProfiles() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("dts", "dca"),
                    supportsDolbyVision = false,
                    directPlayProfiles =
                        listOf(
                            DeviceDirectPlayProfile(
                                containers = listOf("mkv"),
                                videoCodecs = listOf("h264"),
                                audioCodecs = listOf("dts", "dca"),
                            ),
                        ),
                ),
                maxStreamingBitrate = null,
            )

        assertEquals("dts,dca", profile.directPlayProfiles.single { it.type == "Video" }.audioCodec)
    }

    @Test
    fun buildDeviceProfileKeepsTranscodingProfileStable() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = true,
                ),
                maxStreamingBitrate = null,
            )

        val transcodingProfile = profile.transcodingProfiles.single()
        assertEquals("ts", transcodingProfile.container)
        assertEquals("Video", transcodingProfile.type)
        assertEquals("hls", transcodingProfile.protocol)
        assertEquals("h264", transcodingProfile.videoCodec)
        assertEquals("aac", transcodingProfile.audioCodec)
        assertEquals("Streaming", transcodingProfile.context)
    }

    @Test
    fun transcodingProfileUsesBestFirstIntersectionOfHlsTsAndDetectedAudioCodecs() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("eac3", "opus", "ac3", "mp3", "aac"),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = null,
            )

        assertEquals("aac,mp3,ac3,eac3", profile.transcodingProfiles.single().audioCodec)
    }

    @Test
    fun transcodingProfileFallsBackToAacAndKeepsStereoPcmNarrowing() {
        val unsupported =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("dts"),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = null,
            )
        val stereo =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac", "ac3", "eac3"),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = null,
                playerSettings = PlayerDeviceSettings(audioMode = PlayerAudioMode.StereoPcm),
            )

        assertEquals("aac", unsupported.transcodingProfiles.single().audioCodec)
        assertEquals("aac,mp3", stereo.transcodingProfiles.single().audioCodec)
    }

    @Test
    fun buildDeviceProfileEmitsOneRangeAllowlistForEveryAdvertisedVideoCodec() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264", "hevc", "av1"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                    supportsHdr = true,
                    videoRangeCapabilitiesByCodec =
                        mapOf(
                            "hevc" to
                                VideoRangeCapabilities(
                                    supportsHdr10 = true,
                                    supportsDolbyVision = true,
                                ),
                            "av1" to VideoRangeCapabilities(supportsHlg = true),
                        ),
                ),
                maxStreamingBitrate = null,
            )

        val videoProfiles = profile.codecProfiles.filter { codecProfile -> codecProfile.type == "Video" }
        assertEquals(listOf("h264", "hevc", "av1"), videoProfiles.map { codecProfile -> codecProfile.codec })
        assertEquals(
            mapOf(
                "h264" to "SDR|DOVIWithSDR",
                "hevc" to "SDR|HDR10|HDR10Plus|DOVI|DOVIWithHDR10|DOVIWithHDR10Plus|DOVIWithHLG|DOVIWithSDR",
                "av1" to "SDR|HLG|DOVIWithHLG|DOVIWithSDR",
            ),
            videoProfiles.associate { codecProfile ->
                codecProfile.codec to codecProfile.conditions.single { condition -> condition.property == "VideoRangeType" }.value
            },
        )
        videoProfiles.forEach { codecProfile ->
            val rangeConditions = codecProfile.conditions.filter { condition -> condition.property == "VideoRangeType" }
            assertEquals(1, rangeConditions.size)
            assertEquals("EqualsAny", rangeConditions.single().condition)
            assertTrue(rangeConditions.single().isRequired)
        }
    }

    @Test
    fun explicitRangeExclusionsStayInsideTheSinglePositiveAllowlistCondition() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("hevc"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = true,
                    supportsHdr = true,
                    videoRangeCapabilitiesByCodec =
                        mapOf(
                            "hevc" to
                                VideoRangeCapabilities(
                                    supportsHdr10 = true,
                                    supportsHdr10Plus = true,
                                    supportsDolbyVision = true,
                                    supportsDolbyVisionWithEL = true,
                                ),
                        ),
                    unsupportedVideoRangeTypesByCodec =
                        mapOf(
                            "H265" to
                                setOf(
                                    "dovi-with-hdr10-plus",
                                    "DOVI_WITH_EL_HDR10_PLUS",
                                ),
                        ),
                ),
                maxStreamingBitrate = null,
            )

        val rangeConditions =
            profile.codecProfiles
                .single { codecProfile -> codecProfile.codec == "hevc" }
                .conditions
                .filter { condition -> condition.property == "VideoRangeType" }

        assertEquals(1, rangeConditions.size)
        assertEquals("EqualsAny", rangeConditions.single().condition)
        assertEquals(
            "SDR|HDR10|HDR10Plus|DOVI|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR|DOVIWithEL",
            rangeConditions.single().value,
        )
        assertTrue(rangeConditions.single().isRequired)
        assertTrue(
            profile.codecProfiles
                .flatMap { codecProfile -> codecProfile.conditions }
                .none { condition -> condition.condition == "NotEquals" },
        )
    }

    @Test
    fun buildDeviceProfileOmitsVideoBitrateConditionWhenUncapped() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264", "hevc"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = null,
            )

        assertFalse(
            profile.codecProfiles.any { codecProfile ->
                codecProfile.conditions.any { condition -> condition.property == "VideoBitrate" }
            },
        )
    }

    @Test
    fun buildDeviceProfileAddsRequiredVideoBitrateConditionWhenCapped() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264", "hevc"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = true,
                ),
                maxStreamingBitrate = 8_000_000L,
            )

        assertEquals(
            setOf("h264", "hevc"),
            profile.codecProfiles.map { codecProfile -> codecProfile.codec }.toSet(),
        )
        profile.codecProfiles.forEach { codecProfile ->
            assertEquals("Video", codecProfile.type)
            assertTrue(
                codecProfile.conditions.any { condition ->
                    condition.condition == "LessThanEqual" &&
                        condition.property == "VideoBitrate" &&
                        condition.value == "8000000" &&
                        condition.isRequired
                },
            )
        }
    }

    @Test
    fun buildDeviceProfileUsesNoClientLimitSentinelWhenUncapped() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = null,
            )

        assertEquals(Int.MAX_VALUE.toLong(), profile.maxStreamingBitrate)
        assertEquals(Int.MAX_VALUE.toLong(), profile.maxStaticBitrate)
        assertEquals(
            listOf(null),
            profile.transcodingProfiles.map { transcodingProfile -> transcodingProfile.maxBitrate },
        )
    }

    @Test
    fun buildDeviceProfileUsesExplicitCapForBitrateCeilings() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = 8_000_000L,
            )

        assertEquals(8_000_000L, profile.maxStreamingBitrate)
        assertEquals(8_000_000L, profile.maxStaticBitrate)
        assertEquals(
            listOf(8_000_000L),
            profile.transcodingProfiles.map { transcodingProfile -> transcodingProfile.maxBitrate },
        )
    }

    @Test
    fun buildDeviceProfileAddsResolutionConditionsWhenDetected() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264", "vp9"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = true,
                    videoResolutionsByCodec =
                        mapOf(
                            "vp9" to
                                VideoCodecResolution(
                                    maxWidth = 3840,
                                    maxHeight = 2160,
                                ),
                        ),
                ),
                maxStreamingBitrate = null,
            )

        val codecProfile = profile.codecProfiles.single { codecProfile -> codecProfile.codec == "vp9" }
        assertEquals("Video", codecProfile.type)
        assertEquals("vp9", codecProfile.codec)
        val resolutionConditions = codecProfile.conditions.filter { condition -> condition.property in setOf("Width", "Height") }
        assertEquals(2, resolutionConditions.size)
        assertEquals("LessThanEqual", resolutionConditions[0].condition)
        assertEquals("Width", resolutionConditions[0].property)
        assertEquals("3840", resolutionConditions[0].value)
        assertEquals(true, resolutionConditions[0].isRequired)
        assertEquals("LessThanEqual", resolutionConditions[1].condition)
        assertEquals("Height", resolutionConditions[1].property)
        assertEquals("2160", resolutionConditions[1].value)
        assertEquals(true, resolutionConditions[1].isRequired)
    }

    @Test
    fun unrestrictedIosCompatibilityOmitsOnlyTheAppOwnedResolutionConditions() {
        val profile =
            buildDeviceProfile(
                capabilities =
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                        videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(3_840, 2_160)),
                        videoConstraintsByCodec =
                            mapOf("h264" to VideoCodecConstraints(maxVideoFrameRate = 60)),
                        hasUserOverridableVideoInputEnvelope = true,
                    ),
                maxStreamingBitrate = null,
                playerSettings =
                    PlayerDeviceSettings(
                        iosPlaybackCompatibilityMode = IosPlaybackCompatibilityMode.Unrestricted,
                    ),
            )

        val conditions = profile.codecProfiles.single { it.codec == "h264" }.conditions
        assertTrue(conditions.none { it.property == "Width" || it.property == "Height" })
        assertEquals("60", conditions.single { it.property == "VideoFramerate" }.value)
    }

    @Test
    fun buildDeviceProfileAddsAudioChannelConditionsWhenPolicyConstrainsChannels() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac", "mp3", "ac3"),
                    supportsDolbyVision = true,
                    maxAudioChannels = 6,
                ),
                maxStreamingBitrate = null,
            )

        val audioProfiles = profile.codecProfiles.filter { codecProfile -> codecProfile.type in setOf("Audio", "VideoAudio") }

        assertEquals(setOf("Audio", "VideoAudio"), audioProfiles.map { profile -> profile.type }.toSet())
        audioProfiles.forEach { codecProfile ->
            assertEquals("aac,mp3,ac3", codecProfile.codec)
            assertEquals("LessThanEqual", codecProfile.conditions.single().condition)
            assertEquals("AudioChannels", codecProfile.conditions.single().property)
            assertEquals("6", codecProfile.conditions.single().value)
        }
        assertEquals(6, profile.transcodingProfiles.single().maxAudioChannels)
    }

    @Test
    fun buildDeviceProfileGroupsAudioCodecsByEffectiveChannelCeilingAndKeepsPartialMapFallback() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac", "mp3", "eac3"),
                    supportsDolbyVision = false,
                    maxAudioChannels = 2,
                    audioDecodeChannelsByCodec = mapOf("eac3" to 8),
                ),
                maxStreamingBitrate = null,
            )

        val audioProfiles = profile.codecProfiles.filter { codecProfile -> codecProfile.type in setOf("Audio", "VideoAudio") }
        val ceilingsByTypeAndCodecs =
            audioProfiles.associate { codecProfile ->
                (codecProfile.type to codecProfile.codec) to codecProfile.conditions.single().value
            }

        assertEquals(4, audioProfiles.size)
        assertEquals("2", ceilingsByTypeAndCodecs["Audio" to "aac,mp3"])
        assertEquals("2", ceilingsByTypeAndCodecs["VideoAudio" to "aac,mp3"])
        assertEquals("8", ceilingsByTypeAndCodecs["Audio" to "eac3"])
        assertEquals("8", ceilingsByTypeAndCodecs["VideoAudio" to "eac3"])
        assertEquals(8, profile.transcodingProfiles.single().maxAudioChannels)
    }

    @Test
    fun buildDeviceProfileUsesSdrOnlyRangeAllowlistWhenPreferSdr() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = listOf("hevc"),
                    audioCodecs = listOf("aac"),
                    supportsDolbyVision = true,
                    supportsHdr = true,
                    videoRangeCapabilitiesByCodec =
                        mapOf(
                            "hevc" to
                                VideoRangeCapabilities(
                                    supportsHdr10 = true,
                                    supportsHdr10Plus = true,
                                    supportsHlg = true,
                                    supportsDolbyVision = true,
                                    supportsDolbyVisionWithEL = true,
                                ),
                        ),
                ),
                maxStreamingBitrate = null,
                playerSettings = PlayerDeviceSettings(hdrMode = PlayerHdrMode.PreferSdr),
            )

        val codecProfile = profile.codecProfiles.single { codecProfile -> codecProfile.type == "Video" }
        assertEquals("hevc", codecProfile.codec)
        assertEquals(
            "SDR|DOVIWithSDR",
            codecProfile.conditions.single { condition -> condition.property == "VideoRangeType" }.value,
        )
        assertEquals(
            "EqualsAny",
            codecProfile.conditions.single { condition -> condition.property == "VideoRangeType" }.condition,
        )
    }

    @Test
    fun buildDeviceProfileFallsBackToBaselineCodecsWhenDetectionIsEmpty() {
        val profile =
            buildDeviceProfile(
                DeviceDecodingCapabilities(
                    videoCodecs = emptyList(),
                    audioCodecs = emptyList(),
                    supportsDolbyVision = false,
                ),
                maxStreamingBitrate = null,
            )

        assertEquals("h264", profile.directPlayProfiles[0].videoCodec)
        assertEquals("aac,mp3", profile.directPlayProfiles[0].audioCodec)
        assertEquals("aac,mp3", profile.directPlayProfiles[1].audioCodec)
    }

    @Test
    fun serializesCodecProfilesWithPascalCaseFields() {
        val json =
            Json {
                explicitNulls = false
            }.encodeToString(
                buildDeviceProfile(
                    DeviceDecodingCapabilities(
                        videoCodecs = listOf("h264", "hevc"),
                        audioCodecs = listOf("aac"),
                        supportsDolbyVision = false,
                    ),
                    maxStreamingBitrate = null,
                ),
            )

        assertTrue(json.contains(""""CodecProfiles""""))
        assertTrue(json.contains(""""Type":"Video""""))
        assertTrue(json.contains(""""Codec":"hevc""""))
        assertTrue(json.contains(""""Conditions""""))
        assertTrue(json.contains(""""Condition":"EqualsAny""""))
        assertTrue(json.contains(""""Property":"VideoRangeType""""))
        assertTrue(json.contains(""""Value":"SDR|DOVIWithSDR""""))
        assertTrue(json.contains(""""IsRequired":true"""))
        assertFalse(json.contains(""""supportsDolbyVision""""))
    }
}
