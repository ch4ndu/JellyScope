// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

data class DeviceDecodingCapabilities(
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val supportsDolbyVision: Boolean,
    val videoResolutionsByCodec: Map<String, VideoCodecResolution> = emptyMap(),
    val audioPassthroughCodecs: List<String> = emptyList(),
    val maxAudioChannels: Int? = null,
    val audioDecodeChannelsByCodec: Map<String, Int> = emptyMap(),
    val audioPassthroughMaxChannels: Int? = null,
    val supportsHdr: Boolean = false,
    val videoRangeCapabilitiesByCodec: Map<String, VideoRangeCapabilities> = emptyMap(),
    val unsupportedVideoRangeTypesByCodec: Map<String, Set<String>> = emptyMap(),
    val directPlayProfiles: List<DeviceDirectPlayProfile> = emptyList(),
    val subtitleProfiles: List<DeviceSubtitleProfile> = emptyList(),
    val videoConstraintsByCodec: Map<String, VideoCodecConstraints> = emptyMap(),
    val droppedVideoCodecs: List<UnusableVideoCodec> = emptyList(),
    val videoCodecEvidence: Map<String, CodecCapabilityEvidence> = emptyMap(),
    val audioCodecEvidence: Map<String, CodecCapabilityEvidence> = emptyMap(),
    /** True only when the finite video limits are an app policy the user may bypass. */
    val hasUserOverridableVideoInputEnvelope: Boolean = false,
)

enum class CapabilityEvidenceSource {
    PlatformHardwareProbe,
    PlatformSoftwareProbe,
    PlatformUnclassifiedProbe,
    LegacyCodecNameClassification,
    BundledRuntimeProbe,
    PinnedEngineDeclaration,
    StaticDeclaration,
    DocumentedLimit,
    Unknown,
}

data class CodecCapabilityEvidence(
    val decodeSources: Set<CapabilityEvidenceSource> = emptySet(),
    val finiteLimitSources: Set<CapabilityEvidenceSource> = emptySet(),
)

/**
 * The range types a specific decoder can render without server conversion.
 *
 * Dolby Vision base and enhancement-layer support are deliberately separate:
 * a base-layer decoder does not prove that the device can decode the second
 * HEVC instance required by enhancement-layer content.
 */
data class VideoRangeCapabilities(
    val supportsHdr10: Boolean = false,
    val supportsHdr10Plus: Boolean = false,
    val supportsHlg: Boolean = false,
    val supportsDolbyVision: Boolean = false,
    val supportsDolbyVisionWithEL: Boolean = false,
)

data class DeviceDirectPlayProfile(
    val containers: List<String>,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
)

internal data class AppleStaticDeviceCapabilities(
    val audioCodecs: List<String>,
    val maxAudioChannels: Int,
)

internal fun appleStaticDeviceCapabilities(): AppleStaticDeviceCapabilities =
    AppleStaticDeviceCapabilities(
        audioCodecs = appleDeviceProfileDeclaration.audioCodecs,
        maxAudioChannels = 6,
    )

/** Returns the Apple display range claims for the codecs actually advertised by the provider. */
fun appleVideoRanges(
    videoCodecs: List<String>,
    supportsHdr: Boolean,
): Map<String, VideoRangeCapabilities> =
    videoCodecs.associateWith { codec ->
        if (supportsHdr && codec in setOf("hevc", "av1")) {
            VideoRangeCapabilities(supportsHdr10 = true, supportsHlg = true)
        } else {
            VideoRangeCapabilities()
        }
    }

data class DeviceSubtitleProfile(
    val format: String,
    val deliveryMethods: List<SubtitleDeliveryMethod>,
    val kind: SubtitleKind,
)

data class VideoCodecConstraints(
    val supportedProfiles: List<String> = emptyList(),
    val maxLevel: Int? = null,
    val maxBitDepth: Int? = null,
    val requiredCodecTags: List<String> = emptyList(),
    val maxVideoFrameRate: Int? = null,
    val allowsInterlaced: Boolean? = null,
)

/**
 * A decoder's usable frame box plus the limits that couple its dimensions.
 *
 * [maxWidth] and [maxHeight] alone overstate what a decoder can do, because
 * hardware expresses a total-blocks limit that ties the two together: the
 * Chromecast/Amlogic AVC and HEVC decoders both declare `size max 4096x4096`
 * while capping `block-count` at 34560, i.e. 4096x2160. [maxFrameArea] carries
 * that coupling as pixels per frame.
 *
 * [maxFrameAreaPerSecond] carries the throughput limit (`blocks-per-second`),
 * which is independent again: the same Amlogic AVC decoder allows 4096x2160 at
 * 30fps but only ~2560x1440 at 60fps, so a frame box that is legal on its own
 * still fails at a high frame rate. Both are null on platforms that do not
 * report them, in which case only the box applies.
 */
data class VideoCodecResolution(
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val maxFrameArea: Long? = null,
    val maxFrameAreaPerSecond: Long? = null,
)

/**
 * Produces the single effective video bound used for the device profile, URL
 * rewrite, and source-copy preflight. A selected quality rung has a box only;
 * decoder area and throughput limits remain in force when present.
 */
internal fun reconcileVideoResolutionBounds(
    deviceCeiling: VideoCodecResolution?,
    qualityRungCeiling: VideoCodecResolution?,
    userResolutionCeiling: VideoCodecResolution? = null,
): VideoCodecResolution? {
    val bounds = listOfNotNull(deviceCeiling, qualityRungCeiling, userResolutionCeiling)
    if (bounds.isEmpty()) return null
    return VideoCodecResolution(
        maxWidth = bounds.mapNotNull { bound -> bound.maxWidth }.minOrNull(),
        maxHeight = bounds.mapNotNull { bound -> bound.maxHeight }.minOrNull(),
        maxFrameArea = bounds.mapNotNull { bound -> bound.maxFrameArea }.minOrNull(),
        maxFrameAreaPerSecond = bounds.mapNotNull { bound -> bound.maxFrameAreaPerSecond }.minOrNull(),
    )
}

enum class PlaybackResolutionPolicy {
    NoCap,
    VerifiedDeviceCap,
    QualityRung,
    UserSetting,
    VerifiedDeviceAndQualityRung,
    VerifiedDeviceAndUserSetting,
    QualityRungAndUserSetting,
    VerifiedDeviceQualityRungAndUserSetting,
}

internal fun playbackResolutionPolicy(
    deviceCeiling: VideoCodecResolution?,
    qualityRungCeiling: VideoCodecResolution?,
    userResolutionCeiling: VideoCodecResolution?,
): PlaybackResolutionPolicy {
    val hasDeviceCap = deviceCeiling.hasFiniteResolutionLimit()
    val hasQualityCap = qualityRungCeiling.hasFiniteResolutionLimit()
    val hasUserCap = userResolutionCeiling.hasFiniteResolutionLimit()
    return when {
        hasDeviceCap && hasQualityCap && hasUserCap -> PlaybackResolutionPolicy.VerifiedDeviceQualityRungAndUserSetting
        hasDeviceCap && hasQualityCap -> PlaybackResolutionPolicy.VerifiedDeviceAndQualityRung
        hasDeviceCap && hasUserCap -> PlaybackResolutionPolicy.VerifiedDeviceAndUserSetting
        hasQualityCap && hasUserCap -> PlaybackResolutionPolicy.QualityRungAndUserSetting
        hasDeviceCap -> PlaybackResolutionPolicy.VerifiedDeviceCap
        hasQualityCap -> PlaybackResolutionPolicy.QualityRung
        hasUserCap -> PlaybackResolutionPolicy.UserSetting
        else -> PlaybackResolutionPolicy.NoCap
    }
}

private fun VideoCodecResolution?.hasFiniteResolutionLimit(): Boolean =
    this != null &&
        (maxWidth != null || maxHeight != null || maxFrameArea != null || maxFrameAreaPerSecond != null)

/**
 * Smallest frame a decoder must handle before the codec is worth advertising.
 *
 * A decoder that cannot reach 720p is a token software fallback, not a codec the
 * device can play: the Chromecast with Google TV has no hardware AV1 decoder and
 * reports a 720x720 AV1 ceiling, so advertising AV1 promises a capability that
 * only ever resolves to a transcode.
 */
enum class UnusableVideoCodecReason : DiagnosticReason {
    /** The probed decoder cannot reach 720p, so it is a token software fallback. */
    BelowMinimumSize,

    /**
     * No probed limits at all, so nothing downstream can bound the codec:
     * `transcodeResolutionCap` returns null when a target codec has no resolution
     * entry, and the planner then uses the uncapped transcode URL. Advertising an
     * unbounded codec is exactly how an oversized stream reaches the decoder.
     */
    NoProbedLimits,
}

data class UnusableVideoCodec(
    val codec: String,
    val reason: UnusableVideoCodecReason,
)

/** Preserves codec claims when a probe has no usable bound for that codec. */
fun DeviceDecodingCapabilities.withoutUnusableVideoCodecs(): Pair<DeviceDecodingCapabilities, List<UnusableVideoCodec>> {
    // Unknown bounds are not evidence of an unsupported codec. Keep the codec
    // advertised and let source-relative planning compare only concrete files
    // against real bounds.
    return this to emptyList()
}

interface DeviceProfileProvider {
    val backendPolicy: PlayerBackendPolicy
        get() = applePlayerBackendPolicy()

    /**
     * Concrete engines safe to offer or resolve before controller construction.
     *
     * Package/ABI presence is a separate, cheap fact from requested-engine
     * initialization: an Android backend may be bundled here while its first
     * native construction can still fail and must enter the truthful fallback
     * path. Staged backends stay out of this set until their controller factory
     * and activation gate are ready.
     */
    val availableBackends: Set<PlayerBackend>
        get() =
            backendPolicy.visibleBackends
                .takeIf { backends -> backends.isNotEmpty() }
                ?.toSet()
                ?: setOf(backendPolicy.defaultBackend)

    fun capabilities(backend: PlayerBackend = PlayerBackend.AVPlayer): DeviceDecodingCapabilities

    fun refreshCapabilities(backend: PlayerBackend = PlayerBackend.AVPlayer): DeviceDecodingCapabilities = capabilities(backend)
}

/** Pure MobileVLCKit capability data used by the Apple provider. */
internal fun appleAvPlayerDeviceCapabilities(
    videoCodecs: List<String>,
    supportsHdr: Boolean,
    videoResolutionsByCodec: Map<String, VideoCodecResolution>,
    av1DecodeEvidenceSource: CapabilityEvidenceSource = CapabilityEvidenceSource.StaticDeclaration,
    hasUserOverridableVideoInputEnvelope: Boolean = false,
): DeviceDecodingCapabilities {
    val staticCapabilities = appleStaticDeviceCapabilities()
    return DeviceDecodingCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = staticCapabilities.audioCodecs,
        supportsDolbyVision = false,
        videoResolutionsByCodec = videoResolutionsByCodec,
        supportsHdr = supportsHdr,
        maxAudioChannels = staticCapabilities.maxAudioChannels,
        videoRangeCapabilitiesByCodec = appleVideoRanges(videoCodecs, supportsHdr),
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = appleDeviceProfileDeclaration.containers,
                    videoCodecs = videoCodecs,
                    audioCodecs = staticCapabilities.audioCodecs,
                ),
            ),
        subtitleProfiles = appleSubtitleProfiles,
        videoConstraintsByCodec =
            videoCodecs.associateWith { codec ->
                if (codec == "hevc") {
                    VideoCodecConstraints(
                        requiredCodecTags = listOf("hvc1", "dvh1"),
                        maxVideoFrameRate = 60,
                        allowsInterlaced = false,
                    )
                } else {
                    VideoCodecConstraints(allowsInterlaced = false)
                }
            },
        videoCodecEvidence =
            videoCodecs.associateWith { codec ->
                CodecCapabilityEvidence(
                    decodeSources =
                        setOf(
                            if (codec == "av1") {
                                av1DecodeEvidenceSource
                            } else {
                                CapabilityEvidenceSource.StaticDeclaration
                            },
                        ),
                    finiteLimitSources =
                        setOf(
                            if (videoResolutionsByCodec[codec].hasAnyFiniteLimit()) {
                                CapabilityEvidenceSource.DocumentedLimit
                            } else {
                                CapabilityEvidenceSource.Unknown
                            },
                        ),
                )
            },
        audioCodecEvidence =
            staticCapabilities.audioCodecs.associateWith {
                CodecCapabilityEvidence(
                    decodeSources = setOf(CapabilityEvidenceSource.StaticDeclaration),
                    finiteLimitSources = setOf(CapabilityEvidenceSource.DocumentedLimit),
                )
            },
        hasUserOverridableVideoInputEnvelope = hasUserOverridableVideoInputEnvelope,
    ).withoutUnusableVideoCodecs().first
}

internal fun vlcKitDeviceCapabilities(
    supportsAv1HardwareDecode: Boolean,
    // The default exists for host tests and resolves the universal iOS safety
    // envelope. Real providers still pass their platform-family result so a
    // future tvOS VLCKit route cannot inherit the iOS policy accidentally.
    videoResolutionsByCodec: Map<String, VideoCodecResolution> =
        resolveAppleDecodeCeilings(
            deviceModelIdentifier = "unknown",
            platformFamily = ApplePlatformFamily.Ios,
            backend = PlayerBackend.VlcKit,
            videoCodecs = vlcKitDeviceProfileDeclaration.videoCodecs,
        ),
    hasUserOverridableVideoInputEnvelope: Boolean = false,
): DeviceDecodingCapabilities {
    val videoCodecs = vlcKitDeviceProfileDeclaration.videoCodecs
    val audioCodecs = vlcKitDeviceProfileDeclaration.audioCodecs
    return DeviceDecodingCapabilities(
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        supportsDolbyVision = false,
        videoResolutionsByCodec = videoResolutionsByCodec,
        supportsHdr = false,
        videoRangeCapabilitiesByCodec = videoCodecs.associateWith { VideoRangeCapabilities() },
        directPlayProfiles =
            listOf(
                DeviceDirectPlayProfile(
                    containers = vlcKitDeviceProfileDeclaration.containers,
                    videoCodecs = videoCodecs,
                    audioCodecs = audioCodecs,
                ),
            ),
        subtitleProfiles = vlcKitSubtitleProfiles,
        videoCodecEvidence =
            videoCodecs.associateWith { codec ->
                CodecCapabilityEvidence(
                    decodeSources = setOf(CapabilityEvidenceSource.StaticDeclaration),
                    finiteLimitSources =
                        setOf(
                            if (videoResolutionsByCodec[codec].hasAnyFiniteLimit()) {
                                CapabilityEvidenceSource.StaticDeclaration
                            } else {
                                CapabilityEvidenceSource.Unknown
                            },
                        ),
                )
            },
        audioCodecEvidence =
            audioCodecs.associateWith {
                CodecCapabilityEvidence(
                    decodeSources = setOf(CapabilityEvidenceSource.StaticDeclaration),
                    finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown),
                )
            },
        hasUserOverridableVideoInputEnvelope = hasUserOverridableVideoInputEnvelope,
    ).withoutUnusableVideoCodecs().first
}

private fun VideoCodecResolution?.hasAnyFiniteLimit(): Boolean =
    this != null &&
        (maxWidth != null || maxHeight != null || maxFrameArea != null || maxFrameAreaPerSecond != null)

// AVFoundation text-subtitle delivery on AVPlayer is limited to Embed (direct-play
// container tracks, matched by stable container index via AVMediaSelection) and
// Encode (server burn-in). External sidecars are unreliable (AVMutableComposition
// splice fails on many files) and HLS/transcode text tracks carry no stable
// source index, so their native selection false-conflicts on the manifest's
// language string. Both are therefore omitted: for a transcode (or a ForceEncode
// direct-play video) the server burns text subtitles in, which renders with no
// native track selection. Efficient external/HLS text returns with the VLCKit
// backend. (Bitmap formats were always Encode-only.)
private val appleSubtitleProfiles =
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

private val vlcKitSubtitleProfiles =
    listOf(
        "vtt",
        "webvtt",
        "srt",
        "subrip",
        "ass",
        "ssa",
        "ttml",
        "text",
        "mov_text",
    ).map { format ->
        DeviceSubtitleProfile(
            format = format,
            // Embed (direct-play container tracks, rendered natively) + Encode (server
            // burn-in on transcode). NOT External/HLS: VLC selects but does not render
            // an external subtitle slave over an HLS/transcode stream (device-verified),
            // so — like the AVPlayer profile — text subtitles burn in during a transcode.
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
