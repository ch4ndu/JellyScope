// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.di

import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.PlaybackHealthGuidancePolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.playback.PlaybackDiagnosticsContext
import com.jellyscope.core.playback.PlaybackReportingQueue
import com.jellyscope.tvos.presenter.TvHomePresenter
import com.jellyscope.tvos.presenter.TvItemDetailPresenter
import com.jellyscope.tvos.presenter.TvLibrariesPresenter
import com.jellyscope.tvos.presenter.TvLibraryBrowsePresenter
import com.jellyscope.tvos.presenter.TvLoginPresenter
import com.jellyscope.tvos.presenter.TvPlaybackRequest
import com.jellyscope.tvos.presenter.TvPlaybackSessionPresenter
import com.jellyscope.tvos.presenter.TvSearchPresenter
import com.jellyscope.tvos.presenter.TvSessionPresenter
import com.jellyscope.tvos.presenter.TvSettingsPresenter
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
                getUserLibraries = get(),
                getLibraryRecommendationSection = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session) ->
            TvSearchPresenter(
                session = session,
                searchLibrary = get(),
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
                getLogCollectionState = get(),
                setLogCollectionEnabled = get(),
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
        factory { (session: Session, libraryId: String) ->
            TvLibraryBrowsePresenter(
                session = session,
                libraryId = libraryId,
                getLibraryItems = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (session: Session, itemId: String) ->
            TvItemDetailPresenter(
                session = session,
                itemId = itemId,
                getItemDetail = get(),
                getSeriesSeasons = get(),
                getSeasonEpisodes = get(),
                getNextUp = get(),
                getRelatedItems = get(),
                setItemPlayed = get(),
                setItemFavorite = get(),
                imageUrlBuilder = get(),
                dispatchers = get(),
            )
        }
        factory { (request: TvPlaybackRequest) ->
            TvPlaybackSessionPresenter(
                session = request.session,
                initialItemId = request.itemId,
                requestedMediaSourceId = request.mediaSourceId,
                initialStartPositionTicks = request.startPositionTicks,
                playerController =
                    get<PlayerController> {
                        parametersOf(request.session, PlayerBackend.AVPlayer)
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
