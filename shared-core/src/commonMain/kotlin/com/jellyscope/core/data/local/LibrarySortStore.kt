// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder

data class SavedLibrarySort(
    val sortBy: LibrarySortBy,
    val sortOrder: LibrarySortOrder,
)

interface LibrarySortStore : ServerScopedClearableStore {
    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    /** Synchronous read; null if the library has no remembered sort yet. */
    fun savedSort(libraryKey: String): SavedLibrarySort?

    suspend fun setSort(
        libraryKey: String,
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    )
}
