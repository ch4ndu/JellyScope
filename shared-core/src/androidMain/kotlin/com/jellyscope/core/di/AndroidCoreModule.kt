// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.jellyscope.core.data.local.AndroidDeviceInfoProvider
import com.jellyscope.core.data.local.AndroidDownloadArtifactStore
import com.jellyscope.core.data.local.AndroidDownloadDatabaseFactory
import com.jellyscope.core.data.local.AndroidKeystoreSecureStore
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadDatabaseFactory
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.createJellyfinStoreDatabase
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.domain.platform.AndroidDiagnosticsEnvironment
import com.jellyscope.core.domain.platform.DeviceInfoProvider
import com.jellyscope.core.domain.platform.DiagnosticsEnvironment
import com.jellyscope.core.domain.platform.NativeDiagnosticLogSource
import com.jellyscope.core.domain.playback.AndroidDeviceProfileProvider
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.download.AndroidDownloadScheduler
import com.jellyscope.core.download.DownloadExecutionHost
import com.jellyscope.core.download.DownloadExecutionRecovery
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.playback.AndroidAudioFocusCoordinator
import com.jellyscope.core.playback.AndroidMpvDiagnosticLogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

fun androidCoreModule(
    context: Context,
    developerOpenSubtitlesApiKey: String? = null,
) = module {
    single {
        val debuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        CoreConfig(
            enableHttpLogging = debuggable,
            developerOpenSubtitlesApiKey = developerOpenSubtitlesApiKey,
        )
    }
    single<SecureStore> { AndroidKeystoreSecureStore(context = context.applicationContext) }
    single<DeviceInfoProvider> { AndroidDeviceInfoProvider() }
    single<DiagnosticsEnvironment> {
        AndroidDiagnosticsEnvironment(appVersion = get<ClientInfo>().versionName)
    }
    single<NativeDiagnosticLogSource> { AndroidMpvDiagnosticLogSource(context = androidContext()) }
    single<DeviceProfileProvider> { AndroidDeviceProfileProvider(context = androidContext()) }
    single { AndroidAudioFocusCoordinator(context = androidContext()) }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    includes(androidStorageModule())
    single<DownloadDatabaseFactory> { AndroidDownloadDatabaseFactory(context) }
    single<DownloadArtifactStore> { AndroidDownloadArtifactStore(context) }
    single { DownloadExecutionRecovery(queueCoordinator = get(), driver = get()) }
    single {
        AndroidDownloadScheduler(
            context = context,
            driver = get(),
            queueCoordinator = get(),
            recovery = get(),
            scope = get(),
        )
    }
    single<DownloadExecutionHost> { get<AndroidDownloadScheduler>() }
    single<DownloadLifecycleHost> { get<AndroidDownloadScheduler>() }
    single { createJellyfinStoreDatabase(context.applicationContext) }
}
