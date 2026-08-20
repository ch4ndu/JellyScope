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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesLogCollectionPreferenceStoreTest {
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
    fun absentPreferenceDefaultsOnAndFailedCommitDoesNotPublishFalse() =
        runTest {
            val store =
                SharedPreferencesLogCollectionPreferenceStore(
                    context = context,
                    commitEnabled = { false },
                )

            assertTrue(store.enabled.value)
            assertFailsWith<IllegalStateException> { store.setEnabled(false) }
            assertTrue(store.enabled.value)
            assertTrue(SharedPreferencesLogCollectionPreferenceStore(context).enabled.value)
        }

    private fun preferences() = context.getSharedPreferences(LOG_COLLECTION_TEST_PREFS_NAME, Context.MODE_PRIVATE)
}

private const val LOG_COLLECTION_TEST_PREFS_NAME = "log_collection"
