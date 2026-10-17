// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.OfflineArtifactRef

data class PlaybackMediaStream(
    val index: Int?,
    val type: String?,
    val displayTitle: String?,
    val title: String?,
    val language: String?,
    val codec: String?,
    val channelLayout: String?,
    val bitRate: Long?,
    val height: Int?,
    val isDefault: Boolean?,
    val isExternal: Boolean?,
    val deliveryMethod: String?,
    val deliveryUrl: String?,
    val width: Int? = null,
    val realFrameRate: Double? = null,
    val videoRangeType: String? = null,
    val bitDepth: Int? = null,
)

data class AudioTrackOption(
    val streamIndex: Int,
    val ordinal: Int,
    val displayName: String?,
    val language: String?,
    val isDefault: Boolean,
)

data class SubtitleTrackOption(
    val streamIndex: Int,
    val ordinal: Int,
    val displayName: String?,
    val language: String?,
    val isDefault: Boolean,
    val isExternal: Boolean,
)

enum class SubtitleRenderMode {
    None,
    LocalExternalText,
    LocalEmbeddedText,
    LocalEmbeddedBitmap,
    LocalHlsText,
    ServerBurnedIn,
    Unknown,
}

enum class SubtitleRenderStatus {
    Off,
    Pending,
    Active,
    Unavailable,
}

enum class LocalSubtitleKind {
    EmbeddedText,
    EmbeddedBitmap,
    ExternalText,
    HlsText,
}

sealed interface SubtitleActivationIdentity {
    data class JellyfinTrack(
        val streamIndex: Int,
    ) : SubtitleActivationIdentity

    data class LocalAsset(
        val assetId: String,
    ) : SubtitleActivationIdentity

    data class OfflineSidecar(
        val artifactRef: OfflineArtifactRef,
    ) : SubtitleActivationIdentity
}

data class SubtitleActivationTarget(
    val requestId: Long,
    val itemId: String,
    val identity: SubtitleActivationIdentity,
    val kind: LocalSubtitleKind,
) {
    val streamIndex: Int?
        get() = (identity as? SubtitleActivationIdentity.JellyfinTrack)?.streamIndex

    val assetId: String?
        get() = (identity as? SubtitleActivationIdentity.LocalAsset)?.assetId
}

sealed interface SubtitleActivationState {
    data object None : SubtitleActivationState

    data class Pending(
        val target: SubtitleActivationTarget,
    ) : SubtitleActivationState

    data class Active(
        val target: SubtitleActivationTarget,
    ) : SubtitleActivationState

    data class Unavailable(
        val target: SubtitleActivationTarget,
    ) : SubtitleActivationState
}

data class AudioActivationTarget(
    val requestId: Long,
    val itemId: String,
    val streamIndex: Int,
)

sealed interface AudioActivationState {
    data object None : AudioActivationState

    data class Pending(
        val target: AudioActivationTarget,
    ) : AudioActivationState

    data class Active(
        val target: AudioActivationTarget,
    ) : AudioActivationState

    data class Unavailable(
        val target: AudioActivationTarget,
    ) : AudioActivationState
}

data class EmbeddedAudioSelection(
    val target: AudioActivationTarget,
    val descriptor: PlannedEmbeddedTrack,
)

data class EmbeddedSubtitleSelection(
    val target: SubtitleActivationTarget,
    val descriptor: PlannedEmbeddedTrack,
)

data class SubtitleRenderInfo(
    val status: SubtitleRenderStatus,
    val mode: SubtitleRenderMode,
    val streamIndex: Int? = null,
    val label: String? = null,
    val language: String? = null,
    val reason: String,
) {
    val styleable: Boolean
        get() =
            status == SubtitleRenderStatus.Active &&
                (
                    mode == SubtitleRenderMode.LocalExternalText ||
                        mode == SubtitleRenderMode.LocalEmbeddedText
                )

    val activeStreamIndex: Int?
        get() = streamIndex.takeIf { status == SubtitleRenderStatus.Active }
}

data class QualityOption(
    val maxBitrateBps: Long?,
    val tier: QualityTier?,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val isCustom: Boolean = false,
    val inheritsDefault: Boolean = false,
    val mode: PlaybackQualityMode =
        if (maxBitrateBps == null) PlaybackQualityMode.Auto else PlaybackQualityMode.Fixed,
) {
    // There is deliberately no legacy string field here; UI derives its label
    // from the structured resolution when present.
    val displayResolution: String?
        get() = resolutionHeight?.let { height -> "${height}p" }
}

/**
 * A quality choice used by Settings, where the complete canonical ladder must
 * remain visible regardless of the current media source. A custom choice is
 * intentionally bitrate-only and carries no inferred resolution.
 */
data class PlaybackQualityChoice(
    val maxBitrateBps: Long?,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val isCustom: Boolean = false,
    val mode: PlaybackQualityMode =
        if (maxBitrateBps == null) PlaybackQualityMode.Auto else PlaybackQualityMode.Fixed,
)

enum class QualityTier {
    High,
    Medium,
}

// Jellyfin exposes no named quality profiles; the transcoder honors
// MaxStreamingBitrate and its encode ladder resolves each bitrate to a
// matching resolution. These tier names follow the official clients'
// convention of pairing the conventional resolution with each rung.
data class QualityRung(
    val maxBitrateBps: Long,
    val height: Int,
    val tier: QualityTier? = null,
) {
    val width: Int
        get() = ((height * 16 + 8) / 9).let { width -> if (width % 2 == 0) width else width + 1 }

    val resolutionCap: VideoCodecResolution
        get() = VideoCodecResolution(maxWidth = width, maxHeight = height)
}

val qualityRungs =
    listOf(
        QualityRung(80_000_000L, 2_160, QualityTier.High),
        QualityRung(40_000_000L, 2_160, QualityTier.Medium),
        QualityRung(20_000_000L, 1_080, QualityTier.High),
        QualityRung(12_000_000L, 1_080, QualityTier.Medium),
        QualityRung(8_000_000L, 1_080),
        QualityRung(4_000_000L, 720, QualityTier.High),
        QualityRung(2_000_000L, 720),
        QualityRung(1_500_000L, 480),
    )

fun qualityRungForBitrate(maxBitrateBps: Long?): QualityRung? =
    maxBitrateBps?.let { bitrate -> qualityRungs.firstOrNull { rung -> rung.maxBitrateBps == bitrate } }
