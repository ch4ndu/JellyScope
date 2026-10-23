// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.app.Application
import com.jellyscope.core.data.local.AndroidDeviceInfoProvider
import com.jellyscope.core.data.local.AndroidKeystoreSecureStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.data.remote.DefaultImageAuthHeaderProvider
import com.jellyscope.core.data.remote.KtorJellyfinApi
import com.jellyscope.core.data.remote.SessionAuthHeaderProvider
import com.jellyscope.core.data.remote.configureJellyfinApi
import com.jellyscope.core.data.repository.DefaultAuthRepository
import com.jellyscope.core.data.repository.DefaultSessionRepository
import com.jellyscope.core.data.repository.SessionTransitionCoordinator
import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.action.ValidateServerAction
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val json = Json { ignoreUnknownKeys = true }
        val registry = ServerScopedStoreRegistry()
        val store = SessionStore(AndroidKeystoreSecureStore(this), json)
        val snapshots = LauncherSnapshotStore(this, registry, json)
        val device = AndroidDeviceInfoProvider()
        val clientInfo = ClientInfo(BuildConfig.VERSION_NAME, "JellyScope Launcher")
        val images = LauncherImages(this, registry, DefaultImageAuthHeaderProvider(device, clientInfo))
        // Both cleanup participants exist before restoration can publish a session.
        registry.register(snapshots)
        registry.register(images)
        val cleaner = LauncherAccountStoreCleaner(store, snapshots, images)
        val coordinator = SessionTransitionCoordinator(registry, cleaner, store)
        val sessions = DefaultSessionRepository(store, coordinator, scope)
        val client =
            HttpClient(OkHttp) {
                configureJellyfinApi(followRedirects = false, enableHttpLogging = false)
                engine {
                    config {
                        followRedirects(false)
                        followSslRedirects(false)
                    }
                }
            }
        val authorization = SessionAuthHeaderProvider(store, device, clientInfo)
        val api =
            KtorJellyfinApi(
                client,
                authorization,
                preserveForbiddenStatus = true,
            )
        val auth = DefaultAuthRepository(api, store, sessions, coordinator, device)
        val configurations = LauncherConfigurationRepository(client, authorization, json)
        val media = LauncherMediaRepository(api, sessions, registry, snapshots, images, configurations, scope)
        val apps = LauncherAppsRepository(this, json)
        val authMutation = Mutex()
        startKoin {
            androidContext(this@LauncherApplication)
            modules(
                module {
                    single { images }
                    single { apps }
                    viewModel {
                        LauncherViewModel(
                            sessions,
                            coordinator,
                            LoginAction(auth),
                            ValidateServerAction(auth),
                            LogoutAction(auth),
                            media,
                            apps,
                            cleaner,
                            authMutation,
                        )
                    }
                },
            )
        }
    }
}
