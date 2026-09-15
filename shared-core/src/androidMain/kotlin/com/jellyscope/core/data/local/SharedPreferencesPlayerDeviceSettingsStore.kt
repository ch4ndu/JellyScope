// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences
import com.jellyscope.core.domain.playback.AndroidTvMpvVideoOutput
import com.jellyscope.core.domain.playback.IosPlaybackCompatibilityMode
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.core.domain.playback.PlayerVideoResolutionLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SharedPreferencesPlayerDeviceSettingsStore(
    context: Context,
) : PlayerDeviceSettingsStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PLAYER_DEVICE_SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(preferences.readSettings())

    override val settings: StateFlow<PlayerDeviceSettings> = _settings.asStateFlow()

    override suspend fun setSettings(settings: PlayerDeviceSettings) {
        preferences
            .edit()
            .putString(PLAYER_DEVICE_MPV_VIDEO_OUTPUT_KEY, settings.androidTvMpvVideoOutput.name)
            .putString(PLAYER_DEVICE_AUDIO_MODE_KEY, settings.audioMode.name)
            .putString(PLAYER_DEVICE_HDR_MODE_KEY, settings.hdrMode.name)
            .putString(PLAYER_DEVICE_MATCH_DISPLAY_REFRESH_RATE_KEY, settings.matchDisplayRefreshRate.toString())
            .putString(PLAYER_DEVICE_MAX_VIDEO_RESOLUTION_KEY, settings.maxVideoResolution.name)
            .putString(PLAYER_DEVICE_IOS_PLAYBACK_COMPATIBILITY_MODE_KEY, settings.iosPlaybackCompatibilityMode.name)
            .apply()
        _settings.value = settings
    }
}

private fun SharedPreferences.readSettings(): PlayerDeviceSettings =
    PlayerDeviceSettings(
        androidTvMpvVideoOutput =
            getString(PLAYER_DEVICE_MPV_VIDEO_OUTPUT_KEY, null).toTolerantEnumOrNull<AndroidTvMpvVideoOutput>()
                ?: AndroidTvMpvVideoOutput.Gpu,
        audioMode = getString(PLAYER_DEVICE_AUDIO_MODE_KEY, null).toPlayerAudioMode(),
        hdrMode = getString(PLAYER_DEVICE_HDR_MODE_KEY, null).toPlayerHdrMode(),
        matchDisplayRefreshRate = getString(PLAYER_DEVICE_MATCH_DISPLAY_REFRESH_RATE_KEY, null).toMatchDisplayRefreshRate(),
        maxVideoResolution = getString(PLAYER_DEVICE_MAX_VIDEO_RESOLUTION_KEY, null).toPlayerVideoResolutionLimit(),
        iosPlaybackCompatibilityMode =
            getString(PLAYER_DEVICE_IOS_PLAYBACK_COMPATIBILITY_MODE_KEY, null).toIosPlaybackCompatibilityMode(),
    )

private fun String?.toPlayerAudioMode(): PlayerAudioMode = toTolerantEnumOrNull<PlayerAudioMode>() ?: PlayerAudioMode.Auto

private fun String?.toPlayerHdrMode(): PlayerHdrMode = toTolerantEnumOrNull<PlayerHdrMode>() ?: PlayerHdrMode.Auto

private fun String?.toMatchDisplayRefreshRate(): Boolean = this?.toBooleanStrictOrNull() ?: false

private fun String?.toPlayerVideoResolutionLimit(): PlayerVideoResolutionLimit =
    toTolerantEnumOrNull<PlayerVideoResolutionLimit>() ?: PlayerVideoResolutionLimit.Unlimited

private fun String?.toIosPlaybackCompatibilityMode(): IosPlaybackCompatibilityMode =
    toTolerantEnumOrNull<IosPlaybackCompatibilityMode>() ?: IosPlaybackCompatibilityMode.Standard

private const val PLAYER_DEVICE_SETTINGS_PREFS_NAME = "player_device_settings"
private const val PLAYER_DEVICE_AUDIO_MODE_KEY = "audio_mode"
private const val PLAYER_DEVICE_HDR_MODE_KEY = "hdr_mode"
private const val PLAYER_DEVICE_MATCH_DISPLAY_REFRESH_RATE_KEY = "match_display_refresh_rate"
private const val PLAYER_DEVICE_MAX_VIDEO_RESOLUTION_KEY = "max_video_resolution"
private const val PLAYER_DEVICE_IOS_PLAYBACK_COMPATIBILITY_MODE_KEY = "ios_playback_compatibility_mode"

private const val PLAYER_DEVICE_MPV_VIDEO_OUTPUT_KEY = "android_tv_mpv_video_output"
