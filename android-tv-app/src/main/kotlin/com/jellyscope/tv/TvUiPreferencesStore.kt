// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TvUiPreferencesStore {
    val focusedCardZoomEnabled: StateFlow<Boolean>
    val showLibraryGridHero: StateFlow<Boolean>

    suspend fun setFocusedCardZoomEnabled(enabled: Boolean)

    suspend fun setShowLibraryGridHero(enabled: Boolean)
}

internal class SharedPreferencesTvUiPreferencesStore(
    context: Context,
) : TvUiPreferencesStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(TV_UI_PREFS_NAME, Context.MODE_PRIVATE)
    private val _focusedCardZoomEnabled =
        MutableStateFlow(preferences.readFocusedCardZoomEnabled())
    private val _showLibraryGridHero = MutableStateFlow(preferences.readShowLibraryGridHero())

    override val focusedCardZoomEnabled: StateFlow<Boolean> =
        _focusedCardZoomEnabled.asStateFlow()
    override val showLibraryGridHero: StateFlow<Boolean> = _showLibraryGridHero.asStateFlow()

    override suspend fun setFocusedCardZoomEnabled(enabled: Boolean) {
        preferences
            .edit()
            .putBoolean(TV_UI_FOCUSED_CARD_ZOOM_KEY, enabled)
            .apply()
        _focusedCardZoomEnabled.value = enabled
    }

    override suspend fun setShowLibraryGridHero(enabled: Boolean) {
        preferences.edit().putBoolean(TV_UI_LIBRARY_GRID_HERO_KEY, enabled).apply()
        _showLibraryGridHero.value = enabled
    }
}

private fun SharedPreferences.readFocusedCardZoomEnabled(): Boolean = getBoolean(TV_UI_FOCUSED_CARD_ZOOM_KEY, true)

private fun SharedPreferences.readShowLibraryGridHero(): Boolean = getBoolean(TV_UI_LIBRARY_GRID_HERO_KEY, true)

private const val TV_UI_PREFS_NAME = "tv_ui_preferences"
private const val TV_UI_FOCUSED_CARD_ZOOM_KEY = "focused_card_zoom_enabled"
private const val TV_UI_LIBRARY_GRID_HERO_KEY = "library_grid_hero_enabled"
