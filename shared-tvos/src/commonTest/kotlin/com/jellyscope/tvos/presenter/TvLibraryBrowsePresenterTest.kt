// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.data.local.LibrarySortStore
import com.jellyscope.core.data.local.SavedLibrarySort
import com.jellyscope.core.domain.action.SetLibrarySortAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.PagedItems
import com.jellyscope.core.domain.usecase.GetLibraryFiltersUseCase
import com.jellyscope.core.domain.usecase.GetLibraryItemsUseCase
import com.jellyscope.core.domain.usecase.GetLibrarySortUseCase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvLibraryBrowsePresenterTest {
    @Test
    fun loadsFirstPage() =
        runTest {
            val repository = FakeTvMediaRepository(pageProvider = pagedLibrary(totalCount = 100))
            val presenter = presenter(repository)

            presenter.load()
            runCurrent()

            val state = presenter.state.value
            assertFalse(state.isLoading)
            assertEquals(60, state.items.size)
            assertEquals(100, state.totalCount)
            assertFalse(state.endReached)
            presenter.close()
        }

    @Test
    fun loadMoreAppendsNextPageAndReachesEnd() =
        runTest {
            val repository = FakeTvMediaRepository(pageProvider = pagedLibrary(totalCount = 70))
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.loadMoreIfNeeded(focusedIndex = presenter.state.value.items.size - 1)
            runCurrent()

            val state = presenter.state.value
            assertEquals(70, state.items.size)
            assertTrue(state.endReached)
            assertEquals("item-69", state.items.last().id)
            presenter.close()
        }

    @Test
    fun loadMoreFarFromEndIsIgnored() =
        runTest {
            val repository = FakeTvMediaRepository(pageProvider = pagedLibrary(totalCount = 100))
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.loadMoreIfNeeded(focusedIndex = 0)
            runCurrent()

            assertEquals(60, presenter.state.value.items.size)
            presenter.close()
        }

    @Test
    fun pageFailureKeepsLoadedItemsAndSetsError() =
        runTest {
            var calls = 0
            val repository =
                FakeTvMediaRepository(
                    pageProvider = { request ->
                        calls += 1
                        if (calls == 1) {
                            pagedLibrary(totalCount = 100)(request)
                        } else {
                            Result.failure(IllegalStateException("down"))
                        }
                    },
                )
            val presenter = presenter(repository)
            presenter.load()
            runCurrent()

            presenter.loadMoreIfNeeded(focusedIndex = 59)
            runCurrent()

            val state = presenter.state.value
            assertEquals(60, state.items.size)
            assertEquals(TvErrorKind.Network, state.error)
            presenter.close()
        }

    private fun pagedLibrary(totalCount: Int): (com.jellyscope.core.domain.model.LibraryItemsRequest) -> Result<PagedItems> =
        { request ->
            val items =
                (request.startIndex until minOf(request.startIndex + request.limit, totalCount))
                    .map { index -> mediaItem(id = "item-$index") }
            Result.success(
                PagedItems(
                    items = items,
                    totalCount = totalCount,
                    startIndex = request.startIndex,
                ),
            )
        }

    private fun TestScope.presenter(repository: FakeTvMediaRepository): TvLibraryBrowsePresenter =
        object : LibrarySortStore {
            override fun savedSort(libraryKey: String): SavedLibrarySort? = null

            override suspend fun setSort(
                libraryKey: String,
                sortBy: LibrarySortBy,
                sortOrder: LibrarySortOrder,
            ) = Unit
        }.let { sortStore ->
            TvLibraryBrowsePresenter(
                session = testSession(),
                libraryId = "library-1",
                collectionType = LibraryCollectionType.Movies,
                getLibraryItems = GetLibraryItemsUseCase(repository),
                getLibraryFilters = GetLibraryFiltersUseCase(repository),
                getLibrarySort = GetLibrarySortUseCase(sortStore),
                setLibrarySort = SetLibrarySortAction(sortStore),
                imageUrlBuilder = JellyfinImageUrlBuilder(),
                dispatchers = testDispatchers(StandardTestDispatcher(testScheduler)),
            )
        }
}
