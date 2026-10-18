// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.ANDROID_VLC_DEFAULT_TRANSCODE_BITRATE_BPS
import com.jellyscope.core.data.local.AndroidLocalSubtitleFileStore
import com.jellyscope.core.data.local.AndroidPreviousRunFailureStore
import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.GridSortStore
import com.jellyscope.core.data.local.ImageQualityStore
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
import com.jellyscope.core.data.local.RoomRecentSearchStore
import com.jellyscope.core.data.local.RoomSubtitleSelectionStore
import com.jellyscope.core.data.local.RoomWatchNextSyncStore
import com.jellyscope.core.data.local.SharedPreferencesAppThemeStore
import com.jellyscope.core.data.local.SharedPreferencesGridSortStore
import com.jellyscope.core.data.local.SharedPreferencesImageQualityStore
import com.jellyscope.core.data.local.SharedPreferencesLibrarySortStore
import com.jellyscope.core.data.local.SharedPreferencesLibraryViewPreferencesStore
import com.jellyscope.core.data.local.SharedPreferencesLogCollectionPreferenceStore
import com.jellyscope.core.data.local.SharedPreferencesPictureInPictureStore
import com.jellyscope.core.data.local.SharedPreferencesPlayerClockStore
import com.jellyscope.core.data.local.SharedPreferencesPlayerDeviceSettingsStore
import com.jellyscope.core.data.local.SharedPreferencesTileSizeStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.local.TileSizeStore
import com.jellyscope.core.data.local.WatchNextSyncStore
import com.jellyscope.core.data.local.createDiagnosticBreadcrumbStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidStorageModule(): Module =
    module {
        single<AppThemeStore> { SharedPreferencesAppThemeStore(context = androidContext()) }
        single<TileSizeStore> { SharedPreferencesTileSizeStore(context = androidContext()) }
        single<ImageQualityStore> { SharedPreferencesImageQualityStore(context = androidContext()) }
        single<PictureInPictureStore> { SharedPreferencesPictureInPictureStore(context = androidContext()) }
        single<PlayerClockStore> { SharedPreferencesPlayerClockStore(context = androidContext()) }
        single<LogCollectionPreferenceStore> { SharedPreferencesLogCollectionPreferenceStore(context = androidContext()) }
        single<DiagnosticBreadcrumbStore> { createDiagnosticBreadcrumbStore(androidContext()) }
        single<PreviousRunFailureStore> { AndroidPreviousRunFailureStore(androidContext()) }
        single<PlayerDeviceSettingsStore> { SharedPreferencesPlayerDeviceSettingsStore(context = androidContext()) }
        single<LibrarySortStore>(createdAtStart = true) {
            SharedPreferencesLibrarySortStore(androidContext())
        }
        single<LibraryViewPreferencesStore>(createdAtStart = true) {
            SharedPreferencesLibraryViewPreferencesStore(androidContext())
        }
        single<GridSortStore>(createdAtStart = true) {
            SharedPreferencesGridSortStore(androidContext())
        }
        single { get<com.jellyscope.core.data.local.JellyfinStoreDatabase>().jellyfinStoreDao() }
        single<LocalSubtitleFileStore> { AndroidLocalSubtitleFileStore(androidContext()) }
        single<LocalSubtitleAssetStore> { RoomLocalSubtitleAssetStore(dao = get()) }
        single<PlaybackPreferencesStore> {
            RoomPlaybackPreferencesStore(
                dao = get(),
                defaultVlcTranscodeBitrateBps = ANDROID_VLC_DEFAULT_TRANSCODE_BITRATE_BPS,
            )
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
