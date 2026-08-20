// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SharedPreferencesPictureInPictureStore(
    context: Context,
) : PictureInPictureStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PICTURE_IN_PICTURE_PREFS_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.readEnabled())

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        preferences
            .edit()
            .putBoolean(PICTURE_IN_PICTURE_PREFS_KEY, enabled)
            .apply()
        _enabled.value = enabled
    }
}

private fun SharedPreferences.readEnabled(): Boolean = getBoolean(PICTURE_IN_PICTURE_PREFS_KEY, true)

private const val PICTURE_IN_PICTURE_PREFS_NAME = "picture_in_picture"
private const val PICTURE_IN_PICTURE_PREFS_KEY = "enabled"
