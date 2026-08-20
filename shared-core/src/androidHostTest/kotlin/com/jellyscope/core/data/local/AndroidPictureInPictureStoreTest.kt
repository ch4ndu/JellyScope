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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidPictureInPictureStoreTest {
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
    fun defaultsToEnabledAndPersistsSelection() =
        runTest {
            val store = SharedPreferencesPictureInPictureStore(context)

            assertTrue(store.enabled.value)

            store.setEnabled(false)

            val restoredDisabledStore = SharedPreferencesPictureInPictureStore(context)
            assertFalse(restoredDisabledStore.enabled.value)

            restoredDisabledStore.setEnabled(true)

            val restoredEnabledStore = SharedPreferencesPictureInPictureStore(context)
            assertTrue(restoredEnabledStore.enabled.value)
        }

    private fun preferences() = context.getSharedPreferences(PICTURE_IN_PICTURE_TEST_PREFS_NAME, Context.MODE_PRIVATE)
}

private const val PICTURE_IN_PICTURE_TEST_PREFS_NAME = "picture_in_picture"
