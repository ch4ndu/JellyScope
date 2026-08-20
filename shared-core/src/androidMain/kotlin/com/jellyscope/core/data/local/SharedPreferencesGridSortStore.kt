// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences

internal class SharedPreferencesGridSortStore(
    context: Context,
) : GridSortStore,
    ServerScopedClearableStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(GRID_SORT_PREFS_NAME, Context.MODE_PRIVATE)

    override fun savedSort(gridKey: String): String? = preferences.getString(gridKey, null)

    override suspend fun setSort(
        gridKey: String,
        sortName: String,
    ) {
        preferences
            .edit()
            .putString(gridKey, sortName)
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

private const val GRID_SORT_PREFS_NAME = "grid_sort"
