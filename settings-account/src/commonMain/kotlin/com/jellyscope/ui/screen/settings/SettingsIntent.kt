// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.DesktopMpvPresentation
import com.jellyscope.core.domain.model.ImageQuality
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.SegmentSkipPolicy
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.AndroidTvMpvVideoOutput
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.MediaSegmentType
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit

sealed interface SettingsIntent {
    data object RefreshDownloadUsage : SettingsIntent

    data class Logout(
        val onComplete: () -> Unit = {},
    ) : SettingsIntent

    data class ConfirmLogoutRemoval(
        val onComplete: () -> Unit = {},
    ) : SettingsIntent

    data object DismissLogoutRemoval : SettingsIntent

    data object ReloadPlaybackPreferences : SettingsIntent

    data class SetDefaultMaxBitrateBps(
        val defaultMaxBitrateBps: Long?,
    ) : SettingsIntent

    data class SetDefaultQualityPolicy(
        val policy: PlaybackQualityPolicy,
    ) : SettingsIntent

    data class SetVlcTranscodeMaxBitrateBps(
        val maxBitrateBps: Long?,
    ) : SettingsIntent

    data class SetDefaultPlayerBackend(
        val backend: PlayerBackend,
    ) : SettingsIntent

    data class SetPreferredAudioLanguage(
        val language: String?,
    ) : SettingsIntent

    data class SetPreferredSubtitleLanguage(
        val language: String?,
    ) : SettingsIntent

    data class SetAutoPlayNext(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetAutoPlayNextDelaySeconds(
        val delaySeconds: Int,
    ) : SettingsIntent

    data class SetStillWatchingPrompt(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetPlaybackWarningsEnabled(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetAllowInsecureDesktopTls(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetSegmentSkipPolicy(
        val type: MediaSegmentType,
        val policy: SegmentSkipPolicy,
    ) : SettingsIntent

    data class SetAndroidTvMpvVideoOutput(
        val output: AndroidTvMpvVideoOutput,
    ) : SettingsIntent

    data class SetPlayerAudioMode(
        val audioMode: PlayerAudioMode,
    ) : SettingsIntent

    data class SetPlayerHdrMode(
        val hdrMode: PlayerHdrMode,
    ) : SettingsIntent

    data class SetMaxVideoResolution(
        val limit: PlayerVideoResolutionLimit,
    ) : SettingsIntent

    data class SetIosPlaybackCompatibilityMode(
        val mode: IosPlaybackCompatibilityMode,
    ) : SettingsIntent

    data class SetMatchDisplayRefreshRate(
        val enabled: Boolean,
    ) : SettingsIntent

    data object RefreshPlayerDevicePolicy : SettingsIntent

    data class SetAppTheme(
        val theme: AppColorThemeId,
    ) : SettingsIntent

    data class SetTileSize(
        val tileSize: TileSizeId,
    ) : SettingsIntent

    data class SetImageQuality(
        val imageQuality: ImageQuality,
    ) : SettingsIntent

    data class SetPictureInPictureEnabled(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetPlayerClockEnabled(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetRememberLastLibraryView(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetDesktopMpvPresentation(
        val presentation: DesktopMpvPresentation,
    ) : SettingsIntent

    class SetOpenSubtitlesApiKey(
        val value: String,
        val onSuccess: () -> Unit = {},
    ) : SettingsIntent

    data object RetryOpenSubtitlesApiKeyLoad : SettingsIntent

    data class SetOpenSubtitleResultPreference(
        val preference: OpenSubtitleResultPreference,
        val onSuccess: () -> Unit = {},
    ) : SettingsIntent

    data object RetryOpenSubtitleResultPreferenceLoad : SettingsIntent

    data object ClearLocalSubtitles : SettingsIntent

    data class SetLogCollectionEnabled(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetVerboseLogcatEnabled(
        val enabled: Boolean,
    ) : SettingsIntent

    data class SetPlaybackInfoAtStartEnabled(
        val enabled: Boolean,
    ) : SettingsIntent

    data object SendClientLogs : SettingsIntent

    data object SavePlaybackTimeline : SettingsIntent

    data object SendPlaybackTimeline : SettingsIntent
}
