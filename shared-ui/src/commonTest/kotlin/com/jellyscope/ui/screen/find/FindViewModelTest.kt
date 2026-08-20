// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import com.jellyscope.core.data.local.RECENT_SEARCH_LIMIT
import com.jellyscope.core.data.local.RecentSearchStore
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.AddRecentSearchAction
import com.jellyscope.core.domain.action.ClearRecentSearchesAction
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.usecase.FindPersonsUseCase
import com.jellyscope.core.domain.usecase.GetRecentSearchesUseCase
import com.jellyscope.core.domain.usecase.SearchLibraryUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FindViewModelTest {
    @Test
    fun textSearchDebouncesForFourHundredMilliseconds() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel()

                viewModel.onQueryTextChanged("mat")
                advanceTimeBy(399)
                runCurrent()

                assertEquals(emptyList(), repository.searchQueries)

                advanceTimeBy(1)
                runCurrent()

                assertEquals(listOf("mat"), repository.searchQueries.map { it.text })
                assertEquals(listOf("mat"), repository.personTerms)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun chipOnlyBrowseRunsWithoutText() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel()

                viewModel.toggleGenre("Comedy")
                viewModel.selectRuntimeBucket(RuntimeBucket.Under30)
                viewModel.selectWatchedFilter(WatchedFilter.Unwatched)
                advanceUntilIdle()

                val lastQuery = repository.searchQueries.last()
                assertEquals("", lastQuery.text)
                assertEquals(listOf("Comedy"), lastQuery.genreNames)
                assertEquals(RuntimeBucket.Under30, lastQuery.runtimeBucket)
                assertEquals(WatchedFilter.Unwatched, lastQuery.watchedFilter)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun newerSearchCompletionWinsOverOlderSearch() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val oldSearch = CompletableDeferred<Result<FindResults>>()
                val newSearch = CompletableDeferred<Result<FindResults>>()
                val repository =
                    RecordingMediaRepository(
                        deferredSearchResults = ArrayDeque(listOf(oldSearch, newSearch)),
                    )
                val viewModel = repository.findViewModel()

                viewModel.onQueryTextChanged("old")
                advanceTimeBy(400)
                runCurrent()

                viewModel.onQueryTextChanged("new")
                advanceTimeBy(400)
                runCurrent()

                newSearch.complete(
                    Result.success(
                        FindResults(movies = listOf(mediaItem("new-movie"))),
                    ),
                )
                runCurrent()

                oldSearch.complete(
                    Result.success(
                        FindResults(movies = listOf(mediaItem("old-movie"))),
                    ),
                )
                advanceUntilIdle()

                assertEquals(listOf("old", "new"), repository.searchQueries.map { query -> query.text })
                assertEquals(
                    listOf("new-movie"),
                    viewModel.state.value.groupedResults.movies
                        .map { item -> item.id },
                )
                assertFalse(viewModel.state.value.isSearching)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun recentSearchesAreMostRecentFirstAndCappedAtEight() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel()

                (1..9).forEach { index ->
                    viewModel.onQueryTextChanged("term-$index")
                    advanceTimeBy(400)
                    runCurrent()
                    viewModel.commitRecentSearch()
                    runCurrent()
                }
                viewModel.onQueryTextChanged("term-3")
                advanceTimeBy(400)
                runCurrent()
                viewModel.commitRecentSearch()
                runCurrent()

                assertEquals(
                    listOf("term-3", "term-9", "term-8", "term-7", "term-6", "term-5", "term-4", "term-2"),
                    viewModel.state.value.recentSearches,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun stateIsRetainedWhenNavigationReturnsToSameViewModelInstance() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    RecordingMediaRepository(
                        results =
                            FindResults(
                                movies =
                                    listOf(
                                        MediaItem(
                                            id = "movie-1",
                                            name = "Movie",
                                            kind = MediaKind.Movie,
                                        ),
                                    ),
                            ),
                    )
                val viewModel = repository.findViewModel()

                viewModel.onQueryTextChanged("movie")
                advanceTimeBy(400)
                runCurrent()
                viewModel.selectResultTab(FindResultTab.Movies)

                val retainedState = viewModel.state.value

                assertEquals("movie", retainedState.queryText)
                assertEquals(FindResultTab.Movies, retainedState.selectedResultTab)
                assertEquals(listOf("movie-1"), retainedState.groupedResults.movies.map { it.id })
                assertFalse(retainedState.isSearching)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun persistedRecentSearchesLoadAndSaveAcrossViewModels() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeRecentSearchStore(listOf("saved"))
                val repository = RecordingMediaRepository()
                val first = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                assertEquals(listOf("saved"), first.state.value.recentSearches)

                first.onQueryTextChanged("matrix")
                advanceTimeBy(400)
                advanceUntilIdle()

                // Debounced live search must not persist intermediate queries.
                assertEquals(listOf("saved"), first.state.value.recentSearches)

                first.commitRecentSearch()
                advanceUntilIdle()

                assertEquals(listOf("matrix", "saved"), first.state.value.recentSearches)

                val second = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                assertEquals(listOf("matrix", "saved"), second.state.value.recentSearches)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun debouncedTypingNeverPersistsIntermediateQueries() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeRecentSearchStore()
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                listOf("m", "ma", "mat", "matrix").forEach { text ->
                    viewModel.onQueryTextChanged(text)
                    advanceTimeBy(400)
                    runCurrent()
                }
                advanceUntilIdle()

                assertEquals(listOf("matrix"), repository.searchQueries.map { it.text }.takeLast(1))
                assertEquals(emptyList(), store.stored())
                assertEquals(emptyList(), viewModel.state.value.recentSearches)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun commitRecentSearchNoOpsOnBlankQuery() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeRecentSearchStore()
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                viewModel.onQueryTextChanged("   ")
                advanceTimeBy(400)
                advanceUntilIdle()
                viewModel.commitRecentSearch()
                advanceUntilIdle()

                assertEquals(emptyList(), store.stored())
                assertEquals(emptyList(), viewModel.state.value.recentSearches)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun commitRecentSearchKeepsVisibleHistoryWhenPersistenceFails() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeRecentSearchStore(failAdds = true)
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                viewModel.onQueryTextChanged("matrix")
                advanceTimeBy(400)
                advanceUntilIdle()
                viewModel.commitRecentSearch()
                advanceUntilIdle()

                // Persistence failed, but the in-session history still shows the commit.
                assertEquals(listOf("matrix"), viewModel.state.value.recentSearches)
                assertEquals(emptyList(), store.stored())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun clearRecentSearchesClearsStateAndPersistedHistory() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeRecentSearchStore(listOf("saved", "matrix"))
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                viewModel.clearRecentSearches()
                advanceUntilIdle()

                assertEquals(emptyList(), viewModel.state.value.recentSearches)
                assertEquals(emptyList(), store.list(session.serverId))
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun silentRefreshUpdatesSuggestionsAndRecentsWithoutChangingTheActiveQuery() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeRecentSearchStore(listOf("saved"))
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                viewModel.onQueryTextChanged("matrix")
                advanceTimeBy(400)
                advanceUntilIdle()

                store.replace(listOf("remote"))
                repository.personResult = Result.success(listOf(Person(id = "person-1", name = "Keanu Reeves")))
                viewModel.refreshSilently()
                advanceUntilIdle()

                assertEquals("matrix", viewModel.state.value.queryText)
                assertEquals(listOf("remote"), viewModel.state.value.recentSearches)
                assertEquals(
                    listOf("person-1"),
                    viewModel.state.value.personSuggestions
                        .map { person -> person.id },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun silentRefreshKeepsVisibleRecentsOnFailureAndDedupesInFlightLoads() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val deferredRefresh = CompletableDeferred<List<String>>()
                val store = FakeRecentSearchStore(listOf("saved"))
                val repository = RecordingMediaRepository()
                val viewModel = repository.findViewModel(recentSearchStore = store, dispatcher = dispatcher)
                advanceUntilIdle()

                store.nextList = deferredRefresh
                viewModel.refreshSilently()
                viewModel.refreshSilently()
                runCurrent()

                assertEquals(2, store.listRequestCount)
                assertEquals(listOf("saved"), viewModel.state.value.recentSearches)

                deferredRefresh.completeExceptionally(IllegalStateException("recent searches unavailable"))
                advanceUntilIdle()

                assertEquals(listOf("saved"), viewModel.state.value.recentSearches)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun MediaRepository.findViewModel(
    recentSearchStore: RecentSearchStore? = null,
    // Installed Main is a StandardTestDispatcher (setMain), so off-main mapping is
    // driven by runCurrent().
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
) = FindViewModel(
    session = session,
    searchLibraryUseCase = SearchLibraryUseCase(this),
    findPersonsUseCase = FindPersonsUseCase(this),
    imageUrlBuilder = JellyfinImageUrlBuilder(),
    getRecentSearchesUseCase = recentSearchStore?.let { GetRecentSearchesUseCase(it) },
    addRecentSearchAction = recentSearchStore?.let { AddRecentSearchAction(it) },
    clearRecentSearchesAction = recentSearchStore?.let { ClearRecentSearchesAction(it) },
    workDispatcher = dispatcher,
)

private class RecordingMediaRepository(
    private val results: FindResults = FindResults(),
    private val deferredSearchResults: ArrayDeque<CompletableDeferred<Result<FindResults>>> = ArrayDeque(),
) : MediaRepository {
    val searchQueries = mutableListOf<FindQuery>()
    val personTerms = mutableListOf<String>()
    var personResult: Result<List<Person>> = Result.success(emptyList())

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(seriesId: String?): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> = Result.failure(UnsupportedOperationException())

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> {
        searchQueries += query
        if (deferredSearchResults.isNotEmpty()) {
            return deferredSearchResults.removeFirst().await()
        }
        return Result.success(results)
    }

    override suspend fun findPersons(term: String): Result<List<Person>> {
        personTerms += term
        return personResult
    }

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = Result.failure(UnsupportedOperationException())
}

private fun mediaItem(id: String) =
    MediaItem(
        id = id,
        name = id,
        kind = MediaKind.Movie,
    )

private class FakeRecentSearchStore(
    initialSearches: List<String> = emptyList(),
    private val failAdds: Boolean = false,
) : RecentSearchStore {
    private var searches = initialSearches
    var nextList: CompletableDeferred<List<String>>? = null
    var listRequestCount = 0

    fun stored(): List<String> = searches

    fun replace(nextSearches: List<String>) {
        searches = nextSearches
    }

    override suspend fun add(
        serverId: String,
        query: String,
    ) {
        if (failAdds) {
            throw IllegalStateException("recent-search persistence unavailable")
        }
        val trimmed = query.trim().takeIf { value -> value.isNotBlank() } ?: return
        searches =
            (listOf(trimmed) + searches.filterNot { search -> search.equals(trimmed, ignoreCase = true) })
                .take(RECENT_SEARCH_LIMIT)
    }

    override suspend fun list(serverId: String): List<String> {
        listRequestCount += 1
        val deferred = nextList
        if (deferred != null) {
            nextList = null
            return deferred.await()
        }
        return searches
    }

    override suspend fun clear(serverId: String) {
        searches = emptyList()
    }

    override suspend fun clearServerScoped() {
        searches = emptyList()
    }
}

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
