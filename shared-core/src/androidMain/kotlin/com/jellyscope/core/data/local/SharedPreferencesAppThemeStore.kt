// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences
import com.jellyscope.core.domain.model.AppColorThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SharedPreferencesAppThemeStore(
    context: Context,
) : AppThemeStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(APP_THEME_PREFS_NAME, Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(preferences.readTheme())

    override val theme: StateFlow<AppColorThemeId> = _theme.asStateFlow()

    override suspend fun setTheme(theme: AppColorThemeId) {
        preferences
            .edit()
            .putString(APP_THEME_PREFS_KEY, theme.name)
            .apply()
        _theme.value = theme
    }
}

private fun SharedPreferences.readTheme(): AppColorThemeId =
    getString(APP_THEME_PREFS_KEY, null)?.toAppColorThemeId() ?: AppColorThemeId.Ember

private fun String.toAppColorThemeId(): AppColorThemeId = toTolerantEnumOrNull<AppColorThemeId>() ?: AppColorThemeId.Ember

private const val APP_THEME_PREFS_NAME = "app_theme"
private const val APP_THEME_PREFS_KEY = "app_color_theme"
