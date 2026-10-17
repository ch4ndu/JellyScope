// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.grid

import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.SetGridSortAction
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.usecase.GetGridSortUseCase
import com.jellyscope.core.domain.usecase.GetRibbonItemsUseCase
import com.jellyscope.ui.screen.home.HomeRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GridViewModelTest {
    @Test
    fun mapsHomeRowsToCoreRibbons() {
        assertEquals(MediaRibbon.ContinueWatching, HomeRow.ContinueWatching.toMediaRibbon())
        assertEquals(MediaRibbon.Favorites, HomeRow.Favorites.toMediaRibbon())
        assertEquals(MediaRibbon.NextUp, HomeRow.NextUp.toMediaRibbon())
        assertEquals(MediaRibbon.RecentlyAdded, HomeRow.RecentlyAdded.toMediaRibbon())
    }

    @Test
    fun loadsRibbonItemsWithExpandedLimitAndMapsCards() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    GridMediaRepository(
                        Result.success(
                            listOf(
                                media(
                                    id = "movie-1",
                                    name = "Movie",
                                    primaryTag = "primary",
                                ),
                            ),
                        ),
                    )
                val viewModel = repository.gridViewModel(HomeRow.Favorites, dispatcher)

                advanceUntilIdle()

                val content = assertIs<GridUiState.Content>(viewModel.state.value)
                assertEquals(listOf(MediaRibbon.Favorites to 500), repository.ribbonRequests)
                assertEquals(HomeRow.Favorites, content.row)
                assertEquals(listOf("Movie"), content.items.map { item -> item.title })
                assertEquals(
                    "https://jellyfin.example/Items/movie-1/Images/Primary?tag=primary&maxWidth=300&quality=90",
                    content.items.single().imageUrl,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun defaultSortKeepsServerOrderAfterOtherSorts() =
        runTest {
            withGridFixture(
                media(id = "b", name = "Bravo"),
                media(id = "a", name = "alpha"),
                media(id = "c", name = "charlie"),
            ) { fixture ->
                fixture.viewModel.setSort(GridSort.Title)
                advanceUntilIdle()
                assertEquals(listOf("a", "b", "c"), fixture.contentItems())

                fixture.viewModel.setSort(GridSort.Default)
                advanceUntilIdle()
                assertEquals(listOf("b", "a", "c"), fixture.contentItems())
            }
        }

    @Test
    fun titleSortsCaseInsensitiveAscending() =
        runTest {
            withGridFixture(
                media(id = "b", name = "bravo"),
                media(id = "a", name = "Alpha"),
                media(id = "c", name = "charlie"),
            ) { fixture ->
                fixture.viewModel.setSort(GridSort.Title)
                advanceUntilIdle()

                assertEquals(listOf("a", "b", "c"), fixture.contentItems())
            }
        }

    @Test
    fun dateReleaseAndRuntimeSortsKeepNullsLast() =
        runTest {
            data class SortCase(
                val sort: GridSort,
                val items: List<MediaItem>,
                val expectedIds: List<String>,
            )
            listOf(
                SortCase(
                    sort = GridSort.DateAdded,
                    items =
                        listOf(
                            media(id = "old", dateCreated = "2023-01-01T00:00:00Z"),
                            media(id = "none"),
                            media(id = "new", dateCreated = "2024-02-01T00:00:00Z"),
                        ),
                    expectedIds = listOf("new", "old", "none"),
                ),
                SortCase(
                    sort = GridSort.ReleaseDate,
                    items =
                        listOf(
                            media(id = "premiere", premiereDate = "2023-08-01T00:00:00Z"),
                            media(id = "none"),
                            media(id = "year", productionYear = 2024),
                        ),
                    expectedIds = listOf("year", "premiere", "none"),
                ),
                SortCase(
                    sort = GridSort.Runtime,
                    items =
                        listOf(
                            media(id = "short", runtimeMinutes = 20),
                            media(id = "none"),
                            media(id = "long", runtimeMinutes = 120),
                        ),
                    expectedIds = listOf("long", "short", "none"),
                ),
            ).forEach { case ->
                withGridFixture(*case.items.toTypedArray()) { fixture ->
                    fixture.viewModel.setSort(case.sort)
                    advanceUntilIdle()

                    assertEquals(case.expectedIds, fixture.contentItems())
                }
            }
        }

    @Test
    fun newestSortWinsInBothStateAndPersistenceWhenTwoSortsRace() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeGridSortStore()
                val repository =
                    GridMediaRepository(
                        Result.success(
                            listOf(
                                media(id = "b", name = "Beta"),
                                media(id = "a", name = "Alpha"),
                            ),
                        ),
                    )
                val viewModel = repository.gridViewModel(HomeRow.RecentlyAdded, dispatcher, store)
                advanceUntilIdle()

                viewModel.setSort(GridSort.Title)
                viewModel.setSort(GridSort.Runtime)
                advanceUntilIdle()

                assertEquals(GridSort.Runtime, (viewModel.state.value as GridUiState.Content).sort)
                assertEquals(GridSort.Runtime.name, store.savedSort("server-1:${HomeRow.RecentlyAdded.name}"))
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun sortCompletingAfterANewerLoadDoesNotOverwriteContent() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    GridMediaRepository(
                        Result.success(
                            listOf(
                                media(id = "b", name = "Beta"),
                                media(id = "a", name = "Alpha"),
                            ),
                        ),
                    )
                val viewModel = repository.gridViewModel(HomeRow.RecentlyAdded, dispatcher)
                advanceUntilIdle()

                // Start a sort, then invalidate it with a reload before it commits.
                viewModel.setSort(GridSort.Title)
                viewModel.retry()
                advanceUntilIdle()

                // The reload republished content; the older sort must not have
                // overwritten it with cards mapped from the pre-reload snapshot.
                val content = viewModel.state.value as GridUiState.Content
                assertEquals(2, content.items.size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aLoadRacingASortDoesNotDropThePersistedUserChoice() =
        runTest {
            // Regression: the persistence write records user INTENT, so a
            // concurrent load() must not cancel it. Guarding that write on the
            // shared load/sort generation silently skipped it, leaving the
            // displayed sort diverged from the persisted one after relaunch —
            // the same class of defect the guard exists to close.
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeGridSortStore()
                val repository =
                    GridMediaRepository(
                        Result.success(
                            listOf(
                                media(id = "b", name = "Beta"),
                                media(id = "a", name = "Alpha"),
                            ),
                        ),
                    )
                val viewModel = repository.gridViewModel(HomeRow.RecentlyAdded, dispatcher, store)
                advanceUntilIdle()

                viewModel.setSort(GridSort.Title)
                viewModel.retry()
                advanceUntilIdle()

                assertEquals(GridSort.Title.name, store.savedSort("server-1:${HomeRow.RecentlyAdded.name}"))
                assertEquals(GridSort.Title, (viewModel.state.value as GridUiState.Content).sort)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private suspend fun TestScope.withGridFixture(
    vararg items: MediaItem,
    block: (GridFixture) -> Unit,
) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
        val repository = GridMediaRepository(Result.success(items.toList()))
        val viewModel = repository.gridViewModel(HomeRow.RecentlyAdded, dispatcher)
        advanceUntilIdle()
        block(GridFixture(viewModel))
    } finally {
        Dispatchers.resetMain()
    }
}

private data class GridFixture(
    val viewModel: GridViewModel,
) {
    fun contentItems(): List<String> =
        (viewModel.state.value as GridUiState.Content)
            .items
            .map { item -> item.id }
}

private fun GridMediaRepository.gridViewModel(
    row: HomeRow,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    gridSortStore: FakeGridSortStore = FakeGridSortStore(),
): GridViewModel =
    GridViewModel(
        session = session,
        defaultDispatcher = dispatcher,
        // The persistence write launches on ioDispatcher, which defaults to the
        // real platform dispatcher. Tests must supply the test dispatcher or that
        // coroutine escapes runTest's control and any persistence assertion flakes.
        ioDispatcher = dispatcher,
        row = row,
        getRibbonItemsUseCase = GetRibbonItemsUseCase(this),
        imageUrlBuilder = JellyfinImageUrlBuilder(),
        getGridSortUseCase = GetGridSortUseCase(gridSortStore),
        setGridSortAction = SetGridSortAction(gridSortStore),
    )

private class FakeGridSortStore : com.jellyscope.core.data.local.GridSortStore {
    private val sorts = mutableMapOf<String, String>()

    override fun savedSort(gridKey: String): String? = sorts[gridKey]

    override suspend fun setSort(
        gridKey: String,
        sortName: String,
    ) {
        sorts[gridKey] = sortName
    }
}

private class GridMediaRepository(
    private val ribbonResult: Result<List<MediaItem>>,
) : MediaRepository {
    val ribbonRequests = mutableListOf<Pair<MediaRibbon, Int>>()

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun getNextUp(
        seriesId: String?,
        includeResumable: Boolean,
    ): Result<List<MediaItem>> = Result.success(emptyList())

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
    ): Result<List<MediaItem>> {
        ribbonRequests += ribbon to limit
        return ribbonResult
    }

    override suspend fun getFavorites(): Result<List<MediaItem>> = Result.success(emptyList())

    override suspend fun search(query: FindQuery): Result<FindResults> = Result.success(FindResults())

    override suspend fun findPersons(term: String): Result<List<Person>> = Result.success(emptyList())

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

private fun media(
    id: String,
    name: String = id,
    dateCreated: String? = null,
    premiereDate: String? = null,
    productionYear: Int? = null,
    runtimeMinutes: Long? = null,
    sizeBytes: Long? = null,
    primaryTag: String? = null,
): MediaItem =
    MediaItem(
        id = id,
        name = name,
        kind = MediaKind.Movie,
        runtime = runtimeMinutes?.minutes,
        premiereDate = premiereDate?.let(Instant::parse),
        dateCreated = dateCreated?.let(Instant::parse),
        sizeBytes = sizeBytes,
        productionYear = productionYear,
        imageRefs = ImageRefs(primaryTag = primaryTag),
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
