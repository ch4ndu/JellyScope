// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.di

import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.ServerInfo
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.ui.screen.account.AccountViewModel
import com.jellyscope.ui.screen.collection.CollectionViewModel
import com.jellyscope.ui.screen.detail.AmbientColorExtractor
import com.jellyscope.ui.screen.detail.DetailViewModel
import com.jellyscope.ui.screen.detail.OpenSubtitleSearchViewModel
import com.jellyscope.ui.screen.detail.SeriesViewModel
import com.jellyscope.ui.screen.detail.platformAmbientColorExtractor
import com.jellyscope.ui.screen.discover.DiscoverViewModel
import com.jellyscope.ui.screen.downloads.DownloadsViewModel
import com.jellyscope.ui.screen.find.FindViewModel
import com.jellyscope.ui.screen.grid.GridViewModel
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.HomeViewModel
import com.jellyscope.ui.screen.library.LibraryBrowseViewModel
import com.jellyscope.ui.screen.library.LibraryHubViewModel
import com.jellyscope.ui.screen.library.LibraryTabViewModel
import com.jellyscope.ui.screen.login.LoginViewModel
import com.jellyscope.ui.screen.login.QuickConnectViewModel
import com.jellyscope.ui.screen.person.PersonViewModel
import com.jellyscope.ui.screen.player.PendingPlayerController
import com.jellyscope.ui.screen.player.PlaybackSelectionMemory
import com.jellyscope.ui.screen.player.PlayerLaunchOptions
import com.jellyscope.ui.screen.player.PlayerViewModel
import com.jellyscope.ui.screen.serverentry.ServerEntryViewModel
import com.jellyscope.ui.screen.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val sharedUiModule =
    module {
        single {
            PlaybackSelectionMemory().also { memory ->
                get<ServerScopedStoreRegistry>().register(memory)
            }
        }
        single<AmbientColorExtractor> { platformAmbientColorExtractor() }
        viewModel {
            ServerEntryViewModel(
                validateServerAction = get(),
                discoverServersUseCase = get(),
            )
        }
        viewModel { (serverInfo: ServerInfo, initialUsername: String, initialPassword: String) ->
            LoginViewModel(
                serverInfo = serverInfo,
                initialUsername = initialUsername,
                initialPassword = initialPassword,
                loginAction = get(),
            )
        }
        viewModel { (serverInfo: ServerInfo) ->
            QuickConnectViewModel(
                serverInfo = serverInfo,
                quickConnectLoginAction = get(),
            )
        }
        viewModel {
            AccountViewModel(
                observeAccountsUseCase = get(),
                addAccountAction = get(),
                switchAccountAction = get(),
                signOutAccountAction = get(),
                getDownloadRemovalPreviewUseCase = getOrNull(),
                issueDownloadRemovalAuthorizationUseCase = getOrNull(),
                releaseDownloadRemovalPreviewUseCase = getOrNull(),
            )
        }
        viewModel { (session: Session) ->
            HomeViewModel(
                session = session,
                getContinueWatchingUseCase = get(),
                getNextUpUseCase = get(),
                getRecentlyAddedUseCase = get(),
                getFavoritesUseCase = get(),
                imageUrlBuilder = get(),
            )
        }
        viewModel { (session: Session) ->
            FindViewModel(
                session = session,
                searchLibraryUseCase = get(),
                findPersonsUseCase = get(),
                imageUrlBuilder = get(),
                getRecentSearchesUseCase = get(),
                addRecentSearchAction = get(),
                clearRecentSearchesAction = get(),
            )
        }
        viewModel { (session: Session, parentId: String?) ->
            DiscoverViewModel(
                session = session,
                parentId = parentId,
                getGenresUseCase = get(),
                getStudiosUseCase = get(),
                getCollectionsUseCase = get(),
                getSuggestionsUseCase = get(),
                getUpcomingEpisodesUseCase = get(),
                imageUrlBuilder = get(),
            )
        }
        viewModel { (session: Session, row: HomeRow) ->
            GridViewModel(
                session = session,
                row = row,
                getRibbonItemsUseCase = get(),
                imageUrlBuilder = get(),
                getGridSortUseCase = get(),
                setGridSortAction = get(),
            )
        }
        viewModel { (session: Session, parentId: String?, collectionType: LibraryCollectionType) ->
            LibraryBrowseViewModel(
                session = session,
                parentId = parentId,
                collectionType = collectionType,
                getLibraryItemsUseCase = get(),
                getLibraryShuffleQueueUseCase = get(),
                getLibraryFiltersUseCase = get(),
                imageUrlBuilder = get(),
                getLibrarySortUseCase = get(),
                setLibrarySortAction = get(),
            )
        }
        viewModel { (session: Session, parentId: String, collectionType: LibraryCollectionType) ->
            LibraryHubViewModel(
                session = session,
                parentId = parentId,
                collectionType = collectionType,
                getSavedLibraryViewUseCase = get(),
                observeRememberLastLibraryViewUseCase = get(),
                setSavedLibraryViewAction = get(),
                getLibraryRecommendationSectionUseCase = get(),
                imageUrlBuilder = get(),
            )
        }
        viewModel { (session: Session) ->
            LibraryTabViewModel(
                session = session,
                getUserLibrariesUseCase = get(),
                getLastLibraryIdUseCase = get(),
                setLastLibraryIdAction = get(),
            )
        }
        viewModel { (session: Session, collectionId: String) ->
            CollectionViewModel(
                session = session,
                collectionId = collectionId,
                getCollectionItemsUseCase = get(),
                imageUrlBuilder = get(),
            )
        }
        viewModel { (session: Session, personId: String) ->
            PersonViewModel(
                session = session,
                personId = personId,
                getPersonItemsUseCase = get(),
                imageUrlBuilder = get(),
                getPersonUseCase = get(),
                getPersonItemsPageUseCase = get(),
            )
        }
        viewModel { (session: Session) ->
            val playerBackendPolicy = get<DeviceProfileProvider>().backendPolicy
            SettingsViewModel(
                session = session,
                appInfo = get(),
                playerBackendPolicy = playerBackendPolicy,
                logoutAction = get(),
                observeAccountsUseCase = get(),
                getDownloadRemovalPreviewUseCase = getOrNull(),
                issueDownloadRemovalAuthorizationUseCase = getOrNull(),
                releaseDownloadRemovalPreviewUseCase = getOrNull(),
                getDownloadUsageUseCase = getOrNull(),
                getDownloadSettingsUseCase = getOrNull(),
                observeAppThemeUseCase = get(),
                setAppThemeAction = get(),
                observePictureInPictureEnabledUseCase = get(),
                setPictureInPictureEnabledAction = get(),
                observeRememberLastLibraryViewUseCase = get(),
                setRememberLastLibraryViewAction = get(),
                observeTileSizeUseCase = get(),
                setTileSizeAction = get(),
                observePlayerDeviceSettingsUseCase = get(),
                savePlayerDeviceSettingsAction = get(),
                getPlayerDevicePolicyUseCase = get(),
                refreshPlayerDevicePolicyUseCase = get(),
                getPlaybackPreferencesUseCase = get(),
                savePlaybackPreferencesAction = get(),
                getAvailablePlayerBackendsUseCase = get(),
                getOpenSubtitlesApiKeyUseCase = get(),
                setOpenSubtitlesApiKeyAction = get(),
                getOpenSubtitleResultPreferenceUseCase = get(),
                setOpenSubtitleResultPreferenceAction = get(),
                clearLocalSubtitlesAction = get(),
                getLogCollectionStateUseCase = get(),
                setLogCollectionEnabledAction = get(),
                getVerboseLogcatStateUseCase = get(),
                setVerboseLogcatEnabledAction = get(),
                getPlaybackInfoAtStartStateUseCase = get(),
                setPlaybackInfoAtStartEnabledAction = get(),
                sendClientLogsAction = get(),
            )
        }
        viewModel { (session: Session) ->
            DownloadsViewModel(
                session = session,
                observeDownloadsUseCase = get(),
                getDownloadUsageUseCase = get(),
                getDownloadSettingsUseCase = get(),
                configureDownloadQuotaAction = get(),
                pauseDownloadAction = get(),
                resumeDownloadAction = get(),
                resumePausedDownloadsAction = get(),
                retryDownloadAction = get(),
                wakeDownloadsQueueAction = get(),
                cancelDownloadAction = get(),
                deleteDownloadAction = get(),
                isDownloadArtifactLeasedUseCase = getOrNull(),
            )
        }
        viewModel { (session: Session, itemId: String) ->
            DetailViewModel(
                session = session,
                itemId = itemId,
                getItemDetailUseCase = get(),
                getRelatedItemsUseCase = get(),
                observePlaybackStopSettlementUseCase = get(),
                setItemFavoriteAction = get(),
                setItemPlayedAction = get(),
                imageUrlBuilder = get(),
                playbackSelectionMemory = get(),
                getPlaybackLaunchContextUseCase = get(),
                observeLocalSubtitleAssetsUseCase = get(),
                deleteLocalSubtitleAction = get(),
                retryLocalSubtitleSyncAction = get(),
                saveSubtitleSelectionAction = get(),
                previewOriginalDownloadUseCase = getOrNull(),
                enqueueDownloadAction = getOrNull(),
                previewFixedDownloadUseCase = getOrNull(),
                enqueueFixedDownloadAction = getOrNull(),
                fixedDownloadCapability = getOrNull(),
                observeDownloadsUseCase = getOrNull(),
            )
        }
        viewModel { (request: com.jellyscope.core.domain.model.OpenSubtitleSearchRequest) ->
            OpenSubtitleSearchViewModel(
                request = request,
                searchOpenSubtitlesUseCase = get(),
                downloadAndInstallOpenSubtitleAction = get(),
            )
        }
        viewModel { (session: Session, seriesId: String) ->
            SeriesViewModel(
                session = session,
                seriesId = seriesId,
                getItemDetailUseCase = get(),
                getRelatedItemsUseCase = get(),
                getNextUpUseCase = get(),
                getSeriesSeasonsUseCase = get(),
                getSeasonEpisodesUseCase = get(),
                observePlaybackStopSettlementUseCase = get(),
                setItemFavoriteAction = get(),
                setItemPlayedAction = get(),
                imageUrlBuilder = get(),
                playbackSelectionMemory = get(),
                getPlaybackLaunchContextUseCase = get(),
                getSubtitleSelectionsUseCase = get(),
                getPlaybackSelectionsUseCase = get(),
            )
        }
        viewModel { (session: Session, itemId: String, launchOptions: PlayerLaunchOptions) ->
            val backend = get<com.jellyscope.core.domain.playback.DeviceProfileProvider>().backendPolicy.defaultBackend
            PlayerViewModel(
                session = session,
                itemId = itemId,
                startPositionTicks = launchOptions.startPositionTicks,
                mediaSourceId = launchOptions.mediaSourceId,
                initialAudioStreamIndex = launchOptions.initialAudioStreamIndex,
                initialSubtitleSelection = launchOptions.initialSubtitleSelection,
                queue = launchOptions.queue,
                offlineDownloadId = launchOptions.offlineDownloadId,
                backend = backend,
                playerController = PendingPlayerController,
                playerControllerFactory = { resolvedBackend ->
                    get<PlayerController>(parameters = { parametersOf(session, resolvedBackend) })
                },
                initialControllerIsPending = true,
                deviceProfileProvider = get(),
                playbackDiagnosticsContext = get(),
                getPlayerBackendOverrideUseCase = get(),
                getPlaybackInfoAtStartStateUseCase = get(),
                playbackInfoPlanner = get(),
                progressReporter = get(),
                playbackStopSettlementRegistry = get(),
                getItemDetailUseCase = get(),
                getMediaSegmentsUseCase = get(),
                getItemsByIdsUseCase = get(),
                getChronologicalEpisodeQueueUseCase = get(),
                imageUrlBuilder = get(),
                deviceInfoProvider = get(),
                playbackSelectionMemory = get(),
                getPlaybackLaunchContextUseCase = get(),
                getLocalSubtitleAssetUseCase = get(),
                observeLocalSubtitleAssetsUseCase = get(),
                saveSubtitleSelectionAction = get(),
                savePlaybackSelectionAction = get(),
                getPlaybackTimingOffsetUseCase = get(),
                savePlaybackTimingOffsetAction = get(),
                observePlayerDeviceSettingsUseCase = get(),
                playbackHealthGuidancePolicy = get(),
                getOfflinePlaybackPlanUseCase = get(),
            )
        }
    }
