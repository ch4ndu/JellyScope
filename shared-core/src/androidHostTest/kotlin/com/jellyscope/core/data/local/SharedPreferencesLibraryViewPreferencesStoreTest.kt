// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.LibraryInnerView
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesLibraryViewPreferencesStoreTest {
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
    fun viewPreferencePersistsAndClearsOnlyMatchingServer() =
        runTest {
            val store = SharedPreferencesLibraryViewPreferencesStore(context)

            assertTrue(store.rememberLastView.value)
            store.setRememberLastView(false)
            store.setSavedView("server-a:user:library-1", LibraryInnerView.Genres)
            store.setSavedView("server-b:user:library-2", LibraryInnerView.Collections)
            store.clearServerScoped("server-a")

            val restored = SharedPreferencesLibraryViewPreferencesStore(context)
            assertFalse(restored.rememberLastView.value)
            assertNull(restored.savedView("server-a:user:library-1"))
            assertEquals(LibraryInnerView.Collections, restored.savedView("server-b:user:library-2"))
        }

    private fun preferences() = context.getSharedPreferences("library_view_preferences", Context.MODE_PRIVATE)
}
