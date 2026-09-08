// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.di

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.usecase.GetPlaybackInfoAtStartStateUseCase
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackReportingQueue
import com.jellyscope.tvos.presenter.TvAccountsPresenter
import com.jellyscope.tvos.presenter.TvAppearancePresenter
import com.jellyscope.tvos.presenter.TvDeviceSettingsPresenter
import com.jellyscope.tvos.presenter.TvDownloadRequest
import com.jellyscope.tvos.presenter.TvDownloadRequestPresenter
import com.jellyscope.tvos.presenter.TvDownloadsPresenter
import com.jellyscope.tvos.presenter.TvHomePresenter
import com.jellyscope.tvos.presenter.TvHomeRowKind
import com.jellyscope.tvos.presenter.TvHomeViewAllPresenter
import com.jellyscope.tvos.presenter.TvItemDetailPresenter
import com.jellyscope.tvos.presenter.TvItemDetailRequest
import com.jellyscope.tvos.presenter.TvLibrariesPresenter
import com.jellyscope.tvos.presenter.TvLibraryBrowsePresenter
import com.jellyscope.tvos.presenter.TvLibraryHubPresenter
import com.jellyscope.tvos.presenter.TvLibraryTile
import com.jellyscope.tvos.presenter.TvLoginPresenter
import com.jellyscope.tvos.presenter.TvOfflinePlaybackPresenter
import com.jellyscope.tvos.presenter.TvOfflinePlaybackRequest
import com.jellyscope.tvos.presenter.TvPersonPresenter
import com.jellyscope.tvos.presenter.TvPlaybackRequest
import com.jellyscope.tvos.presenter.TvPlaybackSessionPresenter
import com.jellyscope.tvos.presenter.TvSearchPresenter
import com.jellyscope.tvos.presenter.TvSessionPresenter
import com.jellyscope.tvos.presenter.TvSettingsPresenter
import com.jellyscope.tvos.presenter.TvSubtitleSettingsPresenter
import com.jellyscope.tvos.presenter.TvSubtitlesPresenter
import com.jellyscope.tvos.presenter.TvSubtitlesRequest
import com.jellyscope.tvos.presenter.TvosDispatchers
import kotlinx.coroutines.Dispatchers
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val tvosPresentationModule =
    module {
        single { TvosDispatchers(main = Dispatchers.Main, work = Dispatchers.Default) }
        factory {
            TvSessionPresenter(
                observeSessionState = get(),
                logoutAction = get(),
                dispatchers = get(),
            )
        }
        factory {
            TvLoginPresenter(
                validateServerAction = get(),
                loginAction = get(),
                quickConnectLoginAction = get(),
                discoverServers = get(),
                dispatchers = get(),
            )
        }
        factory {
            TvAccountsPresenter(
                observeAccounts = get(),
                switchAccount = get(),
                signOutAccount = get(),
                dispatchers = get(),
                getDownloadRemovalPreview = get(),
                issueDownloadRemovalAuthorization = get(),
                releaseDownloadRemovalPreview = get(),
            )
        }
        factory {
            TvAppearancePresenter(
                observeAppTheme = get(),
                setAppThemeAction = get(),
                observeTileSize = get(),
                setTileSizeAction = get(),
                dispatchers = get(),
            )
        }
        factory {
            TvDeviceSettingsPresenter(
                observePlayerDeviceSettings = get(),
                savePlayerDeviceSettings = get(),
                getPlayerDevicePolicy = get(),
                dispatchers = get(),
            )
        }
        factory {
            TvSubtitleSettingsPresenter(
                getOpenSubtitlesApiKey = get(),
                setOpenSubtitlesApiKey = get(),
                getOpenSubtitleResultPreference = get(),
                setOpenSubtitleResultPreference = get(),
                clearLocalSubtitles = get(),
                dispatchers = get(),
            )
        }
        factory { (request: TvSubtitlesRequest) ->
            TvSubtitlesPresenter(
                request = request,
                getItemDetail = get(),
                getPlaybackLaunchContext = get(),
                searchOpenSubtitles = get(),
                downloadAndInstallOpenSubtitle = get(),
                observeLocalSubtitleAssets = get(),
                getLocalSubtitleAsset = get(),
                deleteLocalSubtitle = get(),
                retryLocalSubtitleSync = get(),
                saveSubtitleSelection = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session) ->
            TvHomePresenter(
                session = session,
                getContinueWatching = get(),
                getNextUp = get(),
                getRecentlyAdded = get(),
                getFavorites = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session, row: TvHomeRowKind) ->
            TvHomeViewAllPresenter(
                session = session,
                row = row,
                getRibbonItems = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session) ->
            TvSearchPresenter(
                session = session,
                searchLibrary = get(),
                findPersons = get(),
                getRecentSearches = get(),
                addRecentSearch = get(),
                clearRecentSearches = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session) ->
            TvSettingsPresenter(
                session = session,
                getPlaybackPreferences = get(),
                savePlaybackPreferences = get(),
                observeRememberLastLibraryView = get(),
                setRememberLastLibraryView = get(),
                getLogCollectionState = get(),
                setLogCollectionEnabled = get(),
                getPlaybackInfoAtStartState = get(),
                setPlaybackInfoAtStartEnabled = get(),
                sendClientLogsAction = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session) ->
            TvLibrariesPresenter(
                session = session,
                getUserLibraries = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session, library: TvLibraryTile) ->
            TvLibraryHubPresenter(
                session = session,
                libraryId = library.id,
                collectionType = library.collectionType,
                getSavedLibraryView = get(),
                observeRememberLastLibraryView = get(),
                setSavedLibraryView = get(),
                getLibraryRecommendationSection = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session, library: TvLibraryTile) ->
            TvLibraryBrowsePresenter(
                session = session,
                libraryId = library.id,
                collectionType = library.collectionType,
                getLibraryItems = get(),
                getLibraryFilters = get(),
                getLibrarySort = get(),
                setLibrarySort = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (request: TvItemDetailRequest) ->
            TvItemDetailPresenter(
                session = request.session,
                itemId = request.itemId,
                initialSeasonId = request.initialSeasonId,
                getPlaybackLaunchContext = get(),
                getItemDetail = get(),
                getSeriesSeasons = get(),
                getSeasonEpisodes = get(),
                getNextUp = get(),
                getRelatedItems = get(),
                observePlaybackStopSettlement = get(),
                setItemPlayed = get(),
                setItemFavorite = get(),
                getLocalSubtitleAsset = get(),
                saveSubtitleSelection = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session, personId: String) ->
            TvPersonPresenter(
                session = session,
                personId = personId,
                getPerson = get(),
                getPersonItemsPage = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session) ->
            TvDownloadsPresenter(
                session = session,
                observeDownloads = get(),
                getDownloadUsage = get(),
                getDownloadSettings = get(),
                getOfflinePlaybackPlan = get(),
                configureDownloadQuota = get(),
                pauseDownload = get(),
                resumeDownload = get(),
                resumePausedDownloads = get(),
                retryDownload = get(),
                wakeDownloadsQueue = get(),
                cancelDownload = get(),
                deleteDownload = get(),
                isDownloadArtifactLeased = get(),
                dispatchers = get(),
            )
        }
        factory { (request: TvDownloadRequest) ->
            TvDownloadRequestPresenter(
                request = request,
                getItemDetail = get(),
                getLocalSubtitleAsset = get(),
                previewOriginalDownload = get(),
                enqueueDownload = get(),
                previewFixedDownload = get(),
                enqueueFixedDownload = get(),
                fixedDownloadCapability = get(),
                dispatchers = get(),
            )
        }
        factory { (request: TvOfflinePlaybackRequest) ->
            TvOfflinePlaybackPresenter(
                request = request,
                startWithPlaybackInfoOverlay = get<GetPlaybackInfoAtStartStateUseCase>()().value,
                getOfflinePlaybackPlan = get(),
                updateDownloadedPlayback = get(),
                deviceProfileProvider = get(),
                playerControllerFactory = {
                    get<PlayerController> { parametersOf(request.session, PlayerBackend.VlcKit, false) }
                },
                dispatchers = get(),
            )
        }
        factory { (request: TvPlaybackRequest) ->
            TvPlaybackSessionPresenter(
                session = request.session,
                startWithPlaybackInfoOverlay = get<GetPlaybackInfoAtStartStateUseCase>()().value,
                initialItemId = request.itemId,
                requestedMediaSourceId = request.mediaSourceId,
                initialStartPositionTicks = request.startPositionTicks,
                initialAudioStreamIndex = request.audioStreamIndex,
                initialSubtitleMode = request.subtitleMode,
                initialSubtitleStreamIndex = request.subtitleStreamIndex,
                initialSubtitleAssetId = request.subtitleAssetId,
                getLocalSubtitleAsset = get(),
                observeLocalSubtitleAssets = get(),
                playerControllerFactory = {
                    get<PlayerController> {
                        parametersOf(request.session, PlayerBackend.AVPlayer, false)
                    }
                },
                playbackInfoPlanner = get(),
                reportingQueue =
                    PlaybackReportingQueue(
                        reporter = get(),
                        dispatcher = get<TvosDispatchers>().work,
                        settlementRegistry = get(),
                    ),
                getItemDetail = get(),
                getMediaSegments = get(),
                getChronologicalEpisodeQueue = get(),
                getPlaybackLaunchContext = get(),
                saveSubtitleSelection = get(),
                savePlaybackSelection = get(),
                imageUrlBuilder = get(),
                deviceInfoProvider = get(),
                dispatchers = get(),
                playbackDiagnosticsContext = get<PlaybackDiagnosticsContext>(),
                deviceProfileProvider = get<DeviceProfileProvider>(),
                playbackHealthGuidancePolicy = PlaybackHealthGuidancePolicy.Advisory,
            )
        }
    }
