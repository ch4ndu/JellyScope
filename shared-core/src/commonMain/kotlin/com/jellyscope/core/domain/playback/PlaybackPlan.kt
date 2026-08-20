// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.OfflineArtifactRef

data class PlaybackPlan(
    val itemId: String,
    val mediaSourceId: String,
    val startPositionMs: Long,
    val streamMode: StreamMode,
    val streamUrl: String,
    val progressReportingPolicy: ProgressReportingPolicy,
    val playSessionId: String? = null,
    val selectedAudioStreamIndex: Int? = null,
    val embeddedAudioTracks: List<PlannedEmbeddedTrack> = emptyList(),
    val embeddedSubtitleTracks: List<PlannedEmbeddedTrack> = emptyList(),
    val audioActivationTarget: AudioActivationTarget? = null,
    val audioSelectionAuthoritative: Boolean = false,
    val selectedSubtitleStreamIndex: Int? = null,
    val subtitleAsset: SubtitleAsset? = null,
    val subtitleActivationTarget: SubtitleActivationTarget? = null,
    val plannedSubtitle: PlannedSubtitle = PlannedSubtitle.Off,
    val streamMimeType: String? = null,
    val maxStreamingBitrate: Long? = null,
    val qualityCapOrigin: PlaybackQualityCapOrigin? = null,
    val resolutionPolicy: PlaybackResolutionPolicy = PlaybackResolutionPolicy.NoCap,
    val clientTrigger: PlaybackClientTrigger? = null,
    val effectiveTranscodeMaxStreamingBitrate: Long? = null,
    val sourceBitrateBps: Long? = null,
    val videoStreamLabel: String? = null,
    val container: String? = null,
    val transcodeReasons: List<String> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val trickplay: TrickplayInfo? = null,
    val mediaSegments: List<MediaSegment> = emptyList(),
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val videoPresentation: PlannedVideoPresentation? = null,
    val qualityPolicy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto,
    val bitrateConstraint: PlaybackBitrateConstraint = PlaybackBitrateConstraint.NoClientLimit,
    val recoveryIntent: PlaybackRecoveryIntent = PlaybackRecoveryIntent.Initial,
    val contentTimeline: PlaybackContentTimeline = PlaybackContentTimeline.UnknownOrUnbounded,
    val videoExpected: Boolean = true,
    /** Opaque generation-bound reference used only by supported Offline controller branches. */
    val offlineArtifactRef: OfflineArtifactRef? = null,
    /** Artifact kind is policy input; no local path or native resolver is carried in the plan. */
    val offlineArtifactKind: DownloadArtifactKind? = null,
    /** Explicit account scope used by the supported offline reporting route. */
    val offlineAccountIdentity: AccountIdentity? = null,
    /** Process-local diagnostic correlation only; never persisted or sent to Jellyfin. */
    val diagnosticSessionSequence: Long? = null,
)

sealed interface PlaybackContentTimeline {
    data class BoundedVod(
        val durationMs: Long,
        val source: PlaybackContentTimelineSource,
    ) : PlaybackContentTimeline {
        init {
            require(durationMs > 0L)
        }
    }

    data object UnknownOrUnbounded : PlaybackContentTimeline
}

enum class PlaybackContentTimelineSource {
    SelectedMediaSource,
    ItemFallback,
}

sealed interface PlaybackQualityCapOrigin {
    data object ExplicitSessionChoice : PlaybackQualityCapOrigin

    data object SettingsDefault : PlaybackQualityCapOrigin

    data object AutoSessionRecovery : PlaybackQualityCapOrigin
}

data class PlannedVideoPresentation(
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val videoRangeType: String?,
    val bitDepth: Int? = null,
)

sealed interface PlannedSubtitle {
    data object Off : PlannedSubtitle

    data class Track(
        val streamIndex: Int,
        val embeddedTrack: PlannedEmbeddedTrack?,
        val deliveryMethod: SubtitleDeliveryMethod,
        val kind: SubtitleKind,
        val externalResource: SubtitleAsset.JellyfinRemote? = null,
        val activationTarget: SubtitleActivationTarget? = null,
        val normalizedFormat: String? = null,
    ) : PlannedSubtitle

    data class LocalAsset(
        val assetId: String,
        val kind: SubtitleKind,
        val activationTarget: SubtitleActivationTarget? = null,
    ) : PlannedSubtitle

    data class Unavailable(
        val streamIndex: Int,
        val kind: SubtitleKind,
        val normalizedFormat: String? = null,
        val reason: String,
        val allowEncodeFallback: Boolean = false,
        val activationTarget: SubtitleActivationTarget? = null,
    ) : PlannedSubtitle
}

data class PlannedEmbeddedTrack(
    val jellyfinStreamIndex: Int,
    val filteredContainerOrdinal: Int,
    val codec: String?,
    val normalizedLanguage: String?,
    val label: String?,
    /**
     * Audio only: false when the active backend cannot decode this codec.
     *
     * A flag rather than a filtered list — the desktop VLC controller uses the
     * descriptor count as a native-track sanity check, so shortening the list
     * would break its ordinal mapping.
     */
    val directPlayAdmissible: Boolean = true,
    /**
     * Cardinality of the response-authoritative filtered native-track cohort.
     * Null means the descriptor came from detail metadata or otherwise lacks
     * enough response evidence for an exact-count native mapping.
     */
    val responseAuthoritativeCohortSize: Int? = null,
)

enum class SubtitleDeliveryMethod {
    Off,
    Drop,
    Embed,
    External,
    Hls,
    Encode,
    Unavailable,
}

enum class SubtitleKind {
    Text,
    Bitmap,
}

sealed interface SubtitleAsset {
    val mimeType: String
    val label: String
    val language: String?

    data class JellyfinRemote(
        val url: String,
        override val mimeType: String,
        override val label: String,
        override val language: String?,
    ) : SubtitleAsset

    data class LocalFile(
        val assetId: String,
        val fileId: String,
        override val mimeType: String,
        override val label: String,
        override val language: String?,
    ) : SubtitleAsset
}

enum class StreamMode {
    DirectPlay,
    DirectStream,
    Transcode,
    Offline,
}

data class ProgressReportingPolicy(
    val reportIntervalMs: Long,
)
