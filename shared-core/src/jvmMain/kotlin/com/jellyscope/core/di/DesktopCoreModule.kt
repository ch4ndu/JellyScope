// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.AppThemeStore
import com.jellyscope.core.data.local.DesktopPlayerVolumeStore
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadDatabaseFactory
import com.jellyscope.core.data.local.GridSortStore
import com.jellyscope.core.data.local.JellyfinStoreDatabase
import com.jellyscope.core.data.local.JvmDownloadArtifactStore
import com.jellyscope.core.data.local.JvmDownloadDatabaseFactory
import com.jellyscope.core.data.local.JvmFileAppThemeStore
import com.jellyscope.core.data.local.JvmFileGridSortStore
import com.jellyscope.core.data.local.JvmFileLibrarySortStore
import com.jellyscope.core.data.local.JvmFileLibraryViewPreferencesStore
import com.jellyscope.core.data.local.JvmFileLogCollectionPreferenceStore
import com.jellyscope.core.data.local.JvmFilePictureInPictureStore
import com.jellyscope.core.data.local.JvmFilePreferencesStore
import com.jellyscope.core.data.local.JvmFileTileSizeStore
import com.jellyscope.core.data.local.JvmFileVolumeStore
import com.jellyscope.core.data.local.JvmLocalSubtitleFileStore
import com.jellyscope.core.data.local.JvmPlainFileSecureStore
import com.jellyscope.core.data.local.JvmPreviousRunFailureStore
import com.jellyscope.core.data.local.LibrarySortStore
import com.jellyscope.core.data.local.LibraryViewPreferencesStore
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.MacKeychainSecureStore
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
import com.jellyscope.core.data.local.jellyscopeDataDirectory
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.DesktopDiagnosticsEnvironment
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.DesktopDeviceProfileProvider
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.download.DownloadExecutionHost
import com.jellyscope.core.download.DownloadExecutionRecovery
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.download.JvmDownloadLifecycleHost
import com.jellyscope.core.playback.DesktopLibVlcPlayerController
import com.jellyscope.core.playback.MpvPlayerController
import com.jellyscope.core.playback.MpvPresentationPreference
import com.jellyscope.core.playback.OfflineArtifactResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import java.io.File
import java.util.UUID

/** Desktop DI for the browse shell and its platform-specific persistence. */
fun desktopCoreModule() =
    module {
        single { CoreConfig(enableHttpLogging = false) }
        single<SecureStore> {
            val legacyFile = File(jellyscopeDataDirectory(), DESKTOP_PLAINTEXT_SECURE_STORE_FILE_NAME)
            if (isMacOs()) {
                MacKeychainSecureStore(legacyFile = legacyFile)
            } else {
                // Explicit development-only fallback for non-macOS JVM hosts.
                JvmPlainFileSecureStore(file = legacyFile)
            }
        }
        single {
            JvmFilePreferencesStore(
                file = File(jellyscopeDataDirectory(), DESKTOP_PREFERENCES_FILE_NAME),
            )
        }
        single<DeviceInfoProvider> { DesktopDeviceInfoProvider() }
        single<DiagnosticsEnvironment> { DesktopDiagnosticsEnvironment() }
        single<DeviceProfileProvider> { DesktopDeviceProfileProvider() }
        single<DesktopPlayerVolumeStore> { JvmFileVolumeStore(preferences = get()) }
        factory<PlayerController> { (session: Session, backend: PlayerBackend) ->
            val offlineArtifactResolver = getOrNull<OfflineArtifactResolver>()
            if (backend == PlayerBackend.LibVlc) {
                DesktopLibVlcPlayerController(
                    session = session,
                    stateScope = get(),
                    localSubtitleFileStore = get(),
                    volumeStore = get(),
                ).also { controller ->
                    offlineArtifactResolver?.let(controller::setOfflineArtifactResolver)
                }
            } else {
                MpvPlayerController(
                    session = session,
                    stateScope = get(),
                    localSubtitleFileStore = get(),
                    volumeStore = get(),
                    presentationPreference = desktopMpvPresentationPreference(),
                ).also { controller ->
                    offlineArtifactResolver?.let(controller::setOfflineArtifactResolver)
                }
            }
        }
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        single<AppThemeStore> { JvmFileAppThemeStore(preferences = get()) }
        single<TileSizeStore> { JvmFileTileSizeStore(preferences = get()) }
        single<PictureInPictureStore> { JvmFilePictureInPictureStore(preferences = get()) }
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
        single<DownloadDatabaseFactory> { JvmDownloadDatabaseFactory() }
        single<DownloadArtifactStore> { JvmDownloadArtifactStore() }
        single { DownloadExecutionRecovery(queueCoordinator = get(), driver = get()) }
        single {
            JvmDownloadLifecycleHost(
                driver = get(),
                recovery = get(),
                scope = get(),
            )
        }
        single<DownloadExecutionHost> { get<JvmDownloadLifecycleHost>() }
        single<DownloadLifecycleHost> { get<JvmDownloadLifecycleHost>() }
        single { createJellyfinStoreDatabase() }
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

private class DesktopDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String = System.getProperty("os.name", "Desktop").ifBlank { "Desktop" }

    override fun newDeviceId(): String = UUID.randomUUID().toString()
}

private const val DESKTOP_PLAINTEXT_SECURE_STORE_FILE_NAME = "secure-store.json"
private const val DESKTOP_PREFERENCES_FILE_NAME = "preferences.json"

private fun isMacOs(): Boolean = System.getProperty("os.name", "").contains("mac", ignoreCase = true)

internal fun desktopMpvPresentationPreference(osName: String = System.getProperty("os.name", "")): MpvPresentationPreference =
    if (osName.contains("mac", ignoreCase = true)) {
        MpvPresentationPreference.MacOsOpenGl
    } else {
        MpvPresentationPreference.Software
    }
