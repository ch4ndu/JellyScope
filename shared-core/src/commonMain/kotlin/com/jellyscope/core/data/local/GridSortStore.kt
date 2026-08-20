// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

/**
 * Persists the last-selected sort for the Home-row "View All" grids so it is
 * restored on app launch. The value is the sort's stable name (the UI owns the
 * sort enum), keyed per grid (e.g. server + row).
 */
interface GridSortStore : ServerScopedClearableStore {
    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    /** Synchronous read; null if the grid has no remembered sort yet. */
    fun savedSort(gridKey: String): String?

    suspend fun setSort(
        gridKey: String,
        sortName: String,
    )
}
