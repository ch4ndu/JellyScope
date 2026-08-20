// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.data.local.LibraryViewPreferencesStore
import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.action.SetLastLibraryIdAction
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.usecase.GetLastLibraryIdUseCase
import com.jellyscope.core.domain.usecase.GetUserLibrariesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LibraryTabViewModelTest {
    @Test
    fun storedLibrarySelectionIsRestoredOnLoad() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeLastLibraryStore(lastLibraryId = "movies")
                val viewModel = viewModel(store)
                runCurrent()

                val loaded = assertIs<LibraryTabUiState.Loaded>(viewModel.state.value)
                assertEquals("movies", loaded.selectedLibraryId)
                // Restoring an already-stored value must not rewrite it.
                assertEquals(emptyList(), store.writes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aStoredLibraryThatNoLongerExistsFallsBackToTheFirstAndRepersists() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeLastLibraryStore(lastLibraryId = "deleted-library")
                val viewModel = viewModel(store)
                runCurrent()

                val loaded = assertIs<LibraryTabUiState.Loaded>(viewModel.state.value)
                assertEquals("shows", loaded.selectedLibraryId)
                assertEquals(listOf("server:user" to "shows"), store.writes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun selectingALibraryPublishesAndPersistsItUnderTheAccountKey() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeLastLibraryStore(lastLibraryId = "shows")
                val viewModel = viewModel(store)
                runCurrent()

                viewModel.selectLibrary("movies")
                runCurrent()

                assertEquals("movies", assertIs<LibraryTabUiState.Loaded>(viewModel.state.value).selectedLibraryId)
                assertEquals(listOf("server:user" to "movies"), store.writes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun selectingAnUnknownLibraryIsIgnored() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeLastLibraryStore(lastLibraryId = "shows")
                val viewModel = viewModel(store)
                runCurrent()

                viewModel.selectLibrary("not-a-library")
                runCurrent()

                assertEquals("shows", assertIs<LibraryTabUiState.Loaded>(viewModel.state.value).selectedLibraryId)
                assertEquals(emptyList(), store.writes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aFailedLibraryLoadReportsErrorAndPersistsNothing() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeLastLibraryStore(lastLibraryId = null)
                val viewModel = viewModel(store, repository = FakeLibrariesRepository(failing = true))
                runCurrent()

                assertEquals(LibraryTabUiState.Error, viewModel.state.value)
                assertEquals(emptyList(), store.writes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun anAccountWithoutLibrariesSelectsNothing() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeLastLibraryStore(lastLibraryId = null)
                val viewModel = viewModel(store, repository = FakeLibrariesRepository(libraries = emptyList()))
                runCurrent()

                val loaded = assertIs<LibraryTabUiState.Loaded>(viewModel.state.value)
                assertNull(loaded.selectedLibraryId)
                assertEquals(emptyList(), store.writes)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun viewModel(
        store: FakeLastLibraryStore,
        repository: FakeLibrariesRepository = FakeLibrariesRepository(),
    ) = LibraryTabViewModel(
        session = session,
        getUserLibrariesUseCase = GetUserLibrariesUseCase(repository),
        getLastLibraryIdUseCase = GetLastLibraryIdUseCase(store),
        setLastLibraryIdAction = SetLastLibraryIdAction(store),
        // Installed Main is a StandardTestDispatcher (setMain), so off-main work is
        // driven by runCurrent().
        workDispatcher = Dispatchers.Main,
    )

    private companion object {
        val session =
            Session(
                serverUrl = "https://example.test",
                serverId = "server",
                serverName = "Server",
                userId = "user",
                userName = "User",
                accessToken = "token",
                deviceId = "device",
            )
    }
}

private class FakeLibrariesRepository(
    private val libraries: List<Library> =
        listOf(
            Library(id = "shows", name = "TV Shows", collectionType = LibraryCollectionType.TvShows),
            Library(id = "movies", name = "Movies", collectionType = LibraryCollectionType.Movies),
        ),
    private val failing: Boolean = false,
) : MediaRepository {
    override suspend fun getLibraries(): Result<List<Library>> =
        if (failing) {
            Result.failure(IllegalStateException("unavailable"))
        } else {
            Result.success(libraries)
        }

    // Only getLibraries() is exercised here; every other read is out of scope for
    // the library-tab selection contract.
    override suspend fun getContinueWatching(): Result<List<MediaItem>> = unused()

    override suspend fun getNextUp(seriesId: String?): Result<List<MediaItem>> = unused()

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> = unused()

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> = unused()

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> = unused()

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> = unused()

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> = unused()

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> = unused()

    override suspend fun getFavorites(): Result<List<MediaItem>> = unused()

    override suspend fun search(query: FindQuery): Result<FindResults> = unused()

    override suspend fun findPersons(term: String): Result<List<Person>> = unused()

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> = unused()

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by LibraryTabViewModel")
}

private class FakeLastLibraryStore(
    private var lastLibraryId: String?,
) : LibraryViewPreferencesStore {
    val writes = mutableListOf<Pair<String, String>>()
    private val rememberFlow = MutableStateFlow(true)

    override val rememberLastView: StateFlow<Boolean> = rememberFlow.asStateFlow()

    override fun lastLibraryId(accountKey: String): String? = lastLibraryId

    override fun savedView(libraryKey: String): LibraryInnerView? = null

    override suspend fun setRememberLastView(enabled: Boolean) {
        rememberFlow.value = enabled
    }

    override suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    ) {
        lastLibraryId = libraryId
        writes += accountKey to libraryId
    }

    override suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    ) = Unit
}
