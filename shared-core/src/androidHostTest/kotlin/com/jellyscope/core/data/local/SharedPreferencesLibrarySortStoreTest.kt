// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesLibrarySortStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest
    fun setUp() {
        preferences().edit().clear().commit()
    }

    @AfterTest
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun savedSortDefaultsToNull() {
        val store = SharedPreferencesLibrarySortStore(context)

        assertNull(store.savedSort("libA"))
    }

    @Test
    fun setSortPersistsSortByLibraryKey() =
        runTest {
            val store = SharedPreferencesLibrarySortStore(context)

            store.setSort("libA", LibrarySortBy.DateCreated, LibrarySortOrder.Descending)

            val restoredStore = SharedPreferencesLibrarySortStore(context)
            assertEquals(
                SavedLibrarySort(
                    sortBy = LibrarySortBy.DateCreated,
                    sortOrder = LibrarySortOrder.Descending,
                ),
                restoredStore.savedSort("libA"),
            )
        }

    @Test
    fun libraryKeysAreIsolated() =
        runTest {
            val store = SharedPreferencesLibrarySortStore(context)

            store.setSort("libA", LibrarySortBy.DateCreated, LibrarySortOrder.Descending)

            assertNull(store.savedSort("libB"))
        }

    private fun preferences() = context.getSharedPreferences(LIBRARY_SORT_TEST_PREFS_NAME, Context.MODE_PRIVATE)
}

private const val LIBRARY_SORT_TEST_PREFS_NAME = "library_sort"
