// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences
import com.jellyscope.core.coroutines.platformIoDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal class SharedPreferencesLogCollectionPreferenceStore(
    context: Context,
    commitEnabled: ((Boolean) -> Boolean)? = null,
) : LogCollectionPreferenceStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(LOG_COLLECTION_PREFS_NAME, Context.MODE_PRIVATE)
    private val commitEnabledWriter: (Boolean) -> Boolean =
        commitEnabled ?: { value -> preferences.edit().putBoolean(LOG_COLLECTION_ENABLED_KEY, value).commit() }
    private val _enabled =
        MutableStateFlow(
            if (preferences.contains(LOG_COLLECTION_ENABLED_KEY)) {
                preferences.getBoolean(LOG_COLLECTION_ENABLED_KEY, false)
            } else {
                true
            },
        )
    private val _verboseLogcatEnabled = MutableStateFlow(preferences.getBoolean(VERBOSE_LOGCAT_ENABLED_KEY, false))
    private val _playbackInfoAtStartEnabled =
        MutableStateFlow(preferences.getBoolean(PLAYBACK_INFO_AT_START_ENABLED_KEY, false))

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val verboseLogcatEnabled: StateFlow<Boolean> = _verboseLogcatEnabled.asStateFlow()
    override val playbackInfoAtStartEnabled: StateFlow<Boolean> = _playbackInfoAtStartEnabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        withContext(platformIoDispatcher()) {
            check(commitEnabledWriter(enabled)) {
                "Unable to durably save log collection preference."
            }
        }
        _enabled.value = enabled
    }

    override suspend fun setVerboseLogcatEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(VERBOSE_LOGCAT_ENABLED_KEY, enabled).apply()
        _verboseLogcatEnabled.value = enabled
    }

    override suspend fun setPlaybackInfoAtStartEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PLAYBACK_INFO_AT_START_ENABLED_KEY, enabled).apply()
        _playbackInfoAtStartEnabled.value = enabled
    }
}

private const val LOG_COLLECTION_PREFS_NAME = "log_collection"
private const val LOG_COLLECTION_ENABLED_KEY = "log_collection_enabled"
private const val VERBOSE_LOGCAT_ENABLED_KEY = "verbose_logcat_enabled"
private const val PLAYBACK_INFO_AT_START_ENABLED_KEY = "playback_info_at_start_enabled"
