// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import com.jellyscope.core.domain.model.LibraryInnerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SharedPreferencesLibraryViewPreferencesStore(
    context: Context,
) : LibraryViewPreferencesStore,
    ServerScopedClearableStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _rememberLastView = MutableStateFlow(preferences.getBoolean(REMEMBER_KEY, true))

    override val rememberLastView: StateFlow<Boolean> = _rememberLastView.asStateFlow()

    override fun lastLibraryId(accountKey: String): String? = preferences.getString("$LAST_LIBRARY_PREFIX$accountKey", null)

    override fun savedView(libraryKey: String): LibraryInnerView? =
        preferences.getString("$VIEW_PREFIX$libraryKey", null)?.toLibraryInnerView()

    override suspend fun setRememberLastView(enabled: Boolean) {
        preferences.edit().putBoolean(REMEMBER_KEY, enabled).apply()
        _rememberLastView.value = enabled
    }

    override suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    ) {
        preferences.edit().putString("$LAST_LIBRARY_PREFIX$accountKey", libraryId).apply()
    }

    override suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    ) {
        preferences.edit().putString("$VIEW_PREFIX$libraryKey", view.name).apply()
    }

    override suspend fun clearServerScoped() {
        clearViews { true }
    }

    override suspend fun clearServerScoped(serverId: String) {
        clearViews { key ->
            key.startsWith("$VIEW_PREFIX$serverId:") || key.startsWith("$LAST_LIBRARY_PREFIX$serverId:")
        }
    }

    private fun clearViews(matches: (String) -> Boolean) {
        val editor = preferences.edit()
        preferences.all.keys
            .filter(matches)
            .forEach(editor::remove)
        editor.apply()
    }
}

private fun String.toLibraryInnerView(): LibraryInnerView? = toTolerantEnumOrNull<LibraryInnerView>()

private const val PREFERENCES_NAME = "library_view_preferences"
private const val REMEMBER_KEY = "remember_last_view"
private const val VIEW_PREFIX = "view."
private const val LAST_LIBRARY_PREFIX = "last_library:"
