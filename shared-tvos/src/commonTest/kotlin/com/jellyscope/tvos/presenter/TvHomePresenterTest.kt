// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetRecentlyAddedUseCase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvHomePresenterTest {
    @Test
    fun continueWatchingBecomesHeroAndLeavesShelves() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    continueWatching =
                        Result.success(
                            listOf(
                                mediaItem(id = "item-1", primaryTag = "tag-1", playedPercentage = 40.0),
                            ),
                        ),
                    nextUp = Result.success(emptyList()),
                    recentlyAdded = Result.success(listOf(mediaItem(id = "item-2"))),
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            val state = presenter.state.value
            assertFalse(state.isLoading)
            assertEquals(listOf(false), repository.nextUpIncludeResumableCalls)
            assertEquals(TvHomeRowKind.ContinueWatching, state.hero?.kind)
            assertEquals(
                listOf(TvHomeRowKind.RecentlyAdded),
                state.rows.map { row -> row.kind },
            )
            val card = requireNotNull(state.hero).items.first()
            assertEquals("item-1", card.id)
            assertEquals(40.0, card.progressPercent)
            assertTrue(requireNotNull(card.imageUrl).contains("/Items/item-1/Images/Primary"))
            presenter.close()
        }

    @Test
    fun recentlyAddedFallsBackAsHeroWhenNothingInProgress() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    continueWatching = Result.failure(IllegalStateException("down")),
                    recentlyAdded = Result.success(listOf(mediaItem(id = "item-2"))),
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            assertEquals(
                TvHomeRowKind.RecentlyAdded,
                presenter.state.value.hero
                    ?.kind,
            )
            assertTrue(
                presenter.state.value.rows
                    .isEmpty(),
            )
            assertNull(presenter.state.value.error)
            presenter.close()
        }

    @Test
    fun favoritesAndPerLibraryLatestRowsComposeWithStableIds() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    recentlyAdded = Result.success(listOf(mediaItem(id = "item-2"))),
                    favorites = Result.success(listOf(mediaItem(id = "fav-1"))),
                    libraries =
                        Result.success(
                            listOf(
                                com.jellyscope.core.domain.model.Library(
                                    id = "lib-movies",
                                    name = "Movies",
                                    collectionType = com.jellyscope.core.domain.model.LibraryCollectionType.Movies,
                                ),
                                com.jellyscope.core.domain.model.Library(
                                    id = "lib-shows",
                                    name = "Shows",
                                    collectionType = com.jellyscope.core.domain.model.LibraryCollectionType.TvShows,
                                ),
                            ),
                        ),
                    recommendationRowsByParent =
                        mapOf(
                            "lib-movies" to
                                listOf(
                                    com.jellyscope.core.domain.model.LibraryRecommendationRow(
                                        key = "latest",
                                        section = com.jellyscope.core.domain.model.LibraryRecommendationSection.RecentlyAdded,
                                        items = listOf(mediaItem(id = "new-movie")),
                                    ),
                                ),
                        ),
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            val state = presenter.state.value
            assertEquals(TvHomeRowKind.RecentlyAdded, state.hero?.kind)
            assertEquals(
                listOf(TvHomeRowKind.Favorites, TvHomeRowKind.LatestInLibrary),
                state.rows.map { row -> row.kind },
            )
            val latestRow = state.rows.last()
            assertEquals("latest:lib-movies", latestRow.stableId)
            assertEquals("Movies", latestRow.libraryName)
            assertEquals(listOf("new-movie"), latestRow.items.map { card -> card.id })
            presenter.close()
        }

    @Test
    fun allRowsFailingSurfacesError() =
        runTest {
            val failure = Result.failure<List<com.jellyscope.core.domain.model.MediaItem>>(IllegalStateException("down"))
            val repository =
                FakeTvMediaRepository(
                    continueWatching = failure,
                    nextUp = failure,
                    recentlyAdded = failure,
                    favorites = failure,
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            assertEquals(TvErrorKind.Network, presenter.state.value.error)
            assertTrue(
                presenter.state.value.rows
                    .isEmpty(),
            )
            presenter.close()
        }

    @Test
    fun emptyServerIsAValidEmptyHomeNotAnError() =
        runTest {
            // Every source succeeds with nothing — a fresh server, not a failure.
            val presenter = presenter(FakeTvMediaRepository())

            presenter.load()
            runCurrent()

            assertNull(presenter.state.value.error)
            assertNull(presenter.state.value.hero)
            assertTrue(
                presenter.state.value.rows
                    .isEmpty(),
            )
            presenter.close()
        }

    private fun TestScope.presenter(repository: FakeTvMediaRepository): TvHomePresenter =
        TvHomePresenter(
            session = testSession(),
            getContinueWatching = GetContinueWatchingUseCase(repository),
            getNextUp = GetNextUpUseCase(repository),
            getRecentlyAdded = GetRecentlyAddedUseCase(repository),
            getFavorites =
                com.jellyscope.core.domain.usecase
                    .GetFavoritesUseCase(repository),
            getUserLibraries =
                com.jellyscope.core.domain.usecase
                    .GetUserLibrariesUseCase(repository),
            getLibraryRecommendationSection =
                com.jellyscope.core.domain.usecase
                    .GetLibraryRecommendationSectionUseCase(repository),
            imageUrlBuilder = JellyfinImageUrlBuilder(),
            dispatchers = testDispatchers(StandardTestDispatcher(testScheduler)),
        )
}
