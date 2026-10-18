// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.playback.desktopLibVlcRuntimeAvailable
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger

class DesktopDeviceProfileProvider(
    private val hostOsName: String = System.getProperty("os.name").orEmpty(),
) : DeviceProfileProvider {
    override val backendPolicy: PlayerBackendPolicy = desktopPlayerBackendPolicy()
    override val availableBackends: Set<PlayerBackend> by lazy {
        buildSet {
            add(PlayerBackend.Mpv)
            if (desktopLibVlcRuntimeAvailable()) add(PlayerBackend.LibVlc)
        }
    }
    private val cachedMpvCapabilities: DeviceDecodingCapabilities by lazy {
        buildDesktopCapabilities(
            declaration = desktopMpvDeviceProfileDeclaration,
            decodeSource = CapabilityEvidenceSource.PinnedEngineDeclaration,
            audioLimitSource = CapabilityEvidenceSource.DocumentedLimit,
            videoInputEnvelope = null,
            supportsHdr = true,
            videoRangeCapabilitiesByCodec =
                mapOf(
                    "h264" to VideoRangeCapabilities(),
                    "hevc" to VideoRangeCapabilities(supportsHdr10 = true),
                    "vp9" to VideoRangeCapabilities(supportsHdr10 = true),
                    "av1" to VideoRangeCapabilities(supportsHdr10 = true),
                ),
        )
    }
    private val cachedLibVlcCapabilities: DeviceDecodingCapabilities by lazy {
        buildDesktopCapabilities(
            declaration = desktopLibVlcDeviceProfileDeclaration,
            decodeSource = CapabilityEvidenceSource.StaticDeclaration,
            audioLimitSource = CapabilityEvidenceSource.Unknown,
            videoInputEnvelope = MACOS_LIBVLC_STANDARD_INPUT_ENVELOPE.takeIf { hostOsName.isMacOs() },
            supportsHdr = false,
            videoRangeCapabilitiesByCodec =
                desktopLibVlcDeviceProfileDeclaration.videoCodecs.associateWith { VideoRangeCapabilities() },
        )
    }

    private fun buildDesktopCapabilities(
        declaration: DeviceProfileDeclaration,
        decodeSource: CapabilityEvidenceSource,
        audioLimitSource: CapabilityEvidenceSource,
        videoInputEnvelope: VideoCodecResolution?,
        supportsHdr: Boolean,
        videoRangeCapabilitiesByCodec: Map<String, VideoRangeCapabilities>,
    ): DeviceDecodingCapabilities =
        DeviceDecodingCapabilities(
            videoCodecs = declaration.videoCodecs,
            audioCodecs = declaration.audioCodecs,
            supportsDolbyVision = false,
            maxAudioChannels = 8,
            videoResolutionsByCodec =
                videoInputEnvelope
                    ?.let { envelope -> declaration.videoCodecs.associateWith { envelope } }
                    .orEmpty(),
            supportsHdr = supportsHdr,
            videoRangeCapabilitiesByCodec = videoRangeCapabilitiesByCodec,
            directPlayProfiles =
                listOf(
                    DeviceDirectPlayProfile(
                        containers = declaration.containers,
                        videoCodecs = declaration.videoCodecs,
                        audioCodecs = declaration.audioCodecs,
                    ),
                ),
            subtitleProfiles = desktopSubtitleProfiles,
            videoCodecEvidence =
                declaration.videoCodecs.associateWith {
                    CodecCapabilityEvidence(
                        decodeSources = setOf(decodeSource),
                        finiteLimitSources =
                            setOf(
                                if (videoInputEnvelope != null) {
                                    CapabilityEvidenceSource.StaticDeclaration
                                } else {
                                    CapabilityEvidenceSource.Unknown
                                },
                            ),
                    )
                },
            audioCodecEvidence =
                declaration.audioCodecs.associateWith {
                    CodecCapabilityEvidence(
                        decodeSources = setOf(decodeSource),
                        finiteLimitSources = setOf(audioLimitSource),
                    )
                },
            hasUserOverridableVideoInputEnvelope = videoInputEnvelope != null,
        ).also { capabilities ->
            deviceProfileLogger.i {
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.Probe,
                        platform = PlaybackDiagnosticPlatform.Desktop,
                        candidateCount = capabilities.videoCodecs.size,
                    ),
                )
            }
        }

    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities =
        if (backend == PlayerBackend.LibVlc) cachedLibVlcCapabilities else cachedMpvCapabilities
}

private val deviceProfileLogger = diagnosticLogger(DiagnosticTag.DeviceProfile)

private val MACOS_LIBVLC_STANDARD_INPUT_ENVELOPE =
    VideoCodecResolution(
        maxWidth = 3_840,
        maxHeight = 2_160,
        maxFrameArea = 8_294_400L,
        maxFrameAreaPerSecond = 497_664_000L,
    )

private fun String.isMacOs(): Boolean = trim().startsWith("mac", ignoreCase = true)

private val desktopSubtitleProfiles =
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
        }
