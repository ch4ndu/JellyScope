// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.SetItemFavoriteAction
import com.jellyscope.core.domain.action.SetItemPlayedAction
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaVersion
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PlaybackSelectionKey
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.JELLYFIN_TICKS_PER_MILLISECOND
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.playback.SubtitleSelectionKey
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackLaunchContextUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionUseCase
import com.jellyscope.core.domain.usecase.GetPlaybackSelectionsUseCase
import com.jellyscope.core.domain.usecase.GetRelatedItemsUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.GetSubtitleSelectionUseCase
import com.jellyscope.core.domain.usecase.GetSubtitleSelectionsUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import com.jellyscope.core.playback.SettlementKey
import com.jellyscope.ui.screen.player.PlaybackSelection
import com.jellyscope.ui.screen.player.PlaybackSelectionMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SeriesViewModelTest {
    @Test
    fun formatsEpisodeLabelsAirDateAndRuntime() {
        assertEquals("S1 · E4", seasonEpisodeLabel(seasonNumber = 1, episodeNumber = 4))
        assertEquals("Feb 5, 2024", airDateText(Instant.parse("2024-02-05T00:00:00Z"), englishDetailFormatterStrings()))
        assertEquals("42 min", episodeRuntimeText(42.minutes))
    }

    @Test
    fun loadsInitialUnplayedSeasonAndCachesSeasonSwitches() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason =
                            mapOf(
                                "season-1" to listOf(episode("episode-1", seasonNumber = 1)),
                                "season-2" to listOf(episode("episode-2", seasonNumber = 2, resumeMinutes = 5)),
                            ),
                        nextUp = listOf(episode("episode-next", seasonNumber = 2, resumeMinutes = 7)),
                    )
                val viewModel = repository.seriesViewModel()
                advanceUntilIdle()

                val initial = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals("season-2", initial.selectedSeasonId)
                assertEquals("★ 8.2", initial.series.communityRating)
                assertEquals("91%", initial.series.criticRatingText)
                assertEquals(listOf("season-2"), repository.episodeCalls)
                assertEquals(listOf<Int?>(2), repository.episodeSeasonIndices)
                assertEquals(listOf<String?>("series-1"), repository.nextUpSeriesIds)
                assertEquals("episode-2", initial.focusedEpisode?.itemId)
                assertEquals("episode-next", initial.seriesPlayEpisode?.itemId)
                assertIs<DetailPlayLabel.Resume>(initial.seriesPlayEpisode?.playAction?.label)

                viewModel.selectSeason("season-1")
                advanceUntilIdle()

                val switched = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals("season-1", switched.selectedSeasonId)
                assertEquals(listOf("season-2", "season-1"), repository.episodeCalls)
                assertEquals(listOf<Int?>(2, 1), repository.episodeSeasonIndices)

                viewModel.selectSeason("season-2")
                advanceUntilIdle()

                val cached = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals("season-2", cached.selectedSeasonId)
                assertEquals(listOf("season-2", "season-1"), repository.episodeCalls)
                assertEquals(listOf<Int?>(2, 1), repository.episodeSeasonIndices)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun settlementBeforeEpisodeIdsPublishRefreshesWhenEpisodesLoad() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val registry = PlaybackStopSettlementRegistry()
                registry.publish(SettlementKey(session.serverId, session.userId, "episode-2"))
                val initialEpisodes = CompletableDeferred<Result<List<MediaItem>>>()
                val settlementRefreshEpisodes = CompletableDeferred<Result<List<MediaItem>>>()
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(episode("episode-2", seasonNumber = 2))),
                        deferredEpisodesBySeason =
                            mapOf(
                                "season-2" to
                                    ArrayDeque(
                                        listOf(initialEpisodes, settlementRefreshEpisodes),
                                    ),
                            ),
                    )
                repository.seriesViewModel(settlementRegistry = registry)
                runCurrent()
                assertEquals(listOf("season-2"), repository.episodeCalls)

                initialEpisodes.complete(Result.success(listOf(episode("episode-2", seasonNumber = 2))))
                runCurrent()
                assertEquals(listOf("season-2", "season-2"), repository.episodeCalls)

                settlementRefreshEpisodes.complete(Result.success(listOf(episode("episode-2", seasonNumber = 2))))
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun settlementDuringEpisodeRefreshRunsOneTrailingRefresh() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val registry = PlaybackStopSettlementRegistry()
                val loadedInitialEpisodes = CompletableDeferred<Result<List<MediaItem>>>()
                loadedInitialEpisodes.complete(Result.success(listOf(episode("episode-2", seasonNumber = 2))))
                val firstRefresh = CompletableDeferred<Result<List<MediaItem>>>()
                val trailingRefresh = CompletableDeferred<Result<List<MediaItem>>>()
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(episode("episode-2", seasonNumber = 2))),
                        deferredEpisodesBySeason =
                            mapOf(
                                "season-2" to
                                    ArrayDeque(
                                        listOf(loadedInitialEpisodes, firstRefresh, trailingRefresh),
                                    ),
                            ),
                    )
                repository.seriesViewModel(settlementRegistry = registry)
                advanceUntilIdle()

                registry.publish(SettlementKey(session.serverId, session.userId, "episode-2"))
                runCurrent()
                registry.publish(SettlementKey(session.serverId, session.userId, "episode-2"))
                runCurrent()
                assertEquals(listOf("season-2", "season-2"), repository.episodeCalls)

                firstRefresh.complete(Result.success(listOf(episode("episode-2", seasonNumber = 2))))
                runCurrent()
                assertEquals(listOf("season-2", "season-2", "season-2"), repository.episodeCalls)

                trailingRefresh.complete(Result.success(listOf(episode("episode-2", seasonNumber = 2))))
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun watchedToggleOptimisticallyUpdatesAndRevertsOnFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(episode("episode-2", seasonNumber = 2))),
                        setPlayedResults = ArrayDeque(listOf(Result.failure(IllegalStateException("failed")))),
                    )
                val viewModel = repository.seriesViewModel()
                advanceUntilIdle()

                viewModel.toggleFocusedEpisodeWatched()

                val optimistic = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(true, optimistic.focusedEpisode?.isWatched)
                assertEquals(true, optimistic.focusedEpisode?.watchedToggleInFlight)

                advanceUntilIdle()

                val reverted = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(false, reverted.focusedEpisode?.isWatched)
                assertEquals(listOf("episode-2" to true), repository.setPlayedCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun watchedRollbackUpdatesEveryCachedAliasAfterSwitchingSeasons() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val playedResult = CompletableDeferred<Result<Unit>>()
                val sharedEpisode = episode("episode-shared", seasonNumber = 2)
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason =
                            mapOf(
                                "season-1" to listOf(sharedEpisode.copy(seasonId = "season-1", parentIndexNumber = 1)),
                                "season-2" to listOf(sharedEpisode),
                            ),
                        deferredSetPlayedResult = playedResult,
                    )
                val viewModel = repository.seriesViewModel()
                advanceUntilIdle()

                viewModel.selectSeason("season-1")
                advanceUntilIdle()
                viewModel.selectSeason("season-2")
                viewModel.toggleFocusedEpisodeWatched()
                runCurrent()
                viewModel.selectSeason("season-1")
                assertEquals(
                    true,
                    assertIs<SeasonEpisodesUiState.Content>(
                        assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                    ).episodes.single().isWatched,
                )

                playedResult.complete(Result.failure(IllegalStateException("played failed")))
                advanceUntilIdle()

                val seasonOneEpisode =
                    assertIs<SeasonEpisodesUiState.Content>(
                        assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                    ).episodes.single()
                assertEquals(false, seasonOneEpisode.isWatched)
                assertEquals(false, seasonOneEpisode.watchedToggleInFlight)

                viewModel.selectSeason("season-2")
                val seasonTwoEpisode =
                    assertIs<SeasonEpisodesUiState.Content>(
                        assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                    ).episodes.single()
                assertEquals(false, seasonTwoEpisode.isWatched)
                assertEquals(false, seasonTwoEpisode.watchedToggleInFlight)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun episodeProjectionRunsOnWorkDispatcherBeforeMainCommit() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val workDispatcher = QueueDispatcher()
            Dispatchers.setMain(mainDispatcher)
            try {
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(episode("episode-2", seasonNumber = 2))),
                    )
                val viewModel = repository.seriesViewModel(workDispatcher = workDispatcher)
                runCurrent()

                assertEquals(0, repository.episodeCalls.size)
                assertIs<SeriesUiState.Loading>(viewModel.state.value)
                assertTrue(workDispatcher.pendingCount > 0)

                workDispatcher.runNext()
                runCurrent()
                assertIs<SeriesUiState.Loading>(viewModel.state.value)

                while (viewModel.state.value is SeriesUiState.Loading) {
                    workDispatcher.runNext()
                    runCurrent()
                }
                assertIs<SeriesUiState.Content>(viewModel.state.value)
                assertEquals(emptyList(), repository.episodeCalls)

                while (
                    (viewModel.state.value as? SeriesUiState.Content)?.content?.episodesState !is
                        SeasonEpisodesUiState.Content
                ) {
                    workDispatcher.runNext()
                    runCurrent()
                }
                assertEquals(listOf("season-2"), repository.episodeCalls)
                assertIs<SeasonEpisodesUiState.Content>(
                    assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun favoriteTogglesOptimisticallyUpdateSeriesAndEpisodeThenRevertOnFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(episode("episode-2", seasonNumber = 2))),
                        setFavoriteResults =
                            ArrayDeque(
                                listOf(
                                    Result.failure(IllegalStateException("series failed")),
                                    Result.failure(IllegalStateException("episode failed")),
                                ),
                            ),
                    )
                val viewModel = repository.seriesViewModel()
                advanceUntilIdle()

                viewModel.toggleSeriesFavorite()
                val optimisticSeries = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(true, optimisticSeries.series.isFavorite)
                assertEquals(true, optimisticSeries.series.favoriteToggleInFlight)

                advanceUntilIdle()

                val revertedSeries = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(false, revertedSeries.series.isFavorite)

                viewModel.toggleFocusedEpisodeFavorite()
                val optimisticEpisode = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(true, optimisticEpisode.focusedEpisode?.isFavorite)
                assertEquals(true, optimisticEpisode.focusedEpisode?.favoriteToggleInFlight)

                advanceUntilIdle()

                val revertedEpisode = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(false, revertedEpisode.focusedEpisode?.isFavorite)
                assertEquals(listOf("series-1" to true, "episode-2" to true), repository.setFavoriteCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun staleSeasonCompletionIsDiscardedBeforeItCanPopulateCache() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val seasonTwo = CompletableDeferred<Result<List<MediaItem>>>()
                val seasonTwoRetry = CompletableDeferred<Result<List<MediaItem>>>()
                val seasonOne = CompletableDeferred<Result<List<MediaItem>>>()
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = emptyMap(),
                        deferredEpisodesBySeason =
                            mapOf(
                                "season-2" to ArrayDeque(listOf(seasonTwo, seasonTwoRetry)),
                                "season-1" to ArrayDeque(listOf(seasonOne)),
                            ),
                    )
                val viewModel = repository.seriesViewModel()
                runCurrent()

                viewModel.selectSeason("season-1")
                runCurrent()

                seasonOne.complete(Result.success(listOf(episode("episode-1", seasonNumber = 1))))
                runCurrent()

                val selected = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals("season-1", selected.selectedSeasonId)
                assertEquals(
                    listOf("episode-1"),
                    (selected.episodesState as SeasonEpisodesUiState.Content).episodes.map { episode -> episode.itemId },
                )

                seasonTwo.complete(Result.success(listOf(episode("episode-2", seasonNumber = 2))))
                advanceUntilIdle()

                val afterStale = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals("season-1", afterStale.selectedSeasonId)
                assertEquals(
                    listOf("episode-1"),
                    (afterStale.episodesState as SeasonEpisodesUiState.Content).episodes.map { episode -> episode.itemId },
                )

                viewModel.selectSeason("season-2")
                runCurrent()

                val retrying = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals("season-2", retrying.selectedSeasonId)
                assertIs<SeasonEpisodesUiState.Loading>(retrying.episodesState)
                assertEquals(listOf("season-2", "season-1", "season-2"), repository.episodeCalls)

                seasonTwoRetry.complete(Result.success(listOf(episode("episode-2", seasonNumber = 2))))
                advanceUntilIdle()

                val loaded = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals("season-2", loaded.selectedSeasonId)
                assertEquals(
                    listOf("episode-2"),
                    (loaded.episodesState as SeasonEpisodesUiState.Content).episodes.map { episode -> episode.itemId },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun selectingEpisodeVersionUpdatesEveryAliasWithoutChangingEpisodeOrder() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val selectableEpisode =
                    episode(
                        id = "episode-2",
                        seasonNumber = 2,
                        resumeMinutes = 5,
                        versions = selectableEpisodeVersions(),
                    )
                val playbackStore =
                    SeriesPlaybackSelectionStore(
                        stored =
                            mapOf(
                                PlaybackSelectionKey("server-1", "user-1", "episode-2", "source-2") to
                                    PlaybackSelection(audioStreamIndex = 11),
                            ),
                    )
                val subtitleStore =
                    RecordingSubtitleSelectionStore(
                        stored = mapOf(("episode-2" to "source-2") to SubtitleSelectionIntent.Track(12)),
                    )
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason =
                            mapOf(
                                "season-2" to
                                    listOf(
                                        selectableEpisode,
                                        episode("episode-3", seasonNumber = 2),
                                    ),
                            ),
                        nextUp = listOf(selectableEpisode),
                    )
                val viewModel =
                    repository.seriesViewModel(
                        getSubtitleSelectionsUseCase = GetSubtitleSelectionsUseCase(subtitleStore),
                        getPlaybackSelectionsUseCase = GetPlaybackSelectionsUseCase(playbackStore),
                        getPlaybackLaunchContextUseCase =
                            GetPlaybackLaunchContextUseCase(
                                getPlaybackSelection = GetPlaybackSelectionUseCase(playbackStore),
                                getSubtitleSelection =
                                    GetSubtitleSelectionUseCase(subtitleStore),
                            ),
                    )
                advanceUntilIdle()
                val initial = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                val initialOrder =
                    (initial.episodesState as SeasonEpisodesUiState.Content).episodes.map(EpisodeUi::itemId)

                viewModel.selectEpisodeMediaVersion("episode-2", "source-2")
                advanceUntilIdle()

                val content = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                val episodes = (content.episodesState as SeasonEpisodesUiState.Content).episodes
                val selected = episodes.first { episode -> episode.itemId == "episode-2" }
                assertEquals(initialOrder, episodes.map(EpisodeUi::itemId))
                assertEquals("source-2", selected.selectedMediaSourceId)
                assertEquals("source-2", selected.playAction.mediaSourceId)
                assertEquals("source-2", selected.restartAction?.mediaSourceId)
                assertEquals(11, selected.trackSelection.defaultAudioStreamIndex)
                assertEquals(12, selected.trackSelection.defaultSubtitleStreamIndex)
                assertEquals("2.1 GB", selected.mediaInfo?.fileLine)
                assertEquals("source-2", content.focusedEpisode?.selectedMediaSourceId)
                assertEquals("source-2", content.nextUpEpisode?.selectedMediaSourceId)
                assertEquals("source-2", content.seriesPlayEpisode?.selectedMediaSourceId)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOptimisticEpisodeTogglesPreserveANewerVersionAcrossAliases() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val playedResult = CompletableDeferred<Result<Unit>>()
                val favoriteResult = CompletableDeferred<Result<Unit>>()
                val selectableEpisode =
                    episode(
                        id = "episode-2",
                        seasonNumber = 2,
                        resumeMinutes = 5,
                        versions = selectableEpisodeVersions(),
                    )
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(selectableEpisode)),
                        nextUp = listOf(selectableEpisode),
                        deferredSetPlayedResult = playedResult,
                        deferredSetFavoriteResult = favoriteResult,
                    )
                val viewModel = repository.seriesViewModel()
                advanceUntilIdle()

                viewModel.toggleFocusedEpisodeWatched()
                viewModel.toggleFocusedEpisodeFavorite()
                runCurrent()
                viewModel.selectEpisodeMediaVersion("episode-2", "source-2")
                advanceUntilIdle()

                playedResult.complete(Result.failure(IllegalStateException("played failed")))
                favoriteResult.complete(Result.failure(IllegalStateException("favorite failed")))
                advanceUntilIdle()

                val content = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                val episode =
                    assertIs<SeasonEpisodesUiState.Content>(content.episodesState)
                        .episodes
                        .single()
                assertEquals("source-2", episode.selectedMediaSourceId)
                assertEquals("source-2", episode.playAction.mediaSourceId)
                assertEquals("source-2", episode.restartAction?.mediaSourceId)
                assertEquals("2.1 GB", episode.mediaInfo?.fileLine)
                assertEquals(false, episode.isWatched)
                assertEquals(false, episode.watchedToggleInFlight)
                assertEquals(false, episode.isFavorite)
                assertEquals(false, episode.favoriteToggleInFlight)
                assertEquals("source-2", content.focusedEpisode?.selectedMediaSourceId)
                assertEquals("source-2", content.nextUpEpisode?.selectedMediaSourceId)
                assertEquals("source-2", content.seriesPlayEpisode?.selectedMediaSourceId)
                assertEquals(listOf("episode-2" to true), repository.setPlayedCalls)
                assertEquals(listOf("episode-2" to true), repository.setFavoriteCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun reselectingRenderedEpisodeVersionCancelsPendingExactSourceRead() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val selectableEpisode =
                    episode(
                        id = "episode-2",
                        seasonNumber = 2,
                        versions = selectableEpisodeVersions(),
                    )
                val selectionStore = DelayedSeriesPlaybackSelectionStore()
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(selectableEpisode)),
                    )
                val viewModel =
                    repository.seriesViewModel(
                        getPlaybackLaunchContextUseCase =
                            GetPlaybackLaunchContextUseCase(
                                getPlaybackSelection = GetPlaybackSelectionUseCase(selectionStore),
                            ),
                    )
                advanceUntilIdle()

                viewModel.selectEpisodeMediaVersion("episode-2", "source-2")
                runCurrent()
                viewModel.selectEpisodeMediaVersion("episode-2", "source-1")
                runCurrent()
                selectionStore.sourceTwo.complete(PlaybackSelection(audioStreamIndex = 11))
                advanceUntilIdle()

                val content = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                val selected =
                    assertIs<SeasonEpisodesUiState.Content>(content.episodesState)
                        .episodes
                        .single()
                assertEquals("source-1", selected.selectedMediaSourceId)
                assertEquals("source-1", selected.playAction.mediaSourceId)
                assertEquals("source-1", content.focusedEpisode?.selectedMediaSourceId)
                assertEquals("source-1", content.seriesPlayEpisode?.selectedMediaSourceId)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sourceSelectionMismatchMergesNewerSourceFieldsIntoFreshServerProjection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val selectable =
                    episode(
                        id = "episode-2",
                        seasonNumber = 2,
                        versions = selectableEpisodeVersions(),
                    )
                val refreshed = selectable.copy(name = "Fresh server title")
                val initial =
                    CompletableDeferred<Result<List<MediaItem>>>().apply {
                        complete(Result.success(listOf(selectable)))
                    }
                val refresh = CompletableDeferred<Result<List<MediaItem>>>()
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = emptyMap(),
                        deferredEpisodesBySeason =
                            mapOf(
                                "season-2" to ArrayDeque(listOf(initial, refresh)),
                            ),
                    )
                val viewModel = repository.seriesViewModel()
                advanceUntilIdle()

                viewModel.refresh()
                runCurrent()
                viewModel.selectEpisodeMediaVersion("episode-2", "source-2")
                advanceUntilIdle()
                assertEquals(
                    "source-2",
                    assertIs<SeasonEpisodesUiState.Content>(
                        assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                    ).episodes.single().selectedMediaSourceId,
                )

                refresh.complete(Result.success(listOf(refreshed)))
                advanceUntilIdle()

                val finalEpisode =
                    assertIs<SeasonEpisodesUiState.Content>(
                        assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                    ).episodes.single()
                assertEquals("Fresh server title", finalEpisode.title)
                assertEquals("source-2", finalEpisode.selectedMediaSourceId)
                assertEquals("source-2", finalEpisode.playAction.mediaSourceId)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun staleEpisodeLoadCannotOverwriteSelectionAndAcceptedRefreshPreservesThenFallsBack() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val selectable =
                    episode(
                        id = "episode-2",
                        seasonNumber = 2,
                        versions = selectableEpisodeVersions(),
                    )
                val sourceOneOnly = selectable.copy(versions = listOf(selectable.versions.first()))

                fun completed(value: MediaItem) =
                    CompletableDeferred<Result<List<MediaItem>>>().apply {
                        complete(Result.success(listOf(value)))
                    }
                val initial = completed(selectable)
                val staleRefresh = CompletableDeferred<Result<List<MediaItem>>>()
                val newerRefresh = CompletableDeferred<Result<List<MediaItem>>>()
                val preservationRefresh = completed(selectable)
                val fallbackRefresh = completed(sourceOneOnly)
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = emptyMap(),
                        deferredEpisodesBySeason =
                            mapOf(
                                "season-2" to
                                    ArrayDeque(
                                        listOf(
                                            initial,
                                            staleRefresh,
                                            newerRefresh,
                                            preservationRefresh,
                                            fallbackRefresh,
                                        ),
                                    ),
                            ),
                    )
                val viewModel = repository.seriesViewModel()
                advanceUntilIdle()
                viewModel.selectEpisodeMediaVersion("episode-2", "source-2")
                advanceUntilIdle()

                viewModel.refresh()
                runCurrent()
                viewModel.refresh()
                runCurrent()
                newerRefresh.complete(Result.success(listOf(selectable)))
                runCurrent()
                staleRefresh.complete(Result.success(listOf(sourceOneOnly)))
                advanceUntilIdle()

                viewModel.refresh()
                advanceUntilIdle()
                val preserved =
                    assertIs<SeasonEpisodesUiState.Content>(
                        assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                    ).episodes.single()
                assertEquals("source-2", preserved.selectedMediaSourceId)

                viewModel.refresh()
                advanceUntilIdle()
                val fallback =
                    assertIs<SeasonEpisodesUiState.Content>(
                        assertIs<SeriesUiState.Content>(viewModel.state.value).content.episodesState,
                    ).episodes.single()
                assertEquals("source-1", fallback.selectedMediaSourceId)
                assertEquals("source-1", fallback.playAction.mediaSourceId)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun batchesStoredSubtitleLookupsIntoASingleQuery() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store =
                    RecordingSubtitleSelectionStore(
                        stored = mapOf(("episode-a" to "source-1") to SubtitleSelectionIntent.Track(0)),
                    )
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason =
                            mapOf(
                                "season-2" to
                                    listOf(
                                        episode("episode-a", seasonNumber = 2),
                                        episode("episode-b", seasonNumber = 2),
                                        episode("episode-c", seasonNumber = 2),
                                    ),
                            ),
                    )
                val viewModel =
                    repository.seriesViewModel(
                        getSubtitleSelectionsUseCase = GetSubtitleSelectionsUseCase(store),
                    )
                advanceUntilIdle()

                val content = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(
                    listOf("episode-a", "episode-b", "episode-c"),
                    (content.episodesState as SeasonEpisodesUiState.Content).episodes.map { episode -> episode.itemId },
                )
                // The whole season's subtitle selections are read in ONE batched query,
                // not one per episode — and the single-key path is never used for the strip.
                assertEquals(listOf(listOf("episode-a", "episode-b", "episode-c")), store.getForItemsCalls)
                assertEquals(0, store.getCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun subtitleStoreFailureStillRendersEpisodeStrip() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                // A subtitle-store read failure must degrade to "no remembered selections",
                // not abort the strip projection (which would crash the launch / stick on Loading).
                val store = RecordingSubtitleSelectionStore(failGetForItems = true)
                val repository =
                    SeriesMediaRepository(
                        episodesBySeason = mapOf("season-2" to listOf(episode("episode-a", seasonNumber = 2))),
                    )
                val viewModel =
                    repository.seriesViewModel(
                        getSubtitleSelectionsUseCase = GetSubtitleSelectionsUseCase(store),
                    )
                advanceUntilIdle()

                val content = assertIs<SeriesUiState.Content>(viewModel.state.value).content
                assertEquals(
                    listOf("episode-a"),
                    (content.episodesState as SeasonEpisodesUiState.Content).episodes.map { episode -> episode.itemId },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun MediaRepository.seriesViewModel(
    getSubtitleSelectionsUseCase: GetSubtitleSelectionsUseCase? = null,
    getPlaybackSelectionsUseCase: GetPlaybackSelectionsUseCase? = null,
    getPlaybackLaunchContextUseCase: GetPlaybackLaunchContextUseCase = GetPlaybackLaunchContextUseCase(),
    settlementRegistry: PlaybackStopSettlementRegistry = PlaybackStopSettlementRegistry(),
    workDispatcher: CoroutineDispatcher = Dispatchers.Main,
) = SeriesViewModel(
    session = session,
    seriesId = "series-1",
    getItemDetailUseCase = GetItemDetailUseCase(this),
    getRelatedItemsUseCase = GetRelatedItemsUseCase(this),
    getNextUpUseCase = GetNextUpUseCase(this),
    getSeriesSeasonsUseCase = GetSeriesSeasonsUseCase(this),
    getSeasonEpisodesUseCase = GetSeasonEpisodesUseCase(this),
    observePlaybackStopSettlementUseCase = ObservePlaybackStopSettlementUseCase(settlementRegistry),
    setItemFavoriteAction = SetItemFavoriteAction(this),
    setItemPlayedAction = SetItemPlayedAction(this),
    imageUrlBuilder = JellyfinImageUrlBuilder(),
    playbackSelectionMemory = PlaybackSelectionMemory(),
    getPlaybackLaunchContextUseCase = getPlaybackLaunchContextUseCase,
    getSubtitleSelectionsUseCase = getSubtitleSelectionsUseCase,
    getPlaybackSelectionsUseCase = getPlaybackSelectionsUseCase,
    formatterStringsProvider = { englishDetailFormatterStrings() },
    workDispatcher = workDispatcher,
)

private class QueueDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()

    val pendingCount: Int get() = pending.size

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        pending.addLast(block)
    }

    fun runNext() {
        pending.removeFirst().run()
    }
}

private class RecordingSubtitleSelectionStore(
    private val stored: Map<Pair<String, String>, SubtitleSelectionIntent> = emptyMap(),
    private val failGetForItems: Boolean = false,
) : SubtitleSelectionStore {
    val getForItemsCalls = mutableListOf<List<String>>()
    var getCalls = 0
        private set

    override suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent? {
        getCalls++
        return stored[key.itemId to key.mediaSourceId]
    }

    override suspend fun getForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, SubtitleSelectionIntent> {
        getForItemsCalls += itemIds
        if (failGetForItems) {
            throw IllegalStateException("subtitle store read failed")
        }
        return stored.filterKeys { key -> key.first in itemIds }
    }

    override suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    ) = Unit

    override suspend fun delete(key: SubtitleSelectionKey) = Unit

    override suspend fun clearServerScoped() = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit
}

private class SeriesPlaybackSelectionStore(
    stored: Map<PlaybackSelectionKey, PlaybackSelection>,
) : PlaybackSelectionStore {
    private val selections = stored.toMutableMap()

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? = selections[key]

    override suspend fun getForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, PlaybackSelection> =
        selections
            .filterKeys { key -> key.serverId == serverId && key.userId == userId && key.itemId in itemIds }
            .mapKeys { (key, _) -> key.itemId to key.mediaSourceId }

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) {
        selections[key] = selection
    }

    override suspend fun delete(key: PlaybackSelectionKey) {
        selections.remove(key)
    }

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) {
        selections.keys.removeAll { key -> key.serverId == serverId && key.userId == userId }
    }

    override suspend fun clearServerScoped(serverId: String) {
        selections.keys.removeAll { key -> key.serverId == serverId }
    }

    override suspend fun clearServerScoped() {
        selections.clear()
    }
}

private class DelayedSeriesPlaybackSelectionStore : PlaybackSelectionStore {
    val sourceTwo = CompletableDeferred<PlaybackSelection?>()

    override suspend fun get(key: PlaybackSelectionKey): PlaybackSelection? =
        if (key.mediaSourceId == "source-2") {
            sourceTwo.await()
        } else {
            null
        }

    override suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    ) = Unit

    override suspend fun delete(key: PlaybackSelectionKey) = Unit

    override suspend fun clearAccount(
        serverId: String,
        userId: String,
    ) = Unit

    override suspend fun clearServerScoped(serverId: String) = Unit

    override suspend fun clearServerScoped() = Unit
}

private class SeriesMediaRepository(
    private val episodesBySeason: Map<String, List<MediaItem>>,
    private val nextUp: List<MediaItem> = emptyList(),
    private val setPlayedResults: ArrayDeque<Result<Unit>> = ArrayDeque(listOf(Result.success(Unit))),
    private val setFavoriteResults: ArrayDeque<Result<Unit>> = ArrayDeque(listOf(Result.success(Unit))),
    private val deferredSetPlayedResult: CompletableDeferred<Result<Unit>>? = null,
    private val deferredSetFavoriteResult: CompletableDeferred<Result<Unit>>? = null,
    private val deferredEpisodesBySeason: Map<String, ArrayDeque<CompletableDeferred<Result<List<MediaItem>>>>> =
        emptyMap(),
) : MediaRepository {
    val episodeCalls = mutableListOf<String>()
    val episodeSeasonIndices = mutableListOf<Int?>()
    val nextUpSeriesIds = mutableListOf<String?>()
    val setPlayedCalls = mutableListOf<Pair<String, Boolean>>()
    val setFavoriteCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(seriesId: String?): Result<List<MediaItem>> {
        nextUpSeriesIds += seriesId
        return Result.success(nextUp)
    }

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> = Result.success(seriesDetail)

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> =
        Result.success(
            listOf(
                MediaItem(
                    id = "season-1",
                    name = "Season 1",
                    kind = MediaKind.Other,
                    indexNumber = 1,
                ),
                MediaItem(
                    id = "season-2",
                    name = "Season 2",
                    kind = MediaKind.Other,
                    indexNumber = 2,
                    unplayedItemCount = 2,
                ),
            ),
        )

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> {
        episodeCalls += seasonId
        episodeSeasonIndices += seasonIndex
        val deferredQueue = deferredEpisodesBySeason[seasonId]
        if (deferredQueue != null && deferredQueue.isNotEmpty()) {
            return deferredQueue.removeFirst().await()
        }
        return Result.success(episodesBySeason[seasonId].orEmpty())
    }

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> = Result.success(FindResults())

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = Result.failure(UnsupportedOperationException())

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> {
        setPlayedCalls += itemId to played
        return deferredSetPlayedResult?.await() ?: setPlayedResults.removeFirst()
    }

    override suspend fun setFavorite(
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> {
        setFavoriteCalls += itemId to favorite
        return deferredSetFavoriteResult?.await() ?: setFavoriteResults.removeFirst()
    }
}

private fun episode(
    id: String,
    seasonNumber: Int,
    resumeMinutes: Int? = null,
    versions: List<MediaVersion> = selectableEpisodeVersions().take(1),
) = MediaItem(
    id = id,
    name = "Episode $id",
    kind = MediaKind.Episode,
    seriesName = "A Show",
    seriesId = "series-1",
    seasonId = "season-$seasonNumber",
    parentIndexNumber = seasonNumber,
    indexNumber = 1,
    runtime = 42.minutes,
    premiereDate = Instant.parse("2024-02-05T00:00:00Z"),
    played = false,
    playedPercentage =
        if (resumeMinutes == null) {
            null
        } else {
            50.0
        },
    playbackPositionTicks =
        resumeMinutes
            ?.minutes
            ?.inWholeMilliseconds
            ?.times(JELLYFIN_TICKS_PER_MILLISECOND),
    overview = "Episode overview.",
    officialRating = "TV-14",
    imageRefs = ImageRefs(primaryTag = "episode-tag"),
    versions = versions,
)

private fun selectableEpisodeVersions(): List<MediaVersion> =
    listOf(
        MediaVersion(
            id = "source-1",
            name = "1080p",
            sizeBytes = 1_073_741_824L,
            mediaStreams =
                listOf(
                    PlaybackMediaStream(
                        index = 0,
                        type = "Video",
                        displayTitle = null,
                        title = null,
                        language = null,
                        codec = "h264",
                        channelLayout = null,
                        bitRate = null,
                        height = 1080,
                        isDefault = null,
                        isExternal = null,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                ),
        ),
        MediaVersion(
            id = "source-2",
            name = "4K",
            sizeBytes = 2_147_483_648L,
            mediaStreams =
                listOf(
                    PlaybackMediaStream(
                        index = 9,
                        type = "Video",
                        displayTitle = null,
                        title = null,
                        language = null,
                        codec = "hevc",
                        channelLayout = null,
                        bitRate = null,
                        height = 2160,
                        isDefault = null,
                        isExternal = null,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                    PlaybackMediaStream(
                        index = 10,
                        type = "Audio",
                        displayTitle = "English AAC 2.0",
                        title = null,
                        language = "eng",
                        codec = "aac",
                        channelLayout = "2.0",
                        bitRate = null,
                        height = null,
                        isDefault = true,
                        isExternal = null,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                    PlaybackMediaStream(
                        index = 11,
                        type = "Audio",
                        displayTitle = "Spanish AAC 2.0",
                        title = null,
                        language = "spa",
                        codec = "aac",
                        channelLayout = "2.0",
                        bitRate = null,
                        height = null,
                        isDefault = false,
                        isExternal = null,
                        deliveryMethod = null,
                        deliveryUrl = null,
                    ),
                    PlaybackMediaStream(
                        index = 12,
                        type = "Subtitle",
                        displayTitle = "Spanish SRT",
                        title = null,
                        language = "spa",
                        codec = "srt",
                        channelLayout = null,
                        bitRate = null,
                        height = null,
                        isDefault = false,
                        isExternal = true,
                        deliveryMethod = "External",
                        deliveryUrl = "/subtitles/12.srt",
                    ),
                ),
        ),
    )

private val seriesDetail =
    MediaItemDetail(
        item =
            MediaItem(
                id = "series-1",
                name = "A Show",
                kind = MediaKind.Series,
                runtime = 42.minutes,
                imageRefs =
                    ImageRefs(
                        primaryTag = "series-poster",
                        backdropTag = "series-backdrop",
                    ),
            ),
        overview = "Series overview.",
        genres = listOf("Drama"),
        officialRating = "TV-14",
        communityRating = 8.2,
        criticRating = 90.6,
        productionYear = 2024,
    )

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        userId = "user-1",
        userName = "Demo User",
        accessToken = "token-1",
        deviceId = "device-1",
    )
