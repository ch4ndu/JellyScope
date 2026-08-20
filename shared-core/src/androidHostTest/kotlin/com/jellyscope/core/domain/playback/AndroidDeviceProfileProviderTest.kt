// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Display
import com.jellyscope.core.data.remote.buildDeviceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDeviceProfileProviderTest {
    @Test
    fun exactFireTvModelsExcludeOnlyTheTwoUnsafeHevcRangeTypesForEveryBackend() {
        val excludedRangeTypes = setOf("DOVIWithHDR10Plus", "DOVIWithELHDR10Plus")
        val backends = listOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc)

        listOf("AFTKRT", "AFTKA", "AFTKM", "AFTMM").forEach { model ->
            val provider =
                AndroidDeviceProfileProvider(
                    environment = FakeAndroidPlaybackCapabilityEnvironment(),
                    deviceModel = model,
                )
            backends.forEach { backend ->
                assertEquals(
                    mapOf("hevc" to excludedRangeTypes),
                    provider.capabilities(backend).unsupportedVideoRangeTypesByCodec,
                    "$model $backend",
                )
                assertEquals(
                    mapOf("hevc" to excludedRangeTypes),
                    provider.refreshCapabilities(backend).unsupportedVideoRangeTypesByCodec,
                    "$model refreshed $backend",
                )
            }
        }
    }

    @Test
    fun fireTvModelMatchingIsExactAndCaseSensitive() {
        val backends = listOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc)

        listOf<String?>(null, "", "aftka", "AFTKA2", "AFTS").forEach { model ->
            val provider =
                AndroidDeviceProfileProvider(
                    environment = FakeAndroidPlaybackCapabilityEnvironment(),
                    deviceModel = model,
                )
            backends.forEach { backend ->
                assertTrue(
                    provider.capabilities(backend).unsupportedVideoRangeTypesByCodec.isEmpty(),
                    "$model $backend",
                )
            }
        }
    }

    @Test
    fun fireTvExclusionLeavesAllPositiveCapabilitiesUnchanged() {
        val affectedProvider =
            AndroidDeviceProfileProvider(
                environment = FakeAndroidPlaybackCapabilityEnvironment(),
                deviceModel = "AFTKA",
            )
        val unaffectedProvider =
            AndroidDeviceProfileProvider(
                environment = FakeAndroidPlaybackCapabilityEnvironment(),
                deviceModel = "AFTS",
            )

        listOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv, PlayerBackend.LibVlc).forEach { backend ->
            val affected = affectedProvider.capabilities(backend)
            assertEquals(
                unaffectedProvider.capabilities(backend),
                affected.copy(unsupportedVideoRangeTypesByCodec = emptyMap()),
                backend.name,
            )
        }
    }

    @Test
    fun androidCapabilitiesAndWireProfilesRemainStable() {
        val environment =
            FakeAndroidPlaybackCapabilityEnvironment().apply {
                decoderVideoCodecs = listOf("h264")
                decoderAudioCodecs = listOf("aac")
                decoderAudioDecodeChannelsByCodec = mapOf("aac" to 2)
                decoderResolutions = mapOf("h264" to completeResolution(1_920, 1_080, 60))
                decoderVideoCodecEvidence =
                    mapOf(
                        "h264" to
                            CodecCapabilityEvidence(
                                decodeSources = setOf(CapabilityEvidenceSource.PlatformHardwareProbe),
                                finiteLimitSources = setOf(CapabilityEvidenceSource.PlatformHardwareProbe),
                            ),
                    )
                decoderAudioCodecEvidence =
                    mapOf(
                        "aac" to
                            CodecCapabilityEvidence(
                                decodeSources = setOf(CapabilityEvidenceSource.PlatformUnclassifiedProbe),
                                finiteLimitSources = setOf(CapabilityEvidenceSource.PlatformUnclassifiedProbe),
                            ),
                    )
                audioSinks = emptyList()
                hdrTypes = emptySet()
            }
        val provider = AndroidDeviceProfileProvider(environment)
        val expectedByBackend =
            mapOf(
                PlayerBackend.ExoPlayer to androidMedia3Capabilities(),
                PlayerBackend.Mpv to androidMpvCapabilities(),
                PlayerBackend.LibVlc to androidLibVlcCapabilities(),
            )

        expectedByBackend.forEach { (backend, expected) ->
            val actual = provider.capabilities(backend)

            assertEquals(
                expected,
                actual.copy(videoCodecEvidence = emptyMap(), audioCodecEvidence = emptyMap()),
            )
            assertEquals(
                buildDeviceProfile(expected, maxStreamingBitrate = null),
                buildDeviceProfile(actual, maxStreamingBitrate = null),
            )
        }
        assertEquals(
            setOf(CapabilityEvidenceSource.PlatformHardwareProbe),
            provider
                .capabilities(PlayerBackend.ExoPlayer)
                .videoCodecEvidence
                .getValue("h264")
                .decodeSources,
        )
        listOf(PlayerBackend.Mpv, PlayerBackend.LibVlc).forEach { backend ->
            val evidence = provider.capabilities(backend).videoCodecEvidence.getValue("h264")
            assertEquals(setOf(CapabilityEvidenceSource.PinnedEngineDeclaration), evidence.decodeSources)
            assertEquals(setOf(CapabilityEvidenceSource.PlatformHardwareProbe), evidence.finiteLimitSources)
        }
    }

    @Test
    fun baselineOnlyH264EnumerationDropsProfileAndLevelButKeepsEightBitCap() {
        val constraints =
            resolveVideoCodecConstraints(
                codec = "h264",
                mappedProfiles = listOf("baseline", "constrained baseline"),
                mappedLevels = listOf(40, 51),
            )

        assertEquals(emptyList(), constraints.supportedProfiles)
        assertNull(constraints.maxLevel)
        assertEquals(8, constraints.maxBitDepth)
    }

    @Test
    fun allUnmappedEnumerationYieldsOnlyEightBitCap() {
        val constraints =
            resolveVideoCodecConstraints(
                codec = "h264",
                mappedProfiles = emptyList(),
                mappedLevels = listOf(40),
            )

        assertEquals(emptyList(), constraints.supportedProfiles)
        assertNull(constraints.maxLevel)
        assertEquals(8, constraints.maxBitDepth)
    }

    @Test
    fun mainWithoutHighH264EnumerationIsTrustedAndFullyPreserved() {
        val constraints =
            resolveVideoCodecConstraints(
                codec = "h264",
                mappedProfiles = listOf("baseline", "main"),
                mappedLevels = listOf(31, 42),
            )

        assertEquals(listOf("main", "baseline"), constraints.supportedProfiles)
        assertEquals(42, constraints.maxLevel)
        assertEquals(8, constraints.maxBitDepth)
    }

    @Test
    fun highTenH264EnumerationKeepsTenBitDepth() {
        val constraints =
            resolveVideoCodecConstraints(
                codec = "h264",
                mappedProfiles = listOf("main", "high", "high 10"),
                mappedLevels = listOf(51),
            )

        assertEquals(listOf("high", "main", "high 10"), constraints.supportedProfiles)
        assertEquals(51, constraints.maxLevel)
        assertEquals(10, constraints.maxBitDepth)
    }

    @Test
    fun trustedProfilesAreOrderedBestFirstSoTheServerEncodeTargetIsNotBaseline() {
        val constraints =
            resolveVideoCodecConstraints(
                codec = "h264",
                mappedProfiles = listOf("baseline", "constrained baseline", "main", "high"),
                mappedLevels = listOf(52),
            )

        assertEquals(
            listOf("high", "main", "baseline", "constrained baseline"),
            constraints.supportedProfiles,
        )
    }

    @Test
    fun hevcAndAv1TrustMainAndMainTenAnchors() {
        val hevcMainTenOnly =
            resolveVideoCodecConstraints(
                codec = "hevc",
                mappedProfiles = listOf("main 10"),
                mappedLevels = listOf(120),
            )
        assertEquals(listOf("main 10"), hevcMainTenOnly.supportedProfiles)
        assertEquals(10, hevcMainTenOnly.maxBitDepth)

        val av1Untrusted =
            resolveVideoCodecConstraints(
                codec = "av1",
                mappedProfiles = emptyList(),
                mappedLevels = listOf(12),
            )
        assertEquals(emptyList(), av1Untrusted.supportedProfiles)
        assertNull(av1Untrusted.maxLevel)
        assertEquals(8, av1Untrusted.maxBitDepth)
    }

    @Test
    fun codecWithoutAnchorTableEntryKeepsMappedConstraints() {
        val constraints =
            resolveVideoCodecConstraints(
                codec = "vp9",
                mappedProfiles = listOf("profile 0"),
                mappedLevels = listOf(30),
            )

        assertEquals(listOf("profile 0"), constraints.supportedProfiles)
        assertEquals(30, constraints.maxLevel)
    }

    @Test
    fun cachesDecodersButReprobesActiveAudioRouteAndDisplay() {
        val environment = FakeAndroidPlaybackCapabilityEnvironment()
        val provider = AndroidDeviceProfileProvider(environment)

        val initial = provider.capabilities(PlayerBackend.AVPlayer)

        assertEquals(listOf("ac3", "eac3"), initial.audioPassthroughCodecs)
        assertEquals(8, initial.maxAudioChannels)
        assertTrue(initial.supportsHdr)
        assertTrue(initial.supportsDolbyVision)
        assertTrue(initial.videoRangeCapabilitiesByCodec.getValue("hevc").supportsHdr10)
        assertTrue(initial.videoRangeCapabilitiesByCodec.getValue("hevc").supportsDolbyVision)
        assertTrue(initial.subtitleProfiles.any { profile -> profile.format == "srt" })
        assertTrue(initial.subtitleProfiles.any { profile -> profile.format == "subrip" })
        assertTrue(initial.subtitleProfiles.any { profile -> profile.format == "vtt" })
        assertTrue(initial.subtitleProfiles.any { profile -> profile.format == "webvtt" })

        environment.audioSinks = emptyList()
        environment.hdrTypes = emptySet()
        val changedRoute = provider.capabilities(PlayerBackend.AVPlayer)

        assertEquals(emptyList(), changedRoute.audioPassthroughCodecs)
        assertEquals(2, changedRoute.maxAudioChannels)
        assertFalse(changedRoute.supportsHdr)
        assertFalse(changedRoute.supportsDolbyVision)
        assertFalse(changedRoute.videoRangeCapabilitiesByCodec.getValue("hevc").supportsHdr10)
        assertFalse(changedRoute.videoRangeCapabilitiesByCodec.getValue("hevc").supportsDolbyVision)
        assertEquals(1, environment.decoderCalls)
        assertEquals(2, environment.audioRouteCalls)
        assertEquals(2, environment.displayCalls)

        provider.refreshCapabilities(PlayerBackend.AVPlayer)
        assertEquals(2, environment.decoderCalls)
    }

    @Test
    fun reportsBundledMpvWhenTheCheapRuntimeCheckerFindsItsPayload() {
        val environment = FakeAndroidPlaybackCapabilityEnvironment()
        val provider =
            AndroidDeviceProfileProvider(
                environment = environment,
                mpvRuntimeAvailable = { true },
            )

        assertEquals(
            setOf(PlayerBackend.ExoPlayer, PlayerBackend.Mpv),
            provider.availableBackends,
        )
    }

    @Test
    fun mpvUsesItsOwnSdrDecodedPcmMatrixAndDoesNotProbeDisplayHdr() {
        val environment = FakeAndroidPlaybackCapabilityEnvironment()
        val provider = AndroidDeviceProfileProvider(environment)

        val capabilities = provider.capabilities(PlayerBackend.Mpv)

        assertEquals(environment.decoderResolutions.getValue("hevc"), capabilities.videoResolutionsByCodec.getValue("hevc"))
        assertEquals(emptyList<String>(), capabilities.audioPassthroughCodecs)
        assertEquals(8, capabilities.maxAudioChannels)
        assertFalse(capabilities.supportsHdr)
        assertFalse(capabilities.supportsDolbyVision)
        assertTrue(capabilities.subtitleProfiles.any { profile -> profile.format == "srt" })
        assertEquals(1, environment.decoderCalls)
        assertEquals(1, environment.audioRouteCalls)
        assertEquals(0, environment.displayCalls)
        assertEquals(setOf(PlayerBackend.ExoPlayer), provider.availableBackends)
    }

    @Test
    fun refreshingMpvReenumeratesItsMediaCodecResolutionTuple() {
        val environment = FakeAndroidPlaybackCapabilityEnvironment()
        val provider = AndroidDeviceProfileProvider(environment)
        provider.capabilities(PlayerBackend.Mpv)
        val refreshed = completeResolution(1_920, 1_080, 60)
        environment.decoderResolutions = environment.decoderResolutions + ("vp9" to refreshed)

        val capabilities = provider.refreshCapabilities(PlayerBackend.Mpv)

        assertEquals(refreshed, capabilities.videoResolutionsByCodec.getValue("vp9"))
        assertEquals(2, environment.decoderCalls)
        assertEquals(2, environment.audioRouteCalls)
        assertEquals(0, environment.displayCalls)
    }

    @Test
    fun libVlcMatrixIsVersionedAndConservativeAboutHdrAndPassthrough() {
        val matrix = androidLibVlcCapabilityMatrix()
        val capabilities = matrix.toDeviceDecodingCapabilities()

        assertEquals("3.7.5", matrix.engineVersion)
        assertTrue("mkv" in matrix.supportedContainers)
        assertTrue("hevc" in matrix.supportedVideoCodecs)
        assertTrue("av1" in matrix.supportedVideoCodecs)
        assertTrue("av1" in capabilities.videoCodecs)
        assertEquals(emptySet(), matrix.supportedVideoBitDepths)
        assertFalse(matrix.supportsHdr)
        assertFalse(matrix.supportsDolbyVision)
        assertFalse(matrix.supportsPassthrough)
        assertEquals(8, capabilities.maxAudioChannels)
        assertEquals(null, capabilities.videoConstraintsByCodec.getValue("hevc").maxBitDepth)
        assertEquals(emptyList(), capabilities.audioPassthroughCodecs)
        assertTrue(capabilities.subtitleProfiles.any { profile -> profile.format == "srt" })
    }

    @Test
    fun shieldSelectionKeepsHardwareResolutionAndConservativeProfileLevelTogether() {
        val nvidiaHardware =
            videoCandidate(
                resolution = completeResolution(3_840, 2_160, 60),
                preference = AndroidDecoderPreference.HardwarePreferred,
                profileLevels =
                    listOf(
                        AndroidVideoProfileLevel(
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
                        ),
                        AndroidVideoProfileLevel(
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41,
                        ),
                    ),
            )
        val googleSoftware =
            videoCandidate(
                resolution = completeResolution(4_096, 2_160, 30),
                preference = AndroidDecoderPreference.Software,
                profileLevels =
                    listOf(
                        AndroidVideoProfileLevel(
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
                        ),
                    ),
            )

        val selected = selectPreferredAndroidVideoDecoder(listOf(googleSoftware, nvidiaHardware))
        val constraints = resolveSelectedVideoCodecConstraints("hevc", selected?.profileLevels.orEmpty())

        assertEquals(nvidiaHardware, selected)
        assertEquals(completeResolution(3_840, 2_160, 60), selected?.resolution)
        assertEquals(listOf("main", "main 10"), constraints.supportedProfiles)
        assertEquals(123, constraints.maxLevel)
        assertEquals(10, constraints.maxBitDepth)
    }

    @Test
    fun decoderClassificationCoversLegacyPatternsApi29FlagsAndSelectionOrder() {
        val legacyExpectations =
            listOf(
                "arc.video.hevc" to AndroidDecoderPreference.HardwarePreferred,
                "OMX.vendor.hevc.decoder" to AndroidDecoderPreference.HardwarePreferred,
                "c2.vendor.hevc.decoder" to AndroidDecoderPreference.HardwarePreferred,
                "OMX.google.hevc.decoder" to AndroidDecoderPreference.Software,
                "OMX.ffmpeg.hevc.decoder" to AndroidDecoderPreference.Software,
                "OMX.SEC.hevc.sw.dec" to AndroidDecoderPreference.Software,
                "OMX.qcom.video.decoder.hevcswvdec" to AndroidDecoderPreference.Software,
                "c2.android.hevc.decoder" to AndroidDecoderPreference.Software,
                "c2.google.hevc.decoder" to AndroidDecoderPreference.Software,
                "foreign.hevc.decoder" to AndroidDecoderPreference.Software,
            )
        legacyExpectations.forEach { (name, expectedPreference) ->
            val classification = classifyAndroidDecoder(name, sdkInt = 28)
            assertEquals(expectedPreference, classification.preference, name)
            assertEquals(
                AndroidDecoderClassificationEvidence.LegacyCodecNameClassification,
                classification.evidence,
                name,
            )
        }
        assertEquals(
            AndroidDecoderClassification(
                AndroidDecoderPreference.Unclassified,
                AndroidDecoderClassificationEvidence.PlatformUnclassifiedProbe,
            ),
            classifyAndroidDecoder("   ", sdkInt = 28),
        )

        val api29Expectations =
            listOf(
                Triple(true, false, AndroidDecoderPreference.HardwarePreferred),
                Triple(false, true, AndroidDecoderPreference.Software),
                Triple(false, false, AndroidDecoderPreference.Unclassified),
                Triple(true, true, AndroidDecoderPreference.Unclassified),
            )
        api29Expectations.forEach { (hardware, software, expectedPreference) ->
            val classification =
                classifyAndroidDecoder(
                    codecName = "ignored-by-api-29",
                    sdkInt = 29,
                    isHardwareAccelerated = hardware,
                    isSoftwareOnly = software,
                )
            assertEquals(expectedPreference, classification.preference)
            assertEquals(
                when (expectedPreference) {
                    AndroidDecoderPreference.HardwarePreferred -> AndroidDecoderClassificationEvidence.PlatformHardwareProbe
                    AndroidDecoderPreference.Software -> AndroidDecoderClassificationEvidence.PlatformSoftwareProbe
                    AndroidDecoderPreference.Unclassified -> AndroidDecoderClassificationEvidence.PlatformUnclassifiedProbe
                },
                classification.evidence,
            )
        }

        val software = videoCandidate(preference = AndroidDecoderPreference.Software)
        val unclassified = videoCandidate(preference = AndroidDecoderPreference.Unclassified)
        val secureHardware =
            videoCandidate(
                preference = AndroidDecoderPreference.HardwarePreferred,
                requiresSecurePlayback = true,
            )
        assertEquals(unclassified, selectPreferredAndroidVideoDecoder(listOf(software, unclassified)))
        assertEquals(software, selectPreferredAndroidVideoDecoder(listOf(software)))
        assertEquals(software, selectPreferredAndroidVideoDecoder(listOf(software, secureHardware)))
    }

    @Test
    fun libVlcProfileDoesNotInferSourceBoundsFromScreenClass() {
        val matrix = androidLibVlcCapabilityMatrix()

        assertEquals(null, matrix.maxWidth)
        assertEquals(null, matrix.maxHeight)
        assertEquals(
            VideoCodecResolution(),
            matrix.toDeviceDecodingCapabilities().videoResolutionsByCodec.getValue("hevc"),
        )
    }

    @Test
    fun libVlcResolutionProjectionIntersectsDeclaredCodecsAndPreservesProbeTuple() {
        val declared = androidLibVlcCapabilityMatrix().toDeviceDecodingCapabilities()
        val cubeVp9 =
            VideoCodecResolution(
                maxWidth = 3_840,
                maxHeight = 2_160,
                maxFrameArea = blockPaddedArea(3_840, 2_160),
                maxFrameAreaPerSecond = blockPaddedArea(3_840, 2_160) * 30L,
            )
        val probed =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("hevc", "vp9", "not-declared"),
                audioCodecs = listOf("aac", "ac3"),
                supportsDolbyVision = false,
                videoResolutionsByCodec =
                    mapOf(
                        "h264" to cubeVp9,
                        "vp9" to cubeVp9,
                        "not-declared" to cubeVp9,
                    ),
            )

        val projected = declared.narrowedToProbedVideoResolutions(probed)

        assertEquals(cubeVp9, projected.videoResolutionsByCodec.getValue("vp9"))
        assertEquals(VideoCodecResolution(), projected.videoResolutionsByCodec.getValue("h264"))
        assertEquals(VideoCodecResolution(), projected.videoResolutionsByCodec.getValue("av1"))
        assertFalse("not-declared" in projected.videoResolutionsByCodec)
        assertEquals(
            declared.copy(videoResolutionsByCodec = projected.videoResolutionsByCodec),
            projected,
        )
    }

    @Test
    fun libVlcResolutionProjectionPreservesUnknownAndIncompleteProbeFacts() {
        val declared = androidLibVlcCapabilityMatrix().toDeviceDecodingCapabilities()
        val probed =
            DeviceDecodingCapabilities(
                videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
                audioCodecs = listOf("aac"),
                supportsDolbyVision = false,
                videoResolutionsByCodec =
                    mapOf(
                        "h264" to
                            VideoCodecResolution(
                                maxWidth = 1_920,
                                maxHeight = 1_080,
                                maxFrameArea = blockPaddedArea(1_920, 1_080),
                            ),
                        "hevc" to VideoCodecResolution(maxWidth = null, maxHeight = null),
                        "vp9" to
                            VideoCodecResolution(
                                maxWidth = 3_840,
                                maxHeight = 2_160,
                                maxFrameArea = blockPaddedArea(3_840, 2_160),
                                maxFrameAreaPerSecond = blockPaddedArea(3_840, 2_160) * 30L,
                            ),
                        "av1" to VideoCodecResolution(),
                    ),
            )

        val projected = declared.narrowedToProbedVideoResolutions(probed)

        assertEquals(VideoCodecResolution(), projected.videoResolutionsByCodec.getValue("h264"))
        assertEquals(VideoCodecResolution(), projected.videoResolutionsByCodec.getValue("hevc"))
        assertEquals(VideoCodecResolution(), projected.videoResolutionsByCodec.getValue("av1"))
        assertEquals(probed.videoResolutionsByCodec.getValue("vp9"), projected.videoResolutionsByCodec.getValue("vp9"))
    }

    @Test
    fun libVlcProviderSharesDecoderCacheAndRefreshesBothBackends() {
        val environment = FakeAndroidPlaybackCapabilityEnvironment()
        val provider = AndroidDeviceProfileProvider(environment)

        val firstLibVlc = provider.capabilities(PlayerBackend.LibVlc)
        assertEquals(1, environment.decoderCalls)
        assertEquals(environment.decoderResolutions.getValue("vp9"), firstLibVlc.videoResolutionsByCodec.getValue("vp9"))
        assertEquals(VideoCodecResolution(), firstLibVlc.videoResolutionsByCodec.getValue("av1"))

        provider.capabilities(PlayerBackend.ExoPlayer)
        provider.capabilities(PlayerBackend.LibVlc)
        assertEquals(1, environment.decoderCalls)

        val refreshedVp9 =
            VideoCodecResolution(
                maxWidth = 1_920,
                maxHeight = 1_080,
                maxFrameArea = blockPaddedArea(1_920, 1_080),
                maxFrameAreaPerSecond = blockPaddedArea(1_920, 1_080) * 60L,
            )
        environment.decoderResolutions = environment.decoderResolutions + ("vp9" to refreshedVp9)

        val refreshedLibVlc = provider.refreshCapabilities(PlayerBackend.LibVlc)
        assertEquals(2, environment.decoderCalls)
        assertEquals(refreshedVp9, refreshedLibVlc.videoResolutionsByCodec.getValue("vp9"))

        provider.refreshCapabilities(PlayerBackend.ExoPlayer)
        assertEquals(3, environment.decoderCalls)
    }

    @Test
    fun duplicatedActiveRoutesUseOnlyCommonFormatsAndConservativeChannels() {
        val capabilities =
            audioRouteCapabilities(
                listOf(
                    AndroidAudioSinkCapabilities(
                        encodings = setOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3),
                        maxChannelCount = 8,
                    ),
                    AndroidAudioSinkCapabilities(
                        encodings = setOf(AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_DTS),
                        maxChannelCount = 6,
                    ),
                ),
            )

        assertEquals(listOf("eac3"), capabilities.passthroughCodecs)
        assertEquals(6, capabilities.maxAudioChannels)
    }

    @Test
    fun detectedDtsCodecsAndPassthroughEmitDcaAliasInTheFinalProfile() {
        assertEquals(
            listOf("dts", "dca"),
            detectedAudioCodecs(listOf(MediaFormat.MIMETYPE_AUDIO_DTS, MediaFormat.MIMETYPE_AUDIO_DTS_HD)),
        )
        assertEquals(
            listOf("dca", "dts"),
            audioRouteCapabilities(
                listOf(
                    AndroidAudioSinkCapabilities(
                        encodings = setOf(AudioFormat.ENCODING_DTS_HD),
                        maxChannelCount = 8,
                    ),
                ),
            ).passthroughCodecs,
        )

        val environment = FakeAndroidPlaybackCapabilityEnvironment()
        environment.decoderAudioCodecs = detectedAudioCodecs(listOf(MediaFormat.MIMETYPE_AUDIO_DTS))
        val profile =
            buildDeviceProfile(
                AndroidDeviceProfileProvider(environment).capabilities(PlayerBackend.AVPlayer),
                maxStreamingBitrate = null,
            )
        val emittedAudioCodecs = profile.directPlayProfiles.single { directProfile -> directProfile.type == "Video" }.audioCodec

        assertTrue(emittedAudioCodecs?.split(',')?.containsAll(listOf("dts", "dca")) == true)
    }

    @Test
    fun media3FfmpegEac3CapabilityAddsEightChannelDecodeWithoutPassthroughAndFailsClosed() {
        val supportedEnvironment =
            FakeAndroidPlaybackCapabilityEnvironment().apply {
                decoderAudioCodecs = listOf("aac", "mp3")
                decoderAudioDecodeChannelsByCodec = mapOf("aac" to 2, "mp3" to 2)
                audioSinks = listOf(AndroidAudioSinkCapabilities(encodings = emptySet(), maxChannelCount = 2))
                ffmpegEac3Probe = { true }
            }
        val supportedCapabilities =
            AndroidDeviceProfileProvider(supportedEnvironment).capabilities(PlayerBackend.ExoPlayer)
        val supportedProfile = buildDeviceProfile(supportedCapabilities, maxStreamingBitrate = null)

        assertEquals(listOf("aac", "mp3", "eac3"), supportedCapabilities.audioCodecs)
        assertEquals(8, supportedCapabilities.audioDecodeChannelsByCodec.getValue("eac3"))
        assertEquals(
            setOf(CapabilityEvidenceSource.BundledRuntimeProbe),
            supportedCapabilities.audioCodecEvidence.getValue("eac3").decodeSources,
        )
        assertEquals(
            setOf(CapabilityEvidenceSource.DocumentedLimit),
            supportedCapabilities.audioCodecEvidence.getValue("eac3").finiteLimitSources,
        )
        assertEquals(emptyList(), supportedCapabilities.audioPassthroughCodecs)
        assertNull(supportedCapabilities.audioPassthroughMaxChannels)
        assertEquals(2, supportedCapabilities.maxAudioChannels)
        assertTrue(
            supportedProfile.directPlayProfiles
                .single { profile -> profile.type == "Video" }
                .audioCodec
                ?.split(',')
                ?.contains("eac3") == true,
        )
        val eac3VideoAudioProfile =
            supportedProfile.codecProfiles.single { profile ->
                profile.type == "VideoAudio" && profile.codec.split(',').contains("eac3")
            }
        assertEquals("8", eac3VideoAudioProfile.conditions.single().value)
        assertEquals(8, supportedProfile.transcodingProfiles.single().maxAudioChannels)

        val duplicateEnvironment =
            FakeAndroidPlaybackCapabilityEnvironment().apply {
                decoderAudioCodecs = listOf("aac", "eac3")
                decoderAudioDecodeChannelsByCodec = mapOf("aac" to 2, "eac3" to 6)
                ffmpegEac3Probe = { true }
            }
        val duplicateCapabilities =
            AndroidDeviceProfileProvider(duplicateEnvironment).capabilities(PlayerBackend.ExoPlayer)
        assertEquals(1, duplicateCapabilities.audioCodecs.count { codec -> codec == "eac3" })
        assertEquals(8, duplicateCapabilities.audioDecodeChannelsByCodec.getValue("eac3"))

        listOf<() -> Boolean>({ false }, { error("probe unavailable") }).forEach { probe ->
            val unavailableEnvironment =
                FakeAndroidPlaybackCapabilityEnvironment().apply {
                    decoderAudioCodecs = listOf("aac", "mp3")
                    ffmpegEac3Probe = probe
                }
            val unavailableCapabilities =
                AndroidDeviceProfileProvider(unavailableEnvironment).capabilities(PlayerBackend.ExoPlayer)
            assertFalse("eac3" in unavailableCapabilities.audioCodecs)
        }

        assertEquals(
            AndroidAudioDecodeChannelLimit(
                maxInputChannelCount = 6,
                evidenceSources = setOf(CapabilityEvidenceSource.DocumentedLimit),
            ),
            adjustedMedia3InputChannelLimit(
                mimeType = "audio/ac3",
                rawMaxInputChannelCount = 1,
                sdkInt = 25,
            ),
        )
    }

    @Test
    fun dolbyVisionProfileResolverMapsBaseEnhancementLayerAndAv1Profiles() {
        val profileFive =
            resolveAndroidDolbyVisionCapabilities(
                AndroidDolbyVisionDecoderInfo(
                    hasDolbyVisionMime = true,
                    profileValues = listOf(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn),
                    supportsMultiInstanceHevc = false,
                ),
            )
        val profileEight =
            resolveAndroidDolbyVisionCapabilities(
                AndroidDolbyVisionDecoderInfo(
                    hasDolbyVisionMime = true,
                    profileValues = listOf(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt),
                    supportsMultiInstanceHevc = false,
                ),
            )
        val profileSevenWithMultiInstanceHevc =
            resolveAndroidDolbyVisionCapabilities(
                AndroidDolbyVisionDecoderInfo(
                    hasDolbyVisionMime = true,
                    profileValues = listOf(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb),
                    supportsMultiInstanceHevc = true,
                ),
            )
        val profileSevenWithoutMultiInstanceHevc =
            resolveAndroidDolbyVisionCapabilities(
                AndroidDolbyVisionDecoderInfo(
                    hasDolbyVisionMime = true,
                    profileValues = listOf(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb),
                    supportsMultiInstanceHevc = false,
                ),
            )
        val profileTen =
            resolveAndroidDolbyVisionCapabilities(
                AndroidDolbyVisionDecoderInfo(
                    hasDolbyVisionMime = true,
                    profileValues = listOf(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110),
                    supportsMultiInstanceHevc = false,
                ),
            )

        assertEquals(AndroidDolbyVisionCapabilities(supportsHevcBase = true), profileFive)
        assertEquals(AndroidDolbyVisionCapabilities(supportsHevcBase = true), profileEight)
        assertEquals(AndroidDolbyVisionCapabilities(supportsEnhancementLayer = true), profileSevenWithMultiInstanceHevc)
        assertEquals(AndroidDolbyVisionCapabilities(), profileSevenWithoutMultiInstanceHevc)
        assertEquals(AndroidDolbyVisionCapabilities(supportsAv1Base = true), profileTen)
    }

    @Test
    fun dolbyVisionProfileResolverFailsClosedWhenMimeIsAbsentOrProfilesAreNotEnumerable() {
        val mimeAbsent =
            resolveAndroidDolbyVisionCapabilities(
                AndroidDolbyVisionDecoderInfo(
                    hasDolbyVisionMime = false,
                    profileValues = listOf(MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn),
                    supportsMultiInstanceHevc = true,
                ),
            )
        val nonEnumerableProfiles =
            resolveAndroidDolbyVisionCapabilities(
                AndroidDolbyVisionDecoderInfo(
                    hasDolbyVisionMime = true,
                    profileValues = emptyList(),
                    supportsMultiInstanceHevc = true,
                ),
            )

        assertEquals(AndroidDolbyVisionCapabilities(), mimeAbsent)
        assertEquals(AndroidDolbyVisionCapabilities(supportsHevcBase = true), nonEnumerableProfiles)
    }

    @Test
    fun legacyHdmiAndUnknownRoutesStayConservative() {
        val hdmi =
            legacyHdmiAudioSink(
                plugState = 1,
                encodings = intArrayOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DOLBY_TRUEHD),
                maxChannelCount = 8,
            )

        assertEquals(setOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DOLBY_TRUEHD), hdmi?.encodings)
        assertEquals(8, hdmi?.maxChannelCount)
        assertNull(legacyHdmiAudioSink(plugState = 0, encodings = null, maxChannelCount = 0))
        assertEquals(2, audioRouteCapabilities(emptyList()).maxAudioChannels)
        assertEquals(emptyList(), audioRouteCapabilities(emptyList()).passthroughCodecs)
    }

    @Test
    fun libVlcSubtitleProfilesOnlyEmbedOrEncodeNeverExternal() {
        run {
            val capabilities = androidLibVlcCapabilityMatrix().toDeviceDecodingCapabilities()
            assertTrue(capabilities.subtitleProfiles.isNotEmpty())
            capabilities.subtitleProfiles.forEach { profile ->
                assertFalse(
                    profile.deliveryMethods.contains(SubtitleDeliveryMethod.External),
                    "LibVLC subtitle profile ${profile.format} must not offer External delivery",
                )
                assertTrue(
                    profile.deliveryMethods.all { method ->
                        method == SubtitleDeliveryMethod.Embed || method == SubtitleDeliveryMethod.Encode
                    },
                )
            }
        }
    }
}

private fun androidMedia3Capabilities(): DeviceDecodingCapabilities =
    DeviceDecodingCapabilities(
        videoCodecs = listOf("h264"),
        audioCodecs = listOf("aac"),
        supportsDolbyVision = false,
        videoResolutionsByCodec = mapOf("h264" to completeResolution(1_920, 1_080, 60)),
        maxAudioChannels = 2,
        audioDecodeChannelsByCodec = mapOf("aac" to 2),
        supportsHdr = false,
        videoRangeCapabilitiesByCodec = mapOf("h264" to VideoRangeCapabilities()),
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "mpegts", "ts"),
                    videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
                    audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "vorbis", "pcm", "dts", "dca"),
                ),
            ),
        subtitleProfiles = androidMedia3SubtitleProfiles(),
    )

private fun androidMpvCapabilities(): DeviceDecodingCapabilities {
    val videoCodecs = listOf("av1", "h264", "hevc", "mpeg2video", "mpeg4", "vp8", "vp9")
    val audioCodecs = listOf("aac", "ac3", "dca", "dts", "eac3", "flac", "mp3", "opus", "pcm", "truehd", "vorbis")
    return DeviceDecodingCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        supportsDolbyVision = false,
        videoResolutionsByCodec =
            videoCodecs.associateWith { codec ->
                if (codec == "h264") completeResolution(1_920, 1_080, 60) else VideoCodecResolution()
            },
        maxAudioChannels = 2,
        supportsHdr = false,
        videoRangeCapabilitiesByCodec = videoCodecs.associateWith { VideoRangeCapabilities() },
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = listOf("avi", "m2ts", "mkv", "mov", "mp4", "mpegts", "ts", "webm"),
                    videoCodecs = videoCodecs,
                    audioCodecs = audioCodecs,
                ),
            ),
        subtitleProfiles = androidMpvSubtitleProfiles(),
        videoConstraintsByCodec = videoCodecs.associateWith { VideoCodecConstraints() },
    )
}

private fun androidLibVlcCapabilities(): DeviceDecodingCapabilities {
    val videoCodecs = listOf("av1", "h264", "hevc", "mpeg1video", "mpeg2video", "vp8", "vp9")
    val audioCodecs = listOf("aac", "ac3", "eac3", "flac", "mp3", "opus", "vorbis")
    return DeviceDecodingCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        supportsDolbyVision = false,
        videoResolutionsByCodec =
            videoCodecs.associateWith { codec ->
                if (codec == "h264") completeResolution(1_920, 1_080, 60) else VideoCodecResolution()
            },
        maxAudioChannels = 8,
        supportsHdr = false,
        videoRangeCapabilitiesByCodec = videoCodecs.associateWith { VideoRangeCapabilities() },
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = listOf("avi", "mkv", "mov", "mp4", "mpegts", "ts", "webm"),
                    videoCodecs = videoCodecs,
                    audioCodecs = audioCodecs,
                ),
            ),
        subtitleProfiles = androidLibVlcSubtitleProfiles(),
        videoConstraintsByCodec = videoCodecs.associateWith { VideoCodecConstraints() },
    )
}

private fun androidMedia3SubtitleProfiles(): List<DeviceSubtitleProfile> =
    listOf("vtt", "webvtt", "ttml", "srt", "subrip", "ass", "ssa").map { format ->
        DeviceSubtitleProfile(
            format = format,
            deliveryMethods =
                buildList {
                    add(SubtitleDeliveryMethod.Embed)
                    add(SubtitleDeliveryMethod.External)
                    if (format == "vtt" || format == "webvtt") add(SubtitleDeliveryMethod.Hls)
                    add(SubtitleDeliveryMethod.Encode)
                },
            kind = SubtitleKind.Text,
        )
    } +
        listOf("pgs", "pgssub", "vobsub", "dvdsub", "dvbsub", "dvb").map { format ->
            DeviceSubtitleProfile(
                format = format,
                deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                kind = SubtitleKind.Bitmap,
            )
        }

private fun androidMpvSubtitleProfiles(): List<DeviceSubtitleProfile> =
    listOf("ass", "srt", "subrip", "vtt", "webvtt").map { format ->
        DeviceSubtitleProfile(
            format = format,
            deliveryMethods =
                listOf(
                    SubtitleDeliveryMethod.Embed,
                    SubtitleDeliveryMethod.External,
                    SubtitleDeliveryMethod.Encode,
                ),
            kind = SubtitleKind.Text,
        )
    } +
        listOf("pgs", "pgssub").map { format ->
            DeviceSubtitleProfile(
                format = format,
                deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
                kind = SubtitleKind.Bitmap,
            )
        }

private fun androidLibVlcSubtitleProfiles(): List<DeviceSubtitleProfile> =
    listOf("ass", "srt", "subrip", "webvtt", "vtt").map { format ->
        DeviceSubtitleProfile(
            format = format,
            deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
            kind = SubtitleKind.Text,
        )
    } +
        DeviceSubtitleProfile(
            format = "pgs",
            deliveryMethods = listOf(SubtitleDeliveryMethod.Embed, SubtitleDeliveryMethod.Encode),
            kind = SubtitleKind.Bitmap,
        )

private class FakeAndroidPlaybackCapabilityEnvironment : AndroidPlaybackCapabilityEnvironment {
    var decoderCalls = 0
    var audioRouteCalls = 0
    var displayCalls = 0
    var audioSinks =
        listOf(
            AndroidAudioSinkCapabilities(
                encodings = setOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3),
                maxChannelCount = 8,
            ),
        )
    var hdrTypes =
        setOf(
            Display.HdrCapabilities.HDR_TYPE_HDR10,
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
        )
    var decoderAudioCodecs = listOf("aac", "ac3", "eac3")
    var decoderAudioDecodeChannelsByCodec = emptyMap<String, Int>()
    var decoderVideoCodecs = listOf("h264", "hevc", "vp9")
    var decoderVideoCodecEvidence = emptyMap<String, CodecCapabilityEvidence>()
    var decoderAudioCodecEvidence = emptyMap<String, CodecCapabilityEvidence>()
    var ffmpegEac3Probe: () -> Boolean = { false }
    var decoderResolutions =
        mapOf(
            "h264" to completeResolution(3_840, 2_160, 30),
            "hevc" to completeResolution(3_840, 2_160, 30),
            "vp9" to completeResolution(3_840, 2_160, 30),
        )

    override fun decoderCapabilities(): AndroidDecoderCapabilities {
        decoderCalls += 1
        return AndroidDecoderCapabilities(
            videoCodecs = decoderVideoCodecs,
            audioCodecs = decoderAudioCodecs,
            videoResolutionsByCodec = decoderResolutions,
            dolbyVisionCapabilities = AndroidDolbyVisionCapabilities(supportsHevcBase = true),
            audioDecodeChannelsByCodec = decoderAudioDecodeChannelsByCodec,
            videoCodecEvidence = decoderVideoCodecEvidence,
            audioCodecEvidence = decoderAudioCodecEvidence,
        )
    }

    override fun supportsMedia3FfmpegEac3(): Boolean = ffmpegEac3Probe()

    override fun activeAudioSinks(): List<AndroidAudioSinkCapabilities> {
        audioRouteCalls += 1
        return audioSinks
    }

    override fun activeDisplayHdrTypes(): Set<Int> {
        displayCalls += 1
        return hdrTypes
    }
}

private fun completeResolution(
    width: Int,
    height: Int,
    frameRate: Int,
): VideoCodecResolution {
    val area = blockPaddedArea(width, height)
    return VideoCodecResolution(width, height, area, area * frameRate)
}

private fun videoCandidate(
    resolution: VideoCodecResolution = completeResolution(1_920, 1_080, 30),
    preference: AndroidDecoderPreference,
    profileLevels: List<AndroidVideoProfileLevel> = emptyList(),
    requiresSecurePlayback: Boolean = false,
): AndroidVideoDecoderCandidate =
    AndroidVideoDecoderCandidate(
        codec = "hevc",
        resolution = resolution,
        profileLevels = profileLevels,
        preference = preference,
        classificationEvidence =
            when (preference) {
                AndroidDecoderPreference.HardwarePreferred -> AndroidDecoderClassificationEvidence.PlatformHardwareProbe
                AndroidDecoderPreference.Software -> AndroidDecoderClassificationEvidence.PlatformSoftwareProbe
                AndroidDecoderPreference.Unclassified -> AndroidDecoderClassificationEvidence.PlatformUnclassifiedProbe
            },
        requiresSecurePlayback = requiresSecurePlayback,
    )
