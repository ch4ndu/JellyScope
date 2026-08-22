// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui

import androidx.compose.ui.window.ComposeUIViewController
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.di.appleCoreModule
import com.jellyscope.core.di.coreModule
import com.jellyscope.core.di.downloadsModule
import com.jellyscope.core.di.iosCoreModule
import com.jellyscope.core.di.iosServerDiscoveryModule
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.detectDisplaySupportsHdr
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.util.LogBufferStore
import com.jellyscope.core.util.configureApplicationLogWriters
import com.jellyscope.core.util.consumePreviousRunFailure
import com.jellyscope.core.util.installPreviousRunFailureHandler
import com.jellyscope.ui.di.sharedUiModule
import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.startKoin
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

// Local Debug builds may prefill the first server entry; Release stays empty.
private val DEFAULT_SERVER_URL = DevServerConfig.SERVER_URL.ifBlank { null }

private var koinStarted = false

private fun ensureKoin() {
    if (!koinStarted) {
        // MainViewController is the UIKit app entry; keep this launch snapshot
        // read on the main thread before the Koin graph is created.
        val displaySupportsHdr = detectDisplaySupportsHdr()
        val koinApplication =
            startKoin {
                allowOverride(true)
                modules(
                    appleCoreModule(displaySupportsHdr = displaySupportsHdr),
                    iosCoreModule,
                    downloadsModule,
                    coreModule,
                    iosServerDiscoveryModule,
                    module {
                        single { ClientInfo(versionName = bundleVersionName()) }
                        single { AppInfo(versionName = get<ClientInfo>().versionName) }
                        single { PlaybackHealthGuidancePolicy.Actionable }
                    },
                    sharedUiModule,
                )
            }
        configureApplicationLogWriters(
            diagnosticsWriter = koinApplication.koin.get<LogBufferStore>().logWriter,
            preferenceStore = koinApplication.koin.get<LogCollectionPreferenceStore>(),
            applicationScope = koinApplication.koin.get<CoroutineScope>(),
            isDebugBuild = DevServerConfig.IS_DEBUG_BUILD,
        )
        consumePreviousRunFailure(
            store = koinApplication.koin.get<PreviousRunFailureStore>(),
            preferenceStore = koinApplication.koin.get<LogCollectionPreferenceStore>(),
            logBufferStore = koinApplication.koin.get<LogBufferStore>(),
        )
        installPreviousRunFailureHandler(
            store = koinApplication.koin.get<PreviousRunFailureStore>(),
            platform = PreviousRunFailurePlatform.Ios,
        )
        koinApplication.koin.get<DownloadLifecycleHost>().start()
        koinStarted = true
    }
}

private fun bundleVersionName(): String =
    listOf("CFBundleShortVersionString", "CFBundleVersion")
        .firstNotNullOfOrNull { key ->
            NSBundle.mainBundle
                .objectForInfoDictionaryKey(key)
                ?.toString()
                ?.takeIf { value -> value.isNotBlank() }
        } ?: "dev"

@Suppress("ktlint:standard:function-naming")
fun MainViewController(): UIViewController {
    ensureKoin()
    return ComposeUIViewController {
        JellyScopeApp(
            initialServerUrl = DEFAULT_SERVER_URL,
            prefillUsername = DevServerConfig.USERNAME,
            prefillPassword = DevServerConfig.PASSWORD,
        )
    }
}
