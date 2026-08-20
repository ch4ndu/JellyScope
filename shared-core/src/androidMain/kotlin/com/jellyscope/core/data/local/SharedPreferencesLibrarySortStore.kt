// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder

internal class SharedPreferencesLibrarySortStore(
    context: Context,
) : LibrarySortStore,
    ServerScopedClearableStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(LIBRARY_SORT_PREFS_NAME, Context.MODE_PRIVATE)

    override fun savedSort(libraryKey: String): SavedLibrarySort? = preferences.getString(libraryKey, null)?.toSavedLibrarySortOrNull()

    override suspend fun setSort(
        libraryKey: String,
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) {
        preferences
            .edit()
            .putString(libraryKey, SavedLibrarySort(sortBy = sortBy, sortOrder = sortOrder).toStoredValue())
            .apply()
    }

    override suspend fun clearServerScoped() {
        preferences.edit().clear().apply()
    }

    override suspend fun clearServerScoped(serverId: String) {
        val prefix = "$serverId:"
        val keys = preferences.all.keys.filter { key -> key.startsWith(prefix) }
        val editor = preferences.edit()
        keys.forEach { key -> editor.remove(key) }
        editor.apply()
    }
}

private const val LIBRARY_SORT_PREFS_NAME = "library_sort"
