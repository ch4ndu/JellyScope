// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AndroidPreviousRunFailureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @AfterTest
    fun tearDown() {
        context
            .getSharedPreferences("previous_run_failure", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun markerRoundTripsAndIsConsumedOnce() {
        val store = AndroidPreviousRunFailureStore(context)
        store.write(
            throwable = IllegalStateException("message=secret"),
            platform = PreviousRunFailurePlatform.AndroidTv,
        )

        val restored = AndroidPreviousRunFailureStore(context)
        assertEquals(
            PreviousRunFailureMarker(
                exceptionType = "IllegalStateException",
                platform = PreviousRunFailurePlatform.AndroidTv,
            ),
            restored.consume(),
        )
        assertNull(restored.consume())
    }
}
