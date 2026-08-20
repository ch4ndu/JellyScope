// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import android.app.Application
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.di.androidCoreModule
import com.jellyscope.core.di.coreModule
import com.jellyscope.core.di.downloadsModule
import com.jellyscope.core.domain.platform.NativeDiagnosticLogSource
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.playback.androidPlaybackModule
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.configureApplicationLogWriters
import com.jellyscope.core.util.consumePreviousRunFailure
import com.jellyscope.core.util.installPreviousRunFailureHandler
import com.jellyscope.core.util.reportLatestAndroidProcessExit
import com.jellyscope.tv.watchnext.WatchNextAccountCacheClearableStore
import com.jellyscope.tv.watchnext.WatchNextScheduler
import com.jellyscope.ui.AppInfo
import com.jellyscope.ui.di.sharedUiModule
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.concurrent.thread

class TvApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@TvApplication)
            modules(
                androidCoreModule(this@TvApplication),
                tvAppModule,
                downloadsModule,
                coreModule,
                androidPlaybackModule(this@TvApplication),
                sharedUiModule,
            )
        }
        val koin = GlobalContext.get()
        koin.get<DownloadLifecycleHost>().start()
        val applicationScope = koin.get<CoroutineScope>()
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
            platform = PreviousRunFailurePlatform.AndroidTv,
        )
        reportLatestAndroidProcessExit(
            context = this,
            preferenceStore = preferenceStore,
            applicationScope = applicationScope,
        )

        runCatching {
            GlobalContext
                .get()
                .get<ServerScopedStoreRegistry>()
                .register(WatchNextAccountCacheClearableStore(this@TvApplication))
        }
        runCatching {
            WatchNextScheduler.schedulePeriodic(this@TvApplication)
        }

        // Warm the expensive singles OFF the main thread so neither cold
        // start nor first playback pays for them there: the Ktor/OkHttp
        // client build and the MediaCodecList enumeration behind the device
        // decoding profile are both slow on Fire TV hardware. Koin single
        // resolution is thread-safe, so racing MainActivity is harmless.
        thread(name = "startup-prewarm") {
            runCatching { koin.get<HttpClient>() }
            runCatching { koin.get<DeviceProfileProvider>().capabilities() }
        }
    }
}

internal val tvAppModule =
    module {
        single { ClientInfo(versionName = BuildConfig.VERSION_NAME) }
        single { AppInfo(versionName = get<ClientInfo>().versionName) }
        single { PlaybackHealthGuidancePolicy.Actionable }
        single<TvUiPreferencesStore> {
            SharedPreferencesTvUiPreferencesStore(androidContext())
        }
        single<ObserveTvFocusedCardZoomUseCase> { ObserveTvFocusedCardZoomUseCase { get<TvUiPreferencesStore>().focusedCardZoomEnabled } }
        single<ObserveTvLibraryGridHeroUseCase> { ObserveTvLibraryGridHeroUseCase { get<TvUiPreferencesStore>().showLibraryGridHero } }
        single<SetTvFocusedCardZoomAction> {
            SetTvFocusedCardZoomAction { enabled ->
                get<TvUiPreferencesStore>().setFocusedCardZoomEnabled(enabled)
            }
        }
        single<SetTvLibraryGridHeroAction> {
            SetTvLibraryGridHeroAction { enabled ->
                get<TvUiPreferencesStore>().setShowLibraryGridHero(enabled)
            }
        }
        viewModel { TvSettingsPreferencesViewModel(get(), get(), get(), get()) }
    }
