// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.data.local.LibrarySortStore
import com.jellyscope.core.data.local.SavedLibrarySort
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.SetLibrarySortAction
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFacet
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.PagedItems
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.usecase.GetLibraryFiltersUseCase
import com.jellyscope.core.domain.usecase.GetLibraryItemsUseCase
import com.jellyscope.core.domain.usecase.GetLibraryShuffleQueueUseCase
import com.jellyscope.core.domain.usecase.GetLibrarySortUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryBrowseViewModelTest {
    @Test
    fun savedSortReadAndWriteUseTheWorkDispatcher() =
        runTest {
            val mainScheduler = TestCoroutineScheduler()
            val workScheduler = TestCoroutineScheduler()
            val mainDispatcher = StandardTestDispatcher(mainScheduler)
            val workDispatcher = StandardTestDispatcher(workScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val librarySortStore = FakeLibrarySortStore()
                val repository = LibraryBrowseRepository(pages = mapOf(0 to Result.success(page(0, 0))))
                val viewModel = repository.libraryBrowseViewModel(workDispatcher, librarySortStore)

                mainScheduler.runCurrent()
                assertEquals(0, librarySortStore.readCount)
                workScheduler.runCurrent()
                assertEquals(1, librarySortStore.readCount)
                mainScheduler.runCurrent()

                viewModel.setSort(LibrarySortBy.DateCreated, LibrarySortOrder.Descending)
                mainScheduler.runCurrent()
                assertEquals(0, librarySortStore.writeCount)
                workScheduler.runCurrent()
                assertEquals(1, librarySortStore.writeCount)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun newerSortWinsOverDelayedSavedSortWithoutLaunchingStaleLoad() =
        runTest {
            val mainScheduler = TestCoroutineScheduler()
            val workScheduler = TestCoroutineScheduler()
            val mainDispatcher = StandardTestDispatcher(mainScheduler)
            val workDispatcher = StandardTestDispatcher(workScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val store =
                    FakeLibrarySortStore(
                        initialSorts =
                            mapOf(
                                "server-1:library-1" to
                                    SavedLibrarySort(LibrarySortBy.DatePlayed, LibrarySortOrder.Descending),
                            ),
                    )
                val delayedFirstWrite = store.delayNextSortWrite()
                val repository = LibraryBrowseRepository(pages = mapOf(0 to Result.success(page(0, 0))))
                val viewModel = repository.libraryBrowseViewModel(workDispatcher, store)

                mainScheduler.runCurrent()
                viewModel.setSort(LibrarySortBy.DateCreated, LibrarySortOrder.Descending)
                workScheduler.runCurrent()
                viewModel.setSort(LibrarySortBy.Name, LibrarySortOrder.Ascending)
                delayedFirstWrite.complete(Unit)
                workScheduler.runCurrent()
                mainScheduler.runCurrent()

                assertEquals(LibrarySortBy.Name, viewModel.state.value.sortBy)
                assertEquals(
                    listOf(LibrarySortBy.DateCreated, LibrarySortBy.Name),
                    repository.requests.map(LibraryItemsRequest::sortBy),
                )
                assertEquals(listOf<String?>("library-1"), repository.facetParentIds)
                assertEquals(
                    SavedLibrarySort(LibrarySortBy.Name, LibrarySortOrder.Ascending),
                    store.storedSort("server-1:library-1"),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun changingFiltersCancelsInFlightShuffle() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val deferred = CompletableDeferred<Result<List<String>>>()
                val repository =
                    LibraryBrowseRepository(
                        pages = mapOf(0 to Result.success(page(startIndex = 0, total = 0))),
                        shuffleDeferred = deferred,
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher)
                advanceUntilIdle()

                viewModel.shuffleAll()
                runCurrent()
                assertTrue(viewModel.state.value.isPreparingShuffle)

                viewModel.setFilters(LibraryFilterSelection(years = listOf(2024)))
                advanceUntilIdle()

                assertFalse(viewModel.state.value.isPreparingShuffle)
                assertEquals(1, repository.shuffleRequests.size)
                assertFalse(deferred.isCompleted)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun movieShuffleUsesActiveFiltersAndEmitsQueue() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    LibraryBrowseRepository(
                        pages = mapOf(0 to Result.success(page(startIndex = 0, total = 0))),
                        shuffleResult = Result.success(listOf("movie-2", "movie-1")),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher, collectionType = LibraryCollectionType.Movies)
                advanceUntilIdle()
                val filters = LibraryFilterSelection(years = listOf(2024))
                viewModel.setFilters(filters)
                advanceUntilIdle()
                val event = async { viewModel.events.first() }

                viewModel.shuffleAll()
                advanceUntilIdle()

                assertEquals(filters, repository.shuffleRequests.single().filters)
                assertEquals("library-1", repository.shuffleRequests.single().parentId)
                assertEquals(listOf("movie-2", "movie-1"), assertIs<LibraryBrowseEvent.LaunchShuffle>(event.await()).itemIds)
                assertFalse(viewModel.state.value.isPreparingShuffle)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun incompatibleSavedSortFallsBackToNameAscending() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store =
                    FakeLibrarySortStore(
                        mapOf(
                            "server-1:library-1" to
                                SavedLibrarySort(LibrarySortBy.VideoBitRate, LibrarySortOrder.Descending),
                        ),
                    )
                val repository = LibraryBrowseRepository(mapOf(0 to Result.success(page(0, 0))))
                val viewModel = repository.libraryBrowseViewModel(dispatcher, store, LibraryCollectionType.TvShows)
                advanceUntilIdle()

                assertEquals(LibrarySortBy.Name, viewModel.state.value.sortBy)
                assertEquals(LibrarySortOrder.Ascending, viewModel.state.value.sortOrder)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun pagingAppendsAndUsesTotalCountForHasMore() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    LibraryBrowseRepository(
                        pages =
                            mapOf(
                                0 to Result.success(page(startIndex = 0, total = 3, mediaItem("item-1"), mediaItem("item-2"))),
                                2 to Result.success(page(startIndex = 2, total = 3, mediaItem("item-3"))),
                            ),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher)
                advanceUntilIdle()

                val first = viewModel.state.value
                assertEquals(listOf("item-1", "item-2"), first.items.map { item -> item.id })
                assertEquals(2, first.totalCount)
                assertTrue(first.hasMore)

                viewModel.loadMore()
                advanceUntilIdle()

                val second = viewModel.state.value
                assertEquals(listOf("item-1", "item-2", "item-3"), second.items.map { item -> item.id })
                assertFalse(second.hasMore)
                assertEquals(listOf(0, 2), repository.requests.map { request -> request.startIndex })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun loadMoreGuardPreventsDuplicatePageFetches() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val deferredPage = CompletableDeferred<Result<PagedItems>>()
                val repository =
                    LibraryBrowseRepository(
                        pages =
                            mapOf(
                                0 to Result.success(page(startIndex = 0, total = 4, mediaItem("item-1"), mediaItem("item-2"))),
                            ),
                        deferredPages = mapOf(2 to ArrayDeque(listOf(deferredPage))),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher)
                advanceUntilIdle()

                viewModel.loadMore()
                viewModel.loadMore()
                runCurrent()

                assertEquals(listOf(0, 2), repository.requests.map { request -> request.startIndex })

                deferredPage.complete(Result.success(page(startIndex = 2, total = 4, mediaItem("item-3"), mediaItem("item-4"))))
                advanceUntilIdle()

                assertEquals(
                    listOf("item-1", "item-2", "item-3", "item-4"),
                    viewModel.state.value.items
                        .map { item -> item.id },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sortAndFilterChangesResetToFirstPage() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val librarySortStore = FakeLibrarySortStore()
                val repository =
                    LibraryBrowseRepository(
                        pages =
                            mapOf(
                                0 to Result.success(page(startIndex = 0, total = 1, mediaItem("initial"))),
                            ),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher, librarySortStore)
                advanceUntilIdle()

                viewModel.setSort(LibrarySortBy.DateCreated, LibrarySortOrder.Descending)
                advanceUntilIdle()
                viewModel.setFilters(LibraryFilterSelection(itemFilters = listOf(LibraryItemFilter.Favorite)))
                advanceUntilIdle()

                assertEquals(listOf(0, 0, 0), repository.requests.map { request -> request.startIndex })
                assertEquals(LibrarySortBy.DateCreated, repository.requests[1].sortBy)
                assertEquals(LibrarySortOrder.Descending, repository.requests[1].sortOrder)
                assertEquals(
                    SavedLibrarySort(
                        sortBy = LibrarySortBy.DateCreated,
                        sortOrder = LibrarySortOrder.Descending,
                    ),
                    librarySortStore.savedSort("server-1:library-1"),
                )
                assertEquals(listOf(LibraryItemFilter.Favorite), repository.requests[2].filters.itemFilters)
                assertEquals(
                    listOf("initial"),
                    viewModel.state.value.items
                        .map { item -> item.id },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun firstPageLoadUsesSavedSort() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val librarySortStore =
                    FakeLibrarySortStore(
                        mapOf(
                            "server-1:library-1" to
                                SavedLibrarySort(
                                    sortBy = LibrarySortBy.DatePlayed,
                                    sortOrder = LibrarySortOrder.Descending,
                                ),
                        ),
                    )
                val repository =
                    LibraryBrowseRepository(
                        pages = mapOf(0 to Result.success(page(startIndex = 0, total = 0))),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher, librarySortStore)
                advanceUntilIdle()

                assertEquals(LibrarySortBy.DatePlayed, viewModel.state.value.sortBy)
                assertEquals(LibrarySortOrder.Descending, viewModel.state.value.sortOrder)
                assertEquals(LibrarySortBy.DatePlayed, repository.requests.single().sortBy)
                assertEquals(LibrarySortOrder.Descending, repository.requests.single().sortOrder)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun loadsFacetsForUiFiltering() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    LibraryBrowseRepository(
                        pages = mapOf(0 to Result.success(page(startIndex = 0, total = 0))),
                        facets =
                            LibraryFacets(
                                genres = listOf(LibraryFacet(id = "genre-1", name = "Drama")),
                                studios = listOf(LibraryFacet(id = "studio-1", name = "Studio One")),
                                officialRatings = listOf("PG"),
                                tags = listOf("Award"),
                                years = listOf(2024),
                            ),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher)
                advanceUntilIdle()

                assertEquals(
                    listOf("Drama"),
                    viewModel.state.value.facets.genres
                        .map { facet -> facet.name },
                )
                assertEquals(
                    listOf("Studio One"),
                    viewModel.state.value.facets.studios
                        .map { facet -> facet.name },
                )
                assertEquals(listOf("PG"), viewModel.state.value.facets.officialRatings)
                assertEquals(listOf("Award"), viewModel.state.value.facets.tags)
                assertEquals(listOf(2024), viewModel.state.value.facets.years)
                assertEquals<List<String?>>(listOf("library-1"), repository.facetParentIds)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun silentRefreshKeepsVisibleItemsOnFailureAndDedupesInFlightLoads() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val failedRefresh = CompletableDeferred<Result<PagedItems>>()
                val refreshPages = ArrayDeque<CompletableDeferred<Result<PagedItems>>>()
                val repository =
                    LibraryBrowseRepository(
                        pages = mapOf(0 to Result.success(page(0, 1, mediaItem("initial")))),
                        deferredPages = mapOf(0 to refreshPages),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher)
                advanceUntilIdle()

                refreshPages.add(failedRefresh)
                viewModel.refreshSilently()
                viewModel.refreshSilently()
                runCurrent()

                assertEquals(2, repository.requests.count { request -> request.startIndex == 0 })
                assertEquals(
                    listOf("initial"),
                    viewModel.state.value.items
                        .map { item -> item.id },
                )
                assertFalse(viewModel.state.value.isLoading)

                failedRefresh.complete(Result.failure(IllegalStateException("library unavailable")))
                advanceUntilIdle()

                assertEquals(
                    listOf("initial"),
                    viewModel.state.value.items
                        .map { item -> item.id },
                )
                assertFalse(viewModel.state.value.error)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun silentRefreshRefetchesTheWholeLoadedWindowForPagedGrids() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val windowRefresh = CompletableDeferred<Result<PagedItems>>()
                val queuedPages = ArrayDeque<CompletableDeferred<Result<PagedItems>>>()
                val repository =
                    LibraryBrowseRepository(
                        pages =
                            mapOf(
                                0 to Result.success(page(startIndex = 0, total = 3, mediaItem("item-1"), mediaItem("item-2"))),
                                2 to Result.success(page(startIndex = 2, total = 3, mediaItem("item-3"))),
                            ),
                        deferredPages = mapOf(0 to queuedPages),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher)
                advanceUntilIdle()
                viewModel.loadMore()
                advanceUntilIdle()

                queuedPages.add(windowRefresh)
                viewModel.refreshSilently()
                runCurrent()

                // A paged grid refreshes its whole loaded window in one request
                // instead of collapsing back to page one; old items stay visible
                // until the fresh window arrives.
                assertEquals(3, repository.requests.last().limit)
                assertEquals(0, repository.requests.last().startIndex)
                assertEquals(
                    listOf("item-1", "item-2", "item-3"),
                    viewModel.state.value.items
                        .map { item -> item.id },
                )

                windowRefresh.complete(
                    Result.success(page(startIndex = 0, total = 3, mediaItem("item-1"), mediaItem("item-2"), mediaItem("item-3"))),
                )
                advanceUntilIdle()

                assertEquals(
                    listOf("item-1", "item-2", "item-3"),
                    viewModel.state.value.items
                        .map { item -> item.id },
                )
                assertTrue(viewModel.state.value.hasMore)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun newerFirstPageLoadWinsOverAnOlderSilentRefresh() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val staleRefresh = CompletableDeferred<Result<PagedItems>>()
                val newerLoad = CompletableDeferred<Result<PagedItems>>()
                val queuedPages = ArrayDeque<CompletableDeferred<Result<PagedItems>>>()
                val repository =
                    LibraryBrowseRepository(
                        pages = mapOf(0 to Result.success(page(0, 1, mediaItem("initial")))),
                        deferredPages = mapOf(0 to queuedPages),
                    )
                val viewModel = repository.libraryBrowseViewModel(dispatcher)
                advanceUntilIdle()

                queuedPages.add(staleRefresh)
                viewModel.refreshSilently()
                runCurrent()

                queuedPages.add(newerLoad)
                viewModel.setFilters(LibraryFilterSelection(years = listOf(2024)))
                runCurrent()

                newerLoad.complete(Result.success(page(0, 1, mediaItem("newer"))))
                advanceUntilIdle()

                staleRefresh.complete(Result.success(page(0, 1, mediaItem("stale"))))
                advanceUntilIdle()

                assertEquals(
                    listOf("newer"),
                    viewModel.state.value.items
                        .map { item -> item.id },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun MediaRepository.libraryBrowseViewModel(
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    librarySortStore: LibrarySortStore = FakeLibrarySortStore(),
    collectionType: LibraryCollectionType = LibraryCollectionType.Movies,
) = LibraryBrowseViewModel(
    session = session,
    parentId = "library-1",
    collectionType = collectionType,
    getLibraryItemsUseCase = GetLibraryItemsUseCase(this),
    getLibraryShuffleQueueUseCase = GetLibraryShuffleQueueUseCase(this),
    getLibraryFiltersUseCase = GetLibraryFiltersUseCase(this),
    imageUrlBuilder = JellyfinImageUrlBuilder(),
    getLibrarySortUseCase = GetLibrarySortUseCase(librarySortStore),
    setLibrarySortAction = SetLibrarySortAction(librarySortStore),
    pageSize = 2,
    workDispatcher = dispatcher,
)

private class FakeLibrarySortStore(
    initialSorts: Map<String, SavedLibrarySort> = emptyMap(),
) : LibrarySortStore {
    private val sortsByLibrary = initialSorts.toMutableMap()
    var readCount = 0
    var writeCount = 0
    private val sortWriteGates = ArrayDeque<CompletableDeferred<Unit>>()

    override fun savedSort(libraryKey: String): SavedLibrarySort? {
        readCount += 1
        return sortsByLibrary[libraryKey]
    }

    override suspend fun setSort(
        libraryKey: String,
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) {
        writeCount += 1
        sortWriteGates.removeFirstOrNull()?.await()
        sortsByLibrary[libraryKey] = SavedLibrarySort(sortBy = sortBy, sortOrder = sortOrder)
    }

    fun delayNextSortWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also(sortWriteGates::addLast)

    fun storedSort(libraryKey: String): SavedLibrarySort? = sortsByLibrary[libraryKey]
}

private class LibraryBrowseRepository(
    private val pages: Map<Int, Result<PagedItems>>,
    private val deferredPages: Map<Int, ArrayDeque<CompletableDeferred<Result<PagedItems>>>> = emptyMap(),
    private val facets: LibraryFacets = LibraryFacets(),
    private val shuffleResult: Result<List<String>> = Result.success(emptyList()),
    private val shuffleDeferred: CompletableDeferred<Result<List<String>>>? = null,
) : MediaRepository {
    val requests = mutableListOf<LibraryItemsRequest>()
    val facetParentIds = mutableListOf<String?>()
    val shuffleRequests = mutableListOf<LibraryShuffleRequest>()

    override suspend fun getLibraryShuffleQueue(request: LibraryShuffleRequest): Result<List<String>> {
        shuffleRequests += request
        return shuffleDeferred?.await() ?: shuffleResult
    }

    override suspend fun getLibraryItems(request: LibraryItemsRequest): Result<PagedItems> {
        requests += request
        val deferredQueue = deferredPages[request.startIndex]
        if (deferredQueue != null && deferredQueue.isNotEmpty()) {
            return deferredQueue.removeFirst().await()
        }
        return pages[request.startIndex] ?: Result.success(page(startIndex = request.startIndex, total = 0))
    }

    override suspend fun getLibraryFilters(parentId: String?): Result<LibraryFacets> {
        facetParentIds += parentId
        return Result.success(facets)
    }

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
    ): Result<Unit> = Result.success(Unit)
}

private fun page(
    startIndex: Int,
    total: Int,
    vararg items: MediaItem,
) = PagedItems(
    items = items.toList(),
    totalCount = total,
    startIndex = startIndex,
)

private fun mediaItem(id: String): MediaItem =
    MediaItem(
        id = id,
        name = "Item $id",
        kind = MediaKind.Movie,
        imageRefs = ImageRefs(primaryTag = "primary-$id"),
    )

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Server",
        userId = "user-1",
        userName = "User",
        accessToken = "token",
        deviceId = "device-1",
    )
