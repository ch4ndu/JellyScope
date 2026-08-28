// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.AppleLocalSubtitleFileStore
import com.jellyscope.core.data.local.ApplePlaintextSecureStore
import com.jellyscope.core.data.local.ApplePreviousRunFailureStore
import com.jellyscope.core.data.local.AppleUserDefaultsAppThemeStore
import com.jellyscope.core.data.local.AppleUserDefaultsGridSortStore
import com.jellyscope.core.data.local.AppleUserDefaultsLibrarySortStore
import com.jellyscope.core.data.local.AppleUserDefaultsLibraryViewPreferencesStore
import com.jellyscope.core.data.local.AppleUserDefaultsLogCollectionPreferenceStore
import com.jellyscope.core.data.local.AppleUserDefaultsPictureInPictureStore
import com.jellyscope.core.data.local.AppleUserDefaultsTileSizeStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.GridSortStore
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
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.local.TileSizeStore
import com.jellyscope.core.data.local.WatchNextSyncStore
import com.jellyscope.core.data.local.createDiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.createJellyfinStoreDatabase
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.AppleDiagnosticsEnvironment
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.AppleDeviceProfileProvider
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.playback.AppleAVPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice

/**
 * Apple-family (iOS/tvOS) DI. Session credentials persist through the shared
 * SecureStore boundary so launch restore follows the same common path as
 * Android; credentials use the app-owned plaintext UserDefaults store.
 * Note: tvOS caps UserDefaults persistence at ~500 KB — the UserDefaults-backed
 * preference stores bound here keep their data well under that budget.
 */
fun appleCoreModule(
    diagnosticPlatform: PlaybackDiagnosticPlatform = PlaybackDiagnosticPlatform.Ios,
    displaySupportsHdr: Boolean = false,
    developerOpenSubtitlesApiKey: String? = null,
) = module {
    single {
        CoreConfig(
            enableHttpLogging = false,
            developerOpenSubtitlesApiKey = developerOpenSubtitlesApiKey,
        )
    }
    single<SecureStore> { ApplePlaintextSecureStore() }
    single<DeviceInfoProvider> { AppleDeviceInfoProvider() }
    single<DiagnosticsEnvironment> { AppleDiagnosticsEnvironment(diagnosticPlatform) }
    single<DeviceProfileProvider> {
        AppleDeviceProfileProvider(
            displaySupportsHdr = displaySupportsHdr,
            isTvOs = diagnosticPlatform == PlaybackDiagnosticPlatform.TvOs,
        )
    }
    factory<PlayerController> { (session: Session, _backend: PlayerBackend) ->
        AppleAVPlayerController(
            session = session,
            deviceInfoProvider = get(),
            clientInfo = get(),
            stateScope = get(),
            localSubtitleFileStore = get(),
            diagnosticPlatform = diagnosticPlatform,
        )
    }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<AppThemeStore> { AppleUserDefaultsAppThemeStore() }
    single<TileSizeStore> { AppleUserDefaultsTileSizeStore() }
    single<PictureInPictureStore> { AppleUserDefaultsPictureInPictureStore() }
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
    single { createJellyfinStoreDatabase() }
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

private class AppleDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String = UIDevice.currentDevice.model

    override fun newDeviceId(): String = NSUUID().UUIDString
}
