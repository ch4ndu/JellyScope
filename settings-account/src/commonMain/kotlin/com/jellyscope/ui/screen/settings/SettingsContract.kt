// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.DesktopMpvPresentationState
import com.jellyscope.core.domain.model.DownloadRemovalPreview
import com.jellyscope.core.domain.model.DownloadSettings
import com.jellyscope.core.domain.model.DownloadUsage
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.util.LogBufferSize

data class SettingsUiState(
    val serverName: String,
    val serverUrl: String,
    val userName: String,
    val versionName: String,
    val sourceRevision: String = "unknown",
    val playerBackendChoices: List<PlayerBackendChoice>,
    val selectedPlayerBackend: PlayerBackend,
    val isLoggingOut: Boolean = false,
    val isLoadingLogoutRemovalPreview: Boolean = false,
    val logoutRemovalPreview: DownloadRemovalPreview? = null,
    val downloadUsage: DownloadUsage? = null,
    val downloadSettings: DownloadSettings? = null,
    val isLoadingDownloadUsage: Boolean = false,
    val appTheme: AppColorThemeId = AppColorThemeId.Ember,
    val tileSize: TileSizeId = TileSizeId.Medium,
    val imageQuality: ImageQuality = ImageQuality.High,
    val pictureInPictureEnabled: Boolean = true,
    val playerClockEnabled: Boolean = true,
    /** Null where the platform binds no desktop presentation store. */
    val desktopMpvPresentation: DesktopMpvPresentationState? = null,
    val desktopMpvPresentationError: Boolean = false,
    val rememberLastLibraryView: Boolean = true,
    val playbackPreferences: PlaybackPreferences = PlaybackPreferences(),
    val isLoadingPlaybackPreferences: Boolean = false,
    val playbackPreferencesError: Boolean = false,
    val playerDeviceSettings: PlayerDeviceSettings = PlayerDeviceSettings(),
    val playerDevicePolicy: EffectivePlayerDevicePolicy? = null,
    val isRefreshingPlayerDevicePolicy: Boolean = false,
    val playerDevicePolicyRefreshCompletion: Int = 0,
    val playerDevicePolicyError: Boolean = false,
    val openSubtitlesApiKey: String = "",
    val isOpenSubtitlesApiKeyLoaded: Boolean = false,
    val isLoadingOpenSubtitlesApiKey: Boolean = false,
    val isSavingOpenSubtitlesApiKey: Boolean = false,
    val openSubtitlesApiKeyLoadError: Boolean = false,
    val openSubtitlesApiKeyError: Boolean = false,
    val openSubtitleResultPreference: OpenSubtitleResultPreference = OpenSubtitleResultPreference.NoPreference,
    val isOpenSubtitleResultPreferenceLoaded: Boolean = false,
    val isLoadingOpenSubtitleResultPreference: Boolean = false,
    val openSubtitleResultPreferenceLoadError: Boolean = false,
    val isSavingOpenSubtitleResultPreference: Boolean = false,
    val openSubtitleResultPreferenceError: Boolean = false,
    val isClearingLocalSubtitles: Boolean = false,
    val localSubtitlesClearCompletion: Int = 0,
    val localSubtitlesClearError: Boolean = false,
    val collectLogs: Boolean = false,
    val verboseLogcatEnabled: Boolean = false,
    val playbackInfoAtStartEnabled: Boolean = false,
    val logBufferSize: LogBufferSize = LogBufferSize(),
    val isSendingClientLogs: Boolean = false,
    val logCollectionPreferenceError: Boolean = false,
    /** False where no timeline action is bound, so the rows stay hidden. */
    val playbackTimelineAvailable: Boolean = false,
    /** The single save or send in progress; payloads stay in the ViewModel, never in state. */
    val playbackTimelineActivity: PlaybackTimelineActivity? = null,
)

enum class PlaybackTimelineActivity {
    Preparing,
    Saving,
    Sending,
}

/** One terminal, identity-free result per timeline save or send click. */
sealed interface PlaybackTimelineFeedback {
    data object CollectionDisabled : PlaybackTimelineFeedback

    data object NoTimedEvents : PlaybackTimelineFeedback

    data object PrepareFailed : PlaybackTimelineFeedback

    data object Saved : PlaybackTimelineFeedback

    data object SaveCancelled : PlaybackTimelineFeedback

    data object SaveUnavailable : PlaybackTimelineFeedback

    data object Interrupted : PlaybackTimelineFeedback

    data object Expired : PlaybackTimelineFeedback

    data object SaveFailed : PlaybackTimelineFeedback

    data class Sent(
        val filename: String,
    ) : PlaybackTimelineFeedback

    data object UploadDisallowed : PlaybackTimelineFeedback

    data object SendFailed : PlaybackTimelineFeedback
}

sealed interface SettingsEvent {
    data class ClientLogsSent(
        val result: SendClientLogsResult,
    ) : SettingsEvent

    data object LogoutFailed : SettingsEvent

    /** Carries only the operation id; the exporter fetches the live payload at handoff time. */
    data class SavePlaybackTimeline(
        val operationId: Long,
    ) : SettingsEvent

    data class PlaybackTimelineDelivered(
        val delivery: PlaybackTimelineDelivery,
        val feedback: PlaybackTimelineFeedback,
    ) : SettingsEvent
}

enum class PlaybackTimelineDelivery {
    Save,
    Send,
}
