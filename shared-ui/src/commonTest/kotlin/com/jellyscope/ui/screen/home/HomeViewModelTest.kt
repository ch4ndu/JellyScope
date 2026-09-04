// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetFavoritesUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetRecentlyAddedUseCase
import kotlinx.coroutines.CompletableDeferred
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

class HomeViewModelTest {
    @Test
    fun rowsLoadIndependentlyWhenOneRowIsStillPendingOrErrors() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = DeferredMediaRepository()
                val viewModel = repository.homeViewModel()
                runCurrent()

                assertIs<RowState.Loading>(viewModel.state.value.continueWatching)
                assertEquals(false, repository.nextUpIncludeResumable)

                repository.continueWatching.complete(Result.failure(IllegalStateException("boom")))
                repository.nextUp.complete(Result.success(listOf(mediaItem("next"))))
                repository.recentlyAdded.complete(Result.success(emptyList()))
                advanceUntilIdle()

                assertIs<RowState.Error>(viewModel.state.value.continueWatching)
                assertIs<RowState.Content>(viewModel.state.value.nextUp)
                assertIs<RowState.Empty>(viewModel.state.value.recentlyAdded)
                assertIs<RowState.Empty>(viewModel.state.value.featured)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun featuredUsesTopFiveRecentlyAddedItems() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    ImmediateMediaRepository(
                        recentlyAddedResults =
                            ArrayDeque(
                                listOf(
                                    Result.success((1..6).map { index -> mediaItem("recent-$index") }),
                                ),
                            ),
                    )
                val viewModel = repository.homeViewModel()
                advanceUntilIdle()

                val featured = assertIs<RowState.Content>(viewModel.state.value.featured)

                assertEquals(
                    listOf("recent-1", "recent-2", "recent-3", "recent-4", "recent-5"),
                    featured.items.map { item -> item.id },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun retryRecoversErroredRow() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    ImmediateMediaRepository(
                        continueResults =
                            ArrayDeque(
                                listOf(
                                    Result.failure(IllegalStateException("first")),
                                    Result.success(listOf(mediaItem("resume"))),
                                ),
                            ),
                    )
                val viewModel = repository.homeViewModel()
                advanceUntilIdle()

                assertIs<RowState.Error>(viewModel.state.value.continueWatching)

                viewModel.retry(HomeRow.ContinueWatching)
                advanceUntilIdle()

                val row = assertIs<RowState.Content>(viewModel.state.value.continueWatching)
                assertEquals(listOf("resume"), row.items.map { it.id })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun newerRowLoadWinsOverOlderCompletion() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val oldLoad = CompletableDeferred<Result<List<MediaItem>>>()
                val newLoad = CompletableDeferred<Result<List<MediaItem>>>()
                val repository =
                    QueuedContinueWatchingRepository(
                        continueResults = ArrayDeque(listOf(oldLoad, newLoad)),
                    )
                val viewModel = repository.homeViewModel()
                runCurrent()

                viewModel.retry(HomeRow.ContinueWatching)
                runCurrent()

                newLoad.complete(Result.success(listOf(mediaItem("new"))))
                runCurrent()

                val afterNew = assertIs<RowState.Content>(viewModel.state.value.continueWatching)
                assertEquals(listOf("new"), afterNew.items.map { item -> item.id })

                oldLoad.complete(Result.success(listOf(mediaItem("old"))))
                advanceUntilIdle()

                val afterOld = assertIs<RowState.Content>(viewModel.state.value.continueWatching)
                assertEquals(listOf("new"), afterOld.items.map { item -> item.id })
                assertEquals(2, repository.continueCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun silentRefreshKeepsContentOnFailureAndReplacesOnSuccess() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    ImmediateMediaRepository(
                        continueResults =
                            ArrayDeque(
                                listOf(
                                    Result.success(listOf(mediaItem("old"))),
                                    Result.failure(IllegalStateException("refresh failed")),
                                    Result.success(listOf(mediaItem("new"))),
                                ),
                            ),
                    )
                val viewModel = repository.homeViewModel()
                advanceUntilIdle()

                val initial = assertIs<RowState.Content>(viewModel.state.value.continueWatching)
                assertEquals(listOf("old"), initial.items.map { it.id })

                // A failing silent refresh keeps the cached row on screen.
                viewModel.refreshSilently()
                advanceUntilIdle()
                val afterFailure = assertIs<RowState.Content>(viewModel.state.value.continueWatching)
                assertEquals(listOf("old"), afterFailure.items.map { it.id })

                // A succeeding one swaps the data in without a Loading pass.
                viewModel.refreshSilently()
                advanceUntilIdle()
                val afterSuccess = assertIs<RowState.Content>(viewModel.state.value.continueWatching)
                assertEquals(listOf("new"), afterSuccess.items.map { it.id })
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun MediaRepository.homeViewModel() =
    HomeViewModel(
        session = session,
        getContinueWatchingUseCase = GetContinueWatchingUseCase(this),
        getNextUpUseCase = GetNextUpUseCase(this),
        getRecentlyAddedUseCase = GetRecentlyAddedUseCase(this),
        getFavoritesUseCase = GetFavoritesUseCase(this),
        imageUrlBuilder = JellyfinImageUrlBuilder(),
        // Installed Main is a StandardTestDispatcher (setMain), so off-main mapping
        // is driven by runCurrent().
        workDispatcher = Dispatchers.Main,
    )

private class QueuedContinueWatchingRepository(
    private val continueResults: ArrayDeque<CompletableDeferred<Result<List<MediaItem>>>>,
) : MediaRepository {
    var continueCalls = 0

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> {
        continueCalls += 1
        return continueResults.removeFirst().await()
    }

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

private class DeferredMediaRepository : MediaRepository {
    val continueWatching = CompletableDeferred<Result<List<MediaItem>>>()
    val nextUp = CompletableDeferred<Result<List<MediaItem>>>()
    val recentlyAdded = CompletableDeferred<Result<List<MediaItem>>>()
    var nextUpIncludeResumable: Boolean? = null

    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching() = continueWatching.await()

    override suspend fun getNextUp(
        seriesId: String?,
        includeResumable: Boolean,
    ): Result<List<MediaItem>> {
        nextUpIncludeResumable = includeResumable
        return nextUp.await()
    }

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

    override suspend fun getRecentlyAdded() = recentlyAdded.await()

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

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

private class ImmediateMediaRepository(
    private val continueResults: ArrayDeque<Result<List<MediaItem>>> =
        ArrayDeque(listOf(Result.success(emptyList()))),
    private val recentlyAddedResults: ArrayDeque<Result<List<MediaItem>>> =
        ArrayDeque(listOf(Result.success(emptyList()))),
) : MediaRepository {
    override suspend fun getLibraries(): Result<List<Library>> = Result.success(emptyList())

    override suspend fun getContinueWatching(): Result<List<MediaItem>> = continueResults.removeFirst()

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

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> =
        if (recentlyAddedResults.isEmpty()) {
            Result.success(emptyList())
        } else {
            recentlyAddedResults.removeFirst()
        }

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = Result.success(emptyList())

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

private fun mediaItem(id: String) =
    MediaItem(
        id = id,
        name = id,
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
