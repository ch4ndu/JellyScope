// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.TileSizeId
import kotlinx.coroutines.flow.StateFlow

interface TileSizeStore {
    val tileSize: StateFlow<TileSizeId>

    suspend fun setTileSize(tileSize: TileSizeId)
}
