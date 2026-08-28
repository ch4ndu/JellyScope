// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.android

import android.app.Application
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.di.androidCoreModule
import com.jellyscope.core.di.coreModule
import com.jellyscope.core.di.downloadsModule
import com.jellyscope.core.domain.platform.NativeDiagnosticLogSource
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.playback.androidPlaybackModule
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.configureApplicationLogWriters
import com.jellyscope.core.util.consumePreviousRunFailure
import com.jellyscope.core.util.installPreviousRunFailureHandler
import com.jellyscope.core.util.reportLatestAndroidProcessExit
import com.jellyscope.ui.di.sharedUiModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class JellyScopeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@JellyScopeApplication)
            modules(
                androidCoreModule(
                    context = this@JellyScopeApplication,
                    developerOpenSubtitlesApiKey =
                        AndroidDeveloperConfig.OPEN_SUBTITLES_API_KEY.takeIf(String::isNotBlank),
                ),
                androidAppModule,
                downloadsModule,
                coreModule,
                androidPlaybackModule(this@JellyScopeApplication),
                sharedUiModule,
            )
        }
        val koin = GlobalContext.get()
        koin.get<DownloadLifecycleHost>().start()
        val applicationScope = koin.get<CoroutineScope>()
        applicationScope.launch {
            runCatching { koin.get<DeviceProfileProvider>().capabilities() }
        }
        val preferenceStore = koin.get<LogCollectionPreferenceStore>()
        val nativeDiagnosticLogSource = koin.get<NativeDiagnosticLogSource>()
        configureApplicationLogWriters(
            diagnosticsWriter = koin.get<LogBufferStore>().logWriter,
            preferenceStore = preferenceStore,
            applicationScope = applicationScope,
            isDebugBuild = BuildConfig.DEBUG,
        )
        if (!preferenceStore.enabled.value) {
            applicationScope.launch {
                runCatching { nativeDiagnosticLogSource.clear() }
            }
        }
        consumePreviousRunFailure(
            store = koin.get<PreviousRunFailureStore>(),
            preferenceStore = preferenceStore,
            logBufferStore = koin.get<LogBufferStore>(),
        )
        installPreviousRunFailureHandler(
            store = koin.get<PreviousRunFailureStore>(),
            platform = PreviousRunFailurePlatform.Android,
        )
        reportLatestAndroidProcessExit(
            context = this,
            preferenceStore = preferenceStore,
            applicationScope = applicationScope,
        )
    }
}
