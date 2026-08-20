// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.local.DownloadArtifactStore
import com.jellyscope.core.data.local.DownloadDatabase
import com.jellyscope.core.data.local.DownloadDatabaseFactory
import com.jellyscope.core.data.local.DownloadRecordStore
import com.jellyscope.core.data.local.DownloadRemovalStore
import com.jellyscope.core.data.local.DownloadSettingsStore
import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.RoomDownloadRecordStore
import com.jellyscope.core.data.local.RoomDownloadRemovalStore
import com.jellyscope.core.data.local.RoomDownloadSettingsStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinClientFactory
import com.jellyscope.core.data.repository.DefaultDownloadRepository
import com.jellyscope.core.data.repository.DefaultFixedDownloadAdmission
import com.jellyscope.core.data.repository.DefaultOriginalDownloadAdmission
import com.jellyscope.core.data.repository.DownloadCommandCoordinator
import com.jellyscope.core.data.repository.DownloadQueueRepository
import com.jellyscope.core.data.repository.DownloadRemovalMutex
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.data.repository.SessionBoundaryParticipant
import com.jellyscope.core.domain.action.CancelDownloadAction
import com.jellyscope.core.domain.action.ConfigureDownloadQuotaAction
import com.jellyscope.core.domain.action.DeleteDownloadAction
import com.jellyscope.core.domain.action.EnqueueDownloadAction
import com.jellyscope.core.domain.action.EnqueueFixedDownloadAction
import com.jellyscope.core.domain.action.PauseDownloadAction
import com.jellyscope.core.domain.action.ResumeDownloadAction
import com.jellyscope.core.domain.action.RetryDownloadAction
import com.jellyscope.core.domain.action.RetryDownloadSchedulingAction
import com.jellyscope.core.domain.action.UpdateDownloadedPlaybackAction
import com.jellyscope.core.domain.usecase.DownloadRemovalAuthorizationIssuer
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReader
import com.jellyscope.core.domain.usecase.DownloadRemovalPreviewReleaser
import com.jellyscope.core.domain.usecase.FixedDownloadAdmission
import com.jellyscope.core.domain.usecase.FixedDownloadCapability
import com.jellyscope.core.domain.usecase.GetDownloadRemovalPreviewUseCase
import com.jellyscope.core.domain.usecase.GetDownloadSettingsUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUsageUseCase
import com.jellyscope.core.domain.usecase.GetDownloadUseCase
import com.jellyscope.core.domain.usecase.GetOfflinePlaybackPlanUseCase
import com.jellyscope.core.domain.usecase.IsDownloadArtifactLeasedUseCase
import com.jellyscope.core.domain.usecase.IssueDownloadRemovalAuthorizationUseCase
import com.jellyscope.core.domain.usecase.ObserveDownloadsUseCase
import com.jellyscope.core.domain.usecase.OriginalDownloadAdmission
import com.jellyscope.core.domain.usecase.PreviewFixedDownloadUseCase
import com.jellyscope.core.domain.usecase.PreviewOriginalDownloadUseCase
import com.jellyscope.core.domain.usecase.ReleaseDownloadRemovalPreviewUseCase
import com.jellyscope.core.download.DefaultDownloadExecutionDriver
import com.jellyscope.core.download.DownloadCleanupCoordinator
import com.jellyscope.core.download.DownloadExecutionDriver
import com.jellyscope.core.download.DownloadHlsTransferCoordinator
import com.jellyscope.core.download.DownloadQueueCoordinator
import com.jellyscope.core.download.DownloadTransferCoordinator
import com.jellyscope.core.playback.DefaultOfflineArtifactResolver
import com.jellyscope.core.playback.OfflineArtifactLeaseRegistry
import com.jellyscope.core.playback.OfflineArtifactResolver
import io.ktor.client.HttpClient
import org.koin.dsl.module

/** Download services included only by Android mobile/TV, iOS, and desktop entry graphs. */
val downloadsModule =
    module {
        single<HttpClient>(downloadTransferClientQualifier) {
            get<JellyfinClientFactory>().createDownloadTransfer()
        }
        single { get<DownloadDatabaseFactory>().create() }
        single { get<DownloadDatabase>().downloadDao() }
        single<DownloadSettingsStore> { RoomDownloadSettingsStore(dao = get()) }
        single<DownloadRecordStore> { RoomDownloadRecordStore(dao = get()) }
        single<DownloadRemovalStore> { RoomDownloadRemovalStore(dao = get()) }
        single { OfflineArtifactLeaseRegistry() }
        single { DownloadRemovalMutex() }
        single<OfflineArtifactResolver> {
            DefaultOfflineArtifactResolver(
                recordStore = get(),
                removalStore = get(),
                artifactStore = get(),
                leaseRegistry = get(),
            )
        }
        single {
            DefaultDownloadRepository(
                settingsStore = get(),
                recordStore = get(),
                removalStore = get(),
                artifactStore = get(),
                artifactLeaseRegistry = get(),
                removalMutex = get(),
            )
        }
        single<DownloadRepository> { get<DefaultDownloadRepository>() }
        single<DownloadQueueRepository> { get<DefaultDownloadRepository>() }
        single<OriginalDownloadAdmission> {
            DefaultOriginalDownloadAdmission(
                sessionRepository = get(),
                serverScopedStoreRegistry = get(),
                jellyfinApi = get(),
                localSubtitleAssetStore = get(),
                localSubtitleFileStore = get(),
            )
        }
        single<FixedDownloadAdmission> {
            DefaultFixedDownloadAdmission(
                sessionRepository = get(),
                serverScopedStoreRegistry = get(),
                jellyfinApi = get(),
            )
        }
        single { FixedDownloadCapability() }
        single { DownloadQueueCoordinator(repository = get()) }
        single {
            DownloadHlsTransferCoordinator(
                serverScopedStoreRegistry = get(),
                jellyfinApi = get(),
                queueCoordinator = get(),
                artifactStore = get(),
            )
        }
        single {
            DownloadTransferCoordinator(
                sessionRepository = get(),
                serverScopedStoreRegistry = get<ServerScopedStoreRegistry>(),
                jellyfinApi = get<JellyfinApi>(),
                queueCoordinator = get(),
                artifactStore = get<DownloadArtifactStore>(),
                localSubtitleAssetStore = get<LocalSubtitleAssetStore>(),
                localSubtitleFileStore = get<LocalSubtitleFileStore>(),
                hlsTransferCoordinator = get(),
            )
        }
        single<DownloadExecutionDriver> {
            DefaultDownloadExecutionDriver(
                sessionRepository = get(),
                queueCoordinator = get(),
                transferCoordinator = get(),
            )
        }
        single<DownloadCommandCoordinator> { get<DownloadQueueCoordinator>() }
        single {
            DownloadCleanupCoordinator(
                removalStore = get(),
                queueRepository = get(),
                queueCoordinator = get(),
                artifactStore = get(),
                artifactLeaseRegistry = get(),
                removalMutex = get(),
                serverScopedStoreRegistry = get(),
            )
        }
        single<SessionBoundaryParticipant> { get<DownloadCleanupCoordinator>() }

        single { ObserveDownloadsUseCase(repository = get()) }
        single { GetDownloadUseCase(repository = get()) }
        single { GetOfflinePlaybackPlanUseCase(repository = get()) }
        single { GetDownloadSettingsUseCase(repository = get()) }
        single { GetDownloadUsageUseCase(repository = get()) }
        single { IsDownloadArtifactLeasedUseCase(repository = get()) }
        single<DownloadRemovalPreviewReader> { GetDownloadRemovalPreviewUseCase(cleanupCoordinator = get()) }
        single<DownloadRemovalAuthorizationIssuer> { IssueDownloadRemovalAuthorizationUseCase(cleanupCoordinator = get()) }
        single<DownloadRemovalPreviewReleaser> { ReleaseDownloadRemovalPreviewUseCase(cleanupCoordinator = get()) }
        single { PreviewOriginalDownloadUseCase(admission = get()) }
        single { PreviewFixedDownloadUseCase(admission = get()) }

        single { ConfigureDownloadQuotaAction(repository = get()) }
        single { EnqueueDownloadAction(repository = get(), lifecycleHost = get(), admission = get()) }
        single { EnqueueFixedDownloadAction(repository = get(), lifecycleHost = get(), admission = get()) }
        single { PauseDownloadAction(commandCoordinator = get(), lifecycleHost = get()) }
        single { ResumeDownloadAction(repository = get(), lifecycleHost = get()) }
        single { RetryDownloadAction(repository = get(), lifecycleHost = get()) }
        single { RetryDownloadSchedulingAction(lifecycleHost = get()) }
        single { CancelDownloadAction(commandCoordinator = get(), lifecycleHost = get()) }
        single { DeleteDownloadAction(repository = get()) }
        single { UpdateDownloadedPlaybackAction(repository = get()) }
    }
