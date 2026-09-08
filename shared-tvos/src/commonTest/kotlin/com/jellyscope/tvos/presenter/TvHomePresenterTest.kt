// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetFavoritesUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetRecentlyAddedUseCase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvHomePresenterTest {
    @Test
    fun rowsPublishInCanonicalOrderWithoutDedicatedHero() =
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

            val rows = presenter.state.value.rows
            assertEquals(
                listOf(
                    TvHomeRowKind.ContinueWatching,
                    TvHomeRowKind.Favorites,
                    TvHomeRowKind.NextUp,
                    TvHomeRowKind.RecentlyAdded,
                ),
                rows.map { row -> row.kind },
            )
            assertEquals(
                listOf(
                    TvHomeRowStatus.Content,
                    TvHomeRowStatus.Empty,
                    TvHomeRowStatus.Empty,
                    TvHomeRowStatus.Content,
                ),
                rows.map { row -> row.status },
            )
            assertEquals(listOf(false), repository.nextUpIncludeResumableCalls)
            val card = rows.first().items.first()
            assertEquals("item-1", card.id)
            assertEquals(40.0, card.progressPercent)
            assertTrue(requireNotNull(card.imageUrl).contains("/Items/item-1/Images/Primary"))
            presenter.close()
        }

    @Test
    fun oneRowFailureDoesNotBlockSuccessfulRows() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    continueWatching = Result.failure(IllegalStateException("down")),
                    recentlyAdded = Result.success(listOf(mediaItem(id = "item-2"))),
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            val rows = presenter.state.value.rows
            assertEquals(TvHomeRowStatus.Error, rows[0].status)
            assertEquals(TvErrorKind.Network, rows[0].error)
            assertEquals(TvHomeRowStatus.Content, rows[3].status)
            assertEquals(listOf("item-2"), rows[3].items.map { card -> card.id })
            presenter.close()
        }

    @Test
    fun retryReloadsOnlyTheRequestedRow() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    continueWatching = Result.success(listOf(mediaItem(id = "continue"))),
                    favorites = Result.failure(IllegalStateException("down")),
                )
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()
            repository.favorites = Result.success(listOf(mediaItem(id = "favorite")))

            presenter.retry(TvHomeRowKind.Favorites)
            runCurrent()

            val rows = presenter.state.value.rows
            assertEquals(listOf("continue"), rows[0].items.map { card -> card.id })
            assertEquals(TvHomeRowStatus.Content, rows[1].status)
            assertEquals(listOf("favorite"), rows[1].items.map { card -> card.id })
            presenter.close()
        }

    @Test
    fun allRowsFailIndependently() =
        runTest {
            val failure =
                Result.failure<List<com.jellyscope.core.domain.model.MediaItem>>(
                    IllegalStateException("down"),
                )
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

            val rows = presenter.state.value.rows
            assertTrue(rows.all { row -> row.status == TvHomeRowStatus.Error })
            assertTrue(rows.all { row -> row.error == TvErrorKind.Network })
            assertTrue(rows.all { row -> row.items.isEmpty() })
            presenter.close()
        }

    @Test
    fun emptyServerCollapsesEveryRowWithoutErrors() =
        runTest {
            val presenter = presenter(FakeTvMediaRepository())

            presenter.load()
            runCurrent()

            val rows = presenter.state.value.rows
            assertFalse(rows.isEmpty())
            assertTrue(rows.all { row -> row.status == TvHomeRowStatus.Empty })
            assertTrue(rows.all { row -> row.items.isEmpty() })
            assertTrue(rows.all { row -> row.error == null })
            presenter.close()
        }

    private fun TestScope.presenter(repository: FakeTvMediaRepository): TvHomePresenter =
        TvHomePresenter(
            session = testSession(),
            getContinueWatching = GetContinueWatchingUseCase(repository),
            getNextUp = GetNextUpUseCase(repository),
            getRecentlyAdded = GetRecentlyAddedUseCase(repository),
            getFavorites = GetFavoritesUseCase(repository),
            imageUrlBuilder = JellyfinImageUrlBuilder(),
            dispatchers = testDispatchers(StandardTestDispatcher(testScheduler)),
        )
}
