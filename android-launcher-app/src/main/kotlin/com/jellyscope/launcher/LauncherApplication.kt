// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.app.Application
import com.jellyscope.core.data.local.AndroidKeystoreSecureStore
import com.jellyscope.core.data.local.SecureStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@LauncherApplication)
            modules(
                module {
                    single { Json { ignoreUnknownKeys = true } }
                    single<SecureStore> { AndroidKeystoreSecureStore(androidContext()) }
                    single { SessionStore(secureStore = get(), json = get()) }
                    single { ServerScopedStoreRegistry() }
                },
            )
        }
    }
}
