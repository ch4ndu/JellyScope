// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.playback.AudioTrackOption
import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.DEFAULT_PLAYBACK_SPEED
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.PlannedVideoPresentation
import com.jellyscope.core.domain.playback.PlaybackActionNotice
import com.jellyscope.core.domain.playback.PlaybackCapabilityResult
import com.jellyscope.core.domain.playback.PlaybackClientTrigger
import com.jellyscope.core.domain.playback.PlaybackError
import com.jellyscope.core.domain.playback.PlaybackFirstVideoOutputState
import com.jellyscope.core.domain.playback.PlaybackHealthGuidance
import com.jellyscope.core.domain.playback.PlaybackHealthSignalKind
import com.jellyscope.core.domain.playback.PlaybackHealthThresholdClass
import com.jellyscope.core.domain.playback.PlaybackQualityCapOrigin
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlaybackQualityPolicyOrigin
import com.jellyscope.core.domain.playback.PlaybackState
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerTimingState
import com.jellyscope.core.domain.playback.PlayerVolumeState
import com.jellyscope.core.domain.playback.QualityOption
import com.jellyscope.core.domain.playback.SubtitleActivationIdentity
import com.jellyscope.core.domain.playback.SubtitleRenderInfo
import com.jellyscope.core.domain.playback.SubtitleRenderMode
import com.jellyscope.core.domain.playback.SubtitleRenderStatus
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.core.domain.playback.SubtitleTrackOption
import com.jellyscope.core.domain.playback.TrickplayInfo
import com.jellyscope.core.domain.playback.VideoOutputEvidence
import com.jellyscope.core.domain.playback.hasSelectableSubtitleChoice

sealed interface PlayerUiState {
    data object Loading : PlayerUiState

    data class Error(
        val retryable: Boolean = true,
        val error: PlaybackError? = null,
    ) : PlayerUiState

    data class Content(
        val playbackState: PlaybackState,
        val volumeControl: PlayerVolumeState? = null,
        val audioUnavailable: Boolean = false,
        val metadata: PlayerMediaMetadata = PlayerMediaMetadata(),
        val audioOptions: List<AudioTrackOption> = emptyList(),
        val subtitleOptions: List<SubtitleTrackOption> = emptyList(),
        val localSubtitleOptions: List<LocalSubtitleAsset> = emptyList(),
        val offlineSidecarOption: OfflineSidecarOption? = null,
        val qualityOptions: List<QualityOption> = emptyList(),
        val selectedAudioStreamIndex: Int? = null,
        val selectedSubtitleStreamIndex: Int? = null,
        val selectedSubtitleAssetId: String? = null,
        val offlineSidecarSelected: Boolean = false,
        val selectedQualityMaxBitrate: Long? = null,
        val selectedQualityPolicy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto,
        val qualityOverrideExplicit: Boolean = false,
        val inheritedQualityPolicy: PlaybackQualityPolicy = PlaybackQualityPolicy.Auto,
        val inheritedQualityUsesVlcSetting: Boolean = false,
        val pickerVisible: PlayerPicker = PlayerPicker.None,
        val playlist: PlaylistUi? = null,
        val chapters: List<Chapter> = emptyList(),
        val mediaSegments: List<MediaSegment> = emptyList(),
        val currentSegment: MediaSegment? = null,
        // Only Ask-policy segments reach the Skip prompt.
        val skipPromptSegment: MediaSegment? = null,
        val trickplay: TrickplayInfo? = null,
        val trickplayTileUrls: List<String> = emptyList(),
        val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
        val subtitleStyle: SubtitleStyle = SubtitleStyle(),
        val subtitleRenderInfo: SubtitleRenderInfo =
            SubtitleRenderInfo(
                status = SubtitleRenderStatus.Off,
                mode = SubtitleRenderMode.None,
                reason = "Off",
            ),
        val subtitleStyleable: Boolean = subtitleRenderInfo.styleable,
        val subtitleNotice: PlayerNotice? = null,
        val backendNotice: PlayerBackendNotice? = null,
        val playbackChangeNotice: PlayerPlaybackChangeNotice? = null,
        val activeBackend: PlayerBackend = PlayerBackend.Auto,
        val backendChoices: List<PlayerBackendSwitchChoice> = emptyList(),
        val backendSwitchControlVisible: Boolean = false,
        val backendSwitchControlEnabled: Boolean = false,
        val backendSwitchInProgress: Boolean = false,
        val playbackGuidance: PlaybackHealthGuidance? = null,
        val playbackActionNotice: PlaybackActionNotice? = null,
        val resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
        val upNext: UpNextInfo? = null,
        val autoplayPolicy: AutoplayPolicySnapshot = AutoplayPolicySnapshot(),
        val stillWatchingPrompt: Boolean = false,
        val timingState: PlayerTimingState = PlayerTimingState.Unsupported,
        val debugInfo: PlayerDebugInfo? = null,
        val videoPresentation: PlannedVideoPresentation? = null,
        val pictureInPictureRequiresLinearPlayback: Boolean = false,
        val isSeekable: Boolean = false,
        // Null during a queue switch so held seeks cannot cross items.
        val playbackItemId: String? = null,
    ) : PlayerUiState
}

/** True for any selectable subtitle source. */
fun PlayerUiState.Content.hasSubtitlePickerChoice(): Boolean =
    subtitleOptions.hasSelectableSubtitleChoice() ||
        localSubtitleOptions.isNotEmpty() ||
        offlineSidecarOption != null

data class OfflineSidecarOption(
    val identity: SubtitleActivationIdentity.OfflineSidecar,
    val displayName: String?,
    val language: String?,
)

data class PlayerNotice(
    val token: Long,
    val message: String,
)

data class PlayerBackendNotice(
    val token: Long,
    val requested: PlayerBackend,
    val active: PlayerBackend,
)

/** Session-only backend projection from the platform policy and availability snapshot. */
data class PlayerBackendSwitchChoice(
    val backend: PlayerBackend,
    val available: Boolean,
)

enum class PlayerPlaybackChangeOperation {
    Quality,
    Backend,
}

/** A proposed plan could not replace healthy playback, so the current session stayed installed. */
data class PlayerPlaybackChangeNotice(
    val token: Long,
    val operation: PlayerPlaybackChangeOperation,
    val error: PlaybackError,
)

// Static debug fields; live fields come from playbackState.
data class PlayerDebugInfo(
    val playMethod: String,
    val backend: PlayerBackend = PlayerBackend.AVPlayer,
    val transcodeReasons: List<String> = emptyList(),
    val container: String? = null,
    val videoCodec: String? = null,
    val videoResolution: String? = null,
    val videoPresentation: PlannedVideoPresentation? = null,
    val videoBitrateBps: Long? = null,
    val audioCodec: String? = null,
    val audioChannels: String? = null,
    val audioLanguage: String? = null,
    val sourceBitrateBps: Long? = null,
    val requestCapBitrateBps: Long? = null,
    val qualityCapOrigin: PlaybackQualityCapOrigin? = null,
    val clientTrigger: PlaybackClientTrigger? = null,
    val effectiveTranscodeCapBitrateBps: Long? = null,
    val launchToFirstFrameMs: Long? = null,
    val healthSignal: PlaybackHealthSignalKind? = null,
    val healthThresholdClass: PlaybackHealthThresholdClass? = null,
    val subtitleStreamIndex: Int? = null,
    val subtitleLabel: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleRenderMode: SubtitleRenderMode = SubtitleRenderMode.None,
    val subtitleRenderStatus: SubtitleRenderStatus = SubtitleRenderStatus.Off,
    val subtitleStyleable: Boolean = false,
    val subtitleRenderReason: String = "Off",
    val playSessionId: String? = null,
    val qualityPolicyMode: String = "Auto",
    val qualityPolicyOrigin: PlaybackQualityPolicyOrigin = PlaybackQualityPolicyOrigin.SettingsDefault,
    val clientLimiter: String = "No client limit",
    val capabilityResult: PlaybackCapabilityResult = PlaybackCapabilityResult.NoFiniteCapabilityCap,
    val capabilityReason: String = "No finite decoder capability cap",
    val configuredVlcTranscodeBudgetBps: Long? = null,
    val effectiveTranscodeCap: String = "None (not transcoding)",
    val recoveryKind: String = "Initial",
    val recoveryReason: String = "None",
    val remainingRecoveryBudget: String = "Compatibility: 1 remaining · Quality: 1 remaining",
    val firstVideoOutput: PlayerFirstVideoOutputDebug = PlayerFirstVideoOutputDebug(),
)

/** Sanitized first-output state for the matching prepare epoch. */
data class PlayerFirstVideoOutputDebug(
    val state: PlaybackFirstVideoOutputState = PlaybackFirstVideoOutputState.Unsupported,
    val evidence: VideoOutputEvidence = VideoOutputEvidence.Unsupported,
)

data class PlaylistUi(
    val items: List<QueueItemUi>,
    val currentIndex: Int,
)

data class QueueItemUi(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class UpNextInfo(
    val itemId: String,
    val title: String,
    val imageUrl: String?,
    val index: Int,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class AutoplayPolicySnapshot(
    val enabled: Boolean = true,
    val delayMs: Long = DEFAULT_AUTOPLAY_DELAY_MS,
    val queueIdentity: String = "",
    val itemId: String = "",
    val playbackGeneration: Long = 0L,
) {
    val countdownKey: AutoplayCountdownKey
        get() = AutoplayCountdownKey(queueIdentity, itemId, playbackGeneration)
}

data class AutoplayCountdownKey(
    val queueIdentity: String,
    val itemId: String,
    val playbackGeneration: Long,
)

data class UpNextDismissalIdentity(
    val itemId: String,
    val queueIndex: Int,
    val countdownKey: AutoplayCountdownKey,
)

fun UpNextInfo.dismissalIdentity(countdownKey: AutoplayCountdownKey): UpNextDismissalIdentity =
    UpNextDismissalIdentity(
        itemId = itemId,
        queueIndex = index,
        countdownKey = countdownKey,
    )

fun AutoplayPolicySnapshot.countdownDelayMs(countdownStarted: Boolean): Long? =
    delayMs
        .coerceAtLeast(0L)
        .takeIf { enabled && countdownStarted && itemId.isNotBlank() }

data class PlayerMediaMetadata(
    val title: String = "",
    val seriesName: String? = null,
    val episodeLabel: String? = null,
    val productionYear: Int? = null,
    val runtimeMs: Long? = null,
    val qualityBadge: String? = null,
    val imageUrl: String? = null,
)

enum class PlayerPicker {
    None,
    Chapters,
    Subtitles,
    Audio,
    Backend,
    Quality,
    Speed,
    SubtitleStyle,
    Resize,
    Queue,

    // Picker state preserves BACK and TV focus behavior for offset panels.
    AudioOffset,
    SubtitleOffset,
}

/** Returns offset panels to their owning track picker. */
fun PlayerPicker.parentPicker(): PlayerPicker =
    when (this) {
        PlayerPicker.AudioOffset -> PlayerPicker.Audio
        PlayerPicker.SubtitleOffset -> PlayerPicker.Subtitles
        else -> PlayerPicker.None
    }

const val DEFAULT_AUTOPLAY_DELAY_MS = 10_000L

data class PlayerLaunchOptions(
    val startPositionTicks: Long,
    val mediaSourceId: String?,
    val initialAudioStreamIndex: Int? = null,
    val initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
    val queue: List<String> = emptyList(),
    val offlineDownloadId: DownloadId? = null,
    val launchPolicy: PlayerLaunchPolicy = PlayerLaunchPolicy.Normal,
)

enum class PlayerLaunchPolicy {
    Normal,
    KidsSingleAsset,
}

data class PlayerPlaybackTarget(
    val itemId: String,
    val offlineDownloadId: DownloadId? = null,
)
