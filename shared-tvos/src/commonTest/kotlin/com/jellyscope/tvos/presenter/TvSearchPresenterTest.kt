// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.AddRecentSearchAction
import com.jellyscope.core.domain.action.ClearRecentSearchesAction
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.usecase.GetRecentSearchesUseCase
import com.jellyscope.core.domain.usecase.SearchLibraryUseCase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSearchPresenterTest {
    @Test
    fun queryDebouncesAndSectionsResults() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    searchProvider = { query ->
                        Result.success(
                            FindResults(
                                movies = listOf(mediaItem(id = "movie-${query.text}")),
                                shows = listOf(mediaItem(id = "show-1", kind = com.jellyscope.core.domain.model.MediaKind.Series)),
                            ),
                        )
                    },
                )
            val fixture = fixture(repository)

            fixture.presenter.setQuery("al")
            fixture.presenter.setQuery("alien")
            advanceTimeBy(400L)
            runCurrent()

            val state = fixture.presenter.state.value
            // Only the final debounced query ran.
            assertEquals(listOf("movie-alien"), state.movies.map { card -> card.id })
            assertEquals(listOf("show-1"), state.shows.map { card -> card.id })
            assertTrue(!state.isSearching)
            presenterClose(fixture)
        }

    @Test
    fun staleResponseNeverOverwritesNewerQuery() =
        runTest {
            var calls = 0
            val repository =
                FakeTvMediaRepository(
                    searchProvider = { query ->
                        calls += 1
                        Result.success(FindResults(movies = listOf(mediaItem(id = "movie-${query.text}"))))
                    },
                )
            val fixture = fixture(repository)

            fixture.presenter.submit("first")
            runCurrent()
            assertEquals(
                listOf("movie-first"),
                fixture.presenter.state.value.movies
                    .map { card -> card.id },
            )

            // A newer query supersedes; the old generation's result is dropped
            // even though both jobs complete.
            fixture.presenter.setQuery("second")
            fixture.presenter.submit("third")
            runCurrent()
            assertEquals(
                listOf("movie-third"),
                fixture.presenter.state.value.movies
                    .map { card -> card.id },
            )
            presenterClose(fixture)
        }

    @Test
    fun successfulSearchPersistsRecentAndClearWorks() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    searchProvider = {
                        Result.success(FindResults(movies = listOf(mediaItem(id = "movie-1"))))
                    },
                )
            val fixture = fixture(repository)

            fixture.presenter.submit("alien")
            runCurrent()
            assertEquals(listOf("alien"), fixture.presenter.state.value.recentSearches)
            assertEquals(fixture.store.expectedDispatcher, fixture.store.observedAddDispatcher)
            assertEquals(fixture.store.expectedDispatcher, fixture.store.observedListDispatcher)

            fixture.presenter.clearRecents()
            runCurrent()
            assertTrue(
                fixture.presenter.state.value.recentSearches
                    .isEmpty(),
            )
            assertEquals(fixture.store.expectedDispatcher, fixture.store.observedClearDispatcher)
            presenterClose(fixture)
        }

    @Test
    fun failedQueryClearsPreviousResults() =
        runTest {
            var fail = false
            val repository =
                FakeTvMediaRepository(
                    searchProvider = {
                        if (fail) {
                            Result.failure(IllegalStateException("down"))
                        } else {
                            Result.success(FindResults(movies = listOf(mediaItem(id = "movie-1"))))
                        }
                    },
                )
            val fixture = fixture(repository)

            fixture.presenter.submit("first")
            runCurrent()
            assertTrue(fixture.presenter.state.value.hasResults)

            fail = true
            fixture.presenter.submit("second")
            runCurrent()
            val state = fixture.presenter.state.value
            assertTrue(!state.hasResults)
            assertEquals(TvErrorKind.Network, state.error)
            presenterClose(fixture)
        }

    @Test
    fun debouncedEditsNeverPersistOnlyCommitsDo() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    searchProvider = {
                        Result.success(FindResults(movies = listOf(mediaItem(id = "movie-1"))))
                    },
                )
            val fixture = fixture(repository)

            fixture.presenter.setQuery("partial")
            advanceTimeBy(400L)
            runCurrent()
            assertTrue(fixture.presenter.state.value.hasResults)
            assertTrue(
                fixture.presenter.state.value.recentSearches
                    .isEmpty(),
            )

            fixture.presenter.submit("committed")
            runCurrent()
            assertEquals(listOf("committed"), fixture.presenter.state.value.recentSearches)
            presenterClose(fixture)
        }

    @Test
    fun recentsPersistenceFailureNeverBlocksTheSearch() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    searchProvider = {
                        Result.success(FindResults(movies = listOf(mediaItem(id = "movie-1"))))
                    },
                )
            val store = FakeRecentSearchStore()
            store.failAdds = true
            val fixture = fixture(repository, store = store)

            fixture.presenter.submit("alien")
            runCurrent()

            val state = fixture.presenter.state.value
            assertTrue(state.hasResults)
            assertTrue(!state.isSearching)
            presenterClose(fixture)
        }

    @Test
    fun blankQueryReturnsToIdleWithRecents() =
        runTest {
            val repository =
                FakeTvMediaRepository(
                    searchProvider = {
                        Result.success(FindResults(movies = listOf(mediaItem(id = "movie-1"))))
                    },
                )
            val fixture = fixture(repository, recents = listOf("older"))

            fixture.presenter.submit("alien")
            runCurrent()
            assertTrue(fixture.presenter.state.value.hasResults)

            fixture.presenter.setQuery("")
            runCurrent()
            val state = fixture.presenter.state.value
            assertTrue(!state.hasResults)
            assertTrue(state.recentSearches.contains("older"))
            presenterClose(fixture)
        }

    @Test
    fun closingDuringRecentsRefreshPreservesPublishedRecents() =
        runTest {
            val repository = FakeTvMediaRepository(searchProvider = { Result.success(FindResults()) })
            val store = FakeRecentSearchStore(listOf("older"))
            val fixture = fixture(repository, store = store)
            runCurrent()
            assertEquals(listOf("older"), fixture.presenter.state.value.recentSearches)

            store.blockLists = true
            fixture.presenter.submit("alien")
            runCurrent()
            fixture.presenter.close()
            runCurrent()

            assertEquals(listOf("older"), fixture.presenter.state.value.recentSearches)
        }

    private class Fixture(
        val presenter: TvSearchPresenter,
        val store: FakeRecentSearchStore,
    )

    private fun presenterClose(fixture: Fixture) = fixture.presenter.close()

    private fun TestScope.fixture(
        repository: FakeTvMediaRepository,
        recents: List<String> = emptyList(),
        store: FakeRecentSearchStore = FakeRecentSearchStore(recents),
    ): Fixture {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        val workDispatcher = StandardTestDispatcher(testScheduler)
        store.expectedDispatcher = workDispatcher
        val presenter =
            TvSearchPresenter(
                session = testSession(),
                searchLibrary = SearchLibraryUseCase(repository),
                getRecentSearches = GetRecentSearchesUseCase(store),
                addRecentSearch = AddRecentSearchAction(store),
                clearRecentSearches = ClearRecentSearchesAction(store),
                imageUrlBuilder = JellyfinImageUrlBuilder(),
                dispatchers =
                    TvosDispatchers(
                        main = mainDispatcher,
                        work = workDispatcher,
                    ),
            )
        return Fixture(presenter, store)
    }
}
