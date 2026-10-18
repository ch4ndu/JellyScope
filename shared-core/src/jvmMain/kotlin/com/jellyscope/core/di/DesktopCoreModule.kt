// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.DesktopMpvPresentationStore
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadDatabaseFactory
import com.jellyscope.core.data.local.JvmDesktopMpvPresentationStore
import com.jellyscope.core.data.local.JvmDownloadArtifactStore
import com.jellyscope.core.data.local.JvmDownloadDatabaseFactory
import com.jellyscope.core.data.local.JvmFilePreferencesStore
import com.jellyscope.core.data.local.JvmPlainFileSecureStore
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.createJellyfinStoreDatabase
import com.jellyscope.core.data.local.jellyscopeDataDirectory
import com.jellyscope.core.domain.action.SetDesktopMpvPresentationAction
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.platform.DesktopDiagnosticsEnvironment
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.playback.DesktopDeviceProfileProvider
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.usecase.ObserveDesktopMpvPresentationUseCase
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
fun desktopCoreModule(developerOpenSubtitlesApiKey: String? = null) =
    module {
        single {
            CoreConfig(
                enableHttpLogging = false,
                developerOpenSubtitlesApiKey = developerOpenSubtitlesApiKey,
            )
        }
        single<SecureStore> {
            JvmPlainFileSecureStore(
                file = File(jellyscopeDataDirectory(), DESKTOP_PLAINTEXT_SECURE_STORE_FILE_NAME),
            )
        }
        single {
            JvmFilePreferencesStore(
                file = File(jellyscopeDataDirectory(), DESKTOP_PREFERENCES_FILE_NAME),
            )
        }
        // Eager: construction freezes the process mpv runtime before any player or native probe.
        single<DesktopMpvPresentationStore>(createdAtStart = true) {
            JvmDesktopMpvPresentationStore(preferences = get())
        }
        single { ObserveDesktopMpvPresentationUseCase(store = get()) }
        single { SetDesktopMpvPresentationAction(store = get()) }
        single<DeviceInfoProvider> { DesktopDeviceInfoProvider() }
        single<DiagnosticsEnvironment> { DesktopDiagnosticsEnvironment() }
        single<DeviceProfileProvider> { DesktopDeviceProfileProvider() }
        includes(desktopStorageModule())
        factory<PlayerController> { (session: Session, backend: PlayerBackend, allowInsecureDesktopTls: Boolean) ->
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
                    allowInsecureDesktopTls = allowInsecureDesktopTls,
                ).also { controller ->
                    offlineArtifactResolver?.let(controller::setOfflineArtifactResolver)
                }
            }
        }
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
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
    }

private class DesktopDeviceInfoProvider : DeviceInfoProvider {
    override val deviceName: String = System.getProperty("os.name", "Desktop").ifBlank { "Desktop" }

    override fun newDeviceId(): String = UUID.randomUUID().toString()
}

private const val DESKTOP_PLAINTEXT_SECURE_STORE_FILE_NAME = "secure-store.json"
private const val DESKTOP_PREFERENCES_FILE_NAME = "preferences.json"

internal fun desktopMpvPresentationPreference(osName: String = System.getProperty("os.name", "")): MpvPresentationPreference =
    if (osName.contains("mac", ignoreCase = true)) {
        MpvPresentationPreference.MacOsOpenGl
    } else {
        MpvPresentationPreference.Software
    }
