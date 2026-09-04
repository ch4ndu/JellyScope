// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadDatabaseFactory
import com.jellyscope.core.data.local.IosDownloadArtifactStore
import com.jellyscope.core.data.local.IosDownloadDatabaseFactory
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.download.DownloadExecutionHost
import com.jellyscope.core.download.DownloadExecutionRecovery
import com.jellyscope.core.download.DownloadLifecycleHost
import com.jellyscope.core.download.IosDownloadLifecycleHost
import com.jellyscope.core.playback.AppleAVPlayerController
import com.jellyscope.core.playback.OfflineArtifactResolver
import com.jellyscope.core.playback.VlcKitPlayerController
import org.koin.dsl.module

/** iOS-only player routing for the selected backend. */
val iosCoreModule =
    module {
        single<DownloadDatabaseFactory> { IosDownloadDatabaseFactory() }
        single<DownloadArtifactStore> { IosDownloadArtifactStore() }
        single { DownloadExecutionRecovery(queueCoordinator = get(), driver = get()) }
        single {
            IosDownloadLifecycleHost(
                driver = get(),
                recovery = get(),
                scope = get(),
            )
        }
        single<DownloadExecutionHost> { get<IosDownloadLifecycleHost>() }
        single<DownloadLifecycleHost> { get<IosDownloadLifecycleHost>() }
        factory<PlayerController> { (session: Session, backend: PlayerBackend, _allowInsecureDesktopTls: Boolean) ->
            val offlineArtifactResolver = getOrNull<OfflineArtifactResolver>()
            when (backend) {
                PlayerBackend.VlcKit ->
                    VlcKitPlayerController(
                        session = session,
                        stateScope = get(),
                        localSubtitleFileStore = get(),
                        diagnosticPlatform = PlaybackDiagnosticPlatform.Ios,
                    ).also { controller ->
                        offlineArtifactResolver?.let(controller::setOfflineArtifactResolver)
                    }

                PlayerBackend.Auto,
                PlayerBackend.AVPlayer,
                PlayerBackend.ExoPlayer,
                PlayerBackend.Mpv,
                PlayerBackend.LibVlc,
                ->
                    AppleAVPlayerController(
                        session = session,
                        deviceInfoProvider = get(),
                        clientInfo = get(),
                        stateScope = get(),
                        localSubtitleFileStore = get(),
                        diagnosticPlatform = PlaybackDiagnosticPlatform.Ios,
                    ).also { controller ->
                        offlineArtifactResolver?.let(controller::setOfflineArtifactResolver)
                    }
            }
        }
    }
