// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.ImageRefs
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryFacet
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaSuggestions
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.usecase.GetCollectionsUseCase
import com.jellyscope.core.domain.usecase.GetGenresUseCase
import com.jellyscope.core.domain.usecase.GetStudiosUseCase
import com.jellyscope.core.domain.usecase.GetSuggestionsUseCase
import com.jellyscope.core.domain.usecase.GetUpcomingEpisodesUseCase
import com.jellyscope.ui.component.MediaCardUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DiscoverViewModelTest {
    @Test
    fun loadsDiscoveryLandingRowsIndependently() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    DiscoverRepository(
                        genres = Result.success(listOf(facet("genre-1", "Drama", "genre-tag"))),
                        studios = Result.success(listOf(facet("studio-1", "Studio One", "studio-tag"))),
                        collections = Result.success(listOf(mediaItem("collection-1", "Collection"))),
                        suggestions =
                            Result.success(
                                MediaSuggestions(
                                    seedItem = mediaItem("seed-1", "Seed"),
                                    items = listOf(mediaItem("similar-1", "Similar")),
                                ),
                            ),
                        upcoming = Result.success(listOf(mediaItem("upcoming-1", "Upcoming"))),
                    )
                val viewModel = repository.discoverViewModel(dispatcher)
                advanceUntilIdle()

                val genres = assertIs<DiscoverListState.Content<DiscoverFacetUi>>(viewModel.state.value.genres)
                val studios = assertIs<DiscoverListState.Content<DiscoverFacetUi>>(viewModel.state.value.studios)
                val collections = assertIs<DiscoverListState.Content<MediaCardUi>>(viewModel.state.value.collections)
                val suggestions = assertIs<DiscoverSuggestionsState.Content>(viewModel.state.value.suggestions)
                val upcoming = assertIs<DiscoverListState.Content<MediaCardUi>>(viewModel.state.value.upcoming)

                assertEquals(listOf<String?>("library-1"), repository.genreParentIds)
                assertEquals(listOf<String?>("library-1"), repository.studioParentIds)
                assertEquals("Drama", genres.items.single().name)
                assertEquals(
                    "https://jellyfin.example/Items/genre-1/Images/Primary?tag=genre-tag&maxWidth=300&quality=90",
                    genres.items.single().imageUrl,
                )
                assertEquals("Studio One", studios.items.single().name)
                assertEquals("collection-1", collections.items.single().id)
                assertEquals("seed-1", suggestions.seedItem?.id)
                assertEquals(listOf("similar-1"), suggestions.items.map { item -> item.id })
                assertEquals("upcoming-1", upcoming.items.single().id)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun emptyResultsExposeEmptyStates() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = DiscoverRepository()
                val viewModel = repository.discoverViewModel(dispatcher)
                advanceUntilIdle()

                assertIs<DiscoverListState.Empty>(viewModel.state.value.genres)
                assertIs<DiscoverListState.Empty>(viewModel.state.value.studios)
                assertIs<DiscoverListState.Empty>(viewModel.state.value.collections)
                assertIs<DiscoverSuggestionsState.Empty>(viewModel.state.value.suggestions)
                assertIs<DiscoverListState.Empty>(viewModel.state.value.upcoming)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failuresExposeRetryableErrorStates() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    DiscoverRepository(
                        genres = Result.failure(IllegalStateException("genres")),
                        suggestions = Result.failure(IllegalStateException("suggestions")),
                    )
                val viewModel = repository.discoverViewModel(dispatcher)
                advanceUntilIdle()

                assertIs<DiscoverListState.Error>(viewModel.state.value.genres)
                assertIs<DiscoverSuggestionsState.Error>(viewModel.state.value.suggestions)
                assertIs<DiscoverListState.Empty>(viewModel.state.value.studios)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun silentRefreshKeepsVisibleRowsOnFailureAndDedupesInFlightRequests() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    DiscoverRepository(
                        genres = Result.success(listOf(facet("genre-1", "Drama", "genre-tag"))),
                        studios = Result.success(listOf(facet("studio-1", "Studio", "studio-tag"))),
                        collections = Result.success(listOf(mediaItem("collection-1", "Collection"))),
                        suggestions = Result.success(MediaSuggestions(seedItem = mediaItem("seed-1", "Seed"))),
                        upcoming = Result.success(listOf(mediaItem("upcoming-1", "Upcoming"))),
                    )
                val viewModel = repository.discoverViewModel(dispatcher)
                advanceUntilIdle()
                val initial = viewModel.state.value

                repository.genresResult = Result.failure(IllegalStateException("genres unavailable"))
                repository.studiosResult = Result.failure(IllegalStateException("studios unavailable"))
                repository.collectionsResult = Result.failure(IllegalStateException("collections unavailable"))
                repository.suggestionsResult = Result.failure(IllegalStateException("suggestions unavailable"))
                repository.upcomingResult = Result.failure(IllegalStateException("upcoming unavailable"))

                viewModel.refreshSilently()
                viewModel.refreshSilently()
                runCurrent()

                assertEquals(initial, viewModel.state.value)
                assertEquals(2, repository.genreRequestCount)
                assertEquals(2, repository.studioRequestCount)

                advanceUntilIdle()

                assertEquals(initial, viewModel.state.value)
                assertEquals(3, repository.genreRequestCount)
                assertEquals(3, repository.studioRequestCount)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun DiscoverRepository.discoverViewModel(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
    DiscoverViewModel(
        session = session,
        parentId = "library-1",
        getGenresUseCase = GetGenresUseCase(this),
        getStudiosUseCase = GetStudiosUseCase(this),
        getCollectionsUseCase = GetCollectionsUseCase(this),
        getSuggestionsUseCase = GetSuggestionsUseCase(this),
        getUpcomingEpisodesUseCase = GetUpcomingEpisodesUseCase(this),
        imageUrlBuilder = JellyfinImageUrlBuilder(),
        workDispatcher = dispatcher,
    )

internal class DiscoverRepository(
    genres: Result<List<LibraryFacet>> = Result.success(emptyList()),
    studios: Result<List<LibraryFacet>> = Result.success(emptyList()),
    collections: Result<List<MediaItem>> = Result.success(emptyList()),
    suggestions: Result<MediaSuggestions> = Result.success(MediaSuggestions(seedItem = null)),
    upcoming: Result<List<MediaItem>> = Result.success(emptyList()),
) : MediaRepository {
    val genreParentIds = mutableListOf<String?>()
    val studioParentIds = mutableListOf<String?>()
    var genresResult = genres
    var studiosResult = studios
    var collectionsResult = collections
    var suggestionsResult = suggestions
    var upcomingResult = upcoming
    var genreRequestCount = 0
    var studioRequestCount = 0

    override suspend fun getGenres(parentId: String?): Result<List<LibraryFacet>> {
        genreParentIds += parentId
        genreRequestCount += 1
        return genresResult
    }

    override suspend fun getStudios(parentId: String?): Result<List<LibraryFacet>> {
        studioParentIds += parentId
        studioRequestCount += 1
        return studiosResult
    }

    override suspend fun getCollections(parentId: String?): Result<List<MediaItem>> = collectionsResult

    override suspend fun getSuggestions(): Result<MediaSuggestions> = suggestionsResult

    override suspend fun getUpcomingEpisodes(): Result<List<MediaItem>> = upcomingResult

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

private fun facet(
    id: String,
    name: String,
    primaryTag: String,
) = LibraryFacet(
    id = id,
    name = name,
    imageRefs = ImageRefs(primaryTag = primaryTag),
)

private fun mediaItem(
    id: String,
    name: String,
) = MediaItem(
    id = id,
    name = name,
    kind = MediaKind.Movie,
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
