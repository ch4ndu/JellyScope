// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.LibraryInnerView
import kotlinx.coroutines.flow.StateFlow

interface LibraryViewPreferencesStore : ServerScopedClearableStore {
    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    val rememberLastView: StateFlow<Boolean>

    fun lastLibraryId(accountKey: String): String?

    fun savedView(libraryKey: String): LibraryInnerView?

    suspend fun setRememberLastView(enabled: Boolean)

    suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    )

    suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    )
}
