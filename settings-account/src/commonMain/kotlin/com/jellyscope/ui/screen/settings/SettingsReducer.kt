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
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerBackendPolicy
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.util.LogBufferSize

internal sealed interface SettingsChange {
    data object PlaybackWriteFailed : SettingsChange

    data object DeviceWriteFailed : SettingsChange

    data object UsageStarted : SettingsChange

    data class UsageFailed(
        val requestGeneration: Long,
    ) : SettingsChange

    data class UsageLoaded(
        val requestGeneration: Long,
        val usage: DownloadUsage,
        val settings: DownloadSettings,
    ) : SettingsChange

    data object LogoutPreviewStarted : SettingsChange

    data object LogoutPreviewFailed : SettingsChange

    data class LogoutPreviewLoaded(
        val preview: DownloadRemovalPreview,
    ) : SettingsChange

    data object LogoutStopped : SettingsChange

    data object LogoutConfirmed : SettingsChange

    data object LogoutDismissed : SettingsChange

    data object LogoutStarted : SettingsChange

    data object DeviceRefreshStarted : SettingsChange

    data object DesktopSaveStarted : SettingsChange

    data object DesktopSaveFailed : SettingsChange

    data object ApiKeySaveStarted : SettingsChange

    class ApiKeySaved(
        val value: String,
    ) : SettingsChange

    data object ApiKeySaveFailed : SettingsChange

    data object SubtitlePreferenceSaveStarted : SettingsChange

    data class SubtitlePreferenceSaved(
        val preference: OpenSubtitleResultPreference,
    ) : SettingsChange

    data object SubtitlePreferenceSaveFailed : SettingsChange

    data object SubtitlePreferenceSaveFinished : SettingsChange

    data object SubtitleClearStarted : SettingsChange

    data object SubtitleClearFailed : SettingsChange

    data object SubtitleClearFinished : SettingsChange

    data object LogCollectionSaved : SettingsChange

    data object LogCollectionFailed : SettingsChange

    data object ClientLogsStarted : SettingsChange

    data object ClientLogsFinished : SettingsChange

    data object TimelinePreparing : SettingsChange

    data object TimelineSaving : SettingsChange

    data object TimelineSending : SettingsChange

    data object TimelineFinished : SettingsChange

    data class BackendChoicesUpdated(
        val choices: List<PlayerBackendChoice>,
    ) : SettingsChange

    data object ApiKeyLoadStarted : SettingsChange

    data class ApiKeyLoadFailed(
        val loadGeneration: Long,
    ) : SettingsChange

    class ApiKeyLoaded(
        val loadGeneration: Long,
        val value: String,
    ) : SettingsChange

    data object SubtitlePreferenceLoadStarted : SettingsChange

    data class SubtitlePreferenceLoaded(
        val value: OpenSubtitleResultPreference,
    ) : SettingsChange

    data object SubtitlePreferenceLoadFailed : SettingsChange

    data class ThemeObserved(
        val theme: AppColorThemeId,
    ) : SettingsChange

    data class TileSizeObserved(
        val value: TileSizeId,
    ) : SettingsChange

    data class ImageQualityObserved(
        val value: ImageQuality,
    ) : SettingsChange

    data class PipObserved(
        val enabled: Boolean,
    ) : SettingsChange

    data class ClockObserved(
        val enabled: Boolean,
    ) : SettingsChange

    data class DesktopObserved(
        val value: DesktopMpvPresentationState,
    ) : SettingsChange

    data class RememberViewObserved(
        val enabled: Boolean,
    ) : SettingsChange

    data class DevicePolicyFailed(
        val deviceSettings: PlayerDeviceSettings,
        val forceRefresh: Boolean,
    ) : SettingsChange

    data class DevicePolicyLoaded(
        val deviceSettings: PlayerDeviceSettings,
        val forceRefresh: Boolean,
        val policy: EffectivePlayerDevicePolicy,
    ) : SettingsChange

    data class LogCollectionObserved(
        val enabled: Boolean,
    ) : SettingsChange

    data class LogSizeObserved(
        val value: LogBufferSize,
    ) : SettingsChange

    data class VerboseLogsObserved(
        val enabled: Boolean,
    ) : SettingsChange

    data class PlaybackInfoObserved(
        val enabled: Boolean,
    ) : SettingsChange

    data object PlaybackLoadStarted : SettingsChange

    data class PlaybackLoaded(
        val preferences: PlaybackPreferences,
    ) : SettingsChange

    data object PlaybackLoadFailed : SettingsChange

    data class PlaybackChanged(
        val transform: (PlaybackPreferences) -> PlaybackPreferences,
    ) : SettingsChange

    data class DeviceSettingsChanged(
        val transform: (PlayerDeviceSettings) -> PlayerDeviceSettings,
    ) : SettingsChange
}

internal fun reduceSettings(
    current: SettingsUiState,
    change: SettingsChange,
    downloadUsageGeneration: Long,
    openSubtitlesApiKeyLoadGeneration: Long,
    playerBackendPolicy: PlayerBackendPolicy,
): SettingsUiState =
    when (change) {
        SettingsChange.PlaybackWriteFailed -> current.copy(playbackPreferencesError = true)
        SettingsChange.DeviceWriteFailed -> current.copy(playerDevicePolicyError = true)
        SettingsChange.UsageStarted -> current.copy(isLoadingDownloadUsage = true)
        is SettingsChange.UsageFailed ->
            with(change) {
                if (requestGeneration == downloadUsageGeneration) current.copy(isLoadingDownloadUsage = false) else current
            }
        is SettingsChange.UsageLoaded ->
            with(change) {
                if (requestGeneration != downloadUsageGeneration) {
                    current
                } else {
                    current.copy(
                        downloadUsage = usage,
                        downloadSettings = settings,
                        isLoadingDownloadUsage = false,
                    )
                }
            }
        SettingsChange.LogoutPreviewStarted -> current.copy(isLoggingOut = true, isLoadingLogoutRemovalPreview = true)
        SettingsChange.LogoutPreviewFailed -> current.copy(isLoggingOut = false, isLoadingLogoutRemovalPreview = false)
        is SettingsChange.LogoutPreviewLoaded ->
            with(change) {
                current.copy(
                    isLoggingOut = false,
                    isLoadingLogoutRemovalPreview = false,
                    logoutRemovalPreview = preview,
                )
            }
        SettingsChange.LogoutStopped -> current.copy(isLoggingOut = false)
        SettingsChange.LogoutConfirmed -> current.copy(isLoggingOut = true, logoutRemovalPreview = null)
        SettingsChange.LogoutDismissed ->
            current.copy(
                isLoggingOut = false,
                isLoadingLogoutRemovalPreview = false,
                logoutRemovalPreview = null,
            )
        SettingsChange.LogoutStarted -> current.copy(isLoggingOut = true)
        SettingsChange.DeviceRefreshStarted ->
            current.copy(
                isRefreshingPlayerDevicePolicy = true,
                playerDevicePolicyError = false,
            )
        SettingsChange.DesktopSaveStarted -> current.copy(desktopMpvPresentationError = false)
        SettingsChange.DesktopSaveFailed -> current.copy(desktopMpvPresentationError = true)
        SettingsChange.ApiKeySaveStarted -> current.copy(isSavingOpenSubtitlesApiKey = true, openSubtitlesApiKeyError = false)
        is SettingsChange.ApiKeySaved ->
            with(change) {
                current.copy(
                    openSubtitlesApiKey = value,
                    isSavingOpenSubtitlesApiKey = false,
                    openSubtitlesApiKeyError = false,
                )
            }
        SettingsChange.ApiKeySaveFailed ->
            current.copy(
                isSavingOpenSubtitlesApiKey = false,
                openSubtitlesApiKeyError = true,
            )
        SettingsChange.SubtitlePreferenceSaveStarted ->
            current.copy(
                isSavingOpenSubtitleResultPreference = true,
                openSubtitleResultPreferenceError = false,
            )
        is SettingsChange.SubtitlePreferenceSaved ->
            with(change) {
                current.copy(
                    openSubtitleResultPreference = preference,
                    openSubtitleResultPreferenceError = false,
                )
            }
        SettingsChange.SubtitlePreferenceSaveFailed -> current.copy(openSubtitleResultPreferenceError = true)
        SettingsChange.SubtitlePreferenceSaveFinished -> current.copy(isSavingOpenSubtitleResultPreference = false)
        SettingsChange.SubtitleClearStarted ->
            current.copy(
                isClearingLocalSubtitles = true,
                localSubtitlesClearError = false,
            )
        SettingsChange.SubtitleClearFailed -> current.copy(localSubtitlesClearError = true)
        SettingsChange.SubtitleClearFinished ->
            current.copy(
                isClearingLocalSubtitles = false,
                localSubtitlesClearCompletion = current.localSubtitlesClearCompletion + 1,
            )
        SettingsChange.LogCollectionSaved -> current.copy(logCollectionPreferenceError = false)
        SettingsChange.LogCollectionFailed -> current.copy(logCollectionPreferenceError = true)
        SettingsChange.ClientLogsStarted -> current.copy(isSendingClientLogs = true)
        SettingsChange.ClientLogsFinished -> current.copy(isSendingClientLogs = false)
        SettingsChange.TimelinePreparing -> current.copy(playbackTimelineActivity = PlaybackTimelineActivity.Preparing)
        SettingsChange.TimelineSaving -> current.copy(playbackTimelineActivity = PlaybackTimelineActivity.Saving)
        SettingsChange.TimelineSending -> current.copy(playbackTimelineActivity = PlaybackTimelineActivity.Sending)
        SettingsChange.TimelineFinished -> current.copy(playbackTimelineActivity = null)
        is SettingsChange.BackendChoicesUpdated ->
            with(change) {
                current.copy(
                    playerBackendChoices = choices,
                    selectedPlayerBackend =
                        playerBackendPolicy.effectivePlayerBackendSelection(
                            persistedBackend = current.playbackPreferences.defaultPlayerBackend,
                            choices = choices,
                        ),
                )
            }
        SettingsChange.ApiKeyLoadStarted ->
            current.copy(
                isLoadingOpenSubtitlesApiKey = true,
                openSubtitlesApiKeyLoadError = false,
            )
        is SettingsChange.ApiKeyLoadFailed ->
            with(change) {
                if (loadGeneration == openSubtitlesApiKeyLoadGeneration) {
                    current.copy(
                        isLoadingOpenSubtitlesApiKey = false,
                        openSubtitlesApiKeyLoadError = true,
                    )
                } else {
                    current
                }
            }
        is SettingsChange.ApiKeyLoaded ->
            with(change) {
                if (loadGeneration != openSubtitlesApiKeyLoadGeneration) {
                    current
                } else if (current.isSavingOpenSubtitlesApiKey) {
                    current.copy(isLoadingOpenSubtitlesApiKey = false)
                } else {
                    current.copy(
                        openSubtitlesApiKey = value,
                        isOpenSubtitlesApiKeyLoaded = true,
                        isLoadingOpenSubtitlesApiKey = false,
                        openSubtitlesApiKeyLoadError = false,
                    )
                }
            }
        SettingsChange.SubtitlePreferenceLoadStarted ->
            current.copy(
                isLoadingOpenSubtitleResultPreference = true,
                openSubtitleResultPreferenceLoadError = false,
            )
        is SettingsChange.SubtitlePreferenceLoaded ->
            with(change) {
                current.copy(
                    openSubtitleResultPreference = value,
                    isOpenSubtitleResultPreferenceLoaded = true,
                    isLoadingOpenSubtitleResultPreference = false,
                    openSubtitleResultPreferenceLoadError = false,
                )
            }
        SettingsChange.SubtitlePreferenceLoadFailed ->
            current.copy(
                isOpenSubtitleResultPreferenceLoaded = false,
                isLoadingOpenSubtitleResultPreference = false,
                openSubtitleResultPreferenceLoadError = true,
            )
        is SettingsChange.ThemeObserved ->
            with(change) {
                current.copy(appTheme = theme)
            }
        is SettingsChange.TileSizeObserved ->
            with(change) {
                current.copy(tileSize = value)
            }
        is SettingsChange.ImageQualityObserved ->
            with(change) {
                current.copy(imageQuality = value)
            }
        is SettingsChange.PipObserved ->
            with(change) {
                current.copy(pictureInPictureEnabled = enabled)
            }
        is SettingsChange.ClockObserved ->
            with(change) {
                current.copy(playerClockEnabled = enabled)
            }
        is SettingsChange.DesktopObserved ->
            with(change) {
                current.copy(desktopMpvPresentation = value)
            }
        is SettingsChange.RememberViewObserved ->
            with(change) {
                current.copy(rememberLastLibraryView = enabled)
            }
        is SettingsChange.DevicePolicyFailed ->
            with(change) {
                current.copy(
                    playerDeviceSettings = deviceSettings,
                    playerDevicePolicyError = true,
                    isRefreshingPlayerDevicePolicy = false,
                    playerDevicePolicyRefreshCompletion =
                        if (forceRefresh) {
                            current.playerDevicePolicyRefreshCompletion + 1
                        } else {
                            current.playerDevicePolicyRefreshCompletion
                        },
                )
            }
        is SettingsChange.DevicePolicyLoaded ->
            with(change) {
                current.copy(
                    playerDeviceSettings = deviceSettings,
                    playerDevicePolicy = policy,
                    playerDevicePolicyError = false,
                    isRefreshingPlayerDevicePolicy = false,
                    playerDevicePolicyRefreshCompletion =
                        if (forceRefresh) {
                            current.playerDevicePolicyRefreshCompletion + 1
                        } else {
                            current.playerDevicePolicyRefreshCompletion
                        },
                )
            }
        is SettingsChange.LogCollectionObserved ->
            with(change) {
                current.copy(collectLogs = enabled)
            }
        is SettingsChange.LogSizeObserved ->
            with(change) {
                current.copy(logBufferSize = value)
            }
        is SettingsChange.VerboseLogsObserved ->
            with(change) {
                current.copy(verboseLogcatEnabled = enabled)
            }
        is SettingsChange.PlaybackInfoObserved ->
            with(change) {
                current.copy(playbackInfoAtStartEnabled = enabled)
            }
        SettingsChange.PlaybackLoadStarted ->
            current.copy(
                isLoadingPlaybackPreferences = true,
                playbackPreferencesError = false,
            )
        is SettingsChange.PlaybackLoaded ->
            with(change) {
                current.copy(
                    playbackPreferences = preferences,
                    selectedPlayerBackend =
                        playerBackendPolicy.effectivePlayerBackendSelection(
                            persistedBackend = preferences.defaultPlayerBackend,
                            choices = current.playerBackendChoices,
                        ),
                    isLoadingPlaybackPreferences = false,
                    playbackPreferencesError = false,
                )
            }
        SettingsChange.PlaybackLoadFailed ->
            current.copy(
                isLoadingPlaybackPreferences = false,
                playbackPreferencesError = true,
            )
        is SettingsChange.PlaybackChanged ->
            with(change) {
                val preferences = transform(current.playbackPreferences)
                current.copy(
                    playbackPreferences = preferences,
                    selectedPlayerBackend =
                        playerBackendPolicy.effectivePlayerBackendSelection(
                            persistedBackend = preferences.defaultPlayerBackend,
                            choices = current.playerBackendChoices,
                        ),
                    playbackPreferencesError = false,
                )
            }
        is SettingsChange.DeviceSettingsChanged ->
            with(change) {
                val nextSettings = transform(current.playerDeviceSettings)
                current.copy(
                    playerDeviceSettings = nextSettings,
                    playerDevicePolicy =
                        current.playerDevicePolicy?.let { policy ->
                            policy.copy(settings = nextSettings)
                        },
                    playerDevicePolicyError = false,
                )
            }
    }

internal fun PlayerBackendPolicy.effectivePlayerBackendSelection(
    persistedBackend: PlayerBackend?,
    choices: List<PlayerBackendChoice>,
): PlayerBackend {
    val normalizedBackend = normalizePersisted(persistedBackend)
    return normalizedBackend.takeIf { backend ->
        choices.any { choice -> choice.backend == backend && choice.available }
    } ?: choices.firstOrNull { choice -> choice.available }?.backend
        ?: choices.firstOrNull()?.backend
        ?: defaultBackend
}
