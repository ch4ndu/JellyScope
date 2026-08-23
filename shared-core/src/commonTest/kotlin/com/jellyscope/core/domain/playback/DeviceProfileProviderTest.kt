// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.data.remote.buildDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceProfileProviderTest {
    @Test
    fun appleCapabilitiesAndWireProfilesRemainStable() {
        val avPlayerExpected = avPlayerCapabilities()
        val avPlayerActual =
            appleAvPlayerDeviceCapabilities(
                videoCodecs = listOf("h264"),
                supportsHdr = false,
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(1_920, 1_080, 2_073_600, 124_416_000)),
            )
        val vlcKitExpected = vlcKitCapabilities()
        val vlcKitActual =
            vlcKitDeviceCapabilities(
                videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution()),
            )

        assertEquals(
            avPlayerExpected,
            avPlayerActual.copy(videoCodecEvidence = emptyMap(), audioCodecEvidence = emptyMap()),
        )
        assertEquals(
            vlcKitExpected,
            vlcKitActual.copy(videoCodecEvidence = emptyMap(), audioCodecEvidence = emptyMap()),
        )
        assertEquals(
            buildDeviceProfile(avPlayerExpected, maxStreamingBitrate = null),
            buildDeviceProfile(avPlayerActual, maxStreamingBitrate = null),
        )
        assertEquals(
            buildDeviceProfile(vlcKitExpected, maxStreamingBitrate = null),
            buildDeviceProfile(vlcKitActual, maxStreamingBitrate = null),
        )
    }

    @Test
    fun representativeDirectPlayClaimsHaveMatchingCapabilityEvidenceKeys() {
        val capabilities =
            appleAvPlayerDeviceCapabilities(
                videoCodecs = listOf("h264", "av1"),
                supportsHdr = true,
                videoResolutionsByCodec =
                    mapOf(
                        "h264" to VideoCodecResolution(3_840, 2_160),
                        "av1" to VideoCodecResolution(3_840, 2_160),
                    ),
            )
        val directPlay = capabilities.directPlayProfiles.single()

        assertEquals(directPlay.videoCodecs.toSet(), capabilities.videoCodecEvidence.keys)
        assertEquals(directPlay.audioCodecs.toSet(), capabilities.audioCodecEvidence.keys)
    }

    @Test
    fun smallAndUnknownBoundsRemainEvidenceWithSourceRelativeRejection() {
        // Device-verified against a Chromecast with Google TV (2026-07-25): it has
        // no hardware AV1 decoder and probes a 720x720 AV1 ceiling, so advertising
        // AV1 promises direct play that can only ever resolve to a transcode.
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264", "hevc", "av1"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                videoResolutionsByCodec =
                    mapOf(
                        "h264" to VideoCodecResolution(maxWidth = 4_096, maxHeight = 2_160),
                        "hevc" to VideoCodecResolution(maxWidth = 4_096, maxHeight = 2_160),
                        "av1" to VideoCodecResolution(maxWidth = 720, maxHeight = 720),
                    ),
                videoConstraintsByCodec = mapOf("av1" to VideoCodecConstraints(maxBitDepth = 10)),
                videoRangeCapabilitiesByCodec = mapOf("av1" to VideoRangeCapabilities()),
                directPlayProfiles =
                    listOf(
                        DeviceDirectPlayProfile(
                            containers = listOf("mp4", "webm"),
                            videoCodecs = listOf("h264", "hevc", "av1"),
                            audioCodecs = listOf("aac"),
                        ),
                    ),
            )

        val (filtered, dropped) = capabilities.withoutUnusableVideoCodecs()

        assertTrue(dropped.isEmpty())
        assertEquals(capabilities, filtered)
    }

    @Test
    fun codecsAtExactlyHdAreKept() {
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264", "vp9"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                videoResolutionsByCodec =
                    mapOf(
                        // Exactly 720p is usable, not unusable.
                        "h264" to VideoCodecResolution(maxWidth = 1_280, maxHeight = 720),
                        "vp9" to VideoCodecResolution(maxWidth = 3_840, maxHeight = 2_160),
                    ),
            )

        val (filtered, dropped) = capabilities.withoutUnusableVideoCodecs()

        assertTrue(dropped.isEmpty())
        assertEquals(capabilities, filtered)
    }

    @Test
    fun codecsWithNoProbedLimitsRemainUnknownRatherThanUnsupported() {
        // An advertised codec with no probed limits cannot be bounded downstream:
        // transcodeResolutionCap returns null when a target codec has no
        // resolution entry, and the planner then uses the uncapped transcode URL,
        // which is how an oversized stream reaches the decoder in the first place.
        val capabilities =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264", "mpeg2video", "vc1"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                videoResolutionsByCodec =
                    mapOf(
                        "h264" to VideoCodecResolution(maxWidth = 3_840, maxHeight = 2_160),
                        // mpeg2video: probe produced no entry at all.
                        // vc1: an entry that carries neither dimension bounds nothing.
                        "vc1" to VideoCodecResolution(),
                    ),
            )

        val (filtered, dropped) = capabilities.withoutUnusableVideoCodecs()

        assertTrue(dropped.isEmpty())
        assertEquals(capabilities, filtered)
        assertEquals(dropped, filtered.droppedVideoCodecs)
    }

    @Test
    fun defaultRefreshPassesTheRequestedBackendToCapabilities() {
        val avPlayerCapabilities = DeviceDecodingCapabilities(listOf("h264"), listOf("aac"), false)
        val vlcKitCapabilities = DeviceDecodingCapabilities(listOf("vp9"), listOf("dts"), false)
        val provider =
            object : DeviceProfileProvider {
                override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
                    if (backend == PlayerBackend.VlcKit) vlcKitCapabilities else avPlayerCapabilities
            }

        assertEquals(vlcKitCapabilities, provider.refreshCapabilities(PlayerBackend.VlcKit))
        assertEquals(avPlayerCapabilities, provider.refreshCapabilities(PlayerBackend.AVPlayer))
    }

    @Test
    fun resolutionPolicyIdentifiesEachActiveBoundAndTheirCombinations() {
        val device = VideoCodecResolution(maxWidth = 3_840, maxHeight = 2_160)
        val quality = VideoCodecResolution(maxWidth = 1_920, maxHeight = 1_080)
        val user = VideoCodecResolution(maxWidth = 1_280, maxHeight = 720)

        assertEquals(PlaybackResolutionPolicy.NoCap, playbackResolutionPolicy(null, null, null))
        assertEquals(PlaybackResolutionPolicy.VerifiedDeviceCap, playbackResolutionPolicy(device, null, null))
        assertEquals(PlaybackResolutionPolicy.QualityRung, playbackResolutionPolicy(null, quality, null))
        assertEquals(PlaybackResolutionPolicy.UserSetting, playbackResolutionPolicy(null, null, user))
        assertEquals(
            PlaybackResolutionPolicy.VerifiedDeviceQualityRungAndUserSetting,
            playbackResolutionPolicy(device, quality, user),
        )
    }

    @Test
    fun fixedVlcPolicyKeepsItsUserBitrateSeparateFromVerifiedDeviceCap() {
        val device = VideoCodecResolution(maxWidth = 3_840, maxHeight = 2_160)
        val quality = requireNotNull(qualityRungForBitrate(8_000_000L)).resolutionCap

        assertEquals(
            PlaybackResolutionPolicy.VerifiedDeviceAndQualityRung,
            playbackResolutionPolicy(device, quality, null),
        )
        assertEquals(
            PlaybackBitrateConstraint.ExactUserLimit(8_000_000L),
            PlaybackQualityPolicy.fixed(8_000_000L).toBitrateConstraint(),
        )
        assertEquals(PlaybackBitrateConstraint.NoClientLimit, PlaybackQualityPolicy.Original.toBitrateConstraint())
        assertEquals(PlaybackBitrateConstraint.NoClientLimit, PlaybackQualityPolicy.Auto.toBitrateConstraint())
    }
}

private fun avPlayerCapabilities(): DeviceDecodingCapabilities {
    val audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "flac", "alac")
    return DeviceDecodingCapabilities(
        videoCodecs = listOf("h264"),
        audioCodecs = audioCodecs,
        supportsDolbyVision = false,
        videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution(1_920, 1_080, 2_073_600, 124_416_000)),
        maxAudioChannels = 6,
        supportsHdr = false,
        videoRangeCapabilitiesByCodec = mapOf("h264" to VideoRangeCapabilities()),
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = listOf("mp4", "m4v", "mov", "mpegts", "ts"),
                    videoCodecs = listOf("h264"),
                    audioCodecs = audioCodecs,
                ),
            ),
        subtitleProfiles = avPlayerSubtitleProfiles(),
        videoConstraintsByCodec = mapOf("h264" to VideoCodecConstraints(allowsInterlaced = false)),
    )
}

private fun vlcKitCapabilities(): DeviceDecodingCapabilities {
    val videoCodecs =
        listOf(
            "h264",
            "hevc",
            "vp8",
            "vp9",
            "mpeg1video",
            "mpeg2video",
            "mpeg4",
            "msmpeg4v1",
            "msmpeg4v2",
            "msmpeg4v3",
            "vc1",
            "wmv1",
            "wmv2",
            "wmv3",
            "prores",
            "theora",
            "dirac",
            "dv",
            "ffv1",
            "flv1",
            "h261",
            "h263",
            "mjpeg",
            "av1",
        )
    val audioCodecs =
        listOf(
            "aac",
            "ac3",
            "alac",
            "amr_nb",
            "amr_wb",
            "dts",
            "dca",
            "eac3",
            "flac",
            "mp1",
            "mp2",
            "mp3",
            "nellymoser",
            "opus",
            "pcm_alaw",
            "pcm_bluray",
            "pcm_dvd",
            "pcm_mulaw",
            "pcm_s16be",
            "pcm_s16le",
            "pcm_s24be",
            "pcm_s24le",
            "pcm_u8",
            "speex",
            "vorbis",
            "wavpack",
            "wmalossless",
            "wmapro",
            "wmav1",
            "wmav2",
        )
    return DeviceDecodingCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        supportsDolbyVision = false,
        videoResolutionsByCodec = mapOf("h264" to VideoCodecResolution()),
        supportsHdr = false,
        videoRangeCapabilitiesByCodec = videoCodecs.associateWith { VideoRangeCapabilities() },
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers =
                        listOf(
                            "mkv",
                            "webm",
                            "mp4",
                            "m4v",
                            "mov",
                            "mpegts",
                            "ts",
                            "m2ts",
                            "avi",
                            "flv",
                            "3gp",
                            "ogv",
                            "asf",
                            "wmv",
                        ),
                    videoCodecs = videoCodecs,
                    audioCodecs = audioCodecs,
                ),
            ),
        subtitleProfiles = vlcKitSubtitleProfiles(),
    )
}

private fun avPlayerSubtitleProfiles(): List<DeviceSubtitleProfile> =
    listOf(
        DeviceSubtitleProfile("vtt", listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
        DeviceSubtitleProfile("webvtt", listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
        DeviceSubtitleProfile("ttml", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
        DeviceSubtitleProfile("srt", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
        DeviceSubtitleProfile("subrip", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
        DeviceSubtitleProfile("ass", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
        DeviceSubtitleProfile("ssa", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Text),
        DeviceSubtitleProfile("pgs", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Bitmap),
        DeviceSubtitleProfile("pgssub", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Bitmap),
        DeviceSubtitleProfile("vobsub", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Bitmap),
        DeviceSubtitleProfile("dvdsub", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Bitmap),
        DeviceSubtitleProfile("dvbsub", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Bitmap),
        DeviceSubtitleProfile("dvb", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Bitmap),
        DeviceSubtitleProfile("xsub", listOf(SubtitleDeliveryMethod.Encode), SubtitleKind.Bitmap),
    )

private fun vlcKitSubtitleProfiles(): List<DeviceSubtitleProfile> =
    listOf("vtt", "webvtt", "srt", "subrip", "ass", "ssa", "ttml", "text", "mov_text").map { format ->
        DeviceSubtitleProfile(
            format = format,
            deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
            kind = SubtitleKind.Text,
        )
    } +
        listOf("pgs", "pgssub", "dvbsub", "dvdsub", "xsub", "vobsub").map { format ->
            DeviceSubtitleProfile(
                format = format,
                deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                kind = SubtitleKind.Bitmap,
            )
        }
