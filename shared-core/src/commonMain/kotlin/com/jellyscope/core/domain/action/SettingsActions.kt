// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.GridSortStore
import com.jellyscope.core.data.local.LibrarySortStore
import com.jellyscope.core.data.local.LibraryViewPreferencesStore
import com.jellyscope.core.data.local.PictureInPictureStore
import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.local.RecentSearchStore
import com.jellyscope.core.data.local.TileSizeStore
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.PlayerDeviceSettings

class SetAppThemeAction(
    private val appThemeStore: AppThemeStore,
) {
    suspend operator fun invoke(theme: AppColorThemeId) = appThemeStore.setTheme(theme)
}

class SetTileSizeAction(
    private val tileSizeStore: TileSizeStore,
) {
    suspend operator fun invoke(tileSize: TileSizeId) = tileSizeStore.setTileSize(tileSize)
}

class SetRememberLastLibraryViewAction(
    private val store: LibraryViewPreferencesStore,
) {
    suspend operator fun invoke(enabled: Boolean) = store.setRememberLastView(enabled)
}

class SetSavedLibraryViewAction(
    private val store: LibraryViewPreferencesStore,
) {
    suspend operator fun invoke(
        libraryKey: String,
        view: LibraryInnerView,
    ) = store.setSavedView(libraryKey, view)
}

class SetLastLibraryIdAction(
    private val store: LibraryViewPreferencesStore,
) {
    suspend operator fun invoke(
        accountKey: String,
        libraryId: String,
    ) {
        store.setLastLibraryId(accountKey, libraryId)
    }
}

class SetPictureInPictureEnabledAction(
    private val pictureInPictureStore: PictureInPictureStore,
) {
    suspend operator fun invoke(enabled: Boolean) = pictureInPictureStore.setEnabled(enabled)
}

class SavePlayerDeviceSettingsAction(
    private val playerDeviceSettingsStore: PlayerDeviceSettingsStore,
) {
    suspend operator fun invoke(settings: PlayerDeviceSettings) = playerDeviceSettingsStore.setSettings(settings)
}

class SavePlaybackPreferencesAction(
    private val playbackPreferencesStore: PlaybackPreferencesStore,
) {
    suspend operator fun invoke(
        serverId: String,
        preferences: PlaybackPreferences,
    ) = playbackPreferencesStore.save(serverId = serverId, preferences = preferences)
}

class SetGridSortAction(
    private val gridSortStore: GridSortStore,
) {
    suspend operator fun invoke(
        gridKey: String,
        sortName: String,
    ) = gridSortStore.setSort(gridKey = gridKey, sortName = sortName)
}

class SetLibrarySortAction(
    private val librarySortStore: LibrarySortStore,
) {
    suspend operator fun invoke(
        libraryKey: String,
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) = librarySortStore.setSort(
        libraryKey = libraryKey,
        sortBy = sortBy,
        sortOrder = sortOrder,
    )
}

class AddRecentSearchAction(
    private val recentSearchStore: RecentSearchStore,
) {
    suspend operator fun invoke(
        serverId: String,
        query: String,
    ) = recentSearchStore.add(serverId = serverId, query = query)

    suspend operator fun invoke(
        serverId: String,
        userId: String,
        query: String,
    ) = recentSearchStore.add(serverId = serverId, userId = userId, query = query)
}

class ClearRecentSearchesAction(
    private val recentSearchStore: RecentSearchStore,
) {
    suspend operator fun invoke(serverId: String) = recentSearchStore.clear(serverId)

    suspend operator fun invoke(
        serverId: String,
        userId: String,
    ) = recentSearchStore.clear(serverId, userId)
}
