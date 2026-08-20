// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.GridSortStore
import com.jellyscope.core.data.local.LibrarySortStore
import com.jellyscope.core.data.local.LibraryViewPreferencesStore
import com.jellyscope.core.data.local.PictureInPictureStore
import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlayerBackendOverrideStore
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.local.RecentSearchStore
import com.jellyscope.core.data.local.SavedLibrarySort
import com.jellyscope.core.data.local.TileSizeStore
import com.jellyscope.core.domain.model.AppColorThemeId
import com.jellyscope.core.domain.model.PlaybackPreferences
import com.jellyscope.core.domain.model.TileSizeId
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.resolvePlayerDevicePolicy
import kotlinx.coroutines.flow.StateFlow

class ObserveAppThemeUseCase(
    private val appThemeStore: AppThemeStore,
) {
    operator fun invoke(): StateFlow<AppColorThemeId> = appThemeStore.theme
}

class ObserveTileSizeUseCase(
    private val tileSizeStore: TileSizeStore,
) {
    operator fun invoke(): StateFlow<TileSizeId> = tileSizeStore.tileSize
}

class ObserveRememberLastLibraryViewUseCase(
    private val store: LibraryViewPreferencesStore,
) {
    operator fun invoke(): StateFlow<Boolean> = store.rememberLastView
}

class GetSavedLibraryViewUseCase(
    private val store: LibraryViewPreferencesStore,
) {
    operator fun invoke(libraryKey: String) = store.savedView(libraryKey)
}

class GetLastLibraryIdUseCase(
    private val store: LibraryViewPreferencesStore,
) {
    operator fun invoke(accountKey: String): String? = store.lastLibraryId(accountKey)
}

class ObservePictureInPictureEnabledUseCase(
    private val pictureInPictureStore: PictureInPictureStore,
) {
    operator fun invoke(): StateFlow<Boolean> = pictureInPictureStore.enabled
}

class ObservePlayerDeviceSettingsUseCase(
    private val playerDeviceSettingsStore: PlayerDeviceSettingsStore,
) {
    operator fun invoke(): StateFlow<PlayerDeviceSettings> = playerDeviceSettingsStore.settings
}

class GetPlayerDevicePolicyUseCase(
    private val deviceProfileProvider: DeviceProfileProvider,
    private val playerDeviceSettingsStore: PlayerDeviceSettingsStore,
) {
    operator fun invoke(): EffectivePlayerDevicePolicy =
        resolvePlayerDevicePolicy(
            capabilities = deviceProfileProvider.capabilities(),
            settings = playerDeviceSettingsStore.settings.value,
        )
}

class RefreshPlayerDevicePolicyUseCase(
    private val deviceProfileProvider: DeviceProfileProvider,
    private val playerDeviceSettingsStore: PlayerDeviceSettingsStore,
) {
    operator fun invoke(): EffectivePlayerDevicePolicy =
        resolvePlayerDevicePolicy(
            capabilities = deviceProfileProvider.refreshCapabilities(),
            settings = playerDeviceSettingsStore.settings.value,
        )
}

class GetPlaybackPreferencesUseCase(
    private val playbackPreferencesStore: PlaybackPreferencesStore,
) {
    suspend operator fun invoke(serverId: String): PlaybackPreferences = playbackPreferencesStore.get(serverId)
}

class GetPlayerBackendOverrideUseCase(
    private val playerBackendOverrideStore: PlayerBackendOverrideStore,
) {
    suspend operator fun invoke(
        serverId: String,
        itemId: String,
    ): PlayerBackend? = playerBackendOverrideStore.get(serverId, itemId)
}

class GetGridSortUseCase(
    private val gridSortStore: GridSortStore,
) {
    operator fun invoke(gridKey: String): String? = gridSortStore.savedSort(gridKey)
}

class GetLibrarySortUseCase(
    private val librarySortStore: LibrarySortStore,
) {
    operator fun invoke(libraryKey: String): SavedLibrarySort? = librarySortStore.savedSort(libraryKey)
}

class GetRecentSearchesUseCase(
    private val recentSearchStore: RecentSearchStore,
) {
    suspend operator fun invoke(serverId: String): List<String> = recentSearchStore.list(serverId)

    suspend operator fun invoke(
        serverId: String,
        userId: String,
    ): List<String> = recentSearchStore.list(serverId, userId)
}
