// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.CapabilityEvidenceSource
import com.jellyscope.core.domain.playback.CodecCapabilityEvidence
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceDirectPlayProfile
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.UnusableVideoCodec
import com.jellyscope.core.domain.playback.UnusableVideoCodecReason
import com.jellyscope.core.domain.playback.VideoCodecConstraints
import com.jellyscope.core.domain.playback.VideoCodecResolution
import com.jellyscope.core.domain.playback.VideoRangeCapabilities
import com.jellyscope.core.playback.DiagnosticsSourceDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    @Test
    fun rendersFullProbeDroppedReasonsAndConcreteBackend() {
        val report =
            DiagnosticsReport.build(
                environment = testEnvironment,
                backend = PlayerBackend.LibVlc,
                capabilities = fullCapabilities,
                source = source,
            )

        assertTrue(report.contains("capabilities.backend=libvlc"))
        assertTrue(report.contains("capabilities.video.h264.maxWidth=3840"))
        assertTrue(report.contains("capabilities.video.h264.maxFrameAreaPerSecond=497664000"))
        assertTrue(report.contains("capabilities.video.h264.supportedProfiles=high,main"))
        assertTrue(report.contains("capabilities.video.h264.supportsHdr10=true"))
        assertTrue(report.contains("capabilities.audioCodecs=aac"))
        assertTrue(report.contains("capabilities.audioPassthroughCodecs=ac3"))
        assertTrue(report.contains("capabilities.video.h264.decodeEvidence=PlatformHardwareProbe"))
        assertTrue(report.contains("capabilities.video.h264.finiteLimitEvidence=DocumentedLimit"))
        assertTrue(report.contains("capabilities.audio.aac.decodeEvidence=PinnedEngineDeclaration"))
        assertTrue(report.contains("capabilities.audio.ac3.decodeEvidence=Unknown"))
        assertTrue(report.contains("capabilities.audio.ac3.finiteLimitEvidence=Unknown"))
        assertTrue(report.contains("capabilities.containers=mkv"))
        assertTrue(report.contains("capabilities.droppedVideoCodec=av1 reason=BelowMinimumSize"))
        assertTrue(report.contains("playbackFailure.videoCodec=h264"))
        assertTrue(report.contains("playbackFailure.channelLayout=5.1"))

        val missingEvidenceReport =
            DiagnosticsReport.build(
                environment = testEnvironment,
                backend = PlayerBackend.LibVlc,
                capabilities = fullCapabilities.copy(videoCodecEvidence = emptyMap(), audioCodecEvidence = emptyMap()),
                source = source,
            )
        assertTrue(missingEvidenceReport.contains("capabilities.video.h264.decodeEvidence=Unknown"))
        assertTrue(missingEvidenceReport.contains("capabilities.audio.aac.finiteLimitEvidence=Unknown"))
    }

    @Test
    fun marksUnavailableCapabilitiesAndMissingPlaybackFailure() {
        val report =
            DiagnosticsReport.build(
                environment = testEnvironment,
                backend = PlayerBackend.ExoPlayer,
                capabilities = null,
                source = null,
            )

        assertTrue(report.contains("capabilities.backend=exoplayer"))
        assertTrue(report.contains("capabilities.status=capabilities unavailable"))
        assertTrue(report.contains("playbackFailure.status=no recent playback failure"))
    }

    @Test
    fun rejectsIdentityAndSecretShapedTechnicalValues() {
        val forbiddenValues =
            listOf(
                "Jugnu",
                "https://jellyfin.example/video",
                "/Users/example/Movie.mkv",
                "token=secret",
            )
        val report =
            DiagnosticsReport.build(
                environment = testEnvironment,
                backend = PlayerBackend.ExoPlayer,
                capabilities =
                    fullCapabilities.copy(
                        videoCodecEvidence =
                            fullCapabilities.videoCodecEvidence +
                                (forbiddenValues[0] to CodecCapabilityEvidence()),
                        audioCodecEvidence =
                            fullCapabilities.audioCodecEvidence +
                                (forbiddenValues[2] to CodecCapabilityEvidence()),
                    ),
                source =
                    source.copy(
                        videoCodec = forbiddenValues[0],
                        container = forbiddenValues[1],
                        audioCodec = forbiddenValues[2],
                        channelLayout = forbiddenValues[3],
                        videoRangeType = "A Movie Title",
                    ),
            )

        forbiddenValues.forEach { forbidden ->
            assertFalse(report.contains(forbidden))
        }
        assertFalse(report.contains("A Movie Title"))
        assertTrue(report.contains("playbackFailure.videoCodec=unrecognized"))
        assertTrue(report.contains("playbackFailure.container=unrecognized"))
        assertTrue(report.contains("playbackFailure.audioCodec=unrecognized"))
        assertTrue(report.contains("playbackFailure.channelLayout=unrecognized"))
        assertTrue(report.contains("playbackFailure.videoRangeType=unrecognized"))
    }

    @Test
    fun environmentFailureFallsBackWithoutThrowing() {
        val unavailableEnvironment =
            object : DiagnosticsEnvironment {
                override val platform: String
                    get() = error("environment unavailable")
                override val osVersion = "unused"
                override val appVersion = "unused"
                override val deviceModel = "unused"
            }

        val report =
            DiagnosticsReport.build(
                environment = unavailableEnvironment,
                backend = PlayerBackend.ExoPlayer,
                capabilities = fullCapabilities,
                source = source,
            )

        assertTrue(report.contains("environment.status=environment unavailable"))
        assertTrue(report.contains("capabilities.status=capabilities unavailable"))
    }
}

private val testEnvironment =
    object : DiagnosticsEnvironment {
        override val platform = "android"
        override val osVersion = "35"
        override val appVersion = "0.1.0-alpha28"
        override val deviceModel = "Test Device"
    }

private val fullCapabilities =
    DeviceDecodingCapabilities(
        videoCodecs = listOf("h264"),
        audioCodecs = listOf("aac"),
        supportsDolbyVision = false,
        videoResolutionsByCodec =
            mapOf(
                "h264" to
                    VideoCodecResolution(
                        maxWidth = 3840,
                        maxHeight = 2160,
                        maxFrameArea = 8_294_400,
                        maxFrameAreaPerSecond = 497_664_000,
                    ),
            ),
        audioPassthroughCodecs = listOf("ac3"),
        maxAudioChannels = 6,
        supportsHdr = true,
        videoRangeCapabilitiesByCodec =
            mapOf(
                "h264" to VideoRangeCapabilities(supportsHdr10 = true),
            ),
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = listOf("mkv"),
                    videoCodecs = listOf("h264"),
                    audioCodecs = listOf("aac"),
                ),
            ),
        videoConstraintsByCodec =
            mapOf(
                "h264" to
                    VideoCodecConstraints(
                        supportedProfiles = listOf("main", "high"),
                        maxLevel = 51,
                        maxBitDepth = 10,
                    ),
            ),
        droppedVideoCodecs =
            listOf(
                UnusableVideoCodec("av1", UnusableVideoCodecReason.BelowMinimumSize),
            ),
        videoCodecEvidence =
            mapOf(
                "h264" to
                    CodecCapabilityEvidence(
                        decodeSources = setOf(CapabilityEvidenceSource.PlatformHardwareProbe),
                        finiteLimitSources = setOf(CapabilityEvidenceSource.DocumentedLimit),
                    ),
            ),
        audioCodecEvidence =
            mapOf(
                "aac" to
                    CodecCapabilityEvidence(
                        decodeSources = setOf(CapabilityEvidenceSource.PinnedEngineDeclaration),
                        finiteLimitSources = setOf(CapabilityEvidenceSource.DocumentedLimit),
                    ),
            ),
    )

private val source =
    DiagnosticsSourceDescriptor(
        videoCodec = "h264",
        width = 3840,
        height = 2160,
        frameRate = 59.94,
        bitRate = 40_000_000,
        bitDepth = 10,
        videoRangeType = "HDR10",
        container = "mkv",
        audioCodec = "aac",
        channelLayout = "5.1",
    )
