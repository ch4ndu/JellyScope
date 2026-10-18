// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.DesktopPlayerVolumeStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.GridSortStore
import com.jellyscope.core.data.local.ImageQualityStore
import com.jellyscope.core.data.local.JellyfinStoreDatabase
import com.jellyscope.core.data.local.JvmFileAppThemeStore
import com.jellyscope.core.data.local.JvmFileGridSortStore
import com.jellyscope.core.data.local.JvmFileImageQualityStore
import com.jellyscope.core.data.local.JvmFileLibrarySortStore
import com.jellyscope.core.data.local.JvmFileLibraryViewPreferencesStore
import com.jellyscope.core.data.local.JvmFileLogCollectionPreferenceStore
import com.jellyscope.core.data.local.JvmFilePictureInPictureStore
import com.jellyscope.core.data.local.JvmFilePlayerClockStore
import com.jellyscope.core.data.local.JvmFileTileSizeStore
import com.jellyscope.core.data.local.JvmFileVolumeStore
import com.jellyscope.core.data.local.JvmLocalSubtitleFileStore
import com.jellyscope.core.data.local.JvmPreviousRunFailureStore
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

fun desktopStorageModule(): Module =
    module {
        single<DesktopPlayerVolumeStore> { JvmFileVolumeStore(preferences = get()) }
        single<AppThemeStore> { JvmFileAppThemeStore(preferences = get()) }
        single<TileSizeStore> { JvmFileTileSizeStore(preferences = get()) }
        single<ImageQualityStore> { JvmFileImageQualityStore(preferences = get()) }
        single<PictureInPictureStore> { JvmFilePictureInPictureStore(preferences = get()) }
        single<PlayerClockStore> { JvmFilePlayerClockStore(preferences = get()) }
        single<LogCollectionPreferenceStore> { JvmFileLogCollectionPreferenceStore(preferences = get()) }
        single<DiagnosticBreadcrumbStore> { createDiagnosticBreadcrumbStore() }
        single<PreviousRunFailureStore> { JvmPreviousRunFailureStore() }
        single<PlayerDeviceSettingsStore> {
            RoomPlayerDeviceSettingsStore(
                dao = get(),
                scope = get(),
            )
        }
        single<LibrarySortStore>(createdAtStart = true) {
            JvmFileLibrarySortStore(preferences = get())
        }
        single<LibraryViewPreferencesStore>(createdAtStart = true) {
            JvmFileLibraryViewPreferencesStore(preferences = get())
        }
        single<GridSortStore>(createdAtStart = true) {
            JvmFileGridSortStore(preferences = get())
        }
        single { get<JellyfinStoreDatabase>().jellyfinStoreDao() }
        single<LocalSubtitleFileStore> { JvmLocalSubtitleFileStore() }
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
