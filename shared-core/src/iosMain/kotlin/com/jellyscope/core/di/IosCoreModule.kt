// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.AppleDownloadArtifactStore
import com.jellyscope.core.data.local.AppleDownloadDatabaseFactory
import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadDatabaseFactory
import com.jellyscope.core.download.AppleDownloadLifecycleHost
import com.jellyscope.core.download.DownloadExecutionHost
import com.jellyscope.core.download.DownloadExecutionRecovery
import com.jellyscope.core.download.DownloadLifecycleHost
import org.koin.dsl.module

/** iOS download storage and app-active transfer bindings. */
val iosCoreModule =
    module {
        single<DownloadDatabaseFactory> { AppleDownloadDatabaseFactory() }
        single<DownloadArtifactStore> { AppleDownloadArtifactStore() }
        single { DownloadExecutionRecovery(queueCoordinator = get(), driver = get()) }
        single {
            AppleDownloadLifecycleHost(
                driver = get(),
                recovery = get(),
                scope = get(),
            )
        }
        single<DownloadExecutionHost> { get<AppleDownloadLifecycleHost>() }
        single<DownloadLifecycleHost> { get<AppleDownloadLifecycleHost>() }
    }
