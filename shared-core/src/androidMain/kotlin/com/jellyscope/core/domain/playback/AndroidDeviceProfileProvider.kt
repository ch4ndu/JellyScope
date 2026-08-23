// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import android.content.Context
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger

class AndroidDeviceProfileProvider internal constructor(
    private val environment: AndroidPlaybackCapabilityEnvironment,
    private val libVlcRuntimeAvailable: () -> Boolean = { false },
    private val mpvRuntimeAvailable: () -> Boolean = { false },
    private val deviceModel: String? = null,
) : DeviceProfileProvider {
    override val backendPolicy: PlayerBackendPolicy = androidPlayerBackendPolicy()
    constructor(context: Context) :
        this(
            environment = SystemAndroidPlaybackCapabilityEnvironment(context.applicationContext),
            libVlcRuntimeAvailable = {
                AndroidLibVlcAvailability.check(context.applicationContext) == AndroidLibVlcRuntimeAvailability.Available
            },
            mpvRuntimeAvailable = {
                AndroidMpvRuntimeAvailability.check(context.applicationContext) is AndroidMpvRuntimeAvailability.Bundled
            },
            deviceModel = Build.MODEL,
        )

    override val availableBackends: Set<PlayerBackend>
        get() =
            buildSet {
                add(PlayerBackend.ExoPlayer)
                if (cachedMpvRuntimeAvailable) add(PlayerBackend.Mpv)
                if (cachedLibVlcRuntimeAvailable) add(PlayerBackend.LibVlc)
            }

    @Volatile
    private var cachedDecoderCapabilities: AndroidDecoderCapabilities? = null
    private val cachedLibVlcRuntimeAvailable: Boolean by lazy(libVlcRuntimeAvailable)
    private val cachedMpvRuntimeAvailable: Boolean by lazy(mpvRuntimeAvailable)

    override fun capabilities(backend: PlayerBackend): DeviceDecodingCapabilities = capabilities(backend, refreshDecoders = false)

    override fun refreshCapabilities(backend: PlayerBackend): DeviceDecodingCapabilities = capabilities(backend, refreshDecoders = true)

    private fun capabilities(
        backend: PlayerBackend,
        refreshDecoders: Boolean,
    ): DeviceDecodingCapabilities =
        when (backend) {
            PlayerBackend.LibVlc -> libVlcCapabilities(refreshDecoders)
            PlayerBackend.Mpv -> mpvCapabilities(refreshDecoders)
            else -> detectCapabilities(refreshDecoders)
        }.withFireTvVideoRangeExclusions(deviceModel)

    private fun libVlcCapabilities(refreshDecoders: Boolean): DeviceDecodingCapabilities {
        val decoderCapabilities = decoderCapabilities(refreshDecoders)
        val probedCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = decoderCapabilities.videoCodecs,
                audioCodecs = decoderCapabilities.audioCodecs,
                supportsDolbyVision = false,
                videoResolutionsByCodec = decoderCapabilities.videoResolutionsByCodec,
                videoCodecEvidence = decoderCapabilities.videoCodecEvidence.withExplicitUnknowns(decoderCapabilities.videoCodecs),
            )
        return androidLibVlcCapabilityMatrix()
            .toDeviceDecodingCapabilities()
            .narrowedToProbedVideoResolutions(
                probed = probedCapabilities,
                allowPartialProbeLimits = true,
            )
    }

    private fun detectCapabilities(refreshDecoders: Boolean): DeviceDecodingCapabilities {
        val decoderCapabilities = decoderCapabilities(refreshDecoders)
        val ffmpegEac3Supported = runCatching(environment::supportsMedia3FfmpegEac3).getOrDefault(false)
        val audioCodecs =
            buildList {
                addAll(decoderCapabilities.audioCodecs)
                if (ffmpegEac3Supported) add(MEDIA3_FFMPEG_JELLYFIN_CODEC)
            }.distinct()
        val audioDecodeChannelsByCodec =
            buildMap {
                putAll(decoderCapabilities.audioDecodeChannelsByCodec)
                if (ffmpegEac3Supported) {
                    put(
                        MEDIA3_FFMPEG_JELLYFIN_CODEC,
                        maxOf(
                            get(MEDIA3_FFMPEG_JELLYFIN_CODEC) ?: 0,
                            MEDIA3_FFMPEG_EAC3_MAX_INPUT_CHANNELS,
                        ),
                    )
                }
            }
        val audioCodecEvidence =
            buildMap {
                audioCodecs.forEach { codec ->
                    put(
                        codec,
                        decoderCapabilities.audioCodecEvidence[codec]
                            ?: CodecCapabilityEvidence(
                                decodeSources = setOf(CapabilityEvidenceSource.Unknown),
                                finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown),
                            ),
                    )
                }
                if (ffmpegEac3Supported) {
                    val existing = get(MEDIA3_FFMPEG_JELLYFIN_CODEC) ?: CodecCapabilityEvidence()
                    put(
                        MEDIA3_FFMPEG_JELLYFIN_CODEC,
                        existing.copy(
                            decodeSources =
                                (existing.decodeSources - CapabilityEvidenceSource.Unknown) +
                                    CapabilityEvidenceSource.BundledRuntimeProbe,
                            finiteLimitSources =
                                (existing.finiteLimitSources - CapabilityEvidenceSource.Unknown) +
                                    CapabilityEvidenceSource.DocumentedLimit,
                        ),
                    )
                }
            }
        val displayHdrTypes = environment.activeDisplayHdrTypes()
        val videoRangeCapabilitiesByCodec = decoderCapabilities.videoRangeCapabilities(displayHdrTypes)
        val supportsDolbyVision = videoRangeCapabilitiesByCodec.values.any { it.supportsDolbyVision }
        val audioRoute = audioRouteCapabilities(environment.activeAudioSinks())

        deviceProfileLogger.i {
            formatPlaybackDiagnostic(
                PlaybackDiagnostic(
                    stage = PlaybackDiagnosticStage.Profile,
                    event = PlaybackDiagnosticEvent.Probe,
                    platform = PlaybackDiagnosticPlatform.Android,
                    candidateCount = decoderCapabilities.videoCodecs.size,
                ),
            )
        }

        val (capabilities, unusableCodecs) =
            DeviceDecodingCapabilities(
                videoCodecs = decoderCapabilities.videoCodecs,
                audioCodecs = audioCodecs,
                supportsDolbyVision = supportsDolbyVision,
                videoResolutionsByCodec = decoderCapabilities.videoResolutionsByCodec,
                videoConstraintsByCodec = decoderCapabilities.videoConstraintsByCodec,
                audioPassthroughCodecs = audioRoute.passthroughCodecs,
                maxAudioChannels = audioRoute.maxAudioChannels,
                audioDecodeChannelsByCodec = audioDecodeChannelsByCodec,
                audioPassthroughMaxChannels = audioRoute.passthroughMaxAudioChannels,
                supportsHdr = displayHdrTypes.isNotEmpty(),
                videoRangeCapabilitiesByCodec = videoRangeCapabilitiesByCodec,
                directPlayProfiles = androidDirectPlayProfiles,
                subtitleProfiles = androidSubtitleProfiles(),
                videoCodecEvidence = decoderCapabilities.videoCodecEvidence.withExplicitUnknowns(decoderCapabilities.videoCodecs),
                audioCodecEvidence = audioCodecEvidence,
            ).withoutUnusableVideoCodecs()
        unusableCodecs.forEach { entry ->
            deviceProfileLogger.i {
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Profile,
                        event = PlaybackDiagnosticEvent.CodecDropped,
                        platform = PlaybackDiagnosticPlatform.Android,
                        codec = entry.codec,
                        reason = entry.reason,
                    ),
                )
            }
        }
        return capabilities
    }

    private fun mpvCapabilities(refreshDecoders: Boolean): DeviceDecodingCapabilities {
        val decoderCapabilities = decoderCapabilities(refreshDecoders)
        val audioRoute = audioRouteCapabilities(environment.activeAudioSinks())
        val matrix = androidMpvCapabilityMatrix()
        val probedCapabilities =
            DeviceDecodingCapabilities(
                videoCodecs = decoderCapabilities.videoCodecs,
                audioCodecs = decoderCapabilities.audioCodecs,
                supportsDolbyVision = false,
                videoResolutionsByCodec = decoderCapabilities.videoResolutionsByCodec,
                videoCodecEvidence = decoderCapabilities.videoCodecEvidence.withExplicitUnknowns(decoderCapabilities.videoCodecs),
            )
        return matrix
            .toDeviceDecodingCapabilities(
                maxAudioChannels =
                    minOf(
                        matrix.maxAudioChannels,
                        audioRoute.maxAudioChannels ?: matrix.maxAudioChannels,
                    ),
            ).narrowedToProbedVideoResolutions(
                probed = probedCapabilities,
                allowPartialProbeLimits = false,
            )
    }

    private fun decoderCapabilities(refreshDecoders: Boolean): AndroidDecoderCapabilities =
        cachedDecoderCapabilities
            ?.takeUnless { refreshDecoders }
            ?: environment.decoderCapabilities().also { capabilities -> cachedDecoderCapabilities = capabilities }
}

private fun DeviceDecodingCapabilities.withFireTvVideoRangeExclusions(deviceModel: String?): DeviceDecodingCapabilities {
    if (deviceModel !in FIRE_TV_DOLBY_VISION_HDR10_PLUS_EXCLUDED_MODELS) return this
    return copy(
        unsupportedVideoRangeTypesByCodec =
            unsupportedVideoRangeTypesByCodec +
                ("hevc" to (unsupportedVideoRangeTypesByCodec["hevc"].orEmpty() + FIRE_TV_UNSUPPORTED_HEVC_RANGE_TYPES)),
    )
}

private val FIRE_TV_DOLBY_VISION_HDR10_PLUS_EXCLUDED_MODELS = setOf("AFTKRT", "AFTKA", "AFTKM", "AFTMM")

private val FIRE_TV_UNSUPPORTED_HEVC_RANGE_TYPES = setOf("DOVIWithHDR10Plus", "DOVIWithELHDR10Plus")

internal interface AndroidPlaybackCapabilityEnvironment {
    fun decoderCapabilities(): AndroidDecoderCapabilities

    fun supportsMedia3FfmpegEac3(): Boolean

    fun activeAudioSinks(): List<AndroidAudioSinkCapabilities>

    fun activeDisplayHdrTypes(): Set<Int>
}

internal data class AndroidDecoderCapabilities(
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val videoResolutionsByCodec: Map<String, VideoCodecResolution>,
    val dolbyVisionCapabilities: AndroidDolbyVisionCapabilities,
    val videoConstraintsByCodec: Map<String, VideoCodecConstraints> = emptyMap(),
    val audioDecodeChannelsByCodec: Map<String, Int> = emptyMap(),
    val videoCodecEvidence: Map<String, CodecCapabilityEvidence> = emptyMap(),
    val audioCodecEvidence: Map<String, CodecCapabilityEvidence> = emptyMap(),
)

internal enum class AndroidDecoderPreference(
    val rank: Int,
) {
    Software(0),
    Unclassified(1),
    HardwarePreferred(2),
}

internal enum class AndroidDecoderClassificationEvidence {
    PlatformHardwareProbe,
    PlatformSoftwareProbe,
    PlatformUnclassifiedProbe,
    LegacyCodecNameClassification,
}

internal data class AndroidDecoderClassification(
    val preference: AndroidDecoderPreference,
    val evidence: AndroidDecoderClassificationEvidence,
)

internal data class AndroidVideoProfileLevel(
    val profile: Int,
    val level: Int,
)

internal data class AndroidVideoDecoderCandidate(
    val codec: String,
    val resolution: VideoCodecResolution,
    val profileLevels: List<AndroidVideoProfileLevel>,
    val preference: AndroidDecoderPreference,
    val classificationEvidence: AndroidDecoderClassificationEvidence,
    val requiresSecurePlayback: Boolean,
)

internal data class AndroidDolbyVisionDecoderInfo(
    val hasDolbyVisionMime: Boolean,
    val profileValues: List<Int>,
    val supportsMultiInstanceHevc: Boolean,
)

internal data class AndroidDolbyVisionCapabilities(
    val supportsHevcBase: Boolean = false,
    val supportsAv1Base: Boolean = false,
    val supportsEnhancementLayer: Boolean = false,
)

internal data class AndroidAudioSinkCapabilities(
    val encodings: Set<Int>,
    val maxChannelCount: Int,
)

@androidx.annotation.OptIn(UnstableApi::class)
private class SystemAndroidPlaybackCapabilityEnvironment(
    private val appContext: Context,
) : AndroidPlaybackCapabilityEnvironment {
    override fun decoderCapabilities(): AndroidDecoderCapabilities {
        val codecInfos =
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filterNot { codecInfo -> codecInfo.isEncoder }
        val supportedTypes = codecInfos.flatMap { codecInfo -> codecInfo.supportedTypes.toList() }
        val selectedVideoCandidates = selectAndroidVideoDecoderCandidates(androidVideoDecoderCandidates(codecInfos))
        val detectedAudioCodecs = detectedAudioCodecs(supportedTypes)
        val audioDecodeLimitsByCodec = audioDecodeChannelLimits(codecInfos)
        val audioDecodeChannelsByCodec =
            audioDecodeLimitsByCodec.mapValues { (_, limit) -> limit.maxInputChannelCount }
        val videoCodecs = selectedVideoCandidates.keys.toList().ifEmpty { listOf("h264") }
        val audioCodecs = detectedAudioCodecs.ifEmpty { listOf("aac", "mp3") }
        return AndroidDecoderCapabilities(
            videoCodecs = videoCodecs,
            audioCodecs = audioCodecs,
            videoResolutionsByCodec = selectedVideoCandidates.mapValues { (_, candidate) -> candidate.resolution },
            videoConstraintsByCodec =
                selectedVideoCandidates
                    .mapNotNull { (codec, candidate) ->
                        if (codec !in constrainedVideoCodecs || candidate.profileLevels.isEmpty()) {
                            null
                        } else {
                            codec to resolveSelectedVideoCodecConstraints(codec, candidate.profileLevels)
                        }
                    }.toMap(),
            audioDecodeChannelsByCodec = audioDecodeChannelsByCodec,
            videoCodecEvidence =
                if (selectedVideoCandidates.isEmpty()) {
                    mapOf(
                        "h264" to
                            CodecCapabilityEvidence(
                                decodeSources = setOf(CapabilityEvidenceSource.StaticDeclaration),
                                finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown),
                            ),
                    )
                } else {
                    selectedVideoCandidates.mapValues { (_, candidate) ->
                        val source = candidate.classificationEvidence.toCapabilityEvidenceSource()
                        CodecCapabilityEvidence(
                            decodeSources = setOf(source),
                            finiteLimitSources =
                                setOf(
                                    if (candidate.resolution.hasFiniteValue()) {
                                        source
                                    } else {
                                        CapabilityEvidenceSource.Unknown
                                    },
                                ),
                        )
                    }
                },
            audioCodecEvidence =
                audioCodecs.associateWith { codec ->
                    if (detectedAudioCodecs.isEmpty()) {
                        CodecCapabilityEvidence(
                            decodeSources = setOf(CapabilityEvidenceSource.StaticDeclaration),
                            finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown),
                        )
                    } else {
                        CodecCapabilityEvidence(
                            decodeSources = setOf(CapabilityEvidenceSource.PlatformUnclassifiedProbe),
                            finiteLimitSources =
                                audioDecodeLimitsByCodec[codec]
                                    ?.evidenceSources
                                    ?.takeIf { sources -> sources.isNotEmpty() }
                                    ?: setOf(CapabilityEvidenceSource.Unknown),
                        )
                    }
                },
            dolbyVisionCapabilities =
                resolveAndroidDolbyVisionCapabilities(
                    AndroidDolbyVisionDecoderInfo(
                        hasDolbyVisionMime =
                            supportedTypes.any { type -> type.equals(DOLBY_VISION_MIME_TYPE, ignoreCase = true) },
                        profileValues = dolbyVisionProfileValues(codecInfos),
                        supportsMultiInstanceHevc = codecInfos.any { codecInfo -> codecInfo.supportsMultiInstanceHevcDecode() },
                    ),
                ),
        )
    }

    override fun supportsMedia3FfmpegEac3(): Boolean = cachedMedia3FfmpegEac3Support

    override fun activeAudioSinks(): List<AndroidAudioSinkCapabilities> {
        val audioManager =
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return emptyList()
        val routedDevices =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val mediaAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        return routedDevices
            .map { device -> device.toAudioSinkCapabilities() }
            .ifEmpty { legacyHdmiAudioSink()?.let(::listOf).orEmpty() }
    }

    override fun activeDisplayHdrTypes(): Set<Int> {
        val displayManager =
            appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return emptySet()
        return runCatching {
            displayManager
                .getDisplay(Display.DEFAULT_DISPLAY)
                ?.hdrCapabilities
                ?.supportedHdrTypes
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun legacyHdmiAudioSink(): AndroidAudioSinkCapabilities? {
        val intent =
            runCatching {
                appContext.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            }.getOrNull()
                ?: return null
        return legacyHdmiAudioSink(
            plugState = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0),
            encodings = intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS),
            maxChannelCount = intent.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, SAFE_STEREO_CHANNEL_COUNT),
        )
    }

    private companion object {
        val cachedMedia3FfmpegEac3Support: Boolean by lazy {
            runCatching {
                FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3)
            }.getOrDefault(false)
        }
    }
}

private fun AndroidDecoderClassificationEvidence.toCapabilityEvidenceSource(): CapabilityEvidenceSource =
    when (this) {
        AndroidDecoderClassificationEvidence.PlatformHardwareProbe -> CapabilityEvidenceSource.PlatformHardwareProbe
        AndroidDecoderClassificationEvidence.PlatformSoftwareProbe -> CapabilityEvidenceSource.PlatformSoftwareProbe
        AndroidDecoderClassificationEvidence.PlatformUnclassifiedProbe -> CapabilityEvidenceSource.PlatformUnclassifiedProbe
        AndroidDecoderClassificationEvidence.LegacyCodecNameClassification -> CapabilityEvidenceSource.LegacyCodecNameClassification
    }

private fun VideoCodecResolution.hasFiniteValue(): Boolean =
    maxWidth != null || maxHeight != null || maxFrameArea != null || maxFrameAreaPerSecond != null

private fun Map<String, CodecCapabilityEvidence>.withExplicitUnknowns(codecs: List<String>): Map<String, CodecCapabilityEvidence> =
    codecs.associateWith { codec ->
        this[codec]
            ?: CodecCapabilityEvidence(
                decodeSources = setOf(CapabilityEvidenceSource.Unknown),
                finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown),
            )
    }

internal fun resolveAndroidDolbyVisionCapabilities(decoderInfo: AndroidDolbyVisionDecoderInfo): AndroidDolbyVisionCapabilities {
    if (!decoderInfo.hasDolbyVisionMime) return AndroidDolbyVisionCapabilities()

    // Some vendor decoders expose the Dolby Vision MIME but no profile list.
    // That proves only the conservative HEVC base path, never AV1 or EL.
    if (decoderInfo.profileValues.isEmpty()) {
        return AndroidDolbyVisionCapabilities(supportsHevcBase = true)
    }

    return AndroidDolbyVisionCapabilities(
        supportsHevcBase =
            decoderInfo.profileValues.any { profile ->
                profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn ||
                    profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt
            },
        supportsAv1Base =
            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110 in decoderInfo.profileValues,
        supportsEnhancementLayer =
            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb in decoderInfo.profileValues &&
                decoderInfo.supportsMultiInstanceHevc,
    )
}

private fun dolbyVisionProfileValues(codecInfos: List<MediaCodecInfo>): List<Int> =
    codecInfos.flatMap { codecInfo ->
        val supportedType =
            codecInfo.supportedTypes.firstOrNull { type -> type.equals(DOLBY_VISION_MIME_TYPE, ignoreCase = true) }
                ?: return@flatMap emptyList()
        runCatching { codecInfo.getCapabilitiesForType(supportedType).profileLevels.map { profileLevel -> profileLevel.profile } }
            .getOrDefault(emptyList())
    }

private fun MediaCodecInfo.supportsMultiInstanceHevcDecode(): Boolean {
    val supportedType =
        supportedTypes.firstOrNull { type -> type.equals(HEVC_MIME_TYPE, ignoreCase = true) }
            ?: return false
    return runCatching { getCapabilitiesForType(supportedType).maxSupportedInstances > 1 }
        .getOrDefault(false)
}

private fun AndroidDecoderCapabilities.videoRangeCapabilities(displayHdrTypes: Set<Int>): Map<String, VideoRangeCapabilities> =
    videoCodecs.associateWith { codec ->
        if (codec !in hdrCapableVideoCodecs) {
            VideoRangeCapabilities()
        } else {
            VideoRangeCapabilities(
                supportsHdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in displayHdrTypes,
                supportsHdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in displayHdrTypes,
                supportsHlg = Display.HdrCapabilities.HDR_TYPE_HLG in displayHdrTypes,
                supportsDolbyVision =
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in displayHdrTypes &&
                        when (codec) {
                            "hevc" -> dolbyVisionCapabilities.supportsHevcBase
                            "av1" -> dolbyVisionCapabilities.supportsAv1Base
                            else -> false
                        },
                supportsDolbyVisionWithEL =
                    codec == "hevc" &&
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in displayHdrTypes &&
                        dolbyVisionCapabilities.supportsEnhancementLayer,
            )
        }
    }

internal fun legacyHdmiAudioSink(
    plugState: Int,
    encodings: IntArray?,
    maxChannelCount: Int,
): AndroidAudioSinkCapabilities? =
    if (plugState == 1) {
        AndroidAudioSinkCapabilities(
            encodings = encodings?.toSet().orEmpty(),
            maxChannelCount = maxChannelCount.takeIf { channels -> channels > 0 } ?: SAFE_STEREO_CHANNEL_COUNT,
        )
    } else {
        null
    }

internal fun audioRouteCapabilities(sinks: List<AndroidAudioSinkCapabilities>): AudioRouteCapabilities {
    if (sinks.isEmpty()) {
        return AudioRouteCapabilities(maxAudioChannels = SAFE_STEREO_CHANNEL_COUNT)
    }
    val passthroughCodecs =
        sinks
            .map { sink -> sink.encodings.flatMap { encoding -> encoding.toJellyfinPassthroughCodecs() }.toSet() }
            .reduce { common, codecs -> common intersect codecs }
            .toList()
            .sorted()
    val maxAudioChannels =
        sinks.minOf { sink -> sink.maxChannelCount.takeIf { channels -> channels > 0 } ?: SAFE_STEREO_CHANNEL_COUNT }
    return AudioRouteCapabilities(
        passthroughCodecs = passthroughCodecs,
        maxAudioChannels = maxAudioChannels,
        passthroughMaxAudioChannels = maxAudioChannels.takeIf { passthroughCodecs.isNotEmpty() },
    )
}

private fun AudioDeviceInfo.toAudioSinkCapabilities(): AndroidAudioSinkCapabilities =
    AndroidAudioSinkCapabilities(
        encodings = encodings.toSet(),
        maxChannelCount = channelCounts.maxOrNull() ?: SAFE_STEREO_CHANNEL_COUNT,
    )

private fun mappedCodecs(
    mappings: List<CodecMimeMapping>,
    supportedTypes: List<String>,
): List<String> =
    mappings
        .filter { mapping ->
            supportedTypes.any { type -> type.equals(mapping.mimeType, ignoreCase = true) }
        }.map { mapping -> mapping.codecName }

internal fun detectedAudioCodecs(supportedTypes: List<String>): List<String> = mappedCodecs(audioCodecMappings, supportedTypes).distinct()

internal data class AndroidAudioDecodeChannelLimit(
    val maxInputChannelCount: Int,
    val evidenceSources: Set<CapabilityEvidenceSource>,
)

private fun audioDecodeChannelLimits(codecInfos: List<MediaCodecInfo>): Map<String, AndroidAudioDecodeChannelLimit> =
    audioCodecMappings
        .mapNotNull { mapping ->
            val limits =
                codecInfos
                    .mapNotNull codecInfo@{ codecInfo ->
                        val supportedType =
                            codecInfo.supportedTypes.firstOrNull { type -> type.equals(mapping.mimeType, ignoreCase = true) }
                                ?: return@codecInfo null
                        val rawMaxInputChannels =
                            runCatching {
                                codecInfo
                                    .getCapabilitiesForType(supportedType)
                                    .audioCapabilities
                                    ?.maxInputChannelCount
                                    ?: 0
                            }.getOrDefault(0)
                        adjustedMedia3InputChannelLimit(
                            mimeType = mapping.mimeType,
                            rawMaxInputChannelCount = rawMaxInputChannels,
                            sdkInt = Build.VERSION.SDK_INT,
                        )
                    }.filter { limit -> limit.maxInputChannelCount > 0 }
            limits.maxChannelLimit()?.let { limit -> mapping.codecName to limit }
        }.groupBy(keySelector = { (codec, _) -> codec }, valueTransform = { (_, limit) -> limit })
        .mapValues { (_, limits) -> limits.maxChannelLimit() }
        .mapNotNull { (codec, limit) -> limit?.let { codec to it } }
        .toMap()

private fun List<AndroidAudioDecodeChannelLimit>.maxChannelLimit(): AndroidAudioDecodeChannelLimit? {
    val maximum = maxOfOrNull(AndroidAudioDecodeChannelLimit::maxInputChannelCount) ?: return null
    return AndroidAudioDecodeChannelLimit(
        maxInputChannelCount = maximum,
        evidenceSources =
            filter { limit -> limit.maxInputChannelCount == maximum }
                .flatMap(AndroidAudioDecodeChannelLimit::evidenceSources)
                .toSet(),
    )
}

internal fun adjustedMedia3InputChannelLimit(
    mimeType: String,
    rawMaxInputChannelCount: Int,
    sdkInt: Int,
): AndroidAudioDecodeChannelLimit {
    if (rawMaxInputChannelCount > 1 || (sdkInt >= Build.VERSION_CODES.O && rawMaxInputChannelCount > 0)) {
        return AndroidAudioDecodeChannelLimit(
            maxInputChannelCount = rawMaxInputChannelCount,
            evidenceSources = setOf(CapabilityEvidenceSource.PlatformUnclassifiedProbe),
        )
    }
    val adjustedCount =
        when (mimeType) {
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_AMR_NB,
            MimeTypes.AUDIO_AMR_WB,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_RAW,
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_ALAW,
            MimeTypes.AUDIO_MLAW,
            MimeTypes.AUDIO_MSGSM,
            -> rawMaxInputChannelCount
            MimeTypes.AUDIO_AC3 -> 6
            MimeTypes.AUDIO_E_AC3 -> 16
            else -> 30
        }
    val evidenceSource =
        if (adjustedCount == rawMaxInputChannelCount) {
            CapabilityEvidenceSource.PlatformUnclassifiedProbe
        } else {
            CapabilityEvidenceSource.DocumentedLimit
        }
    return AndroidAudioDecodeChannelLimit(
        maxInputChannelCount = adjustedCount,
        evidenceSources = setOf(evidenceSource),
    )
}

private fun MediaCodecInfo.CodecCapabilities.videoResolution(): VideoCodecResolution? =
    runCatching {
        val videoCapabilities = videoCapabilities ?: return@runCatching null
        val maxWidth = videoCapabilities.supportedWidths.upper
        // `supportedHeights.upper` is the tallest height for *some* width, not
        // the height that pairs with `maxWidth`. Decoders couple the two through
        // a block-count limit, so ask the platform which height actually fits at
        // this width: Amlogic AVC/HEVC report size max 4096x4096 with block-count
        // 34560, and answer 2160 here rather than 4096. Never fall back to the
        // independent ceiling — that is the overstatement this exists to remove.
        val maxHeight = pairedMaxHeight(videoCapabilities, maxWidth) ?: return@runCatching null
        // Budget in block-padded pixels, matching how `blockPaddedArea` charges a
        // frame. Comparing a raw ceiling against a padded frame would spuriously
        // cap an exact 1920x1080 source on a 1920x1080 decoder, because 1080 pads
        // to 1088.
        val frameArea = blockPaddedArea(maxWidth, maxHeight)
        // Throughput is a third, independent limit (`blocks-per-second`). Express
        // it as pixels per second so shared code needs no notion of macroblocks.
        val maxFrameRate = pairedMaxFrameRate(videoCapabilities, maxWidth, maxHeight)
        VideoCodecResolution(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxFrameArea = frameArea,
            maxFrameAreaPerSecond = maxFrameRate?.let { rate -> (frameArea.toDouble() * rate).toLong() },
        )
    }.getOrNull()

private fun androidVideoDecoderCandidates(codecInfos: List<MediaCodecInfo>): List<AndroidVideoDecoderCandidate> =
    videoCodecMappings.flatMap { mapping ->
        codecInfos.mapNotNull { codecInfo ->
            val supportedType =
                codecInfo.supportedTypes.firstOrNull { type -> type.equals(mapping.mimeType, ignoreCase = true) }
                    ?: return@mapNotNull null
            val capabilities = runCatching { codecInfo.getCapabilitiesForType(supportedType) }.getOrNull() ?: return@mapNotNull null
            val resolution = capabilities.videoResolution() ?: VideoCodecResolution()
            val classification =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    classifyAndroidDecoder(
                        codecName = codecInfo.name,
                        sdkInt = Build.VERSION.SDK_INT,
                        isHardwareAccelerated = codecInfo.isHardwareAccelerated,
                        isSoftwareOnly = codecInfo.isSoftwareOnly,
                    )
                } else {
                    classifyAndroidDecoder(codecName = codecInfo.name, sdkInt = Build.VERSION.SDK_INT)
                }
            AndroidVideoDecoderCandidate(
                codec = mapping.codecName,
                resolution = resolution,
                profileLevels =
                    capabilities.profileLevels.map { value ->
                        AndroidVideoProfileLevel(profile = value.profile, level = value.level)
                    },
                preference = classification.preference,
                classificationEvidence = classification.evidence,
                requiresSecurePlayback =
                    runCatching {
                        capabilities.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                    }.getOrDefault(false),
            )
        }
    }

internal fun classifyAndroidDecoder(
    codecName: String,
    sdkInt: Int,
    isHardwareAccelerated: Boolean = false,
    isSoftwareOnly: Boolean = false,
): AndroidDecoderClassification {
    if (sdkInt >= Build.VERSION_CODES.Q) {
        return when {
            isHardwareAccelerated && !isSoftwareOnly ->
                AndroidDecoderClassification(
                    AndroidDecoderPreference.HardwarePreferred,
                    AndroidDecoderClassificationEvidence.PlatformHardwareProbe,
                )
            !isHardwareAccelerated && isSoftwareOnly ->
                AndroidDecoderClassification(
                    AndroidDecoderPreference.Software,
                    AndroidDecoderClassificationEvidence.PlatformSoftwareProbe,
                )
            else ->
                AndroidDecoderClassification(
                    AndroidDecoderPreference.Unclassified,
                    AndroidDecoderClassificationEvidence.PlatformUnclassifiedProbe,
                )
        }
    }

    val normalizedName = codecName.trim().lowercase()
    if (normalizedName.isEmpty()) {
        return AndroidDecoderClassification(
            AndroidDecoderPreference.Unclassified,
            AndroidDecoderClassificationEvidence.PlatformUnclassifiedProbe,
        )
    }
    val preference =
        when {
            normalizedName.startsWith("arc.") -> AndroidDecoderPreference.HardwarePreferred
            normalizedName.startsWith("omx.google.") -> AndroidDecoderPreference.Software
            normalizedName.startsWith("omx.ffmpeg.") -> AndroidDecoderPreference.Software
            normalizedName.startsWith("omx.sec.") && ".sw." in normalizedName -> AndroidDecoderPreference.Software
            normalizedName == "omx.qcom.video.decoder.hevcswvdec" -> AndroidDecoderPreference.Software
            normalizedName.startsWith("c2.android.") -> AndroidDecoderPreference.Software
            normalizedName.startsWith("c2.google.") -> AndroidDecoderPreference.Software
            !normalizedName.startsWith("omx.") && !normalizedName.startsWith("c2.") -> AndroidDecoderPreference.Software
            else -> AndroidDecoderPreference.HardwarePreferred
        }
    return AndroidDecoderClassification(
        preference = preference,
        evidence = AndroidDecoderClassificationEvidence.LegacyCodecNameClassification,
    )
}

internal fun selectPreferredAndroidVideoDecoder(candidates: List<AndroidVideoDecoderCandidate>): AndroidVideoDecoderCandidate? =
    candidates
        .filterNot(AndroidVideoDecoderCandidate::requiresSecurePlayback)
        .maxWithOrNull(
            compareBy(
                { candidate -> candidate.preference.rank },
                { candidate ->
                    candidate.resolution.maxFrameArea
                        ?: blockPaddedArea(
                            candidate.resolution.maxWidth ?: 0,
                            candidate.resolution.maxHeight ?: 0,
                        )
                },
                { candidate -> candidate.resolution.maxFrameAreaPerSecond ?: 0L },
            ),
        )

private fun selectAndroidVideoDecoderCandidates(
    candidates: List<AndroidVideoDecoderCandidate>,
): Map<String, AndroidVideoDecoderCandidate> =
    candidates
        .groupBy(AndroidVideoDecoderCandidate::codec)
        .mapNotNull { (codec, codecCandidates) ->
            selectPreferredAndroidVideoDecoder(codecCandidates)?.let { selected -> codec to selected }
        }.toMap()

// Descending probes for the paired limits. `getSupportedHeightsFor` and
// `getSupportedFrameRatesFor` throw for values the decoder rejects, so fall back
// to asking about concrete sizes/rates rather than to an unpaired maximum.
private val pairedHeightCandidates =
    listOf(4_320, 2_880, 2_160, 1_600, 1_440, 1_200, 1_080, 720, 576, 480, 360, 288, 240)
private val pairedFrameRateCandidates = listOf(120.0, 100.0, 60.0, 50.0, 30.0, 25.0, 24.0)

private fun pairedMaxHeight(
    videoCapabilities: MediaCodecInfo.VideoCapabilities,
    width: Int,
): Int? =
    runCatching { videoCapabilities.getSupportedHeightsFor(width).upper }.getOrNull()
        ?: pairedHeightCandidates.firstOrNull { height ->
            runCatching { videoCapabilities.isSizeSupported(width, height) }.getOrDefault(false)
        }

private fun pairedMaxFrameRate(
    videoCapabilities: MediaCodecInfo.VideoCapabilities,
    width: Int,
    height: Int,
): Double? =
    runCatching { videoCapabilities.getSupportedFrameRatesFor(width, height).upper }
        .getOrNull()
        ?.takeIf { rate -> rate.isFinite() && rate > 0.0 }
        ?: pairedFrameRateCandidates.firstOrNull { rate ->
            runCatching { videoCapabilities.areSizeAndRateSupported(width, height, rate) }.getOrDefault(false)
        }

internal fun resolveSelectedVideoCodecConstraints(
    codec: String,
    profileLevels: List<AndroidVideoProfileLevel>,
): VideoCodecConstraints {
    val mappedProfileLevels =
        profileLevels.mapNotNull { value ->
            codec.profileName(value.profile)?.let { profile -> profile to codec.levelValue(value.level) }
        }
    val mappedProfiles = mappedProfileLevels.map { (profile, _) -> profile }.distinct()
    val profileMaximumLevels =
        mappedProfileLevels
            .groupBy(keySelector = { (profile, _) -> profile }, valueTransform = { (_, level) -> level })
            .values
            .map { levels -> levels.filterNotNull().maxOrNull() }
    val conservativeMaxLevel =
        if (profileMaximumLevels.isNotEmpty() && profileMaximumLevels.all { level -> level != null }) {
            profileMaximumLevels.filterNotNull().minOrNull()
        } else {
            null
        }
    return resolvedVideoCodecConstraints(
        codec = codec,
        mappedProfiles = mappedProfiles,
        maxLevel = conservativeMaxLevel,
    )
}

// A profile enumeration that lacks the codec's mainstream anchor profiles is
// vendor under-reporting (e.g. Amlogic OMX listing only baseline): trusting it
// makes the server encode degraded transcodes, so only the conservative 8-bit
// cap survives and the server keeps its default encode profile.
internal fun resolveVideoCodecConstraints(
    codec: String,
    mappedProfiles: List<String>,
    mappedLevels: List<Int>,
): VideoCodecConstraints =
    resolvedVideoCodecConstraints(
        codec = codec,
        mappedProfiles = mappedProfiles,
        maxLevel = mappedLevels.maxOrNull(),
    )

private fun resolvedVideoCodecConstraints(
    codec: String,
    mappedProfiles: List<String>,
    maxLevel: Int?,
): VideoCodecConstraints {
    val anchors = trustAnchorProfilesByCodec[codec]
    if (anchors != null && mappedProfiles.none { profile -> profile in anchors }) {
        deviceProfileLogger.i { "codec-constraints-untrusted codec=$codec mappedProfiles=${mappedProfiles.size}" }
        return VideoCodecConstraints(
            supportedProfiles = emptyList(),
            maxLevel = null,
            maxBitDepth = UNTRUSTED_ENUMERATION_MAX_BIT_DEPTH,
        )
    }
    return VideoCodecConstraints(
        // Jellyfin uses the FIRST advertised profile as its transcode encode
        // target (verified against 10.11: baseline-first lists produce
        // constrained_baseline HLS), so order best-first; EqualsAny direct-play
        // eligibility is order-insensitive.
        supportedProfiles = mappedProfiles.sortedBy { profile -> encodePreferenceRank[profile] ?: Int.MAX_VALUE },
        maxLevel = maxLevel,
        maxBitDepth = if (mappedProfiles.any { it.contains("10") }) 10 else 8,
    )
}

private val encodePreferenceRank =
    mapOf(
        "high" to 0,
        "main" to 1,
        "main 10" to 2,
        "high 10" to 3,
        "baseline" to 4,
        "constrained baseline" to 5,
    )

private val trustAnchorProfilesByCodec =
    mapOf(
        "h264" to setOf("main", "high"),
        "hevc" to setOf("main", "main 10"),
        "av1" to setOf("main", "main 10"),
    )

private const val UNTRUSTED_ENUMERATION_MAX_BIT_DEPTH = 8

private fun String.profileName(profile: Int): String? =
    when (this) {
        "h264" ->
            when (profile) {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "baseline"
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "constrained baseline"
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "main"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "high"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "high 10"
                else -> null
            }
        "hevc" ->
            when (profile) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "main"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "main 10"
                else -> null
            }
        "av1" ->
            when (profile) {
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 -> "main"
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 -> "main 10"
                else -> null
            }
        else -> null
    }

private fun String.levelValue(level: Int): Int? =
    when (this) {
        "h264" -> avcLevels[level]
        "hevc" -> hevcLevels[level]
        "av1" -> av1Levels[level]
        else -> null
    }

private data class CodecMimeMapping(
    val mimeType: String,
    val codecName: String,
)

internal data class AudioRouteCapabilities(
    val passthroughCodecs: List<String> = emptyList(),
    val maxAudioChannels: Int? = null,
    val passthroughMaxAudioChannels: Int? = null,
)

private fun Int.toJellyfinPassthroughCodecs(): List<String> =
    when (this) {
        AudioFormat.ENCODING_AC3 -> listOf("ac3")
        AudioFormat.ENCODING_E_AC3 -> listOf("eac3")
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD,
        -> listOf("dts", "dca")
        AudioFormat.ENCODING_DOLBY_TRUEHD -> listOf("truehd")
        else -> emptyList()
    }

private val videoCodecMappings =
    listOf(
        CodecMimeMapping(mimeType = "video/avc", codecName = "h264"),
        CodecMimeMapping(mimeType = "video/hevc", codecName = "hevc"),
        CodecMimeMapping(mimeType = "video/x-vnd.on2.vp9", codecName = "vp9"),
        CodecMimeMapping(mimeType = "video/av01", codecName = "av1"),
    )

private const val DOLBY_VISION_MIME_TYPE = "video/dolby-vision"
private const val HEVC_MIME_TYPE = "video/hevc"
private const val MEDIA3_FFMPEG_JELLYFIN_CODEC = "eac3"
private const val MEDIA3_FFMPEG_EAC3_MAX_INPUT_CHANNELS = 8
private val hdrCapableVideoCodecs = setOf("hevc", "vp9", "av1")
private val audioCodecMappings =
    listOf(
        CodecMimeMapping(mimeType = "audio/mp4a-latm", codecName = "aac"),
        CodecMimeMapping(mimeType = "audio/mpeg", codecName = "mp3"),
        CodecMimeMapping(mimeType = "audio/ac3", codecName = "ac3"),
        CodecMimeMapping(mimeType = "audio/eac3", codecName = "eac3"),
        CodecMimeMapping(mimeType = "audio/opus", codecName = "opus"),
        CodecMimeMapping(mimeType = "audio/flac", codecName = "flac"),
        CodecMimeMapping(mimeType = "audio/vorbis", codecName = "vorbis"),
        CodecMimeMapping(mimeType = "audio/raw", codecName = "pcm"),
        CodecMimeMapping(mimeType = MediaFormat.MIMETYPE_AUDIO_DTS, codecName = "dts"),
        CodecMimeMapping(mimeType = MediaFormat.MIMETYPE_AUDIO_DTS, codecName = "dca"),
        CodecMimeMapping(mimeType = MediaFormat.MIMETYPE_AUDIO_DTS_HD, codecName = "dts"),
        CodecMimeMapping(mimeType = MediaFormat.MIMETYPE_AUDIO_DTS_HD, codecName = "dca"),
    )

private val constrainedVideoCodecs = setOf("h264", "hevc", "av1")
private val avcLevels =
    mapOf(
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 to 10,
        MediaCodecInfo.CodecProfileLevel.AVCLevel11 to 11,
        MediaCodecInfo.CodecProfileLevel.AVCLevel12 to 12,
        MediaCodecInfo.CodecProfileLevel.AVCLevel13 to 13,
        MediaCodecInfo.CodecProfileLevel.AVCLevel2 to 20,
        MediaCodecInfo.CodecProfileLevel.AVCLevel21 to 21,
        MediaCodecInfo.CodecProfileLevel.AVCLevel22 to 22,
        MediaCodecInfo.CodecProfileLevel.AVCLevel3 to 30,
        MediaCodecInfo.CodecProfileLevel.AVCLevel31 to 31,
        MediaCodecInfo.CodecProfileLevel.AVCLevel32 to 32,
        MediaCodecInfo.CodecProfileLevel.AVCLevel4 to 40,
        MediaCodecInfo.CodecProfileLevel.AVCLevel41 to 41,
        MediaCodecInfo.CodecProfileLevel.AVCLevel42 to 42,
        MediaCodecInfo.CodecProfileLevel.AVCLevel5 to 50,
        MediaCodecInfo.CodecProfileLevel.AVCLevel51 to 51,
        MediaCodecInfo.CodecProfileLevel.AVCLevel52 to 52,
        MediaCodecInfo.CodecProfileLevel.AVCLevel6 to 60,
        MediaCodecInfo.CodecProfileLevel.AVCLevel61 to 61,
        MediaCodecInfo.CodecProfileLevel.AVCLevel62 to 62,
    )

// Jellyfin's HEVC `Level` is the general_level_idc (level x 30) and carries no
// tier, so Main and High tier map to the same value. Both tiers must be listed:
// a decoder that enumerates only High-tier levels otherwise maps to nothing,
// leaving maxLevel null and suppressing the `VideoLevel` condition entirely — so
// the server would consider an out-of-level HEVC source eligible for direct play.
private val hevcLevels =
    mapOf(
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3 to 90,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3 to 90,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31 to 93,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31 to 93,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4 to 120,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4 to 120,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41 to 123,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41 to 123,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 to 150,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5 to 150,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51 to 153,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51 to 153,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52 to 156,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52 to 156,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6 to 180,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6 to 180,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61 to 183,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61 to 183,
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62 to 186,
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62 to 186,
    )
private val av1Levels =
    mapOf(
        MediaCodecInfo.CodecProfileLevel.AV1Level3 to 30,
        MediaCodecInfo.CodecProfileLevel.AV1Level31 to 31,
        MediaCodecInfo.CodecProfileLevel.AV1Level4 to 40,
        MediaCodecInfo.CodecProfileLevel.AV1Level41 to 41,
        MediaCodecInfo.CodecProfileLevel.AV1Level5 to 50,
        MediaCodecInfo.CodecProfileLevel.AV1Level51 to 51,
        MediaCodecInfo.CodecProfileLevel.AV1Level52 to 52,
        MediaCodecInfo.CodecProfileLevel.AV1Level53 to 53,
        MediaCodecInfo.CodecProfileLevel.AV1Level6 to 60,
        MediaCodecInfo.CodecProfileLevel.AV1Level61 to 61,
        MediaCodecInfo.CodecProfileLevel.AV1Level62 to 62,
        MediaCodecInfo.CodecProfileLevel.AV1Level63 to 63,
    )

private val deviceProfileLogger = diagnosticLogger(DiagnosticTag.DeviceProfile)
private const val SAFE_STEREO_CHANNEL_COUNT = 2

private fun androidSubtitleProfiles(): List<DeviceSubtitleProfile> {
    val parserFactory = DefaultSubtitleParserFactory()
    val textProfiles =
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
        }
    val bitmapProfiles =
        listOf(
            "pgs" to MimeTypes.APPLICATION_PGS,
            "pgssub" to MimeTypes.APPLICATION_PGS,
            "vobsub" to MimeTypes.APPLICATION_VOBSUB,
            "dvdsub" to MimeTypes.APPLICATION_VOBSUB,
            "dvbsub" to MimeTypes.APPLICATION_DVBSUBS,
            "dvb" to MimeTypes.APPLICATION_DVBSUBS,
        ).map { (format, mimeType) ->
            val embedSupported = parserFactory.supportsFormat(Format.Builder().setSampleMimeType(mimeType).build())
            DeviceSubtitleProfile(
                format = format,
                deliveryMethods =
                    buildList {
                        if (embedSupported) add(SubtitleDeliveryMethod.Embed)
                        add(SubtitleDeliveryMethod.Encode)
                    },
                kind = SubtitleKind.Bitmap,
            )
        }
    return textProfiles + bitmapProfiles
}
