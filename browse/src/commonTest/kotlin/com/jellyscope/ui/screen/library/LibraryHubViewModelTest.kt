// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.data.local.LibraryViewPreferencesStore
import com.jellyscope.core.domain.action.SetSavedLibraryViewAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetLibraryRecommendationSectionUseCase
import com.jellyscope.core.domain.usecase.GetSavedLibraryViewUseCase
import com.jellyscope.core.domain.usecase.ObserveRememberLastLibraryViewUseCase
import com.jellyscope.ui.screen.discover.DiscoverRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryHubViewModelTest {
    @Test
    fun restoresAvailableSavedViewAndPersistsChanges() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val store = FakeLibraryViewPreferencesStore(saved = LibraryInnerView.Library)
                val viewModel = libraryHub(store, LibraryCollectionType.Movies, dispatcher)

                advanceUntilIdle()
                assertEquals(LibraryInnerView.Library, viewModel.state.value.selectedView)
                viewModel.selectView(LibraryInnerView.Recommended)
                testScheduler.advanceUntilIdle()
                assertEquals(LibraryInnerView.Recommended, store.saved)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun savedViewReadAndWriteUseTheWorkDispatcher() =
        runTest {
            val mainScheduler = TestCoroutineScheduler()
            val workScheduler = TestCoroutineScheduler()
            val mainDispatcher = StandardTestDispatcher(mainScheduler)
            val workDispatcher = StandardTestDispatcher(workScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val store = FakeLibraryViewPreferencesStore(saved = LibraryInnerView.Library)
                val viewModel = libraryHub(store, LibraryCollectionType.Movies, workDispatcher)

                mainScheduler.runCurrent()
                assertEquals(0, store.savedViewReadCount)
                workScheduler.runCurrent()
                assertEquals(1, store.savedViewReadCount)
                mainScheduler.runCurrent()

                viewModel.selectView(LibraryInnerView.Recommended)
                mainScheduler.runCurrent()
                assertEquals(0, store.savedViewWriteCount)
                workScheduler.runCurrent()
                assertEquals(1, store.savedViewWriteCount)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun newerViewSelectionWinsOverDelayedSavedView() =
        runTest {
            val mainScheduler = TestCoroutineScheduler()
            val workScheduler = TestCoroutineScheduler()
            val mainDispatcher = StandardTestDispatcher(mainScheduler)
            val workDispatcher = StandardTestDispatcher(workScheduler)
            Dispatchers.setMain(mainDispatcher)
            try {
                val store = FakeLibraryViewPreferencesStore(saved = LibraryInnerView.Recommended)
                val delayedFirstWrite = store.delayNextSavedViewWrite()
                val viewModel =
                    libraryHub(
                        store,
                        LibraryCollectionType.Movies,
                        workDispatcher,
                    )

                mainScheduler.runCurrent()
                viewModel.selectView(LibraryInnerView.Library)
                workScheduler.runCurrent()
                viewModel.selectView(LibraryInnerView.Recommended)
                delayedFirstWrite.complete(Unit)
                workScheduler.runCurrent()
                mainScheduler.runCurrent()

                assertEquals(LibraryInnerView.Recommended, viewModel.state.value.selectedView)
                assertEquals(LibraryInnerView.Recommended, store.saved)
                assertEquals(
                    listOf(LibraryInnerView.Library, LibraryInnerView.Recommended),
                    store.savedViewWrites,
                )

                val sameViewStore = FakeLibraryViewPreferencesStore(saved = LibraryInnerView.Library)
                val sameViewModel = libraryHub(sameViewStore, LibraryCollectionType.Movies, workDispatcher)
                mainScheduler.runCurrent()
                sameViewModel.selectView(LibraryInnerView.Recommended)
                workScheduler.runCurrent()
                mainScheduler.runCurrent()

                assertEquals(LibraryInnerView.Recommended, sameViewModel.state.value.selectedView)
                assertEquals(LibraryInnerView.Recommended, sameViewStore.saved)
                assertEquals(1, sameViewStore.savedViewWriteCount)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun disabledMemoryAndUnavailableSavedViewFallBackToRecommended() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val disabled = FakeLibraryViewPreferencesStore(remember = false, saved = LibraryInnerView.Library)
                assertEquals(
                    LibraryInnerView.Recommended,
                    libraryHub(disabled, LibraryCollectionType.Movies, dispatcher).state.value.selectedView,
                )

                // Genres/Collections survive as enum entries but are no longer
                // offered, so a preference persisted before alpha27 degrades to the
                // default view instead of selecting a view that cannot render.
                val retiredGenres = FakeLibraryViewPreferencesStore(saved = LibraryInnerView.Genres)
                assertEquals(
                    LibraryInnerView.Recommended,
                    libraryHub(retiredGenres, LibraryCollectionType.Movies, dispatcher).state.value.selectedView,
                )

                val retiredCollections = FakeLibraryViewPreferencesStore(saved = LibraryInnerView.Collections)
                assertEquals(
                    LibraryInnerView.Recommended,
                    libraryHub(retiredCollections, LibraryCollectionType.TvShows, dispatcher).state.value.selectedView,
                )
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private fun libraryHub(
    store: FakeLibraryViewPreferencesStore,
    collectionType: LibraryCollectionType,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) = DiscoverRepository().let { repository ->
    LibraryHubViewModel(
        session = session,
        parentId = "library-1",
        collectionType = collectionType,
        getSavedLibraryViewUseCase = GetSavedLibraryViewUseCase(store),
        observeRememberLastLibraryViewUseCase = ObserveRememberLastLibraryViewUseCase(store),
        setSavedLibraryViewAction = SetSavedLibraryViewAction(store),
        getLibraryRecommendationSectionUseCase = GetLibraryRecommendationSectionUseCase(repository),
        imageUrlBuilder = JellyfinImageUrlBuilder(),
        workDispatcher = dispatcher,
    )
}

private class FakeLibraryViewPreferencesStore(
    remember: Boolean = true,
    var saved: LibraryInnerView? = null,
) : LibraryViewPreferencesStore {
    var savedViewReadCount = 0
    var savedViewWriteCount = 0
    val savedViewWrites = mutableListOf<LibraryInnerView>()
    private val savedViewWriteGates = ArrayDeque<CompletableDeferred<Unit>>()
    private val rememberFlow = MutableStateFlow(remember)
    override val rememberLastView: StateFlow<Boolean> = rememberFlow.asStateFlow()

    override fun lastLibraryId(accountKey: String): String? = null

    override suspend fun setLastLibraryId(
        accountKey: String,
        libraryId: String,
    ) = Unit

    override fun savedView(libraryKey: String): LibraryInnerView? {
        savedViewReadCount += 1
        return saved
    }

    override suspend fun setRememberLastView(enabled: Boolean) {
        rememberFlow.value = enabled
    }

    override suspend fun setSavedView(
        libraryKey: String,
        view: LibraryInnerView,
    ) {
        savedViewWriteCount += 1
        savedViewWriteGates.removeFirstOrNull()?.await()
        saved = view
        savedViewWrites += view
    }

    fun delayNextSavedViewWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also(savedViewWriteGates::addLast)
}

private val session =
    Session(
        serverUrl = "https://example.test",
        serverId = "server",
        serverName = "Server",
        userId = "user",
        userName = "User",
        accessToken = "token",
        deviceId = "device",
    )
