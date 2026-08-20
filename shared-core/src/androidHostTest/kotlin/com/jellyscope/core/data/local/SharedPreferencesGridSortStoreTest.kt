// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesGridSortStoreTest {
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
    fun setSortPersistsSortByGridKey() =
        runTest {
            val store = SharedPreferencesGridSortStore(context)

            store.setSort("server-a:resume", "DateCreated")

            assertEquals("DateCreated", SharedPreferencesGridSortStore(context).savedSort("server-a:resume"))
        }

    @Test
    fun clearServerScopedRemovesOnlyMatchingServer() =
        runTest {
            val store = SharedPreferencesGridSortStore(context)

            store.setSort("server-a:resume", "DateCreated")
            store.setSort("server-b:next-up", "Name")
            store.clearServerScoped("server-a")

            val restoredStore = SharedPreferencesGridSortStore(context)
            assertNull(restoredStore.savedSort("server-a:resume"))
            assertEquals("Name", restoredStore.savedSort("server-b:next-up"))
        }

    private fun preferences() = context.getSharedPreferences(GRID_SORT_TEST_PREFS_NAME, Context.MODE_PRIVATE)
}

private const val GRID_SORT_TEST_PREFS_NAME = "grid_sort"
