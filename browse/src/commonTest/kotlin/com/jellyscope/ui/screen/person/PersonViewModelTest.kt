// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.person

import com.jellyscope.core.data.repository.MediaRepository
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
import com.jellyscope.core.domain.model.PersonFilmography
import com.jellyscope.core.domain.model.PersonHeader
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.usecase.GetPersonItemsPageUseCase
import com.jellyscope.core.domain.usecase.GetPersonItemsUseCase
import com.jellyscope.core.domain.usecase.GetPersonUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PersonViewModelTest {
    @Test
    fun consumesRawEmptyPagesWithinCapAndRetriesWithoutLosingProgress() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    PersonRepository(
                        pages =
                            mapOf(
                                0 to PersonFilmography(totalCount = 10, startIndex = 0, receivedCount = 2),
                                2 to PersonFilmography(totalCount = 10, startIndex = 2, receivedCount = 2),
                                4 to PersonFilmography(totalCount = 10, startIndex = 4, receivedCount = 2),
                                6 to
                                    PersonFilmography(
                                        movies = listOf(mediaItem("movie", MediaKind.Movie), mediaItem("movie", MediaKind.Movie)),
                                        totalCount = 10,
                                        startIndex = 6,
                                        receivedCount = 3,
                                    ),
                                9 to PersonFilmography(totalCount = 10, startIndex = 9, receivedCount = 0),
                            ),
                    )
                val viewModel = repository.personViewModel(dispatcher)
                advanceUntilIdle()
                val capped = assertIs<PersonUiState.Content>(viewModel.state.value)
                assertEquals(listOf(0, 2, 4), repository.pageStartIndexes)
                assertEquals(6, capped.consumedOffset)
                assertEquals(emptyList(), capped.movies)
                assertEquals(true, capped.error)
                assertEquals("Actor", capped.header?.name)

                viewModel.accept(PersonIntent.LoadMoreAutomatically)
                advanceUntilIdle()
                assertEquals(listOf(0, 2, 4), repository.pageStartIndexes)
                viewModel.accept(PersonIntent.Retry)
                advanceUntilIdle()
                val recovered = assertIs<PersonUiState.Content>(viewModel.state.value)
                assertEquals(listOf(0, 2, 4, 6), repository.pageStartIndexes)
                assertEquals(listOf("movie"), recovered.movies.map { it.id })
                assertEquals(9, recovered.consumedOffset)
                assertEquals(false, recovered.error)
                assertEquals(true, recovered.hasMore)

                viewModel.accept(PersonIntent.LoadMore)
                advanceUntilIdle()
                val terminal = assertIs<PersonUiState.Content>(viewModel.state.value)
                assertEquals(listOf(0, 2, 4, 6, 9), repository.pageStartIndexes)
                assertEquals(9, terminal.consumedOffset)
                assertEquals(false, terminal.hasMore)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun surfacesHeaderAndPagedFilmography() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    PersonRepository(
                        pages =
                            mapOf(
                                0 to
                                    PersonFilmography(
                                        movies = listOf(mediaItem("movie-1", MediaKind.Movie)),
                                        series = listOf(mediaItem("series-1", MediaKind.Series)),
                                        totalCount = 3,
                                        startIndex = 0,
                                    ),
                                2 to
                                    PersonFilmography(
                                        movies = listOf(mediaItem("movie-2", MediaKind.Movie)),
                                        totalCount = 3,
                                        startIndex = 2,
                                    ),
                            ),
                    )
                val viewModel = repository.personViewModel(dispatcher)
                advanceUntilIdle()

                val first = assertIs<PersonUiState.Content>(viewModel.state.value)
                assertEquals("Actor", first.header?.name)
                assertEquals("Actor biography.", first.header?.overview)
                assertEquals(
                    "https://jellyfin.example/Items/person-1/Images/Primary?tag=person-primary&maxWidth=300&quality=90",
                    first.header?.imageUrl,
                )
                assertEquals(listOf("movie-1"), first.movies.map { item -> item.id })
                assertEquals(listOf("series-1"), first.series.map { item -> item.id })
                assertEquals(true, first.hasMore)

                viewModel.accept(PersonIntent.LoadMore)
                advanceUntilIdle()

                val second = assertIs<PersonUiState.Content>(viewModel.state.value)
                assertEquals(listOf(0, 2), repository.pageStartIndexes)
                assertEquals(listOf("movie-1", "movie-2"), second.movies.map { item -> item.id })
                assertEquals(listOf("series-1"), second.series.map { item -> item.id })
                assertEquals(false, second.hasMore)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun PersonRepository.personViewModel(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
    PersonViewModel(
        session = session,
        personId = "person-1",
        getPersonItemsUseCase = GetPersonItemsUseCase(this),
        imageUrlBuilder = JellyfinImageUrlBuilder(),
        getPersonUseCase = GetPersonUseCase(this),
        getPersonItemsPageUseCase = GetPersonItemsPageUseCase(this),
        workDispatcher = dispatcher,
    )

private class PersonRepository(
    private val pages: Map<Int, PersonFilmography>,
) : MediaRepository {
    val pageStartIndexes = mutableListOf<Int>()

    override suspend fun getPerson(personId: String): Result<PersonHeader> =
        Result.success(
            PersonHeader(
                id = personId,
                name = "Actor",
                overview = "Actor biography.",
                imageRefs = ImageRefs(primaryTag = "person-primary"),
            ),
        )

    override suspend fun getPersonItemsPage(
        personId: String,
        startIndex: Int,
        limit: Int,
    ): Result<PersonFilmography> {
        pageStartIndexes += startIndex
        return Result.success(pages[startIndex] ?: PersonFilmography(startIndex = startIndex))
    }

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

private fun mediaItem(
    id: String,
    kind: MediaKind,
) = MediaItem(
    id = id,
    name = id,
    kind = kind,
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
