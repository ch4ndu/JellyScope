// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import android.content.SharedPreferences
import com.jellyscope.core.domain.model.TileSizeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SharedPreferencesTileSizeStore(
    context: Context,
) : TileSizeStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(TILE_SIZE_PREFS_NAME, Context.MODE_PRIVATE)
    private val _tileSize = MutableStateFlow(preferences.readTileSize())

    override val tileSize: StateFlow<TileSizeId> = _tileSize.asStateFlow()

    override suspend fun setTileSize(tileSize: TileSizeId) {
        preferences
            .edit()
            .putString(TILE_SIZE_PREFS_KEY, tileSize.name)
            .apply()
        _tileSize.value = tileSize
    }
}

private fun SharedPreferences.readTileSize(): TileSizeId = getString(TILE_SIZE_PREFS_KEY, null)?.toTileSizeId() ?: TileSizeId.Medium

private fun String.toTileSizeId(): TileSizeId = toTolerantEnumOrNull<TileSizeId>() ?: TileSizeId.Medium

private const val TILE_SIZE_PREFS_NAME = "tile_size"
private const val TILE_SIZE_PREFS_KEY = "tile_size"
