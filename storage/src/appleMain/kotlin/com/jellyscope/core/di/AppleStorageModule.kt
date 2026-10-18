// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.AppleLocalSubtitleFileStore
import com.jellyscope.core.data.local.ApplePreviousRunFailureStore
import com.jellyscope.core.data.local.AppleUserDefaultsAppThemeStore
import com.jellyscope.core.data.local.AppleUserDefaultsGridSortStore
import com.jellyscope.core.data.local.AppleUserDefaultsImageQualityStore
import com.jellyscope.core.data.local.AppleUserDefaultsLibrarySortStore
import com.jellyscope.core.data.local.AppleUserDefaultsLibraryViewPreferencesStore
import com.jellyscope.core.data.local.AppleUserDefaultsLogCollectionPreferenceStore
import com.jellyscope.core.data.local.AppleUserDefaultsPictureInPictureStore
import com.jellyscope.core.data.local.AppleUserDefaultsPlayerClockStore
import com.jellyscope.core.data.local.AppleUserDefaultsTileSizeStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.GridSortStore
import com.jellyscope.core.data.local.ImageQualityStore
import com.jellyscope.core.data.local.JellyfinStoreDatabase
import com.jellyscope.core.data.local.LibrarySortStore
import com.jellyscope.core.data.local.LibraryViewPreferencesStore
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PictureInPictureStore
import com.jellyscope.core.data.local.PlaybackPreferencesStore
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.PlaybackTimingStore
import com.jellyscope.core.data.local.PlayerBackendOverrideStore
import com.jellyscope.core.data.local.PlayerClockStore
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.local.RecentSearchStore
import com.jellyscope.core.data.local.RoomLocalSubtitleAssetStore
import com.jellyscope.core.data.local.RoomPlaybackPreferencesStore
import com.jellyscope.core.data.local.RoomPlaybackSelectionStore
import com.jellyscope.core.data.local.RoomPlaybackTimingStore
import com.jellyscope.core.data.local.RoomPlayerBackendOverrideStore
import com.jellyscope.core.data.local.RoomPlayerDeviceSettingsStore
import com.jellyscope.core.data.local.RoomRecentSearchStore
import com.jellyscope.core.data.local.RoomSubtitleSelectionStore
import com.jellyscope.core.data.local.RoomWatchNextSyncStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.local.TileSizeStore
import com.jellyscope.core.data.local.WatchNextSyncStore
import com.jellyscope.core.data.local.createDiagnosticBreadcrumbStore
import org.koin.core.module.Module
import org.koin.dsl.module

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
fun appleStorageModule(): Module =
    module {
        single<AppThemeStore> { AppleUserDefaultsAppThemeStore() }
        single<TileSizeStore> { AppleUserDefaultsTileSizeStore() }
        single<ImageQualityStore> { AppleUserDefaultsImageQualityStore() }
        single<PictureInPictureStore> { AppleUserDefaultsPictureInPictureStore() }
        single<PlayerClockStore> { AppleUserDefaultsPlayerClockStore() }
        single<LogCollectionPreferenceStore> { AppleUserDefaultsLogCollectionPreferenceStore() }
        single<DiagnosticBreadcrumbStore> { createDiagnosticBreadcrumbStore() }
        single<PreviousRunFailureStore> { ApplePreviousRunFailureStore() }
        single<PlayerDeviceSettingsStore> {
            RoomPlayerDeviceSettingsStore(
                dao = get(),
                scope = get(),
            )
        }
        single<LibrarySortStore>(createdAtStart = true) {
            AppleUserDefaultsLibrarySortStore()
        }
        single<LibraryViewPreferencesStore>(createdAtStart = true) {
            AppleUserDefaultsLibraryViewPreferencesStore()
        }
        single<GridSortStore>(createdAtStart = true) {
            AppleUserDefaultsGridSortStore()
        }
        single { get<JellyfinStoreDatabase>().jellyfinStoreDao() }
        single<LocalSubtitleFileStore> { AppleLocalSubtitleFileStore() }
        single<LocalSubtitleAssetStore> { RoomLocalSubtitleAssetStore(dao = get()) }
        single<PlaybackPreferencesStore> {
            RoomPlaybackPreferencesStore(dao = get())
        }
        single<PlaybackSelectionStore> { RoomPlaybackSelectionStore(dao = get()) }
        single<PlaybackTimingStore> { RoomPlaybackTimingStore(dao = get()) }
        single<PlayerBackendOverrideStore> {
            RoomPlayerBackendOverrideStore(dao = get())
        }
        single<SubtitleSelectionStore>(localSubtitleSelectionPersistenceQualifier) {
            RoomSubtitleSelectionStore(dao = get())
        }
        single<RecentSearchStore> {
            RoomRecentSearchStore(dao = get())
        }
        single<WatchNextSyncStore> {
            RoomWatchNextSyncStore(dao = get())
        }
    }
