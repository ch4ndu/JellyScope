// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jellyscope.core.domain.model.TileSizeId
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AndroidTileSizeStoreTest {
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
    fun defaultsToMediumAndPersistsSelectedTileSize() =
        runTest {
            val store = SharedPreferencesTileSizeStore(context)

            assertEquals(TileSizeId.Medium, store.tileSize.value)
            store.setTileSize(TileSizeId.Large)

            assertEquals(TileSizeId.Large, SharedPreferencesTileSizeStore(context).tileSize.value)
        }

    @Test
    fun unknownPersistedTileSizeFallsBackToMedium() {
        preferences()
            .edit()
            .putString(TILE_SIZE_TEST_PREFS_KEY, "Unknown")
            .commit()

        val store = SharedPreferencesTileSizeStore(context)

        assertEquals(TileSizeId.Medium, store.tileSize.value)
    }

    private fun preferences() = context.getSharedPreferences(TILE_SIZE_TEST_PREFS_NAME, Context.MODE_PRIVATE)
}

private const val TILE_SIZE_TEST_PREFS_NAME = "tile_size"
private const val TILE_SIZE_TEST_PREFS_KEY = "tile_size"
