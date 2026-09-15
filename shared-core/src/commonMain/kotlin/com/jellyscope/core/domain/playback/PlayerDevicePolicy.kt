// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

data class PlayerDeviceSettings(
    val audioMode: PlayerAudioMode = PlayerAudioMode.Auto,
    val hdrMode: PlayerHdrMode = PlayerHdrMode.Auto,
    val matchDisplayRefreshRate: Boolean = false,
    val maxVideoResolution: PlayerVideoResolutionLimit = PlayerVideoResolutionLimit.Unlimited,
    val iosPlaybackCompatibilityMode: IosPlaybackCompatibilityMode = IosPlaybackCompatibilityMode.Standard,
    val androidTvMpvVideoOutput: AndroidTvMpvVideoOutput = AndroidTvMpvVideoOutput.Gpu,
)

enum class AndroidTvMpvVideoOutput {
    Gpu,
    DirectMediaCodec,
}

enum class IosPlaybackCompatibilityMode {
    Standard,
    Unrestricted,
}

enum class PlayerVideoResolutionLimit(
    val maxWidth: Int?,
    val maxHeight: Int?,
) {
    Unlimited(null, null),
    Height4320(7_680, 4_320),
    Height2160(3_840, 2_160),
    Height1440(2_560, 1_440),
    Height1080(1_920, 1_080),
    Height720(1_280, 720),
    Height480(854, 480),
    Height360(640, 360),
    ;

    val resolutionCap: VideoCodecResolution?
        get() =
            if (maxWidth == null && maxHeight == null) {
                null
            } else {
                VideoCodecResolution(
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                )
            }
}

enum class PlayerAudioMode {
    Auto,
    StereoPcm,
    PassthroughWhenSupported,
}

enum class PlayerHdrMode {
    Auto,
    PreferSdr,
}

data class EffectivePlayerDevicePolicy(
    val settings: PlayerDeviceSettings,
    val capabilities: DeviceDecodingCapabilities,
    val effectiveAudioMode: PlayerAudioMode,
    val effectiveHdrMode: PlayerHdrMode,
    val audioModeSupported: Boolean,
    val hdrModeSupported: Boolean,
    val audioDisabledReason: PlayerSettingDisabledReason? = null,
    val hdrDisabledReason: PlayerSettingDisabledReason? = null,
    val audioCodecs: List<String>,
    val maxAudioChannelsByCodec: Map<String, Int>,
    val maxAudioChannels: Int?,
    val allowAudioStreamCopy: Boolean = true,
    val allowVideoStreamCopy: Boolean = true,
)

enum class PlayerSettingDisabledReason {
    AudioRouteUnsupported,
    HdrDisplayUnsupported,
}

data class PlaybackInfoRequestPolicy(
    val enableDirectPlay: Boolean = true,
    val enableDirectStream: Boolean = true,
    val enableTranscoding: Boolean = true,
    val allowAudioStreamCopy: Boolean = true,
    val allowVideoStreamCopy: Boolean = true,
    val forceEncodeSubtitle: ForceEncodeSubtitle? = null,
    val backend: PlayerBackend = PlayerBackend.AVPlayer,
    val clientTrigger: PlaybackClientTrigger? = null,
    /** Derived centrally from a user-selected quality rung; never from automatic caps. */
    val qualityResolutionCap: VideoCodecResolution? = null,
    val qualityCapOrigin: PlaybackQualityCapOrigin? = null,
    /** Snapshotted from the global player-device setting at the start of planning. */
    val userVideoResolutionCap: VideoCodecResolution? = null,
    /** Distinguishes a snapshotted Unlimited value from an unset policy field. */
    val userVideoResolutionCapIsResolved: Boolean = false,
    /** Sent only by the forced-transcode recovery and not trusted until server-probed. */
    val maxFramerate: Int? = null,
    /** Internal request/profile bitrate contract; never a product quality choice. */
    val bitrateConstraint: PlaybackBitrateConstraint = PlaybackBitrateConstraint.NoClientLimit,
    /** Explicit authorization for a planner recovery attempt. */
    val recoveryIntent: PlaybackRecoveryIntent = PlaybackRecoveryIntent.Initial,
    /** Process-local diagnostic correlation only; never sent to Jellyfin. */
    val diagnosticSessionSequence: Long? = null,
)

enum class PlaybackClientTrigger {
    NetworkRetry,
    PlayerFailureFallback,
    BackendFallback,
    AudioActivationFallback,
    SubtitleActivationFallback,

    /**
     * The capability preflight refused to hand the source video to the decoder
     * and forced the re-encode recovery. Without this the overlay showed an
     * app-forced transcode as "App trigger: None" — and the server side is dark
     * too, because AllowVideoStreamCopy=false adds no TranscodeReason.
     */
    DecodeCapabilityCap,

    /** The source exceeds only the user's explicit maximum video resolution. */
    UserResolutionLimit,
}

data class ForceEncodeSubtitle(
    val streamIndex: Int,
    val normalizedFormat: String,
)

val PlaybackInfoRequestPolicy.isDefault: Boolean
    get() =
        enableDirectPlay &&
            enableDirectStream &&
            enableTranscoding &&
            allowAudioStreamCopy &&
            allowVideoStreamCopy &&
            forceEncodeSubtitle == null &&
            backend == PlayerBackend.AVPlayer &&
            userVideoResolutionCap == null &&
            bitrateConstraint == PlaybackBitrateConstraint.NoClientLimit &&
            recoveryIntent == PlaybackRecoveryIntent.Initial

fun resolvePlayerDevicePolicy(
    capabilities: DeviceDecodingCapabilities,
    settings: PlayerDeviceSettings,
): EffectivePlayerDevicePolicy {
    val effectiveCapabilities =
        if (
            settings.iosPlaybackCompatibilityMode == IosPlaybackCompatibilityMode.Unrestricted &&
            capabilities.hasUserOverridableVideoInputEnvelope
        ) {
            capabilities.copy(
                videoResolutionsByCodec = emptyMap(),
                videoCodecEvidence =
                    capabilities.videoCodecEvidence.mapValues { (_, evidence) ->
                        evidence.copy(finiteLimitSources = setOf(CapabilityEvidenceSource.Unknown))
                    },
            )
        } else {
            capabilities
        }
    val safeDecodeAudioCodecs = effectiveCapabilities.audioCodecs.ifEmpty { baselineAudioCodecs }
    val passthroughCodecs = effectiveCapabilities.audioPassthroughCodecs.distinct()
    val passthroughSupported = passthroughCodecs.isNotEmpty()
    val effectiveAudioMode =
        when (settings.audioMode) {
            PlayerAudioMode.Auto -> PlayerAudioMode.Auto
            PlayerAudioMode.StereoPcm -> PlayerAudioMode.StereoPcm
            PlayerAudioMode.PassthroughWhenSupported ->
                if (passthroughSupported) {
                    PlayerAudioMode.PassthroughWhenSupported
                } else {
                    PlayerAudioMode.Auto
                }
        }
    val audioCodecs =
        when (effectiveAudioMode) {
            PlayerAudioMode.Auto ->
                if (passthroughSupported) {
                    (safeDecodeAudioCodecs + passthroughCodecs).distinct()
                } else {
                    safeDecodeAudioCodecs
                }
            PlayerAudioMode.StereoPcm -> baselineAudioCodecs
            PlayerAudioMode.PassthroughWhenSupported -> (safeDecodeAudioCodecs + passthroughCodecs).distinct()
        }
    val positiveLegacyMaxAudioChannels = effectiveCapabilities.maxAudioChannels?.takeIf { channels -> channels > 0 }
    val maxAudioChannelsByCodec =
        audioCodecs
            .distinct()
            .mapNotNull { codec ->
                val maxChannels =
                    when (effectiveAudioMode) {
                        PlayerAudioMode.StereoPcm -> 2
                        else -> {
                            val decodeMaxChannels =
                                effectiveCapabilities.audioDecodeChannelsByCodec[codec]
                                    ?.takeIf { channels -> channels > 0 }
                                    ?: positiveLegacyMaxAudioChannels
                            val passthroughMaxChannels =
                                effectiveCapabilities.audioPassthroughMaxChannels
                                    ?.takeIf { channels -> channels > 0 && codec in passthroughCodecs }
                            listOfNotNull(decodeMaxChannels, passthroughMaxChannels).maxOrNull()
                        }
                    }
                maxChannels?.let { channels -> codec to channels }
            }.toMap()
    val maxAudioChannels = maxAudioChannelsByCodec.values.maxOrNull()
    val effectiveHdrMode =
        when (settings.hdrMode) {
            PlayerHdrMode.Auto ->
                if (effectiveCapabilities.supportsHdr || effectiveCapabilities.supportsDolbyVision) {
                    PlayerHdrMode.Auto
                } else {
                    PlayerHdrMode.PreferSdr
                }
            PlayerHdrMode.PreferSdr -> PlayerHdrMode.PreferSdr
        }

    return EffectivePlayerDevicePolicy(
        settings = settings,
        capabilities = effectiveCapabilities,
        effectiveAudioMode = effectiveAudioMode,
        effectiveHdrMode = effectiveHdrMode,
        audioModeSupported = settings.audioMode != PlayerAudioMode.PassthroughWhenSupported || passthroughSupported,
        hdrModeSupported =
            settings.hdrMode != PlayerHdrMode.Auto ||
                effectiveCapabilities.supportsHdr ||
                effectiveCapabilities.supportsDolbyVision,
        audioDisabledReason =
            if (settings.audioMode == PlayerAudioMode.PassthroughWhenSupported && !passthroughSupported) {
                PlayerSettingDisabledReason.AudioRouteUnsupported
            } else {
                null
            },
        hdrDisabledReason =
            if (
                settings.hdrMode == PlayerHdrMode.Auto &&
                !effectiveCapabilities.supportsHdr &&
                !effectiveCapabilities.supportsDolbyVision
            ) {
                PlayerSettingDisabledReason.HdrDisplayUnsupported
            } else {
                null
            },
        audioCodecs = audioCodecs,
        maxAudioChannelsByCodec = maxAudioChannelsByCodec,
        maxAudioChannels = maxAudioChannels,
        allowAudioStreamCopy = effectiveAudioMode != PlayerAudioMode.StereoPcm,
        // PreferSdr must NOT disable stream copy wholesale: the per-codec
        // EqualsAny VideoRangeType conditions already deny copying any
        // non-SDR source (server tone-maps it), while SDR sources stay
        // remuxable when only the audio needs transcoding.
        allowVideoStreamCopy = true,
    )
}

private val baselineAudioCodecs = listOf("aac", "mp3")
