// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

import com.jellyscope.core.data.discovery.KtorUdpServerDiscovery
import com.jellyscope.core.data.local.DetailRelatedCache
import com.jellyscope.core.data.local.DiagnosticBreadcrumbStore
import com.jellyscope.core.data.local.DiscoveryCache
import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.NoOpPreviousRunFailureStore
import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore
import com.jellyscope.core.data.local.PersistentAccountStoreCleaner
import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.PlaybackTimingStore
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.local.SessionStore
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.data.remote.ClientInfo
import com.jellyscope.core.data.remote.DefaultImageAuthHeaderProvider
import com.jellyscope.core.data.remote.ImageAuthHeaderProvider
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinClientFactory
import com.jellyscope.core.data.remote.KtorJellyfinApi
import com.jellyscope.core.data.remote.OpenSubtitlesApi
import com.jellyscope.core.data.remote.SessionAuthHeaderProvider
import com.jellyscope.core.data.repository.AuthRepository
import com.jellyscope.core.data.repository.DefaultAuthRepository
import com.jellyscope.core.data.repository.DefaultLocalSubtitleSyncRepository
import com.jellyscope.core.data.repository.DefaultMediaRepository
import com.jellyscope.core.data.repository.DefaultOpenSubtitlesRepository
import com.jellyscope.core.data.repository.DefaultPlaybackProgressReporter
import com.jellyscope.core.data.repository.DefaultSessionRepository
import com.jellyscope.core.data.repository.DownloadRepository
import com.jellyscope.core.data.repository.JellyfinLocalSubtitleSyncApi
import com.jellyscope.core.data.repository.LocalSubtitleStorageReconciler
import com.jellyscope.core.data.repository.LocalSubtitleSyncApi
import com.jellyscope.core.data.repository.LocalSubtitleSyncCoordinator
import com.jellyscope.core.data.repository.LocalSubtitleSyncRepository
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.data.repository.NoOpSessionBoundaryParticipant
import com.jellyscope.core.data.repository.OpenSubtitlesRepository
import com.jellyscope.core.data.repository.RoutingPlaybackProgressReporter
import com.jellyscope.core.data.repository.SessionBoundaryParticipant
import com.jellyscope.core.data.repository.SessionRepository
import com.jellyscope.core.data.repository.SessionTransitionCoordinator
import com.jellyscope.core.domain.action.AddAccountAction
import com.jellyscope.core.domain.action.AddRecentSearchAction
import com.jellyscope.core.domain.action.ClearLocalSubtitlesAction
import com.jellyscope.core.domain.action.ClearRecentSearchesAction
import com.jellyscope.core.domain.action.DeleteLocalSubtitleAction
import com.jellyscope.core.domain.action.DeletePlaybackSelectionAction
import com.jellyscope.core.domain.action.DeletePlayerBackendOverrideAction
import com.jellyscope.core.domain.action.DownloadAndInstallOpenSubtitleAction
import com.jellyscope.core.domain.action.InstallLocalSubtitleAction
import com.jellyscope.core.domain.action.LoginAction
import com.jellyscope.core.domain.action.LogoutAction
import com.jellyscope.core.domain.action.QuickConnectLoginAction
import com.jellyscope.core.domain.action.RetryLocalSubtitleSyncAction
import com.jellyscope.core.domain.action.SavePlaybackPreferencesAction
import com.jellyscope.core.domain.action.SavePlaybackSelectionAction
import com.jellyscope.core.domain.action.SavePlaybackTimingOffsetAction
import com.jellyscope.core.domain.action.SavePlayerBackendOverrideAction
import com.jellyscope.core.domain.action.SavePlayerDeviceSettingsAction
import com.jellyscope.core.domain.action.SaveSubtitleSelectionAction
import com.jellyscope.core.domain.action.SendClientLogsAction
import com.jellyscope.core.domain.action.SetAppThemeAction
import com.jellyscope.core.domain.action.SetGridSortAction
import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.action.SetLastLibraryIdAction
import com.jellyscope.core.domain.action.SetLibrarySortAction
import com.jellyscope.core.domain.action.SetLogCollectionEnabledAction
import com.jellyscope.core.domain.action.SetOpenSubtitleResultPreferenceAction
import com.jellyscope.core.domain.action.SetOpenSubtitlesApiKeyAction
import com.jellyscope.core.domain.action.SetPictureInPictureEnabledAction
import com.jellyscope.core.domain.action.SetPlaybackInfoAtStartEnabledAction
import com.jellyscope.core.domain.action.SetRememberLastLibraryViewAction
import com.jellyscope.core.domain.action.SetSavedLibraryViewAction
import com.jellyscope.core.domain.action.SetTileSizeAction
import com.jellyscope.core.domain.action.SetVerboseLogcatEnabledAction
import com.jellyscope.core.domain.action.SignOutAccountAction
import com.jellyscope.core.domain.action.SwitchAccountAction
import com.jellyscope.core.domain.action.ValidateServerAction
import com.jellyscope.core.domain.discovery.ServerDiscovery
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.platform.NativeDiagnosticLogSource
import com.jellyscope.core.domain.playback.DirectPlayPlanner
import com.jellyscope.core.domain.playback.PlaybackInfoPlanner
import com.jellyscope.core.domain.playback.PlaybackProgressReporter
import com.jellyscope.core.domain.usecase.DiscoverServersUseCase
import com.jellyscope.core.domain.usecase.DownloadOpenSubtitleUseCase
import com.jellyscope.core.domain.usecase.FindPersonsUseCase
import com.jellyscope.core.domain.usecase.GetAvailablePlayerBackendsUseCase
import com.jellyscope.core.domain.usecase.GetChronologicalEpisodeQueueUseCase
import com.jellyscope.core.domain.usecase.GetCollectionItemsUseCase
import com.jellyscope.core.domain.usecase.GetCollectionsUseCase
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetFavoritesUseCase
import com.jellyscope.core.domain.usecase.GetGenreItemsUseCase
import com.jellyscope.core.domain.usecase.GetGenresUseCase
import com.jellyscope.core.domain.usecase.GetGridSortUseCase
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetItemsByIdsUseCase
import com.jellyscope.core.domain.usecase.GetLastLibraryIdUseCase
import com.jellyscope.core.domain.usecase.GetLibraryFiltersUseCase
import com.jellyscope.core.domain.usecase.GetLibraryItemsUseCase
import com.jellyscope.core.domain.usecase.GetLibraryRecommendationSectionUseCase
import com.jellyscope.core.domain.usecase.GetLibraryShuffleQueueUseCase
import com.jellyscope.core.domain.usecase.GetLibrarySortUseCase
import com.jellyscope.core.domain.usecase.GetLocalSubtitleAssetUseCase
import com.jellyscope.core.domain.usecase.GetLogCollectionStateUseCase
import com.jellyscope.core.domain.usecase.GetMediaSegmentsUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetOpenSubtitleResultPreferenceUseCase
import com.jellyscope.core.domain.usecase.GetOpenSubtitlesApiKeyUseCase
import com.jellyscope.core.domain.usecase.GetPersonItemsPageUseCase
import com.jellyscope.core.domain.usecase.GetPersonItemsUseCase
import com.jellyscope.core.domain.usecase.GetPersonUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackPreferencesUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionsUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackTimingOffsetUseCase
import com.jellyscope.core.domain.usecase.GetPlayerBackendOverrideUseCase
import com.jellyscope.core.domain.usecase.GetPlayerDevicePolicyUseCase
import com.jellyscope.core.domain.usecase.GetRecentSearchesUseCase
import com.jellyscope.core.domain.usecase.GetRecentlyAddedUseCase
import com.jellyscope.core.domain.usecase.GetRelatedItemsUseCase
import com.jellyscope.core.domain.usecase.GetRibbonItemsUseCase
import com.jellyscope.core.domain.usecase.GetSavedLibraryViewUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.GetStudioItemsUseCase
import com.jellyscope.core.domain.usecase.GetStudiosUseCase
import com.jellyscope.core.domain.usecase.GetSubtitleSelectionUseCase
import com.jellyscope.core.domain.usecase.GetSubtitleSelectionsUseCase
import com.jellyscope.core.domain.usecase.GetSuggestionsUseCase
import com.jellyscope.core.domain.usecase.GetUpcomingEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetUserLibrariesUseCase
import com.jellyscope.core.domain.usecase.GetVerboseLogcatStateUseCase
import com.jellyscope.core.domain.usecase.ObserveAccountsUseCase
import com.jellyscope.core.domain.usecase.ObserveAppThemeUseCase
import com.jellyscope.core.domain.usecase.ObserveLocalSubtitleAssetsUseCase
import com.jellyscope.core.domain.usecase.ObservePictureInPictureEnabledUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import com.jellyscope.core.domain.usecase.ObservePlayerDeviceSettingsUseCase
import com.jellyscope.core.domain.usecase.ObserveRememberLastLibraryViewUseCase
import com.jellyscope.core.domain.usecase.ObserveSessionStateUseCase
import com.jellyscope.core.domain.usecase.ObserveTileSizeUseCase
import com.jellyscope.core.domain.usecase.RefreshPlayerDevicePolicyUseCase
import com.jellyscope.core.domain.usecase.SearchLibraryUseCase
import com.jellyscope.core.domain.usecase.SearchOpenSubtitlesUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.util.LogBufferStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.dsl.module

internal val downloadTransferClientQualifier = named("DownloadTransfer")

val coreModule =
    module {
        single {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        single { JellyfinClientFactory(enableHttpLogging = get<CoreConfig>().enableHttpLogging) }
        single<HttpClient> { get<JellyfinClientFactory>().create() }
        single<HttpClient>(named("OpenSubtitlesApi")) {
            JellyfinClientFactory(enableHttpLogging = false).create(followRedirects = false)
        }
        single<HttpClient>(named("OpenSubtitlesDownload")) {
            JellyfinClientFactory(enableHttpLogging = false).create(followRedirects = false)
        }
        single { OpenSubtitlesSettingsStore(secureStore = get()) }
        single {
            OpenSubtitlesApi(
                apiClient = get(named("OpenSubtitlesApi")),
                downloadClient = get(named("OpenSubtitlesDownload")),
                userAgent = "${get<ClientInfo>().clientName} v${get<ClientInfo>().versionName}",
            )
        }
        single<OpenSubtitlesRepository> { DefaultOpenSubtitlesRepository(api = get(), settings = get()) }
        single<LocalSubtitleSyncApi> { JellyfinLocalSubtitleSyncApi(api = get()) }
        single<LocalSubtitleSyncRepository> {
            DefaultLocalSubtitleSyncRepository(api = get(), assetStore = get(), fileStore = get())
        }
        single(createdAtStart = true) {
            LocalSubtitleSyncCoordinator(
                sessionRepository = get(),
                assetStore = get(),
                syncRepository = get(),
                scope = get(),
            )
        }
        single(createdAtStart = true) {
            LocalSubtitleStorageReconciler(
                assetStore = get(),
                fileStore = get(),
                selectionStore = get(),
                scope = get(),
            )
        }
        single { ServerScopedStoreRegistry() }
        single {
            PlaybackDiagnosticsContext().also { context ->
                get<ServerScopedStoreRegistry>().register(context)
            }
        }
        single {
            DiscoveryCache(serverScopedStoreRegistry = get()).also { cache ->
                get<ServerScopedStoreRegistry>().register(cache)
            }
        }
        single {
            DetailRelatedCache(serverScopedStoreRegistry = get()).also { cache ->
                get<ServerScopedStoreRegistry>().register(cache)
            }
        }
        single { SessionStore(secureStore = get(), json = get()) }
        single<AuthHeaderProvider> {
            SessionAuthHeaderProvider(
                sessionStore = get(),
                deviceInfoProvider = get(),
                clientInfo = get<ClientInfo>(),
            )
        }
        single<ImageAuthHeaderProvider> {
            DefaultImageAuthHeaderProvider(
                deviceInfoProvider = get(),
                clientInfo = get<ClientInfo>(),
            )
        }
        single<JellyfinApi> {
            KtorJellyfinApi(
                client = get(),
                authHeaderProvider = get(),
                downloadClient = getOrNull<HttpClient>(downloadTransferClientQualifier) ?: get(),
            )
        }
        single {
            PersistentAccountStoreCleaner(
                sessionStore = get(),
                playbackPreferencesStore = get(),
                playerBackendOverrideStore = get(),
                recentSearchStore = get(),
                watchNextSyncStore = get(),
                subtitleSelectionStore = get(),
                librarySortStore = get(),
                libraryViewPreferencesStore = get(),
                gridSortStore = get(),
                playbackSelectionStore = get(),
                playbackTimingStore = get(),
            )
        }
        single {
            SessionTransitionCoordinator(
                serverScopedStoreRegistry = get(),
                persistentAccountStoreCleaner = get(),
                sessionStore = get(),
                sessionBoundaryParticipant =
                    getOrNull<SessionBoundaryParticipant>() ?: NoOpSessionBoundaryParticipant,
            )
        }
        single<SessionRepository> {
            DefaultSessionRepository(
                sessionStore = get(),
                sessionTransitionCoordinator = get(),
                scope = get(),
            )
        }
        single<AuthRepository> {
            DefaultAuthRepository(
                jellyfinApi = get(),
                sessionStore = get(),
                sessionRepository = get(),
                sessionTransitionCoordinator = get(),
                deviceInfoProvider = get(),
            )
        }
        single<MediaRepository> {
            DefaultMediaRepository(
                jellyfinApi = get(),
                sessionRepository = get(),
                deviceProfileProvider = get(),
                playerDeviceSettingsStore = get(),
                discoveryCache = get(),
                detailRelatedCache = get(),
            )
        }
        single {
            LogBufferStore(
                preferenceStore = get<LogCollectionPreferenceStore>(),
                breadcrumbStore = getOrNull<DiagnosticBreadcrumbStore>(),
                ownerScope = getOrNull<CoroutineScope>() ?: CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            )
        }
        single<ServerDiscovery> { KtorUdpServerDiscovery(json = get()) }
        single { JellyfinImageUrlBuilder() }
        single { DirectPlayPlanner() }
        single {
            PlaybackInfoPlanner(
                mediaRepository = get(),
                directPlayPlanner = get(),
                deviceProfileProvider = get(),
                playbackDiagnosticsContext = get(),
                playerDeviceSettingsStore = get<PlayerDeviceSettingsStore>(),
            )
        }
        single<PlaybackProgressReporter> {
            val remoteReporter = DefaultPlaybackProgressReporter(jellyfinApi = get())
            val downloadRepository = getOrNull<DownloadRepository>()
            if (downloadRepository == null) {
                remoteReporter
            } else {
                RoutingPlaybackProgressReporter(
                    downloadRepository = downloadRepository,
                    remoteReporter = remoteReporter,
                    sessionRepository = get(),
                )
            }
        }
        single { PlaybackStopSettlementRegistry() }
        single { ObserveSessionStateUseCase(sessionRepository = get()) }
        single { ObservePlaybackStopSettlementUseCase(registry = get()) }
        single { GetUserLibrariesUseCase(mediaRepository = get()) }
        single { GetContinueWatchingUseCase(mediaRepository = get()) }
        single { GetNextUpUseCase(mediaRepository = get()) }
        single { GetItemDetailUseCase(mediaRepository = get()) }
        single { GetRelatedItemsUseCase(mediaRepository = get()) }
        single { GetSeriesSeasonsUseCase(mediaRepository = get()) }
        single { GetSeasonEpisodesUseCase(mediaRepository = get()) }
        single {
            GetChronologicalEpisodeQueueUseCase(
                getSeriesSeasonsUseCase = get(),
                getSeasonEpisodesUseCase = get(),
            )
        }
        single { GetRecentlyAddedUseCase(mediaRepository = get()) }
        single { GetUpcomingEpisodesUseCase(mediaRepository = get()) }
        single { GetFavoritesUseCase(mediaRepository = get()) }
        single { GetRibbonItemsUseCase(mediaRepository = get()) }
        single { GetLibraryItemsUseCase(mediaRepository = get()) }
        single { GetLibraryShuffleQueueUseCase(mediaRepository = get()) }
        single { GetLibraryFiltersUseCase(mediaRepository = get()) }
        single { GetLibraryRecommendationSectionUseCase(mediaRepository = get()) }
        single { GetGenresUseCase(mediaRepository = get()) }
        single { GetStudiosUseCase(mediaRepository = get()) }
        single { GetMediaSegmentsUseCase(mediaRepository = get()) }
        single { GetCollectionsUseCase(mediaRepository = get()) }
        single { GetCollectionItemsUseCase(mediaRepository = get()) }
        single { GetGenreItemsUseCase(mediaRepository = get()) }
        single { GetStudioItemsUseCase(mediaRepository = get()) }
        single { GetPersonItemsUseCase(mediaRepository = get()) }
        single { GetPersonUseCase(mediaRepository = get()) }
        single { GetPersonItemsPageUseCase(mediaRepository = get()) }
        single { GetSuggestionsUseCase(mediaRepository = get()) }
        single { GetItemsByIdsUseCase(mediaRepository = get()) }
        single { SearchLibraryUseCase(mediaRepository = get()) }
        single { FindPersonsUseCase(mediaRepository = get()) }
        single { SearchOpenSubtitlesUseCase(repository = get()) }
        single { DownloadOpenSubtitleUseCase(repository = get()) }
        single { DiscoverServersUseCase(serverDiscovery = get()) }
        single { ObserveAppThemeUseCase(appThemeStore = get()) }
        single { GetLogCollectionStateUseCase(preferenceStore = get(), logBufferStore = get()) }
        single { GetVerboseLogcatStateUseCase(preferenceStore = get()) }
        single { GetPlaybackInfoAtStartStateUseCase(preferenceStore = get()) }
        single { ObserveTileSizeUseCase(tileSizeStore = get()) }
        single { ObserveRememberLastLibraryViewUseCase(store = get()) }
        single { GetSavedLibraryViewUseCase(store = get()) }
        single { GetLastLibraryIdUseCase(store = get()) }
        single { ObservePictureInPictureEnabledUseCase(pictureInPictureStore = get()) }
        single { ObservePlayerDeviceSettingsUseCase(playerDeviceSettingsStore = get()) }
        single {
            GetPlayerDevicePolicyUseCase(
                deviceProfileProvider = get(),
                playerDeviceSettingsStore = get(),
            )
        }
        single {
            RefreshPlayerDevicePolicyUseCase(
                deviceProfileProvider = get(),
                playerDeviceSettingsStore = get(),
            )
        }
        single { GetAvailablePlayerBackendsUseCase(deviceProfileProvider = get()) }
        single { GetPlaybackPreferencesUseCase(playbackPreferencesStore = get()) }
        single { GetPlaybackSelectionUseCase(store = get<PlaybackSelectionStore>()) }
        single {
            GetPlaybackLaunchContextUseCase(
                getPlaybackPreferences = get(),
                getPlaybackSelection = get(),
                getSubtitleSelection = get(),
            )
        }
        single { GetPlaybackSelectionsUseCase(store = get<PlaybackSelectionStore>()) }
        single { GetPlaybackTimingOffsetUseCase(store = get<PlaybackTimingStore>()) }
        single { GetPlayerBackendOverrideUseCase(playerBackendOverrideStore = get()) }
        single { GetSubtitleSelectionUseCase(store = get()) }
        single { GetSubtitleSelectionsUseCase(store = get()) }
        single { GetLocalSubtitleAssetUseCase(assetStore = get(), fileStore = get()) }
        single { ObserveLocalSubtitleAssetsUseCase(assetStore = get()) }
        single { GetGridSortUseCase(gridSortStore = get()) }
        single { GetLibrarySortUseCase(librarySortStore = get()) }
        single { GetRecentSearchesUseCase(recentSearchStore = get()) }
        single { ValidateServerAction(authRepository = get()) }
        single { LoginAction(authRepository = get()) }
        single { QuickConnectLoginAction(authRepository = get()) }
        single { AddAccountAction(authRepository = get()) }
        single { SwitchAccountAction(sessionRepository = get()) }
        single { SignOutAccountAction(authRepository = get()) }
        single { LogoutAction(authRepository = get()) }
        single { SetAppThemeAction(appThemeStore = get()) }
        single { SetTileSizeAction(tileSizeStore = get()) }
        single { SetRememberLastLibraryViewAction(store = get()) }
        single { SetSavedLibraryViewAction(store = get()) }
        single { SetLastLibraryIdAction(store = get()) }
        single { SetPictureInPictureEnabledAction(pictureInPictureStore = get()) }
        single { SavePlayerDeviceSettingsAction(playerDeviceSettingsStore = get()) }
        single { SavePlaybackPreferencesAction(playbackPreferencesStore = get()) }
        single { SavePlaybackSelectionAction(store = get(), scope = get()) }
        single { DeletePlaybackSelectionAction(store = get()) }
        single { DeletePlayerBackendOverrideAction(store = get()) }
        single { SavePlayerBackendOverrideAction(store = get()) }
        single { SavePlaybackTimingOffsetAction(store = get(), scope = get()) }
        single { SaveSubtitleSelectionAction(store = get(), scope = get()) }
        single { InstallLocalSubtitleAction(assetStore = get(), fileStore = get(), saveSubtitleSelectionAction = get()) }
        single { DeleteLocalSubtitleAction(assetStore = get(), fileStore = get(), saveSubtitleSelectionAction = get()) }
        single { ClearLocalSubtitlesAction(assetStore = get(), fileStore = get(), subtitleSelectionStore = get()) }
        single { RetryLocalSubtitleSyncAction(assetStore = get(), syncRepository = get()) }
        single { DownloadAndInstallOpenSubtitleAction(repository = get(), installLocalSubtitleAction = get()) }
        single { GetOpenSubtitlesApiKeyUseCase(store = get()) }
        single { SetOpenSubtitlesApiKeyAction(store = get()) }
        single { GetOpenSubtitleResultPreferenceUseCase(store = get()) }
        single { SetOpenSubtitleResultPreferenceAction(store = get()) }
        single {
            SetLogCollectionEnabledAction(
                preferenceStore = get(),
                logBufferStore = get(),
                nativeDiagnosticLogSource = getOrNull() ?: NativeDiagnosticLogSource.None,
                previousRunFailureStore = getOrNull<PreviousRunFailureStore>() ?: NoOpPreviousRunFailureStore,
            )
        }
        single { SetVerboseLogcatEnabledAction(preferenceStore = get()) }
        single { SetPlaybackInfoAtStartEnabledAction(preferenceStore = get()) }
        single {
            SendClientLogsAction(
                logBufferStore = get(),
                mediaRepository = get(),
                diagnosticsEnvironment = get(),
                deviceProfileProvider = get(),
                playbackDiagnosticsContext = get(),
            )
        }
        single { SetGridSortAction(gridSortStore = get()) }
        single { SetLibrarySortAction(librarySortStore = get()) }
        single { AddRecentSearchAction(recentSearchStore = get()) }
        single { ClearRecentSearchesAction(recentSearchStore = get()) }
        single { SetItemFavoriteAction(mediaRepository = get()) }
        single { SetItemPlayedAction(mediaRepository = get()) }
        single { ObserveAccountsUseCase(sessionRepository = get()) }
    }
