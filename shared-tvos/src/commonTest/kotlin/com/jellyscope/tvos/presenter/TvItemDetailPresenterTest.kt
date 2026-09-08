// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.usecase.GetItemDetailUseCase
import com.jellyscope.core.domain.usecase.GetSeasonEpisodesUseCase
import com.jellyscope.core.domain.usecase.GetSeriesSeasonsUseCase
import com.jellyscope.core.domain.usecase.ObservePlaybackStopSettlementUseCase
import com.jellyscope.core.playback.PlaybackStopSettlementRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvItemDetailPresenterTest {
    @Test
    fun movieDetailLoadsWithoutSeasons() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    detail =
                        Result.success(
                            testDetail(
                                item =
                                    mediaItem(
                                        id = "item-1",
                                        name = "Movie",
                                        playbackPositionTicks = 5_000_000L,
                                    ),
                            ),
                        ),
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            val state = presenter.state.value
            assertEquals("Movie", state.content?.title)
            assertEquals(5_000_000L, state.content?.resumePositionTicks)
            assertTrue(state.seasons.isEmpty())
            presenter.close()
        }

    @Test
    fun seriesDetailLoadsSeasonsAndAutoSelectsFirst() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    detail = Result.success(testDetail(item = mediaItem(id = "series-1", kind = MediaKind.Series))),
                    seasons =
                        Result.success(
                            listOf(
                                mediaItem(id = "season-1", name = "Season 1"),
                                mediaItem(id = "season-2", name = "Season 2"),
                            ),
                        ),
                    episodesBySeasonId =
                        mapOf(
                            "season-1" to listOf(mediaItem(id = "episode-1", kind = MediaKind.Episode)),
                        ),
                )
            val presenter = presenter(repository, itemId = "series-1")

            presenter.load()
            runCurrent()

            val state = presenter.state.value
            assertEquals(listOf("season-1", "season-2"), state.seasons.map { season -> season.id })
            assertEquals("season-1", state.selectedSeasonId)
            assertEquals(listOf("episode-1"), state.episodes.map { episode -> episode.id })
            presenter.close()
        }

    @Test
    fun selectingAnotherSeasonReplacesEpisodes() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    detail = Result.success(testDetail(item = mediaItem(id = "series-1", kind = MediaKind.Series))),
                    seasons =
                        Result.success(
                            listOf(
                                mediaItem(id = "season-1", name = "Season 1"),
                                mediaItem(id = "season-2", name = "Season 2"),
                            ),
                        ),
                    episodesBySeasonId =
                        mapOf(
                            "season-1" to listOf(mediaItem(id = "episode-1", kind = MediaKind.Episode)),
                            "season-2" to listOf(mediaItem(id = "episode-2", kind = MediaKind.Episode)),
                        ),
                )
            val presenter = presenter(repository, itemId = "series-1")
            presenter.load()
            runCurrent()

            presenter.selectSeason("season-2")
            runCurrent()

            assertEquals("season-2", presenter.state.value.selectedSeasonId)
            assertEquals(
                listOf("episode-2"),
                presenter.state.value.episodes
                    .map { episode -> episode.id },
            )
            presenter.close()
        }

    @Test
    fun seasonFailureSurfacesErrorAndRetryRecovers() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    detail = Result.success(testDetail(item = mediaItem(id = "series-1", kind = MediaKind.Series))),
                    seasons = Result.failure(IllegalStateException("down")),
                )
            val presenter = presenter(repository, itemId = "series-1")
            presenter.load()
            runCurrent()

            assertEquals(TvErrorKind.Network, presenter.state.value.seasonsError)

            repository.seasons = Result.success(listOf(mediaItem(id = "season-1", name = "Season 1")))
            repository.episodesBySeasonId = mapOf("season-1" to listOf(mediaItem(id = "episode-1", kind = MediaKind.Episode)))
            presenter.retrySeasons()
            runCurrent()

            assertEquals(null, presenter.state.value.seasonsError)
            assertEquals(
                listOf("season-1"),
                presenter.state.value.seasons
                    .map { season -> season.id },
            )
            assertEquals(
                listOf("episode-1"),
                presenter.state.value.episodes
                    .map { episode -> episode.id },
            )
            presenter.close()
        }

    @Test
    fun episodeFailureSurfacesErrorAndSelectSeasonRetries() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    detail = Result.success(testDetail(item = mediaItem(id = "series-1", kind = MediaKind.Series))),
                    seasons = Result.success(listOf(mediaItem(id = "season-1", name = "Season 1"))),
                    episodesBySeasonId = emptyMap(),
                    episodesFailure = true,
                )
            val presenter = presenter(repository, itemId = "series-1")
            presenter.load()
            runCurrent()

            assertEquals(TvErrorKind.Network, presenter.state.value.episodesError)

            repository.episodesFailure = false
            repository.episodesBySeasonId = mapOf("season-1" to listOf(mediaItem(id = "episode-1", kind = MediaKind.Episode)))
            presenter.retryEpisodes()
            runCurrent()

            assertEquals(null, presenter.state.value.episodesError)
            assertEquals(
                listOf("episode-1"),
                presenter.state.value.episodes
                    .map { episode -> episode.id },
            )
            presenter.close()
        }

    @Test
    fun badgesDeriveFromMediaStreams() =
        runTest {
            val uhdVideo = testStreams[0].copy(height = 2160)
            val surround = testStreams[1].copy(channelLayout = "5.1")
            val repository =
                FakeTvMediaRepository(
                    detail =
                        Result.success(
                            testDetail(
                                item = mediaItem(id = "item-1", name = "Movie"),
                                streams = listOf(uhdVideo, surround, testSubtitleStream),
                            ),
                        ),
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            val badges = requireNotNull(presenter.state.value.content).badges
            assertEquals("4K", badges.resolution)
            assertEquals("5.1", badges.audioLayout)
            assertTrue(badges.hasSubtitles)
            presenter.close()
        }

    @Test
    fun togglePlayedIsOptimisticAndRevertsOnFailure() =
        runTest {
            val repository = FakeTvMediaRepository()
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.togglePlayed()
            assertTrue(requireNotNull(presenter.state.value.content).played)
            runCurrent()
            assertEquals("item-1" to true, repository.playedCalls.single())
            assertTrue(requireNotNull(presenter.state.value.content).played)

            repository.setPlayedResult = Result.failure(IllegalStateException("down"))
            presenter.togglePlayed()
            assertTrue(!requireNotNull(presenter.state.value.content).played)
            runCurrent()
            // Failure reverts the optimistic flip back to the pre-toggle truth.
            assertTrue(requireNotNull(presenter.state.value.content).played)
            presenter.close()
        }

    @Test
    fun rapidPlayedTogglesPersistTheNewestIntentLast() =
        runTest {
            val repository = FakeTvMediaRepository()
            val first = CompletableDeferred<Result<Unit>>()
            val second = CompletableDeferred<Result<Unit>>()
            repository.playedResponses.addLast(first)
            repository.playedResponses.addLast(second)
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.togglePlayed()
            presenter.togglePlayed()
            runCurrent()
            assertEquals(listOf("item-1" to true), repository.playedCalls)

            first.complete(Result.success(Unit))
            runCurrent()
            assertEquals(listOf("item-1" to true, "item-1" to false), repository.playedCalls)

            second.complete(Result.success(Unit))
            runCurrent()
            assertEquals(false, requireNotNull(presenter.state.value.content).played)
            presenter.close()
        }

    @Test
    fun rapidPlayedFailuresRestoreTheLastConfirmedValue() =
        runTest {
            val repository = FakeTvMediaRepository()
            val first = CompletableDeferred<Result<Unit>>()
            val second = CompletableDeferred<Result<Unit>>()
            repository.playedResponses.addLast(first)
            repository.playedResponses.addLast(second)
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.togglePlayed()
            presenter.togglePlayed()
            runCurrent()
            first.complete(Result.failure(IllegalStateException("first")))
            runCurrent()
            second.complete(Result.failure(IllegalStateException("second")))
            runCurrent()

            assertEquals(false, requireNotNull(presenter.state.value.content).played)
            presenter.close()
        }

    @Test
    fun rapidFavoriteTogglesPersistTheNewestIntentLast() =
        runTest {
            val repository = FakeTvMediaRepository()
            val first = CompletableDeferred<Result<Unit>>()
            val second = CompletableDeferred<Result<Unit>>()
            repository.favoriteResponses.addLast(first)
            repository.favoriteResponses.addLast(second)
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.toggleFavorite()
            presenter.toggleFavorite()
            runCurrent()
            assertEquals(listOf("item-1" to true), repository.favoriteCalls)

            first.complete(Result.success(Unit))
            runCurrent()
            assertEquals(listOf("item-1" to true, "item-1" to false), repository.favoriteCalls)

            second.complete(Result.success(Unit))
            runCurrent()
            assertEquals(false, requireNotNull(presenter.state.value.content).isFavorite)
            presenter.close()
        }

    @Test
    fun rapidFavoriteFailuresRestoreTheLastConfirmedValue() =
        runTest {
            val repository = FakeTvMediaRepository()
            val first = CompletableDeferred<Result<Unit>>()
            val second = CompletableDeferred<Result<Unit>>()
            repository.favoriteResponses.addLast(first)
            repository.favoriteResponses.addLast(second)
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.toggleFavorite()
            presenter.toggleFavorite()
            runCurrent()
            first.complete(Result.failure(IllegalStateException("first")))
            runCurrent()
            second.complete(Result.failure(IllegalStateException("second")))
            runCurrent()

            assertEquals(false, requireNotNull(presenter.state.value.content).isFavorite)
            presenter.close()
        }

    @Test
    fun nextUpSelectsItsSeasonAndExposesFocusTarget() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    detail = Result.success(testDetail(item = mediaItem(id = "series-1", kind = MediaKind.Series))),
                    seasons =
                        Result.success(
                            listOf(
                                mediaItem(id = "season-1", name = "Season 1"),
                                mediaItem(id = "season-2", name = "Season 2"),
                            ),
                        ),
                    episodesBySeasonId =
                        mapOf(
                            "season-1" to listOf(mediaItem(id = "episode-1", kind = MediaKind.Episode)),
                            "season-2" to listOf(mediaItem(id = "episode-5", kind = MediaKind.Episode)),
                        ),
                    nextUp =
                        Result.success(
                            listOf(
                                mediaItem(
                                    id = "episode-5",
                                    kind = MediaKind.Episode,
                                    seriesId = "series-1",
                                    seasonId = "season-2",
                                ),
                            ),
                        ),
                )
            val presenter = presenter(repository, itemId = "series-1")

            presenter.load()
            runCurrent()

            assertEquals(listOf(true), repository.nextUpIncludeResumableCalls)
            assertEquals("season-2", presenter.state.value.selectedSeasonId)
            assertEquals("episode-5", presenter.state.value.nextUpEpisodeId)
            assertEquals(
                listOf("episode-5"),
                presenter.state.value.episodes
                    .map { episode -> episode.id },
            )
            presenter.close()
        }

    private fun TestScope.presenter(
        repository: FakeTvMediaRepository,
        itemId: String = "item-1",
    ): TvItemDetailPresenter =
        TvItemDetailPresenter(
            session = testSession(),
            itemId = itemId,
            getItemDetail = GetItemDetailUseCase(repository),
            getSeriesSeasons = GetSeriesSeasonsUseCase(repository),
            getSeasonEpisodes = GetSeasonEpisodesUseCase(repository),
            getNextUp =
                com.jellyscope.core.domain.usecase
                    .GetNextUpUseCase(repository),
            getRelatedItems =
                com.jellyscope.core.domain.usecase
                    .GetRelatedItemsUseCase(repository),
            observePlaybackStopSettlement =
                ObservePlaybackStopSettlementUseCase(PlaybackStopSettlementRegistry()),
            setItemPlayed =
                com.jellyscope.core.domain.action
                    .SetItemPlayedAction(repository),
            setItemFavorite =
                com.jellyscope.core.domain.action
                    .SetItemFavoriteAction(repository),
            imageUrlBuilder = JellyfinImageUrlBuilder(),
            dispatchers = testDispatchers(StandardTestDispatcher(testScheduler)),
        )
}
